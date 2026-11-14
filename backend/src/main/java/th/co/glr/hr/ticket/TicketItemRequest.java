package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record TicketItemRequest(
    @NotBlank String brand,
    @NotBlank String model,
    // color and texture are OPTIONAL (deliberate sales API contract change, 2026-08-10). Invented specs on a deal
    // line are worse than absent ones: they flow into the pricing request and the quotation as though someone had
    // checked them.
    String color,
    String texture,
    @NotBlank String size,
    String factory,
    BigDecimal qty,
    BigDecimal qtySqm,
    String unitBasis,
    BigDecimal rawPrice,
    String rawCurrency,
    String rawUnit,
    BigDecimal proposedPrice,
    String currency,
    // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110): the catalog product picked when this line was
    // created/edited, so PricingRequestCreateModal.emptyItemFromTicketItem can seed productId/catalogProductCode
    // without re-searching.
    Long catalogPriceId,
    String catalogProductCode,
    // Stock-sourced pricing (owner ruling, see the matching migration V183's header for the full context): sales
    // may mark a line as "from warehouse" and type in its own selling price.
    Boolean sourcedFromStock,
    BigDecimal stockSalePrice
) {
    // Compat shape for call sites written before V110 (mostly tests) that construct this record positionally
    // without the two new trailing fields -- defaults both to null, same "no catalog link" meaning as an
    // explicitly-cleared one.
    public TicketItemRequest(
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        String unitBasis,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        String currency
    ) {
        this(brand, model, color, texture, size, factory, qty, qtySqm, unitBasis,
            rawPrice, rawCurrency, rawUnit, proposedPrice, currency, null, null);
    }

    // Compat shape for call sites written before the stock-sourced-pricing fields (the pre-V183 canonical shape,
    // ending catalogProductCode) -- defaults sourcedFromStock/stockSalePrice to false/null, same "not from stock"
    // meaning as an explicit false.
    public TicketItemRequest(
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        String unitBasis,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        String currency,
        Long catalogPriceId,
        String catalogProductCode
    ) {
        this(brand, model, color, texture, size, factory, qty, qtySqm, unitBasis,
            rawPrice, rawCurrency, rawUnit, proposedPrice, currency,
            catalogPriceId, catalogProductCode, false, null);
    }
}
