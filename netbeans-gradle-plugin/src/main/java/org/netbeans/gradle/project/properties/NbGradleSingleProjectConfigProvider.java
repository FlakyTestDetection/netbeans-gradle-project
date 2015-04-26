package org.netbeans.gradle.project.properties;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.jtrim.event.ListenerRef;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.gradle.model.util.CollectionUtils;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.ProjectModelChangeListener;
import org.netbeans.gradle.project.api.config.CustomProfileQuery;
import org.netbeans.gradle.project.api.config.ProfileDef;
import org.netbeans.spi.project.ProjectConfigurationProvider;
import org.netbeans.spi.project.ui.CustomizerProvider;

public final class NbGradleSingleProjectConfigProvider
implements
        ProjectConfigurationProvider<NbGradleConfiguration>,
        ProjectModelChangeListener {

    private final NbGradleProject project;
    private final NbGradleConfigProvider commonConfig;
    private final SwingPropertyChangeForwarder propertyChangeForwarder;
    private volatile Set<NbGradleConfiguration> extensionProfiles;

    private NbGradleSingleProjectConfigProvider(
            NbGradleProject project,
            NbGradleConfigProvider multiProjectProvider) {
        ExceptionHelper.checkNotNullArgument(project, "project");
        ExceptionHelper.checkNotNullArgument(multiProjectProvider, "multiProjectProvider");

        this.project = project;
        this.commonConfig = multiProjectProvider;
        this.extensionProfiles = Collections.emptySet();

        this.propertyChangeForwarder = createPropertyChanges(commonConfig);
    }

    private SwingPropertyChangeForwarder createPropertyChanges(NbGradleConfigProvider configProvider) {
        SwingPropertyChangeForwarder.Builder result = new SwingPropertyChangeForwarder.Builder();

        result.addProperty(PROP_CONFIGURATION_ACTIVE, configProvider.activeConfiguration(), this);
        result.addPropertyNoValue(PROP_CONFIGURATIONS, configProvider.configurations(), this);

        return result.create();
    }

    public static NbGradleSingleProjectConfigProvider create(NbGradleProject project) {
        Path rootDir = SettingsFiles.getRootDirectory(project);

        return new NbGradleSingleProjectConfigProvider(
                project,
                NbGradleConfigProvider.getConfigProvider(rootDir));
    }

    public ActiveSettingsQueryEx getActiveSettingsQuery() {
        return commonConfig.getActiveSettingsQuery();
    }

    public ProfileSettingsContainer getProfileSettingsContainer() {
        return commonConfig.getProfileSettingsContainer();
    }

    private void updateExtensionProfiles() {
        List<ProfileDef> customProfileDefs = new LinkedList<>();
        for (CustomProfileQuery profileQuery: project.getLookup().lookupAll(CustomProfileQuery.class)) {
            for (ProfileDef profileDef: profileQuery.getCustomProfiles()) {
                customProfileDefs.add(profileDef);
            }
        }

        Set<NbGradleConfiguration> customProfiles = CollectionUtils.newHashSet(customProfileDefs.size());
        for (ProfileDef profileDef: customProfileDefs) {
            customProfiles.add(new NbGradleConfiguration(profileDef));
        }

        extensionProfiles = Collections.unmodifiableSet(customProfiles);
        commonConfig.fireConfigurationListChange();
    }

    @Override
    public void onModelChanged() {
        updateExtensionProfiles();
    }

    @Override
    public Collection<NbGradleConfiguration> getConfigurations() {
        Collection<NbGradleConfiguration> commonProfiles = commonConfig.getConfigurations();
        Collection<NbGradleConfiguration> currentExtProfiles = extensionProfiles;

        List<NbGradleConfiguration> result
                = new ArrayList<>(commonProfiles.size() + currentExtProfiles.size());
        result.addAll(commonProfiles);
        result.addAll(currentExtProfiles);
        NbGradleConfiguration.sortProfiles(result);

        return result;
    }

    @Override
    public NbGradleConfiguration getActiveConfiguration() {
        NbGradleConfiguration config = commonConfig.getActiveConfiguration();
        if (!extensionProfiles.contains(config) && !commonConfig.getConfigurations().contains(config)) {
            return NbGradleConfiguration.DEFAULT_CONFIG;
        }
        return config;
    }

    @Override
    public void setActiveConfiguration(NbGradleConfiguration configuration) throws IOException {
        commonConfig.setActiveConfiguration(configuration);
    }

    private CustomizerProvider getCustomizerProvider() {
        return project.getLookup().lookup(CustomizerProvider.class);
    }

    @Override
    public boolean hasCustomizer() {
        return getCustomizerProvider() != null;
    }

    @Override
    public void customize() {
        CustomizerProvider customizerProvider = getCustomizerProvider();
        if (customizerProvider != null) {
            customizerProvider.showCustomizer();
        }
    }

    @Override
    public boolean configurationsAffectAction(String command) {
        return commonConfig.configurationsAffectAction(command);
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener lst) {
        propertyChangeForwarder.addPropertyChangeListener(lst);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener lst) {
        propertyChangeForwarder.removePropertyChangeListener(lst);
    }

    public void removeConfiguration(NbGradleConfiguration config) {
        commonConfig.removeConfiguration(config);
    }

    public void addConfiguration(NbGradleConfiguration config) {
        commonConfig.addConfiguration(config);
    }

    public ListenerRef addActiveConfigChangeListener(Runnable listener) {
        return commonConfig.addActiveConfigChangeListener(listener);
    }
}
