package org.netbeans.gradle.project.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jtrim.cancel.Cancellation;
import org.jtrim.cancel.CancellationToken;
import org.jtrim.collections.RefLinkedList;
import org.jtrim.collections.RefList;
import org.jtrim.concurrent.CancelableTask;
import org.jtrim.concurrent.CleanupTask;
import org.jtrim.concurrent.GenericUpdateTaskExecutor;
import org.jtrim.concurrent.MonitorableTaskExecutorService;
import org.jtrim.concurrent.TaskExecutor;
import org.jtrim.concurrent.UpdateTaskExecutor;
import org.jtrim.event.ListenerRef;
import org.jtrim.event.UnregisteredListenerRef;
import org.jtrim.swing.concurrent.SwingTaskExecutor;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.gradle.project.api.event.NbListenerRefs;

public final class FileSystemWatcher {
    private static final Logger LOGGER = Logger.getLogger(FileSystemWatcher.class.getName());

    private static final WatchEvent.Kind<?>[] EVENTS = new WatchEvent.Kind<?>[]{
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE
    };

    private final FileSystem fileSystem;

    private final MonitorableTaskExecutorService pollExecutor;
    private final TaskExecutor eventExecutor;

    private WatchService activeWatchService;
    private final Map<Path, Listeners> checkedPaths;
    private final Map<WatchKey, WatchKeyListeners> watchKeys;

    private final Lock mainLock;

    public FileSystemWatcher(FileSystem fileSystem, TaskExecutor eventExecutor) {
        ExceptionHelper.checkNotNullArgument(fileSystem, "fileSystem");
        ExceptionHelper.checkNotNullArgument(eventExecutor, "eventExecutor");

        this.fileSystem = fileSystem;
        this.eventExecutor = eventExecutor;
        this.mainLock = new ReentrantLock();
        this.pollExecutor = NbTaskExecutors.newStoppableExecutor("FileSystem-watcher-poll", 1);
        this.checkedPaths = new HashMap<>();
        this.watchKeys = new HashMap<>();
        this.activeWatchService = null;
    }

    public static FileSystemWatcher getDefault() {
        return DefaultHolder.DEFAULT;
    }

    /**
     * Waits until there are no more watches registered and no more polling is done.
     * Fails if that state cannot be reached.
     * <P>
     * This method is only for testing purposes to verify that this filesystem watcher cleans up
     * properly.
     */
    void waitFor(long timeout, TimeUnit unit) {
        pollExecutor.shutdown();
        if (!pollExecutor.tryAwaitTermination(Cancellation.UNCANCELABLE_TOKEN, timeout, unit)) {
            throw new IllegalStateException("Failed to wait for polling executor.");
        }

        mainLock.lock();
        try {
            if (!checkedPaths.isEmpty()) {
                throw new IllegalStateException("There are checked paths: " + checkedPaths.keySet());
            }
            if (!watchKeys.isEmpty()) {
                throw new IllegalStateException("There are watched keys: " + toString(watchKeys.keySet()));
            }
        } finally {
            mainLock.unlock();
        }
    }

    private static String toString(Collection<WatchKey> keys) {
        List<Object> watchables = new ArrayList<>(keys.size());
        for (WatchKey key: keys) {
            watchables.add(key.watchable());
        }
        return watchables.toString();
    }

    private Path tryResolve(Path keyContext, Object context) {
        if (context instanceof Path) {
            Path relPath = (Path)context;
            if (keyContext != null) {
                return keyContext.resolve(relPath);
            }
            else {
                return relPath;
            }
        }
        return null;
    }

    private void notifyPath(Path keyContext, WatchEvent<?> event) {
        Path path = tryResolve(keyContext, event.context());
        if (path != null) {
            notifyPath(path);
        }
    }

    private void notifyPath(Path path) {
        Listeners listenerRefs;

        mainLock.lock();
        try {
            listenerRefs = checkedPaths.get(path);
        } finally {
            mainLock.unlock();
        }

        if (listenerRefs != null) {
            listenerRefs.notifyIfChanged();
        }
    }

    private static void cancelWatchService(WatchService watchService) {
        try {
            watchService.close();
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to close watch service.", ex);
        }
    }

    private void stopPolling(WatchService watchService) throws IOException {
        WatchService newWatchService = null;
        mainLock.lock();
        try {
            if (watchService == activeWatchService) {
                if (checkedPaths.isEmpty()) {
                    activeWatchService = null;
                }
                else {
                    newWatchService = fileSystem.newWatchService();
                    activeWatchService = newWatchService;
                }
            }
        } finally {
            mainLock.unlock();
        }

        if (newWatchService != null) {
            startPolling(newWatchService);
        }
    }

    private boolean hasCheckedPaths() {
        mainLock.lock();
        try {
            return !checkedPaths.isEmpty();
        } finally {
            mainLock.unlock();
        }
    }

    private static Path keyContext(WatchKey key) {
        Watchable result = key.watchable();
        return result instanceof Path
                ? (Path)result
                : null;
    }

