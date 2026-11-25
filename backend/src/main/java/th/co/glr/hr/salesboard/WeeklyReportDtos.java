package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** GET /api/sales/weekly-report (spec 0003). Money is VAT-inclusive cash; dates are Asia/Bangkok. */
public final class WeeklyReportDtos {
    private WeeklyReportDtos() {}

    /** One week of the ledger. {@code slipped} is the part of this (current) week's three OPEN buckets that came from
     * earlier weeks; past weeks carry only {@code received}. */
    public record WeeklyLedgerWeek(LocalDate weekStart, BigDecimal received, BigDecimal deliveredAwaiting,
                             BigDecimal deliveriesScheduled, BigDecimal depositsExpected, BigDecimal slipped,
                             boolean isCurrent, boolean isPast) {}

    /** The month holding the report week's Thursday. {@code total} = received + the three OPEN buckets. */
    public record WeeklyLedger(String month, List<WeeklyLedgerWeek> weeks, BigDecimal received, BigDecimal deliveredAwaiting,
                         BigDecimal deliveriesScheduled, BigDecimal depositsExpected, BigDecimal total) {}

    /** The earliest OPEN expected payment of an order; {@code amount} is what is still to come. */
    public record WeeklyNextPayment(long id, String kind, BigDecimal amount, LocalDate week) {}

    public record WeeklyOrderRow(long ticketId, String code, String title, String customerName, String projectName,
                           long ownerId, String ownerName, String salesStage, Instant orderConfirmedAt,
                           BigDecimal value, BigDecimal received, BigDecimal outstanding, WeeklyNextPayment nextPayment,
                           boolean slipped) {}

    public record WeeklyRepRow(long ownerId, String name, int newDeals, int stageMoves, int quotationsIssued,
                         int ordersConfirmed, int lost, int visitsDone, int visitsPlanned, int visitsDue,
                         int visitsDueWithResult, int ordersOpen, int ordersWithDate, int entriesToday,
                         Instant lastEntryAt) {}

    public record WeeklyTodayEntry(Instant at, String kind, String label, Long ticketId, String ticketTitle) {}

    /** {@code moneyFrom} is null when no cutoff is set; {@code today} is empty unless the caller reads their own. */
    public record WeeklyReport(LocalDate weekStart, LocalDate weekEnd, LocalDate moneyFrom, String scope,
                               WeeklyLedger ledger, List<WeeklyOrderRow> orders, List<WeeklyRepRow> reps, List<WeeklyTodayEntry> today) {}
}
