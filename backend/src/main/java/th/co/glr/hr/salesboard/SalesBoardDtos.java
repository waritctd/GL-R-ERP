package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import th.co.glr.hr.ticket.QuietRule;

/** Read models for the rep's "งานของฉัน" and the manager/CEO "ภาพรวมทีมขาย" (spec 2026-11-15 §7.3/§7.4). */
public final class SalesBoardDtos {
    private SalesBoardDtos() {}

    /**
     * One open deal on the board (ACTIVE, not CLOSED_PAID). {@code value}/{@code currency} come from the deal's
     * headline-party quotation, else its latest live (APPROVED/ISSUED/SENT/ACCEPTED) one; when none is live the last
     * EXPIRED one is used and {@code valueExpired} is true. {@code lastTouchedAt}/{@code daysQuiet}/{@code quiet}
     * follow {@link QuietRule}; {@code lastActivityKind}/{@code lastActivityNote} are the latest deal_activity.
     */
    public record BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                            String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                            String currency, boolean valueExpired, String designerName, Integer winProbability,
                            String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                            String lastActivityKind, String lastActivityNote) {}

    /**
     * {@code followUp}: quiet deals, by daysQuiet × value then daysQuiet, max 5. {@code board}: every open deal.
     * {@code visitsThisWeek}: the caller's own visits in this ISO week (Mon–Sun, Asia/Bangkok), in
     * {@link VisitRepository}'s order.
     */
    public record MyWork(List<BoardDeal> followUp, List<BoardDeal> board, int quietDays,
                         List<VisitDtos.VisitDto> visitsThisWeek) {}

    /**
     * One owner's open deals in aggregate. {@code openValue} sums THB values only; any other currency is summed per
     * code in {@code otherCurrencyValues} and never mixed into the ฿ figure. {@code role} labels a manager's or the
     * CEO's own deals ({@code DivisionAccessPolicy#roleFor}); null when the owner has no login record.
     * {@code visitsDone} / {@code visitsPlanned} count this week's visits: DONE ones, and every one booked and not
     * cancelled (done included), so done / planned reads as "3 of 5 made".
     */
    public record RepRow(long employeeId, String name, String role, int openDeals, BigDecimal openValue,
                         Map<String, BigDecimal> otherCurrencyValues, int quietDeals, Instant lastUpdateAt,
                         int visitsDone, int visitsPlanned) {}

    /**
     * {@code stale}: quiet deals across the team, ordered as {@link MyWork#followUp}, max 20.
     * {@code teamVisitsThisWeek}: every rep's visits this ISO week. An owner with visits but no open deal still gets
     * a {@link RepRow} (0 deals).
     */
    public record TeamOverview(List<RepRow> reps, List<BoardDeal> stale, int quietDays,
                               List<VisitDtos.VisitDto> teamVisitsThisWeek) {}
}