    private void startPolling(final WatchService watchService) {
        pollExecutor.execute(Cancellation.UNCANCELABLE_TOKEN, new CancelableTask() {
            @Override
            public void execute(CancellationToken cancelToken) throws Exception {
                ListenerRef cancelRef = null;
                try {
                    cancelRef = cancelToken.addCancellationListener(new Runnable() {
                        @Override
                        public void run() {
                            cancelWatchService(watchService);
                        }
                    });

                    while (!cancelToken.isCanceled() && hasCheckedPaths()) {
                        WatchKey key = watchService.take();
                        Path keyContext = keyContext(key);

                        List<WatchEvent<?>> events = key.pollEvents();
                        for (WatchEvent<?> event : events) {
                            notifyPath(keyContext, event);
                        }

                        resetWatchKey(watchService, key, events);
                    }
                } catch (ClosedWatchServiceException ex) {
                    // Canceled
                } finally {
                    if (cancelRef != null) {
                        cancelRef.unregister();
                    }
                    stopPolling(watchService);
                }
            }
        }, new CleanupTask() {
            @Override
            public void cleanup(boolean canceled, Throwable error) throws Exception {
                NbTaskExecutors.defaultCleanup(canceled, error);
                watchService.close();
            }
        });
    }

    public ListenerRef watchPath(final Path path, Runnable listener) {
        ExceptionHelper.checkNotNullArgument(path, "path");
        ExceptionHelper.checkNotNullArgument(listener, "listener");
        if (path.getFileSystem() != fileSystem) {
            return UnregisteredListenerRef.INSTANCE;
        }

        try {
            return watchPathUnsafe(path, listener);
        } catch (IOException ex) {
            LOGGER.log(Level.INFO, "Failed to watch for path: " + path, ex);
            return UnregisteredListenerRef.INSTANCE;
        }
    }

    private ListenerRef watchPathUnsafe(final Path path, Runnable listener) throws IOException {
        final ElementRemover listenerRemover;
        WatchService newWatchService = null;
        WatchService currentWatchService;
        Listeners listeners;

        mainLock.lock();
        try {
            listeners = checkedPaths.get(path);
            if (listeners == null) {
                listeners = new Listeners(path, eventExecutor);
                checkedPaths.put(path, listeners);
            }
            listenerRemover = listeners.addListener(listener);

            if (activeWatchService == null) {
                newWatchService = fileSystem.newWatchService();
                activeWatchService = newWatchService;
            }
            currentWatchService = activeWatchService;
        } finally {
            mainLock.unlock();
        }

        if (newWatchService != null) {
            startPolling(newWatchService);
        }

        startWatching(currentWatchService, path, listeners);

        return NbListenerRefs.fromRunnable(new Runnable() {
            @Override
            public void run() {
                unregisterPath(path, listenerRemover);
            }
        });
    }

    private void unregisterPath(Path path, ElementRemover listenerRemover) {
        WatchService watchServiceToClose = null;
        WatchKey watchKey;

        mainLock.lock();
        try {
            int remaining = listenerRemover.removeAndGetRemainingCount();
            if (remaining > 0) {
                return;
            }

            watchServiceToClose = activeWatchService;
            activeWatchService = null;

            Listeners listeners = checkedPaths.remove(path);
            if (listeners == null) {
                return;
            }

            watchKey = listeners.getWatchKey();
            if (!removeWathKeyOfPath(path, watchKey)) {
                return;
            }
        } finally {
            mainLock.unlock();
            tryClose(watchServiceToClose);
        }

        watchKey.cancel();
    }

    private static void tryClose(WatchService watchService) {
        if (watchService == null) {
            return;
        }

        try {
            watchService.close();
        } catch (IOException ex) {
            LOGGER.log(Level.INFO, "Failed to close watch service.", ex);
        }
    }

    private boolean removeWathKeyOfPath(Path path, WatchKey watchKey) {
        if (watchKey == null) {
            return false;
        }

        WatchKeyListeners watchKeyListeners = watchKeys.get(watchKey);
        if (watchKeyListeners == null) {
            return false;
        }

        if (watchKeyListeners.removeAndGetRemainingCount(path) > 0) {
            return false;
        }

        watchKeys.remove(watchKey);
        return true;
    }

