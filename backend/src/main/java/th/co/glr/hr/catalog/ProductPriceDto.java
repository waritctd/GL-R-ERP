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
    BigDecimal price,
    String     currency,
    String     priceUnit,
    BigDecimal sqmPerPiece,
    // Quotation v2 (direct deal quotation, V165): the item editor's catalog typeahead autofills
    // thickness/pieces-per-box/sqm-per-box straight from the catalog row so Sales does not have to
    // re-type them (see docs/sales/quotation-v2-plan.md's Catalog section) -- appended at the end so every
    // pre-existing 13-arg construction site (CatalogRepository's other query, tests) keeps compiling.
    BigDecimal thicknessMm,
    BigDecimal pcsPerBox,
    BigDecimal sqmPerBox,
    // Item completeness rule (inline-deal-spec.md, owner ruling 2026-09-10): the editor autofills
    // ประเทศต้นทาง (+ its default lead-time range) from the catalog row the same way it already
    // autofills thickness/pieces-per-box -- price_catalog.factories.country via
    // product_prices.factory_id, the SAME base-table join CatalogRepository#findPricingKeys'
    // CatalogPricingKey already uses (never sales.factory_config.country -- V151). Appended last,
    // same reason as thicknessMm/pcsPerBox/sqmPerBox: every pre-existing 16-arg construction site
    // keeps compiling.
    String originCountryCode,
    // Unit-guessing heuristic removal (owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง"):
    // price_catalog.product_prices.width_mm/height_mm are ALWAYS millimetres (unlike the
    // free-text size_raw column, which mixes cm and mm) -- DealQuotationService#resolveSqmPerPiece
    // uses these to COMPUTE sqmPerPiece when the catalog row's own sqm_per_piece is absent,
    // instead of guessing the unit of a typed size string. Never used for a per_linear_m row (its
    // sqm_per_piece is linear metres, not area, and geometry disagrees with the catalogue's own
    // sqm_per_piece on ~1,500 further rows -- mesh/mosaic sheets at a clean 0.750 ratio -- so the
    // catalogue's own sqm_per_piece always wins when present). Appended last, same reason as
    // thicknessMm/pcsPerBox/sqmPerBox/originCountryCode above: every pre-existing 17-arg
    // construction site keeps compiling.
    BigDecimal widthMm,
    BigDecimal heightMm
) {}
