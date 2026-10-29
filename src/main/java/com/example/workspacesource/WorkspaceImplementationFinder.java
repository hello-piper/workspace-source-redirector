package com.example.workspacesource;

import com.intellij.codeInsight.navigation.ClassImplementationsSearch;
import com.intellij.codeInsight.navigation.MethodImplementationsSearch;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds implementations of a class or method in workspace sources, tolerating version skew
 * between the jar that the call site is compiled against and the sources in the workspace.
 * <p>
 * PSI inheritance is never trusted across versions: two copies of the same interface coming from
 * different jar versions are different PSI objects, so the platform's own implementation search
 * (which matches overriding methods against a resolved super method) silently drops them.
 * Everything here is driven by the fully qualified class name plus the erased method signature
 * from {@link WorkspaceSourceMatcher}.
 * <p>
 * Three strategies are tried in order, and the first one that yields anything wins:
 * <ol>
 *   <li>every source copy of the interface (same FQCN) is used as a search root, and the
 *       platform searches implementations inside that copy's own project;</li>
 *   <li>the {@code XxxService -> XxxServiceImpl} naming convention;</li>
 *   <li>a word-in-text scan over workspace sources — this one does not go through the inheritance
 *       index at all, so it still works when the PSI inheritance chain is broken or the index is
 *       incomplete.</li>
 * </ol>
 * Only source results are returned — jumping into another jar is exactly what this plugin exists
 * to avoid.
 */
final class WorkspaceImplementationFinder {

    private static final Logger LOG = Logger.getInstance(WorkspaceImplementationFinder.class);

    private WorkspaceImplementationFinder() {
    }

    /**
     * Must be called inside a read action. Returns an empty list when nothing can be resolved,
     * in which case the caller falls back to the native action.
     */
    @NotNull
    static List<PsiElement> find(@Nullable PsiElement declaration) {
        List<PsiElement> results = new ArrayList<PsiElement>();
        if (declaration == null) {
            return results;
        }
        PsiClass anchorClass = containingClass(declaration);
        if (anchorClass == null) {
            return results;
        }
        String qualifiedName = anchorClass.getQualifiedName();
        if (qualifiedName == null) {
            return results;
        }
        List<Project> projects = WorkspaceProjects.inWorkspace(declaration.getProject());
        if (projects.isEmpty()) {
            return results;
        }

        LOG.info("Go to Implementation, anchor " + describe(declaration) + ", FQCN " + qualifiedName);
        LOG.debug("go-to-implementation started, anchor=" + describe(declaration)
                + ", fqcn=" + qualifiedName + ", scope=[" + projectNames(projects) + "]");

        Set<PsiElement> collected = new LinkedHashSet<PsiElement>();

        // Step 1: every source copy of the interface, each searched inside its own project.
        for (Project project : projects) {
            List<PsiClass> copies = WorkspaceSourceLocator.findSourceClasses(project, qualifiedName);
            LOG.info("Found " + copies.size() + " source copy(ies) of the interface in project " + project.getName());
            LOG.debug("found " + copies.size() + " interface copies in project " + project.getName());
            for (PsiClass copy : copies) {
                LOG.info("Trying interface copy " + copy.getQualifiedName() + " @ " + filePath(copy));
                collectFromCopy(copy, declaration, project, collected);
            }
        }
        String strategy = "FQCN copies";

        // Step 2: XxxService -> XxxServiceImpl.
        if (collected.isEmpty()) {
            strategy = "naming convention";
            collectByNamingConvention(declaration, qualifiedName, projects, collected);
        }

        // Step 3: word-in-text scan, independent of the inheritance index.
        if (collected.isEmpty() && declaration instanceof PsiMethod) {
            strategy = "source text scan";
            collectByTextScan((PsiMethod) declaration, qualifiedName, projects, collected);
        }

        // A copy of the declaration itself is not an implementation of it.
        collected.remove(declaration);
        results.addAll(collected);
        logResult(qualifiedName, strategy, projects, results);
        return results;
    }

    /**
     * Runs the platform implementation search with a source copy of the interface as the root.
     * The copy lives in {@code project}, so the search is scoped to that project as well.
     */
    private static void collectFromCopy(PsiClass searchRoot, PsiElement declaration, Project project,
                                        final Set<PsiElement> collected) {
        final PsiClass root = searchRoot;
        Processor<PsiElement> consumer = element -> {
            if (element != root && isSource(element)) {
                collected.add(element);
            }
            return true;
        };
        GlobalSearchScope scope = WorkspaceProjects.sourceScope(project);
        if (declaration instanceof PsiClass) {
            ClassImplementationsSearch.processImplementations(searchRoot, consumer, scope);
            return;
        }
        PsiMethod rootMethod = WorkspaceSourceMatcher.matchMethod(searchRoot, (PsiMethod) declaration);
        if (rootMethod == null) {
            // The method does not exist in this version of the interface; other copies may have it.
            LOG.debug("no signature match in " + searchRoot.getQualifiedName()
                    + " for " + ((PsiMethod) declaration).getName());
            return;
        }
        MethodImplementationsSearch.processImplementations(rootMethod, consumer, scope);
    }

