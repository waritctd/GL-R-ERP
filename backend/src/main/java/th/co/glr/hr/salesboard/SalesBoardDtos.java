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
     *
     * <p>Slice 6.2, the card's drawn details. {@code quotationNumber} is the number of the SAME quotation
     * {@code value} comes from (null when the value is the import / estimate fallback, or there is none);
     * {@code buyerName} is {@code sales.ticket.buyer_name}. The money state: {@code expectedDepositWeek} is the
     * earliest OPEN {@code DEPOSIT} row in {@code sales.expected_payment} (V212) — a WEEK, its Monday, never a day;
     * {@code depositReceived} is true when the payment track is DEPOSIT_PAID / AWAITING_FINAL_PAYMENT on a deal whose
     * deposit policy did not bypass the deposit ({@code DepositPolicy#NON_REQUIRED} reach AWAITING_FINAL_PAYMENT
     * without one); {@code paymentDueDate} / {@code overdue} are the deal's {@code TicketSummaryDto#paymentDueDate} /
     * {@code #overdue}, read through {@code TicketRepository#paymentDueStates} — the one derivation the finance
     * worklist uses, never a second copy — and SERVED ONLY on rows a screen renders as a DealBoard card, from รอมัดจำ
     * (S6) on: {@code MyWork.board}, {@code TeamBoard.board}, and the RETURNING rows of {@code MyWork.followUp}
     * (งานของฉัน's "เฉพาะงานเงียบ" view). Everywhere else — earlier columns, the active {@code followUp} rows (ควรตามต่อ
     * list only), {@code TeamOverview.stale} — they are null / false, never computed. Every board read is already scoped (own deals for my-work, sales_manager / ceo for the team reads), and
     * those roles already read the deal's value, so these add no exposure.
     *
     * <p>สั่งเพิ่ม: a project is ONE row however many add-on orders it has ({@code SalesBoardRepository#fold}).
     * {@code orderCount} is the orders the row stands for (1 for an ordinary deal), {@code value} their sum when the
     * currencies agree, and {@code orderCodes} every order's code (root first) so a search by an add-on's own number
     * finds the project's row. {@code openOrderCount} of them are open (the value counts only those) and
     * {@code leadTicketId} is the order whose state the row shows (the payment-due chip is looked up by it);
     * {@code ticketId} is the id the card opens. Rule: {@code ticket.ProjectFold}.
     * {@code awardedBuyerName}: the AWARDED buyer of the order quotation (owner rule #65,
     * {@code TicketRepository#AWARDED_BUYER_SQL}); on a project row the first order's, with " +N" for N other buyers;
     * null when no party is awarded (the card then names {@code customerName}).
     */
    public record BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                            String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                            String currency, boolean valueExpired, String designerName, Integer winProbability,
                            String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                            String lastActivityKind, String lastActivityNote, boolean returning,
                            LocalDate pauseReturnOn, String quotationNumber, String buyerName,
                            LocalDate expectedDepositWeek, boolean depositReceived, LocalDate paymentDueDate,
                            boolean overdue, int orderCount, List<String> orderCodes, int openOrderCount,
                            long leadTicketId, String awardedBuyerName) {
        /** The shape before add-on folding: one order, no codes. */
        public BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                         String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                         String currency, boolean valueExpired, String designerName, Integer winProbability,
                         String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                         String lastActivityKind, String lastActivityNote, boolean returning,
                         LocalDate pauseReturnOn, String quotationNumber, String buyerName,
                         LocalDate expectedDepositWeek, boolean depositReceived, LocalDate paymentDueDate,
                         boolean overdue) {
            this(ticketId, title, customerName, projectName, ownerId, ownerName, salesStage, repCode, boardColumn,
                value, currency, valueExpired, designerName, winProbability, expectedQuarter, lastTouchedAt,
                daysQuiet, quiet, lastActivityKind, lastActivityNote, returning, pauseReturnOn, quotationNumber,
                buyerName, expectedDepositWeek, depositReceived, paymentDueDate, overdue, 1, List.of(), 1, ticketId, null);
        }

        /** The shape before slice 6.2's card fields: no quotation number / buyer, no money state. */
        public BoardDeal(long ticketId, String title, String customerName, String projectName, long ownerId,
                         String ownerName, String salesStage, String repCode, int boardColumn, BigDecimal value,
                         String currency, boolean valueExpired, String designerName, Integer winProbability,
                         String expectedQuarter, Instant lastTouchedAt, int daysQuiet, boolean quiet,
                         String lastActivityKind, String lastActivityNote, boolean returning,
                         LocalDate pauseReturnOn) {
            this(ticketId, title, customerName, projectName, ownerId, ownerName, salesStage, repCode, boardColumn,
                value, currency, valueExpired, designerName, winProbability, expectedQuarter, lastTouchedAt,
                daysQuiet, quiet, lastActivityKind, lastActivityNote, returning, pauseReturnOn, null, null, null,
                false, null, false);
        }

        /** Copy with the finance summary's payment-due state ({@code TicketRepository#paymentDueStates}). */
        public BoardDeal withPaymentDue(LocalDate paymentDueDate, boolean overdue) {
            return new BoardDeal(ticketId, title, customerName, projectName, ownerId, ownerName, salesStage, repCode,
                boardColumn, value, currency, valueExpired, designerName, winProbability, expectedQuarter,
                lastTouchedAt, daysQuiet, quiet, lastActivityKind, lastActivityNote, returning, pauseReturnOn,
                quotationNumber, buyerName, expectedDepositWeek, depositReceived, paymentDueDate, overdue,
                orderCount, orderCodes, openOrderCount, leadTicketId, awardedBuyerName);
        }

        /**
         * The project's row (ProjectFold): this lead order's state under the root's identity and the folded orders:
         * {@code linkId} (the id the card opens), the root's title / customer / project, the owner, the value of the
         * open orders, and the order count / open count / codes.
         */
        public BoardDeal asProject(long linkId, BoardDeal root, long ownerId, String ownerName, BigDecimal totalValue,
                                   String totalCurrency, int count, int openCount, List<String> codes, String awardedBuyer) {
            return new BoardDeal(linkId, root.title, root.customerName, root.projectName, ownerId, ownerName,
                salesStage, repCode, boardColumn, totalValue, totalCurrency, valueExpired, designerName,
                winProbability, expectedQuarter, lastTouchedAt, daysQuiet, quiet, lastActivityKind, lastActivityNote,
                returning, pauseReturnOn, quotationNumber, buyerName, expectedDepositWeek, depositReceived,
                paymentDueDate, overdue, count, codes, openCount, ticketId, awardedBuyer);
        }

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
