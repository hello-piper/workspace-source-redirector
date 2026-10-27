package com.example.workspacesource;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes over Ctrl+Click / Ctrl+B. When the reference at the caret resolves into a class or
 * member from a jar (compiled artifact), look up a class with the same fully qualified name
 * among the project sources and redirect the navigation target to the source member.
 * <p>
 * Returns {@code null} when no source exists, or when the target already points at source,
 * leaving the behavior entirely to the platform default.
 */
public class WorkspaceSourceRedirectHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(WorkspaceSourceRedirectHandler.class);

    /** Maximum number of ancestor levels to walk up, to avoid looping on a malformed syntax tree. */
    private static final int MAX_ANCESTOR_DEPTH = 15;

    /** Placeholder used for generic type parameters during signature comparison. */
    private static final String TYPE_VARIABLE_PLACEHOLDER = "T";

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
                PsiClass workspaceClass = findInProjectSource(elementAtCaret.getProject(), qualifiedName);
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
     * Looks up a source class by fully qualified name in the current project scope;
     * the result must not itself be a compiled artifact.
     */
    @Nullable
    private PsiClass findInProjectSource(Project project, String qualifiedName) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        PsiClass candidate = JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.projectScope(project));
        if (candidate == null) {
            return null;
        }
        return candidate.getContainingFile() instanceof PsiCompiledFile ? null : candidate;
    }

    /**
     * Maps a member from the jar onto the corresponding member of the source class;
     * falls back to navigating to the class when no mapping is possible.
     */
    private PsiElement mapMemberToWorkspace(PsiElement member, PsiClass workspaceClass) {
        if (member instanceof PsiMethod) {
            PsiMethod matched = matchMethod((PsiMethod) member, workspaceClass);
            return matched != null ? matched : workspaceClass;
        }
        if (member instanceof PsiField) {
            PsiField matched = workspaceClass.findFieldByName(((PsiField) member).getName(), false);
            return matched != null ? matched : workspaceClass;
        }
        return workspaceClass;
    }

    /**
     * Matches a method by the simple names of its erased parameter types; when several
     * overloads match, gives up and falls back to navigating to the class.
     */
    @Nullable
    private PsiMethod matchMethod(PsiMethod libraryMethod, PsiClass workspaceClass) {
        String name = libraryMethod.getName();
        List<String> paramNames = erasedParamNames(libraryMethod);
        PsiMethod[] candidates = libraryMethod.isConstructor()
                ? workspaceClass.getConstructors()
                : workspaceClass.findMethodsByName(name, false);
        PsiMethod matched = null;
        for (PsiMethod candidate : candidates) {
            if (!paramNames.equals(erasedParamNames(candidate))) {
                continue;
            }
            if (matched != null) {
                return null;
            }
            matched = candidate;
        }
        return matched;
    }

    private List<String> erasedParamNames(PsiMethod method) {
        List<String> names = new ArrayList<String>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            names.add(erasedName(parameter.getType()));
        }
        return names;
    }

    /**
     * Reduces a type to the simple form used for comparison: generic type parameters become T,
     * arrays and varargs accumulate [], and type arguments are dropped.
     */
    private String erasedName(PsiType type) {
        String arraySuffix = "";
        PsiType current = type;
        if (current instanceof PsiEllipsisType) {
            current = ((PsiEllipsisType) current).getComponentType();
            arraySuffix = "[]";
        }
        while (current instanceof PsiArrayType) {
            arraySuffix = arraySuffix + "[]";
            current = ((PsiArrayType) current).getComponentType();
        }
        if (current instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) current).resolve();
            if (resolved instanceof PsiTypeParameter) {
                return TYPE_VARIABLE_PLACEHOLDER + arraySuffix;
            }
            String canonicalText = current.getCanonicalText(false);
            int dot = canonicalText.lastIndexOf('.');
            String simpleName = dot >= 0 ? canonicalText.substring(dot + 1) : canonicalText;
            int genericStart = simpleName.indexOf('<');
            if (genericStart >= 0) {
                simpleName = simpleName.substring(0, genericStart);
            }
            return simpleName + arraySuffix;
        }
        return current.getCanonicalText(false) + arraySuffix;
    }

    private void logRedirect(Project currentProject, String qualifiedName, PsiElement target) {
        PsiFile targetFile = target.getContainingFile();
        String targetProject = targetFile == null ? "?" : targetFile.getProject().getName();
        String targetPath = targetFile == null || targetFile.getVirtualFile() == null
                ? "?" : targetFile.getVirtualFile().getPath();
        String memberName = target instanceof PsiMember ? ((PsiMember) target).getName() : String.valueOf(target);
        String scope = targetProject.equals(currentProject.getName()) ? "same project" : "cross project";
        LOG.info("Workspace Source Redirector: " + qualifiedName + " -> " + memberName
                + " @ " + targetProject + ":" + targetPath);
        LOG.info("Workspace Source Redirector: navigation taken over, class " + qualifiedName
                + ", member " + memberName + ", target project " + targetProject
                + " (" + scope + "), file " + targetPath);
    }
}
