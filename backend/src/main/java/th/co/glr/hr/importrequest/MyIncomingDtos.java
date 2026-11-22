package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.util.List;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingLot;

/** ของที่ฉันสั่ง: every incoming lot and every unplanned quantity the caller may see (sales sees the whole team's).
 * No price, no factory document. {@code canOpenDeal}: the caller may open that deal's page; seeing the row never widens it. */
public final class MyIncomingDtos {
    private MyIncomingDtos() {}

    /** {@code kind}: PROJECT, SAMPLE, SHOWROOM or STOCK. A project row has a ticket; a no-project one names who it is for.
     * {@code lot} is null on a row of quantity still on no round ({@code waiting}). */
    public record MyIncomingRow(String kind, Long ticketId, String ticketCode, String who, String requesterName,
                                String factoryName, String docNumber, long importRequestId, String wantedBy,
                                boolean waiting, String product, String unit, BigDecimal qty, IncomingLot lot,
                                boolean canWithdraw, Long repId, String repName, boolean canOpenDeal, boolean canPair) {}

    public record MyIncomingResponse(List<MyIncomingRow> rows) {}
}
