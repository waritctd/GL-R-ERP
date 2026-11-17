package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
     * {@code returning} is true for a PAUSED deal whose วันที่จะกลับมาดู ({@code pauseReturnOn}, V220) has arrived
     * ({@link QuietRule#returningSql}): such a row is never on the board, only in ควรตามต่อ / the team's stale list,
     * and {@code quiet} is false on it (that rule is ACTIVE-only).
     */
    public record BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                            String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                            String currency, boolean valueExpired, String designerName, Integer winProbability,
                            String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                            String lastActivityKind, String lastActivityNote, boolean returning,
                            LocalDate pauseReturnOn) {
        /** The shape before {@code returning}/{@code pauseReturnOn}: an ACTIVE board row. */
        public BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                         String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                         String currency, boolean valueExpired, String designerName, Integer winProbability,
                         String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                         String lastActivityKind, String lastActivityNote) {
            this(ticketId, title, customerName, projectName, ownerId, ownerName, salesStage, repCode, boardColumn,
                value, currency, valueExpired, designerName, winProbability, expectedQuarter, lastTouchedAt,
                daysQuiet, quiet, lastActivityKind, lastActivityNote, false, null);
        }
    }

    /**
     * {@code followUp}: quiet deals plus the caller's returning paused deals (V220), by daysQuiet × value then
     * daysQuiet, max 5. {@code board}: every open deal.
     * {@code visitsThisWeek}: the caller's own visits in this ISO week (Mon–Sun, Asia/Bangkok), in
     * {@link VisitRepository}'s order.
     */
    public record MyWork(List<BoardDeal> followUp, List<BoardDeal> board, int quietDays,
                         List<VisitDtos.VisitDto> visitsThisWeek, CashMonth cash) {}

    /**
     * GET /api/sales/team-board: every rep's open deals in {@link BoardDeal} shape (each row names its owner), for the
     * sales_manager / ceo งานทั้งหมด board. {@code quietDays} is {@link QuietRule#quietDays()}, as on {@link MyWork}.
     */
    public record TeamBoard(List<BoardDeal> board, int quietDays) {}

    /**
     * One week of the เงินที่จะเข้า bars. {@code weekStart} is the ISO week's Monday; the first week of the month also
     * holds any receipt dated before that Monday (the 1st falling mid-week), so the weeks' {@code received} sum to
     * {@link CashMonth#received}. The three expected buckets are OPEN rows due in this week.
     */
    public record CashWeek(LocalDate weekStart, BigDecimal deliveredAwaiting, BigDecimal deliveriesScheduled,
                           BigDecimal depositsExpected, BigDecimal received) {}

    /**
     * Cash for one calendar month (Asia/Bangkok), spec 2026-11-15 §7.3 item 3 / §7.4 item 1 — the weekly email's five
     * lines. {@code month} is "YYYY-MM". Expected rows count by their {@code expected_week} Monday falling in the
     * month; rows on lost/cancelled deals never count.
     * <ul>
     *   <li>{@code forecast} — OPEN + RECEIVED expected payments due in the month (unweighted, spec §9);</li>
     *   <li>{@code received} — the signed sum of THB {@code payment_receipt} rows received in the month (DEPOSIT and
     *       BALANCE add, ADJUSTMENT subtracts — TicketRepository.SIGNED_RECEIPT_SUM), whatever their source;</li>
     *   <li>{@code deliveredAwaiting} — OPEN ON_DELIVERY / BALANCE on deals at DELIVERED;</li>
     *   <li>{@code deliveriesScheduled} — OPEN ON_DELIVERY on deals at DELIVERY_SCHEDULING or PROCUREMENT;</li>
     *   <li>{@code depositsExpected} — OPEN DEPOSIT.</li>
     * </ul>
     */
    public record CashMonth(String month, BigDecimal forecast, BigDecimal received, BigDecimal deliveredAwaiting,
                            BigDecimal deliveriesScheduled, BigDecimal depositsExpected, List<CashWeek> weeks) {}

    /**
     * One owner's open deals in aggregate. {@code openValue} sums THB values only; any other currency is summed per
     * code in {@code otherCurrencyValues} and never mixed into the ฿ figure. {@code role} labels a manager's or the
     * CEO's own deals ({@code DivisionAccessPolicy#roleFor}); null when the owner has no login record.
     * {@code visitsDone} / {@code visitsPlanned} count this week's visits: DONE ones, and every one booked and not
     * cancelled (done included), so done / planned reads as "3 of 5 made". {@code expected} / {@code received} are
     * that owner's {@link CashMonth#forecast} / {@link CashMonth#received} for the current month.
     */
    public record RepRow(long employeeId, String name, String role, int openDeals, BigDecimal openValue,
                         Map<String, BigDecimal> otherCurrencyValues, int quietDeals, Instant lastUpdateAt,
                         int visitsDone, int visitsPlanned, BigDecimal expected, BigDecimal received) {}

    /**
     * {@code stale}: quiet deals plus returning paused deals (V220) across the team, ordered as
     * {@link MyWork#followUp}, max 20. A rep's {@link RepRow#quietDeals} counts both the same way.
     * {@code teamVisitsThisWeek}: every rep's visits this ISO week. An owner with visits but no open deal still gets
     * a {@link RepRow} (0 deals).
     */
    public record TeamOverview(List<RepRow> reps, List<BoardDeal> stale, int quietDays,
                               List<VisitDtos.VisitDto> teamVisitsThisWeek, CashMonth cash) {}
}
