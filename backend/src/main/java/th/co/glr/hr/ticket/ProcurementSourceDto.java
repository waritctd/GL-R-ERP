package th.co.glr.hr.ticket;

import java.time.Instant;
import java.util.List;

/**
 * Where each deal line comes from (import vs stock), for the stage-10 "จัดซื้อสินค้า" dialog.
 * {@code legacy}: the order was confirmed before the quotation carried the choice, so the rep still decides.
 * {@code pending}: the decision window is open and nothing has been decided yet (new deals too).
 * {@code editable}: legacy, not yet decided, and the caller may decide.
 */
public record ProcurementSourceDto(boolean legacy, boolean editable, boolean pending, Instant decidedAt, String quotationNumber,
                                   List<Line> lines) {
    public record Line(long itemId, boolean fromStock) {}
}
