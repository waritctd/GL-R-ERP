package th.co.glr.hr.importrequest;

import java.util.ArrayList;
import java.util.List;

/** The F-SM-001 line-prefill fallback chain — pure and static, no Spring, no I/O, so the cascade itself is
 * unit-testable and mutation-checkable without a database, matching ImportRequestFormAssembler's own "pure and
 * static" discipline for the same reason. */
final class ImportRequestLinePrefill {

    private ImportRequestLinePrefill() {}

    /** First non-blank candidate, stripped. */
    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }

    /** The printed note sub-row: "สี <colour> · ผิว <surface>" — both when both are known, either alone when only
     * one is, and null (omit the sub-row entirely) when neither is. Never invents a placeholder for the missing
     * half. */
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

    /** Never overwrites a note the business already wrote. */
    static String mergeNote(String existingNote, String derivedNote) {
        return existingNote != null && !existingNote.isBlank() ? existingNote : derivedNote;
    }
}
