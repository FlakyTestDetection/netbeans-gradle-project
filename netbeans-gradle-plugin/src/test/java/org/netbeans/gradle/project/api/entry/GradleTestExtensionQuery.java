package org.netbeans.gradle.project.api.entry;

import org.netbeans.api.project.Project;
import org.openide.util.lookup.ServiceProvider;

/**
 * @deprecated
 *
 * @author radim
 */
@ServiceProvider(service = GradleProjectExtensionQuery.class, position = 500)
@Deprecated
public class GradleTestExtensionQuery implements GradleProjectExtensionQuery {

    @Override
    public GradleProjectExtension loadExtensionForProject(Project project) {
        return new GradleTestExtension();
    }
}
