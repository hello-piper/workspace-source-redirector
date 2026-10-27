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
 * 接管 Ctrl+点击 / Ctrl+B：当光标处引用被解析到 jar（编译产物）里的类或成员时，
 * 改用全限定名在当前工程的源码里找同名类，把跳转目标换成源码里的成员。
 * <p>
 * 找不到源码、或本来就指向源码时返回 {@code null}，完全交还给平台默认逻辑。
 */
public class WorkspaceSourceRedirectHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(WorkspaceSourceRedirectHandler.class);

    /** 向上追溯引用元素的最大层数，防止在异常语法树下无限爬 */
    private static final int MAX_ANCESTOR_DEPTH = 15;

    /** 泛型类型参数在签名比对时的占位符 */
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
            // 索引没建好，不接管，让平台按默认方式处理
            return null;
        }
    }

    /**
     * 从光标所在的叶子节点向上找，直到找到第一个能解析成 jar 里成员的引用。
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
     * 先看元素自身的引用，再看它携带的引用（PSI 里同一位置可能有多个引用链）。
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
     * 只认来自编译产物（PsiCompiledFile，即 jar / class 目录）的类、方法、字段。
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
     * 在当前工程 scope 里按全限定名找源码类；找到的还得不是编译产物。
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
     * 把 jar 里的成员映射到源码类里的对应成员；映射不到就退回跳类。
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
     * 按"擦除后的参数类型简单名"匹配方法；有多个重载都命中时放弃，退回跳类。
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
     * 类型擦除成用于比对的简单形式：泛型参数统一记作 T，数组/可变参数按 [] 累加，丢掉泛型实参。
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
        String scope = targetProject.equals(currentProject.getName()) ? "同项目" : "跨项目";
        LOG.info("Workspace Source Redirector: " + qualifiedName + " -> " + memberName
                + " @ " + targetProject + ":" + targetPath);
        LOG.info("Workspace Source Redirector: 已接管跳转,类 " + qualifiedName + ",成员 " + memberName
                + ",目标项目 " + targetProject + "(" + scope + "),文件 " + targetPath);
    }
}
