package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record TicketItemRequest(
    @NotBlank String brand,
    @NotBlank String model,
    @NotBlank String color,
    @NotBlank String texture,
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
    String catalogProductCode
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
}