    /**
     * Second chance for the widespread {@code FooService} / {@code FooServiceImpl} layout, which
     * still works when the interface source is missing from the workspace entirely.
     * <p>
     * The super type is checked against the FQCN, so a jar version mismatch does not matter. When
     * the check cannot resolve the hierarchy (no inheritance index) but the method signature
     * matches exactly, the candidate is still accepted — the naming convention plus an identical
     * signature is strong enough, and returning nothing here would defeat the purpose of a
     * fallback.
     */
    private static void collectByNamingConvention(PsiElement declaration, String qualifiedName,
                                                 List<Project> projects, Set<PsiElement> collected) {
        String implQualifiedName = qualifiedName + "Impl";
        for (Project project : projects) {
            for (PsiClass implClass : WorkspaceSourceLocator.findSourceClasses(project, implQualifiedName)) {
                boolean inheritor = InheritanceUtil.isInheritor(implClass, true, qualifiedName);
                LOG.info("Naming convention candidate " + implQualifiedName + ", super type check: "
                        + (inheritor ? "passed" : "not resolved"));
                LOG.debug("naming convention candidate " + implQualifiedName
                        + " inheritorOf(" + qualifiedName + ")=" + inheritor);
                if (!(declaration instanceof PsiMethod)) {
                    if (inheritor) {
                        collected.add(implClass);
                    }
                    continue;
                }
                PsiMethod matched = WorkspaceSourceMatcher.matchMethod(implClass, (PsiMethod) declaration);
                if (matched == null) {
                    continue;
                }
                if (!inheritor) {
                    LOG.debug("accepted " + implQualifiedName + " despite an unresolved super type chain,"
                            + " the signature matches");
                }
                collected.add(matched);
            }
        }
    }

    /**
     * Last resort: scan workspace sources for the method name in plain text, then keep the
     * non-interface classes that inherit the FQCN and declare a matching method. The text index
     * knows nothing about inheritance, which is precisely why this step works when the inheritance
     * index does not.
     */
    private static void collectByTextScan(PsiMethod declaration, String qualifiedName,
                                          List<Project> projects, Set<PsiElement> collected) {
        String methodName = declaration.getName();
        LOG.info("Starting word-in-text fallback scan for method " + methodName);
        LOG.debug("starting word-in-text fallback scan for method " + methodName);
        for (Project project : projects) {
            GlobalSearchScope scope = WorkspaceProjects.sourceScope(project);
            PsiSearchHelper.getInstance(project).processAllFilesWithWordInText(methodName, scope,
                    new Processor<PsiFile>() {
                        @Override
                        public boolean process(PsiFile psiFile) {
                            collectFromFile(psiFile, declaration, qualifiedName, collected);
                            return true;
                        }
                    }, false);
        }
    }

    private static void collectFromFile(PsiFile psiFile, PsiMethod declaration, String qualifiedName,
                                        Set<PsiElement> collected) {
        if (!(psiFile instanceof PsiJavaFile) || psiFile instanceof PsiCompiledFile) {
            return;
        }
        for (PsiClass topLevel : ((PsiJavaFile) psiFile).getClasses()) {
            collectFromCandidateClass(topLevel, declaration, qualifiedName, collected);
        }
    }

    private static void collectFromCandidateClass(PsiClass candidate, PsiMethod declaration,
                                                  String qualifiedName, Set<PsiElement> collected) {
        if (!candidate.isInterface() && !candidate.isAnnotationType()
                && InheritanceUtil.isInheritor(candidate, true, qualifiedName)) {
            PsiMethod matched = WorkspaceSourceMatcher.matchMethod(candidate, declaration);
            if (matched != null) {
                LOG.info("Fallback candidate class " + candidate.getQualifiedName() + " @ " + filePath(candidate));
                LOG.info("Fallback matched method " + matched.getName() + " @ " + filePath(matched));
                collected.add(matched);
            }
        }
        for (PsiClass inner : candidate.getInnerClasses()) {
            collectFromCandidateClass(inner, declaration, qualifiedName, collected);
        }
    }

    @Nullable
    private static PsiClass containingClass(@Nullable PsiElement declaration) {
        if (declaration instanceof PsiClass) {
            return (PsiClass) declaration;
        }
        if (declaration instanceof PsiMember) {
            return ((PsiMember) declaration).getContainingClass();
        }
        return null;
    }

    private static boolean isSource(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        PsiFile file = element.getContainingFile();
        return file != null && !(file instanceof PsiCompiledFile);
    }

    private static String describe(PsiElement declaration) {
        PsiClass owner = containingClass(declaration);
        String ownerName = owner == null ? "?" : String.valueOf(owner.getQualifiedName());
        if (declaration instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) declaration;
            return ownerName + "#" + method.getName()
                    + WorkspaceSourceMatcher.erasedParameterNames(method);
        }
        return ownerName;
    }

    private static String filePath(PsiElement element) {
        PsiFile file = element.getContainingFile();
        return file == null || file.getVirtualFile() == null ? "?" : file.getVirtualFile().getPath();
    }

    private static String projectNames(List<Project> projects) {
        StringBuilder names = new StringBuilder();
        for (Project project : projects) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(project.getName());
        }
        return names.toString();
    }

    private static void logResult(String qualifiedName, String strategy, List<Project> projects,
                                  List<PsiElement> results) {
        if (results.isEmpty()) {
            LOG.info("No source implementation of " + qualifiedName + " in the workspace (tried: "
                    + strategy + "), falling back to the native action");
            LOG.debug("no source implementation for " + qualifiedName + " in ["
                    + projectNames(projects) + "], native action is used");
            return;
        }
        StringBuilder targets = new StringBuilder();
        for (PsiElement result : results) {
            if (targets.length() > 0) {
                targets.append(", ");
            }
            targets.append(result instanceof PsiMember ? ((PsiMember) result).getName() : String.valueOf(result));
            targets.append(" @ ").append(filePath(result));
        }
        LOG.info("Resolved " + results.size() + " implementation target(s) via " + strategy + ": " + targets);
        LOG.debug("resolved " + results.size() + " implementation(s) of " + qualifiedName
                + " via " + strategy + " in [" + projectNames(projects) + "]: " + targets);
    }
}
