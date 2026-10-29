package com.example.workspacesource;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.Nullable;

/**
 * Takes over Ctrl+Click / Ctrl+B. When the reference at the caret resolves into a class or
 * member from a jar (compiled artifact), look up a class with the same fully qualified name
 * among the workspace sources and redirect the navigation target to the source member.
 * <p>
 * An extension point is the right tool here: results returned by a {@code gotoDeclarationHandler}
 * take precedence over standard reference resolution, which is what makes it possible to override
 * the default "jump into the jar" behavior without touching reference resolution itself.
 * <p>
 * Returns {@code null} when no source exists, or when the target already points at source,
 * leaving the behavior entirely to the platform default.
 */
public class WorkspaceSourceRedirectHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(WorkspaceSourceRedirectHandler.class);

    /** Maximum number of ancestor levels to walk up, to avoid looping on a malformed syntax tree. */
    private static final int MAX_ANCESTOR_DEPTH = 15;

    @Override
    @Nullable
    public PsiElement[] getGotoDeclarationTargets(@Nullable PsiElement elementAtCaret, int offset, Editor editor) {
        if (elementAtCaret == null || !(elementAtCaret.getContainingFile() instanceof PsiJavaFile)) {
            return null;
        }
        try {
            return ReadAction.compute(() -> {
                PsiElement libraryMember = findLibraryMemberReference(elementAtCaret);
                if (libraryMember == null) {
                    return null;
                }
                String qualifiedName = containingQualifiedName(libraryMember);
                if (qualifiedName == null) {
                    return null;
                }
                PsiClass workspaceClass = findSourceClass(elementAtCaret.getProject(), qualifiedName);
                if (workspaceClass == null) {
                    return null;
                }
                PsiElement target = mapMemberToWorkspace(libraryMember, workspaceClass);
                logRedirect(elementAtCaret.getProject(), qualifiedName, target);
                return new PsiElement[]{target};
            });
        } catch (IndexNotReadyException e) {
            // Index is not ready yet, do not take over and let the platform handle it by default.
            return null;
        }
    }

    /**
     * Walks up from the leaf element at the caret until it finds the first reference that
     * resolves to a member coming from a jar.
     */
    @Nullable
    private PsiElement findLibraryMemberReference(PsiElement leafElement) {
        PsiElement element = leafElement;
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH && element != null && !(element instanceof PsiFile);
             element = element.getParent(), depth++) {
            PsiElement target = resolveLibraryMember(element);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /**
     * Checks the element's own reference first, then the references it carries
     * (PSI may expose several reference chains at the same position).
     */
    @Nullable
    private PsiElement resolveLibraryMember(PsiElement element) {
        if (element instanceof PsiReference) {
            PsiElement target = asLibraryMember(((PsiReference) element).resolve());
            if (target != null) {
                return target;
            }
        }
        for (PsiReference reference : element.getReferences()) {
            if (reference == element) {
                continue;
            }
            PsiElement target = asLibraryMember(reference.resolve());
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /**
     * Accepts only classes, methods and fields that come from compiled output
     * (PsiCompiledFile, i.e. a jar or a class directory).
     */
    @Nullable
    private PsiElement asLibraryMember(@Nullable PsiElement resolved) {
        if (resolved == null) {
            return null;
        }
        if (resolved instanceof PsiClass) {
            return inCompiledClassFile(resolved) ? resolved : null;
        }
        if (resolved instanceof PsiMethod) {
            PsiClass containingClass = ((PsiMethod) resolved).getContainingClass();
            return containingClass != null && inCompiledClassFile(containingClass) ? resolved : null;
        }
        if (resolved instanceof PsiField) {
            PsiClass containingClass = ((PsiField) resolved).getContainingClass();
            return containingClass != null && inCompiledClassFile(containingClass) ? resolved : null;
        }
        return null;
    }

    private boolean inCompiledClassFile(PsiElement member) {
        return member.getContainingFile() instanceof PsiCompiledFile;
    }

    @Nullable
    private String containingQualifiedName(PsiElement member) {
        PsiClass containingClass;
        if (member instanceof PsiClass) {
            containingClass = (PsiClass) member;
        } else if (member instanceof PsiMember) {
            containingClass = ((PsiMember) member).getContainingClass();
        } else {
            return null;
        }
        return containingClass == null ? null : containingClass.getQualifiedName();
    }

    /**
     * Looks up a source class by fully qualified name inside the window: the current project
     * first, then the other projects of the same source tree. Compiled copies are rejected here,
     * they are what the plugin exists to avoid.
     */
    @Nullable
    private PsiClass findSourceClass(@Nullable Project currentProject, String qualifiedName) {
        for (Project project : WorkspaceProjects.inWorkspace(currentProject)) {
            PsiClass candidate = WorkspaceSourceLocator.findSourceClass(project, qualifiedName);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Maps a member from the jar onto the corresponding member of the source class: methods by
     * erased signature, fields by name, and the class itself when no member matches — so the user
     * always lands somewhere in source.
     */
    private PsiElement mapMemberToWorkspace(PsiElement member, PsiClass workspaceClass) {
        if (member instanceof PsiMethod) {
            PsiMethod matched = WorkspaceSourceMatcher.matchMethod(workspaceClass, (PsiMethod) member);
            return matched != null ? matched : workspaceClass;
        }
        if (member instanceof PsiField) {
            PsiField matched = WorkspaceSourceMatcher.matchField(workspaceClass, (PsiField) member);
            return matched != null ? matched : workspaceClass;
        }
        return workspaceClass;
    }

    private void logRedirect(@Nullable Project currentProject, String qualifiedName, PsiElement target) {
        PsiFile targetFile = target.getContainingFile();
        String targetProject = targetFile == null ? "?" : targetFile.getProject().getName();
        String targetPath = targetFile == null || targetFile.getVirtualFile() == null
                ? "?" : targetFile.getVirtualFile().getPath();
        String memberName = target instanceof PsiMember ? ((PsiMember) target).getName() : String.valueOf(target);
        String current = currentProject == null ? "?" : currentProject.getName();
        String scope = targetProject.equals(current) ? "当前工程" : "工作区其他工程";
        LOG.info("已接管声明跳转，类 " + qualifiedName + "，成员 " + memberName
                + "，目标工程 " + targetProject + "（" + scope + "），文件 " + targetPath);
        LOG.debug("declaration navigation taken over, class=" + qualifiedName + ", member=" + memberName
                + ", project=" + targetProject + ", file=" + targetPath);
    }
}
