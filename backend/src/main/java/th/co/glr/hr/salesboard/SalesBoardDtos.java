package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read models for the rep's "งานของฉัน" and the manager/CEO "ภาพรวมทีมขาย" (spec 2026-11-15 §7.3/§7.4). */
public final class SalesBoardDtos {
    private SalesBoardDtos() {}

    /**
     * One ACTIVE deal on the board. {@code value} is the grand total of the deal's latest
     * APPROVED/ISSUED/SENT/ACCEPTED quotation, else null. {@code lastTouchedAt}/{@code daysQuiet}/{@code quiet}
     * follow {@link QuietRule}.
     */
    public record BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                            String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                            String designerName, Integer winProbability, String expectedQuarter,
                            Instant lastTouchedAt, int daysQuiet, boolean quiet) {}

    /** {@code followUp}: quiet deals, by daysQuiet × value then daysQuiet, max 5. {@code board}: every ACTIVE deal. */
    public record MyWork(List<BoardDeal> followUp, List<BoardDeal> board, int quietDays) {}

    /** One sales rep's ACTIVE deals in aggregate. {@code lastUpdateAt} is their newest deal's last touch. */
    public record RepRow(long employeeId, String name, int openDeals, BigDecimal openValue, int quietDeals,
                         Instant lastUpdateAt) {}

    /** {@code stale}: quiet deals across the team, ordered as {@link MyWork#followUp}, max 20. */
    public record TeamOverview(List<RepRow> reps, List<BoardDeal> stale, int quietDays) {}
}
