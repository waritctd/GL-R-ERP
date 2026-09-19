package th.co.glr.hr.importrequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The F-SM-001 line-prefill fallback chain (owner decision 09-18 #3 §A: "IR really should be
 * filled") — pure and static, no Spring, no I/O, so the cascade itself is unit-testable and
 * mutation-checkable without a database, matching {@code ImportRequestFormAssembler}'s own
 * "pure and static" discipline for the same reason.
 *
 * <p>{@link ImportRequestQueryRepository#factoryResolutionCandidates} reads the raw candidate
 * values off the deal (catalog code, order-confirmed pricing-request-item code, hand-typed
 * model; color/texture from the ticket item or the order-confirmed pricing-request item) and
 * hands them to the methods here, which decide what actually gets printed.
 */
final class ImportRequestLinePrefill {

    private ImportRequestLinePrefill() {}

    /**
     * First non-blank candidate, stripped. Used for BOTH the code cascade (catalog product code →
     * order-confirmed pricing-request-item code → model) and the color/texture cascade
     * (ticket_item → order-confirmed pricing_request_item) — the two are the same shape of
     * problem ("prefer the most authoritative source that actually has a value"), so one method
     * serves both rather than two near-identical copies.
     */
    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }

    /**
     * The printed note sub-row: "สี &lt;colour&gt; · ผิว &lt;surface&gt;" — both when both are
     * known, either alone when only one is, and {@code null} (omit the sub-row entirely) when
     * neither is. Never invents a placeholder for the missing half.
     */
    static String buildColorSurfaceNote(String color, String texture) {
        List<String> parts = new ArrayList<>();
        if (color != null && !color.isBlank()) {
            parts.add("สี " + color.strip());
        }
        if (texture != null && !texture.isBlank()) {
            parts.add("ผิว " + texture.strip());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * Never overwrites a note the business already wrote. The auto-derived colour/surface note
     * only fills in when there is nothing there yet; an existing note (typed by hand, or carried
     * forward from a prior revision) is kept exactly as-is rather than appended to — appending
     * risks garbling a business-authored sentence with a machine-formatted "สี X · ผิว Y" tacked
     * onto the end of it. At the one call site that exists today ({@code
     * ImportRequestService#resolveFactoryGroups}, building a brand-new draft's items) {@code
     * existingNote} is always null, so this always resolves to {@code derivedNote} in practice —
     * the merge is written generally so it stays correct if this is ever reused on a path where a
     * note can already be present.
     */
    static String mergeNote(String existingNote, String derivedNote) {
        return existingNote != null && !existingNote.isBlank() ? existingNote : derivedNote;
    }
}