    private static WatchKey tryRegister(WatchService watchService, Path path) throws IOException {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return path.register(watchService, EVENTS);
        } catch (NoSuchFileException ex) {
            return null;
        }
    }

    private void startWatching(WatchService watchService, Path path, Listeners listeners) throws IOException {
        Path parent = path.getParent();
        while (parent != null) {
            WatchKey watchKey = tryRegister(watchService, parent);
            if (watchKey != null && watchKey.reset()) {
                WatchKey prevWatchKey;

                mainLock.lock();
                try {
                    WatchKeyListeners watchKeyListeners = watchKeys.get(watchKey);
                    if (watchKeyListeners == null) {
                        watchKeyListeners = new WatchKeyListeners();
                        watchKeys.put(watchKey, watchKeyListeners);
                    }
                    watchKeyListeners.setListeners(path, listeners);

                    prevWatchKey = listeners.setWatchKey(watchKey);
                    if (!removeWathKeyOfPath(path, prevWatchKey)) {
                        return;
                    }
                } finally {
                    mainLock.unlock();
                }

                prevWatchKey.cancel();
            }
            parent = parent.getParent();
        }
    }

    private boolean mayCreatedChild(List<WatchEvent<?>> events) {
        for (WatchEvent<?> event: events) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.OVERFLOW) {
                return true;
            }
        }
        return false;
    }

    private void resetWatchKey(WatchService watchService, WatchKey watchKey, List<WatchEvent<?>> events) throws IOException {
        if (mayCreatedChild(events)) {
            watchKey.cancel();
            rebuildWatchKeys(watchService, watchKey);
        }
        else {
            if (!watchKey.reset()) {
                rebuildWatchKeys(watchService, watchKey);
            }
        }
    }

    private void rebuildWatchKeys(WatchService watchService, WatchKey watchKey) throws IOException {
        WatchKeyListeners watchKeyListeners = null;
        mainLock.lock();
        try {
            watchKeyListeners = watchKeys.remove(watchKey);
        } finally {
            mainLock.unlock();
        }

        if (watchKeyListeners != null) {
            List<Listeners> listenersSnapshot = new ArrayList<>(watchKeyListeners.listeners.size());
            for (Map.Entry<Path, Listeners> entry: watchKeyListeners.listeners.entrySet()) {
                Listeners listeners = entry.getValue();
                listenersSnapshot.add(listeners);

                startWatching(watchService, entry.getKey(), listeners);
            }

            for (Listeners listeners: listenersSnapshot) {
                listeners.notifyIfChanged();
            }
        }
    }

    private static final class WatchKeyListeners {
        private final Map<Path, Listeners> listeners;

        public WatchKeyListeners() {
            this.listeners = new HashMap<>();
        }

        public int removeAndGetRemainingCount(Path path) {
            listeners.remove(path);
            return listeners.size();
        }

        public void setListeners(Path path, Listeners pathListeners) {
            listeners.put(path, pathListeners);
        }
    }

    private static final class Listeners {
        private final Path path;

        private final Lock listenersLock;
        private final UpdateTaskExecutor executor;
        private final RefList<Runnable> listeners;

        private volatile boolean notifiedOnce;
        private final AtomicBoolean lastState;

        private WatchKey watchKey;

        public Listeners(Path path, TaskExecutor eventExecutor) {
            this.path = path;
            this.executor = new GenericUpdateTaskExecutor(eventExecutor);
            this.listenersLock = new ReentrantLock();
            this.listeners = new RefLinkedList<>();
            this.watchKey = null;
            this.notifiedOnce = false;
            this.lastState = new AtomicBoolean();
        }

        public WatchKey getWatchKey() {
            return watchKey;
        }

        public WatchKey setWatchKey(WatchKey watchKey) {
            WatchKey prevWatchKey = this.watchKey;
            if (Objects.equals(prevWatchKey, watchKey)) {
                prevWatchKey = null;
            }

            this.watchKey = watchKey;
            return prevWatchKey;
        }

        public ElementRemover addListener(Runnable listener) {
            final RefList.ElementRef<Runnable> elementRef;
            listenersLock.lock();
            try {
                elementRef = listeners.addLastGetReference(listener);
            } finally {
                listenersLock.unlock();
            }

            return new ElementRemover() {
                @Override
                public int removeAndGetRemainingCount() {
                    listenersLock.lock();
                    try {
                        elementRef.remove();
                        return listeners.size();
                    } finally {
                        listenersLock.unlock();
                    }
                }
            };
        }

        private boolean getState() {
            return Files.exists(path);
        }

        private boolean needNotify() {
            boolean currentState = getState();
            if (!notifiedOnce) {
                notifiedOnce = true;
                lastState.set(currentState);
                return true;
            }

            boolean prevState = lastState.getAndSet(currentState);
            return prevState != currentState;
        }

        public void notifyIfChanged() {
            if (!needNotify()) {
                return;
            }

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    for (Runnable listener: getListenersSnapshot()) {
                        try {
                            listener.run();
                        } catch (Throwable ex) {
                            LOGGER.log(Level.WARNING, "Path change listener has thrown an unexpected exception.", ex);
                        }
                    }
                }
            });
        }

        private List<Runnable> getListenersSnapshot() {
            listenersLock.lock();
            try {
                return new ArrayList<>(listeners);
            } finally {
                listenersLock.unlock();
            }
        }
    }

    private interface ElementRemover {
        public int removeAndGetRemainingCount();
    }

    private static final class DefaultHolder {
        private static final FileSystemWatcher DEFAULT = new FileSystemWatcher(FileSystems.getDefault(), SwingTaskExecutor.getStrictExecutor(true));
    }
}
