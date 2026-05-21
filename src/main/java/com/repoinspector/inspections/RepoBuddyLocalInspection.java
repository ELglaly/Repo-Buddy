package com.repoinspector.inspections;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.repoinspector.settings.RepoBuddySettings;

/**
 * Common base for the RepoBuddy Java inspections that adds panel-only gating.
 *
 * <p>The platform instantiates each inspection through its no-argument constructor; that instance
 * respects {@link RepoBuddySettings#isPanelOnlyMode()} and produces nothing in the editor daemon
 * when panel-only mode is on. The
 * {@link com.repoinspector.inspections.scan.RepoBuddyInspectionScanner scanner} constructs the same
 * classes with {@code alwaysAnalyze = true} so the Issues panel and indicators always receive
 * findings regardless of the setting.
 *
 * <p>Subclasses must short-circuit {@code buildVisitor} with
 * {@code if (!shouldAnalyze()) return PsiElementVisitor.EMPTY_VISITOR;} as their first statement.
 */
public abstract class RepoBuddyLocalInspection extends AbstractBaseJavaLocalInspectionTool {

    private final boolean alwaysAnalyze;

    protected RepoBuddyLocalInspection() {
        this(false);
    }

    protected RepoBuddyLocalInspection(boolean alwaysAnalyze) {
        this.alwaysAnalyze = alwaysAnalyze;
    }

    /**
     * True when this instance should produce findings: either it was created for the scanner
     * ({@code alwaysAnalyze}) or panel-only mode is off, in which case the inspection runs in the
     * editor daemon as usual.
     */
    protected final boolean shouldAnalyze() {
        return alwaysAnalyze || !RepoBuddySettings.getInstance().isPanelOnlyMode();
    }
}
