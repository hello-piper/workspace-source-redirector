package com.example.workspacesource;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.navigation.GotoImplementationHandler;
import com.intellij.codeInsight.navigation.ImplementationSearcher;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.codeInsight.navigation.actions.GotoImplementationAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Takes over Go to Implementation (Ctrl+Alt+B / Ctrl+Alt+Click / gutter icon / Ctrl+hover) by
 * replacing the platform action of the same id.
 * <p>
 * The {@code definitionsScopedSearch} extension point is intentionally <em>not</em> used: it
 * contributes to a global inheritance search, which changes results everywhere (structure view,
 * find usages, call hierarchy) and would need to stay consistent with the platform's
 * {@code acceptImplementationForReference} validation — a validation that filters out
 * implementations whose super method comes from a different jar version, which is exactly the
 * case this plugin has to support.
 * <p>
 * Instead the whole flow is owned here: resolve the element at the caret, look for
 * source implementations across the workspace, then navigate. When nothing is found the native
 * handler runs unchanged, so behavior is never worse than stock IDEA.
 */
public class WorkspaceGotoImplementationAction extends GotoImplementationAction {

    private static final String CHOOSER_TITLE = "选择实现";

    /** Action id of the platform action this class replaces; also used to look up gutter icons. */
    private static final String ACTION_ID = "GotoImplementation";

    @Override
    protected CodeInsightActionHandler getHandler() {
        return new WorkspaceImplementationHandler(super.getHandler());
    }

    private static final class WorkspaceImplementationHandler implements CodeInsightActionHandler {

        private static final Logger LOG = Logger.getInstance(WorkspaceGotoImplementationAction.class);

        private final CodeInsightActionHandler nativeHandler;

        private WorkspaceImplementationHandler(CodeInsightActionHandler nativeHandler) {
            this.nativeHandler = nativeHandler;
        }

        @Override
        public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
            List<PsiElement> targets = findTargets(editor);
            if (targets.isEmpty()) {
                nativeHandler.invoke(project, editor, file);
                return;
            }
            if (targets.size() == 1) {
                NavigationUtil.activateFileWithPsiElement(targets.get(0), true);
                return;
            }
            JBPopup popup = NavigationUtil.getPsiElementPopup(targets.toArray(new PsiElement[0]), CHOOSER_TITLE);
            popup.showCenteredInCurrentWindow(project);
        }

        /**
         * Resolves the caret to the declaration the user is standing on, exactly like the native
         * action does (including the gutter-icon case), then searches for source implementations.
         * Everything runs in a read action; navigation itself stays outside of it.
         */
        private static List<PsiElement> findTargets(Editor editor) {
            try {
                return ReadAction.compute(() -> {
                    PsiElement declaration = resolveDeclaration(editor, editor.getCaretModel().getOffset());
                    if (declaration == null) {
                        // Clicked the "implemented" gutter icon: the marker knows the real offset.
                        int gutterOffset = GotoImplementationHandler
                                .tryGetNavigationSourceOffsetFromGutterIcon(editor, ACTION_ID);
                        if (gutterOffset >= 0) {
                            declaration = resolveDeclaration(editor, gutterOffset);
                        }
                    }
                    if (!(declaration instanceof PsiClass) && !(declaration instanceof PsiMethod)) {
                        return Collections.<PsiElement>emptyList();
                    }
                    return WorkspaceImplementationFinder.find(declaration);
                });
            } catch (IndexNotReadyException e) {
                // Index is not ready yet: leave the whole navigation to the platform.
                LOG.debug("Workspace Source Redirector: index not ready, native Go to Implementation is used");
                return Collections.emptyList();
            }
        }

        @Nullable
        private static PsiElement resolveDeclaration(Editor editor, int offset) {
            return TargetElementUtil.getInstance()
                    .findTargetElement(editor, ImplementationSearcher.getFlags(), offset);
        }

        @Override
        public boolean startInWriteAction() {
            return nativeHandler.startInWriteAction();
        }

        @Nullable
        @Override
        public PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            return nativeHandler.getElementToMakeWritable(file);
        }
    }
}
