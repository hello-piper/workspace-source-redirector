package com.example.workspacesource;

import com.intellij.codeInsight.navigation.ClassImplementationsSearch;
import com.intellij.codeInsight.navigation.MethodImplementationsSearch;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 补全"跳到实现"（Ctrl+Alt+B / Ctrl+Alt+点击 / 行号栏图标 / Ctrl+悬停）。
 * <p>
 * 平台默认只在当前工程 scope 里找实现；这里先把所有同名类（含其它 module 的源码）当成搜索根，
 * 各自在自己的 project scope 里重跑一次实现搜索，把平台漏掉的实现补上。
 * 原生结果照旧保留，只是做了去重，不会改变原有行为。
 */
public class WorkspaceImplementationsSearch implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {

    private static final Logger LOG = Logger.getInstance(WorkspaceImplementationsSearch.class);

    private static final String TYPE_VARIABLE_PLACEHOLDER = "T";

    @Override
    public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters parameters,
                           @NotNull Processor<? super PsiElement> consumer) {
        if (parameters == null || consumer == null) {
            return true;
        }
        final PsiElement anchor = parameters.getElement();
        if (!(anchor instanceof PsiClass) && !(anchor instanceof PsiMethod)) {
            return true;
        }
        try {
            return ReadAction.compute(() -> processCurrentProject(anchor, parameters.getScope(), consumer));
        } catch (IndexNotReadyException e) {
            return true;
        }
    }

    private boolean processCurrentProject(PsiElement anchor, SearchScope nativeScope,
                                          Processor<? super PsiElement> consumer) {
        String qualifiedName = containingQualifiedName(anchor);
        if (qualifiedName == null) {
            return true;
        }
        Set<PsiElement> nativeTargets = collectNativeTargets(anchor, nativeScope);
        Set<PsiElement> emitted = new HashSet<PsiElement>();
        return searchProject(anchor.getProject(), anchor, qualifiedName, nativeTargets, emitted, consumer);
    }

    private boolean searchProject(Project project, PsiElement anchor, String qualifiedName,
                                  Set<PsiElement> nativeTargets, final Set<PsiElement> emitted,
                                  final Processor<? super PsiElement> consumer) {
        Processor<PsiElement> filteringConsumer = target -> {
            if (!emitted.add(target) || nativeTargets.contains(target)) {
                return true;
            }
            logRedirect(project, qualifiedName, target);
            return consumer.process(target);
        };
        for (PsiClass searchRoot : collectSearchRoots(project, anchor, qualifiedName)) {
            if (!processInProject(anchor, searchRoot, project, filteringConsumer)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 先跑一遍平台原生搜索，结果用来去重——原生已经给出的不再重复上报。
     */
    private Set<PsiElement> collectNativeTargets(PsiElement anchor, SearchScope nativeScope) {
        Set<PsiElement> targets = new HashSet<PsiElement>();
        Processor<PsiElement> collector = target -> {
            targets.add(target);
            return true;
        };
        if (anchor instanceof PsiClass) {
            ClassImplementationsSearch.processImplementations((PsiClass) anchor, collector, nativeScope);
        } else if (anchor instanceof PsiMethod) {
            MethodImplementationsSearch.processImplementations((PsiMethod) anchor, collector, nativeScope);
        }
        return targets;
    }

    private boolean processInProject(PsiElement anchor, PsiClass searchRoot, Project project,
                                     Processor<PsiElement> consumer) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        if (anchor instanceof PsiClass) {
            return ClassImplementationsSearch.processImplementations(searchRoot, consumer, scope);
        }
        PsiMethod rootMethod = matchMethod((PsiMethod) anchor, searchRoot);
        if (rootMethod == null) {
            return ClassImplementationsSearch.processImplementations(searchRoot, consumer, scope);
        }
        return MethodImplementationsSearch.processImplementations(rootMethod, consumer, scope);
    }

    @Nullable
    private String containingQualifiedName(PsiElement anchor) {
        PsiClass containingClass = anchorClassOf(anchor);
        return containingClass == null ? null : containingClass.getQualifiedName();
    }

    /**
     * 收集所有同名类作为搜索根：先取 allScope 里的（可能是 jar、也可能是别的 module），
     * 再补上锚点自己所在的类，避免它没被 allScope 命中时漏掉。
     */
    private List<PsiClass> collectSearchRoots(Project project, PsiElement anchor, String qualifiedName) {
        List<PsiClass> roots = new ArrayList<PsiClass>();
        for (PsiClass candidate : JavaPsiFacade.getInstance(project).findClasses(qualifiedName, GlobalSearchScope.allScope(project))) {
            if (roots.contains(candidate)) {
                continue;
            }
            roots.add(candidate);
        }
        PsiClass anchorClass = anchorClassOf(anchor);
        if (anchorClass != null && anchorClass.getProject() == project && !roots.contains(anchorClass)) {
            roots.add(anchorClass);
        }
        return roots;
    }

    @Nullable
    private PsiClass anchorClassOf(PsiElement anchor) {
        if (anchor instanceof PsiClass) {
            return (PsiClass) anchor;
        }
        return anchor instanceof PsiMember ? ((PsiMember) anchor).getContainingClass() : null;
    }

    @Nullable
    private PsiMethod matchMethod(PsiMethod anchorMethod, PsiClass workspaceClass) {
        List<String> paramNames = erasedParamNames(anchorMethod);
        PsiMethod[] candidates = anchorMethod.isConstructor()
                ? workspaceClass.getConstructors()
                : workspaceClass.findMethodsByName(anchorMethod.getName(), false);
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

    private static void logRedirect(Project project, String qualifiedName, PsiElement target) {
        PsiFile targetFile = target.getContainingFile();
        String targetPath = targetFile == null || targetFile.getVirtualFile() == null
                ? "?" : targetFile.getVirtualFile().getPath();
        String memberName = target instanceof PsiMember ? ((PsiMember) target).getName() : String.valueOf(target);
        LOG.debug("Workspace Source Redirector: " + qualifiedName + " -> " + memberName
                + " @ " + project.getName() + ":" + targetPath);
        LOG.info("Workspace Source Redirector: 已接管跳转(实现),类 " + qualifiedName + ",成员 " + memberName
                + ",目标项目 " + project.getName() + ",文件 " + targetPath);
    }
}
