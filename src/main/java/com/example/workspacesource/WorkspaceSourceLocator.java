package com.example.workspacesource;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a fully qualified class name to source PSI inside one project.
 * <p>
 * Two lookups are chained, because they fail in different situations:
 * <ol>
 *   <li>the class index ({@link JavaPsiFacade#findClasses}) is exact and fast, but only knows
 *       about files that live under a <em>source root</em>;</li>
 *   <li>the file name index plus a PSI parse works for a plain folder opened as a project —
 *       the usual "one parent directory, several flat modules" layout — where the {@code .java}
 *       files are inside the content root but no module source root has been configured, so
 *       nothing but file names is indexed.</li>
 * </ol>
 * The second lookup is only tried when the first one finds nothing, so a properly imported
 * project never pays for it.
 */
final class WorkspaceSourceLocator {

    private static final Logger LOG = Logger.getInstance(WorkspaceSourceLocator.class);

    private WorkspaceSourceLocator() {
    }

    /**
     * All source copies of a class inside {@code project}. Empty when the class only exists as a
     * compiled artifact. Must be called inside a read action.
     */
    @NotNull
    static List<PsiClass> findSourceClasses(@Nullable Project project, String qualifiedName) {
        List<PsiClass> found = new ArrayList<PsiClass>();
        if (project == null || project.isDisposed() || qualifiedName.isEmpty()) {
            return found;
        }
        GlobalSearchScope projectScope = WorkspaceProjects.sourceScope(project);

        for (PsiClass candidate : JavaPsiFacade.getInstance(project).findClasses(qualifiedName, projectScope)) {
            addIfSource(candidate, found);
        }
        if (!found.isEmpty()) {
            return found;
        }

        for (GlobalSearchScope scope : fallbackScopes(project, projectScope)) {
            for (VirtualFile file : candidateFiles(qualifiedName, scope)) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (!(psiFile instanceof PsiJavaFile)) {
                    continue;
                }
                for (PsiClass candidate : matchingClasses((PsiJavaFile) psiFile, qualifiedName)) {
                    addIfSource(candidate, found);
                }
            }
        }
        if (!found.isEmpty()) {
            // Reaching this branch means the project has no usable source root and the class
            // index is empty. Worth a log line: it explains why "Go to Class" cannot find the
            // file, and it is rare enough not to flood the log.
            LOG.info("Workspace Source Redirector: " + qualifiedName
                    + " was resolved through the file name index, the project has no source root");
        }
        return found;
    }

    /**
     * Single-result convenience wrapper: the first source copy, or {@code null}.
     */
    @Nullable
    static PsiClass findSourceClass(@Nullable Project project, String qualifiedName) {
        List<PsiClass> classes = findSourceClasses(project, qualifiedName);
        return classes.isEmpty() ? null : classes.get(0);
    }

    /**
     * The project scope comes first because it honours excluded folders (build output, for
     * example). The base path directory is added on top: when a folder was opened without
     * importing anything, that directory is the only reliable description of "this window".
     */
    private static List<GlobalSearchScope> fallbackScopes(Project project, GlobalSearchScope projectScope) {
        List<GlobalSearchScope> scopes = new ArrayList<GlobalSearchScope>();
        scopes.add(projectScope);
        VirtualFile baseDir = baseDirectory(project);
        if (baseDir != null) {
            GlobalSearchScope directoryScope = GlobalSearchScopes.directoryScope(project, baseDir, true);
            if (!directoryScope.equals(projectScope)) {
                scopes.add(directoryScope);
            }
        }
        return scopes;
    }

    @Nullable
    private static VirtualFile baseDirectory(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isEmpty()) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(basePath);
    }

    /**
     * Files whose name could contain the class. Inner classes live in the file of their outermost
     * class, so {@code a.b.Outer.Inner} tries {@code Inner.java} and then {@code Outer.java}.
     */
    private static List<VirtualFile> candidateFiles(String qualifiedName, GlobalSearchScope scope) {
        List<VirtualFile> files = new ArrayList<VirtualFile>();
        for (String name : candidateFileNames(qualifiedName)) {
            files.addAll(FilenameIndex.getVirtualFilesByName(name + ".java", scope));
            if (!files.isEmpty()) {
                break;
            }
        }
        return files;
    }

    /**
     * Class-named segments of the qualified name, innermost first. Segments starting with a
     * lowercase letter are package names and stop the walk.
     */
    private static List<String> candidateFileNames(String qualifiedName) {
        List<String> names = new ArrayList<String>();
        String[] segments = qualifiedName.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (segment.isEmpty() || !Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            names.add(segment);
        }
        return names;
    }

    /**
     * Classes declared in the file whose fully qualified name matches exactly. Comparing names
     * instead of trusting the file name keeps a stray same-named class in another package from
     * being picked up.
     */
    private static List<PsiClass> matchingClasses(PsiJavaFile file, String qualifiedName) {
        List<PsiClass> matches = new ArrayList<PsiClass>();
        for (PsiClass topLevel : file.getClasses()) {
            collectMatching(topLevel, qualifiedName, matches);
        }
        return matches;
    }

    private static void collectMatching(PsiClass candidate, String qualifiedName, List<PsiClass> matches) {
        if (qualifiedName.equals(candidate.getQualifiedName())) {
            matches.add(candidate);
        }
        for (PsiClass inner : candidate.getInnerClasses()) {
            collectMatching(inner, qualifiedName, matches);
        }
    }

    private static void addIfSource(@Nullable PsiClass candidate, List<PsiClass> found) {
        if (candidate == null || found.contains(candidate)) {
            return;
        }
        PsiFile file = candidate.getContainingFile();
        if (file == null || file instanceof PsiCompiledFile) {
            return;
        }
        found.add(candidate);
    }
}
