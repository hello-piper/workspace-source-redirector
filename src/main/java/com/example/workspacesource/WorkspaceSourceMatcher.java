package com.example.workspacesource;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches members across jar versions without trusting PSI identity or PSI inheritance.
 * <p>
 * Two copies of the same interface coming from different jar versions are different PSI objects,
 * and the implementation side may have no resolvable super method at all, so platform matching
 * silently drops them. Everything here therefore compares stable textual facts only: the member
 * name, the constructor flag, and the <em>erased</em> parameter type names.
 * <p>
 * Erasure rules, in line with what the plugin promises:
 * <ul>
 *   <li>type arguments are dropped: {@code List<String>} and {@code List<Integer>} both become
 *       {@code List};</li>
 *   <li>varargs and arrays accumulate {@code []}, so {@code String...} equals {@code String[]};</li>
 *   <li>a generic type variable collapses to the placeholder {@code T}.</li>
 * </ul>
 */
final class WorkspaceSourceMatcher {

    /** Placeholder used for generic type parameters during signature comparison. */
    private static final String TYPE_VARIABLE_PLACEHOLDER = "T";

    private WorkspaceSourceMatcher() {
    }

    static List<String> erasedParameterNames(PsiMethod method) {
        List<String> names = new ArrayList<String>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            names.add(erasedName(parameter.getType()));
        }
        return names;
    }

    /**
     * Finds the method in {@code owner} that has the same name, the same constructor flag and the
     * same erased parameter types as {@code reference}. Returns {@code null} when nothing matches,
     * or when several overloads match — an ambiguous match would navigate to the wrong
     * declaration.
     */
    @Nullable
    static PsiMethod matchMethod(@Nullable PsiClass owner, PsiMethod reference) {
        if (owner == null) {
            return null;
        }
        List<String> expectedParameters = erasedParameterNames(reference);
        PsiMethod[] candidates = reference.isConstructor()
                ? owner.getConstructors()
                : owner.findMethodsByName(reference.getName(), false);
        PsiMethod matched = null;
        for (PsiMethod candidate : candidates) {
            if (candidate.isConstructor() != reference.isConstructor()) {
                continue;
            }
            if (!expectedParameters.equals(erasedParameterNames(candidate))) {
                continue;
            }
            if (matched != null) {
                return null;
            }
            matched = candidate;
        }
        return matched;
    }

    /**
     * Fields are matched by name only: a field's type can legitimately change between versions
     * without making it a different field.
     */
    @Nullable
    static PsiField matchField(@Nullable PsiClass owner, PsiField reference) {
        return owner == null ? null : owner.findFieldByName(reference.getName(), false);
    }

    /**
     * Reduces a type to the simple form used for comparison: generic type parameters become T,
     * arrays and varargs accumulate [], and type arguments are dropped.
     */
    private static String erasedName(PsiType type) {
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
}
