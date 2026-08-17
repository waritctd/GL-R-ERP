package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public final class ThicknessDefaultDtos {

    private ThicknessDefaultDtos() {}

    /**
     * One (factory, collection) that needs a thickness, and what currently covers it.
     *
     * @param rowsMissingThickness how many ACTIVE catalogue rows this one entry would unblock --
     *        the CEO works top-down, so this is what the list sorts by
     * @param currentDefaultMm     null when nothing covers it yet
     * @param hasSizeLevelOverride true when a size-specific override also exists for this
     *        collection; a bulk collection-level save leaves those untouched
     */
    public record ThicknessGapDto(
        long factoryId,
        String factoryName,
        String collection,
        int rowsMissingThickness,
        BigDecimal currentDefaultMm,
        boolean hasSizeLevelOverride
    ) {}
}
