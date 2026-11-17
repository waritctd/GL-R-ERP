package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public final class ThicknessDefaultDtos {

    private ThicknessDefaultDtos() {}

    /** One (factory, collection) that needs a thickness, and what currently covers it. */
    public record ThicknessGapDto(
        long factoryId,
        String factoryName,
        String collection,
        int rowsMissingThickness,
        BigDecimal currentDefaultMm,
        boolean hasSizeLevelOverride
    ) {}
}
