package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public record ProductPriceDto(
    long       priceId,
    long       factoryId,
    String     factoryName,
    String     productCode,
    String     grade,
    String     collection,
    String     productName,
    String     color,
    String     surface,
    String     sizeRaw,

    /** The size to SHOW a human, in centimetres — price_catalog.product_prices.size_cm (V174), a stored generated
     * column derived from width_mm/height_mm. Prefer this over #sizeRaw everywhere a size is displayed or printed. */
    String     sizeCm,
    BigDecimal price,
    String     currency,
    String     priceUnit,
    BigDecimal sqmPerPiece,
    // Quotation v2: the item editor's catalog typeahead autofills thickness/pieces-per-box/sqm-per-box straight
    // from the catalog row so Sales does not have to re-type them -- appended at the end so every pre-existing
    // 13-arg construction site keeps compiling.
    BigDecimal thicknessMm,
    BigDecimal pcsPerBox,
    BigDecimal sqmPerBox,
    // Item completeness rule: the editor autofills ประเทศต้นทาง from the catalog row the same way it already
    // autofills thickness/pieces-per-box -- price_catalog.factories.country via product_prices.factory_id, the
    // SAME base-table join CatalogRepository#findPricingKeys' CatalogPricingKey already uses.
    String originCountryCode,
    // Unit-guessing heuristic removal ไม่มีค่อยคำนวนเอง"): price_catalog.product_prices.width_mm/height_mm are
    // ALWAYS millimetres. Appended last, same reason as thicknessMm/pcsPerBox/sqmPerBox/originCountryCode above:
    // every pre-existing 17-arg construction site keeps compiling.
    BigDecimal widthMm,
    BigDecimal heightMm
) {}
