package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record TicketItemRequest(
    @NotBlank String brand,
    @NotBlank String model,
    // color and texture are OPTIONAL (deliberate sales API contract change, 2026-08-10). They were
    // @NotBlank, which the create-deal form mirrored as required fields -- but the price catalog
    // populates `color` on only 21% of its 22,455 active rows and `surface` on 22% (both only for
    // factories CDE/LEA/Panaria, plus Bode for surface), so a rep picking a catalogued product had
    // to invent a colour and a finish on roughly four of every five picks just to get past
    // validation. Invented specs on a deal line are worse than absent ones: they flow into the
    // pricing request and the quotation as though someone had checked them.
    //
    // sales.ticket_item.color and .texture were already NULLable, so nothing migrates. brand,
    // model and size stay @NotBlank -- factory name, collection and size_raw are present on
    // essentially every catalog row, and brand additionally backs a NOT NULL column.
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
    // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110): the catalog product picked
    // when this line was created/edited, so PricingRequestCreateModal.emptyItemFromTicketItem
    // can seed productId/catalogProductCode without re-searching. Null for a hand-typed
    // ("custom") line, or once the frontend clears the link because the user hand-edited a
    // descriptive field after picking it (see TicketCreateModal.jsx's updateItem).
    Long catalogPriceId,
    String catalogProductCode,
    // Stock-sourced pricing (owner ruling, see the matching migration V183's header for the full
    // context): sales may mark a line as "from warehouse" and type in its own selling price. This
    // is CAPTURE-ONLY today -- nothing downstream (PricingRequest, quotation, commission) reads
    // this pair yet, so a flagged line can still be routed through a PricingRequest exactly as
    // before; wiring it into that chain is a later follow-up. Null/false is the "not from stock,
    // priced normally" default every pre-existing call site keeps via the compat constructor
    // below. TicketService validates the pairing (stockSalePrice required+positive iff
    // sourcedFromStock is true) before either write path (create/editItems) persists it -- see
    // TicketService#create's item-validation loop and #mergeEditedItemsPreservingPricing.
    Boolean sourcedFromStock,
    BigDecimal stockSalePrice
) {
    // Compat shape for call sites written before V110 (mostly tests) that construct this
    // record positionally without the two new trailing fields -- defaults both to null,
    // same "no catalog link" meaning as an explicitly-cleared one.
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

    // Compat shape for call sites written before the stock-sourced-pricing fields (the pre-V183
    // canonical shape, ending catalogProductCode) -- defaults sourcedFromStock/stockSalePrice to
    // false/null, same "not from stock" meaning as an explicit false.
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
