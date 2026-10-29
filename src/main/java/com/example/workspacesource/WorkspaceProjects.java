package com.example.workspacesource;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which projects count as "the workspace" for one navigation.
 * <p>
 * The answer is: the current project, and nothing else. A project's own scope already covers
 * every module imported into it, which is how multi-module workspaces are normally opened — a
 * flat folder such as {@code workspace/module-a}, {@code workspace/module-b}, added as Maven
 * projects into a single window.
 * <p>
 * Widening the search is a liability:
 * <ul>
 *   <li>projects opened in other windows are usually unrelated history, and navigating into them
 *       makes the result unpredictable;</li>
 *   <li>expanding to "projects under the same parent folder" looks harmless but fires for every
 *       project in {@code ~/IdeaProjects}, so each jump pays for scans in unrelated projects and
 *       can surface an implementation the user never meant to reach.</li>
 * </ul>
 * A project nested strictly <em>below</em> the current base path is the single exception: that is
 * the same source tree opened twice (a container in one window, a module in another). Projects
 * sitting next to the current one are never searched.
 */
final class WorkspaceProjects {

    private WorkspaceProjects() {
    }

    /**
     * Returns the projects to search, current project first. Always non-null; returns an empty
     * list when the current project is missing or already disposed.
     */
    static List<Project> inWorkspace(@Nullable Project current) {
        List<Project> projects = new ArrayList<Project>();
        if (current == null || current.isDisposed()) {
            return projects;
        }
        projects.add(current);
        String basePath = canonicalPath(current.getBasePath());
        if (basePath == null) {
            return projects;
        }
        String prefix = basePath + File.separator;
        for (Project candidate : ProjectManager.getInstance().getOpenProjects()) {
            if (candidate == current || candidate.isDisposed()) {
                continue;
            }
            String candidatePath = canonicalPath(candidate.getBasePath());
            if (candidatePath == null) {
                continue;
            }
            // Only a project nested inside the current one: the same source tree opened twice.
            if (candidatePath.startsWith(prefix)) {
                projects.add(candidate);
            }
        }
        return projects;
    }

    /**
     * Sources only: the project scope contains the project's own content roots but no libraries,
     * so classes found in it are exactly the ones that can be navigated to.
     */
    static GlobalSearchScope sourceScope(Project project) {
        return GlobalSearchScope.projectScope(project);
    }

    @Nullable
    private static String canonicalPath(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }
}
