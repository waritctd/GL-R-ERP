package th.co.glr.hr.common;

import java.util.regex.Pattern;

/**
 * Display-unit helper for tile sizes. Sales data mixes sizes that carry a unit ("60x60 ซม.", "60x120 cm") with
 * bare ones ("20x30.5", centimetres since the 2026-10-29 ruling). Anchored at the end of the trimmed value, so a bare size (ends in a digit)
 * can never match and still gets its unit.
 */
public final class SizeUnit {

    private static final Pattern ALREADY_HAS_UNIT =
        Pattern.compile("(?i)(ซม\\.?|มม\\.?|ม\\.?|นิ้ว|cm\\.?|mm\\.?|m\\.?|in\\.?|\")\\s*$");

    private SizeUnit() {}

    public static boolean hasUnit(String size) {
        return size != null && ALREADY_HAS_UNIT.matcher(size.trim()).find();
    }

    /** Owner ruling 2026-10-30: IR factory email/PDF show cm. Unitless gets " cm"; blank stays as-is. */
    public static String withCmUnit(String size) {
        if (size == null || size.isBlank()) return size;
        return hasUnit(size) ? size : size + " cm";
    }
}
