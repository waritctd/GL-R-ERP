package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentDto;

/**
 * sales.expected_payment (V212). {@link #findByTicket} returns a deal's non-cancelled rows ordered by
 * {@code expected_week, expected_payment_id} — mockApi.expectedPayments mirrors this ORDER BY. Every write is keyed on
 * BOTH the row id and its ticket id, so a row id guessed through another deal's path matches nothing.
 */
@Repository
public class ExpectedPaymentRepository {
    private static final String SELECT = """
        SELECT ep.expected_payment_id, ep.ticket_id, ep.kind, ep.amount, ep.expected_week, ep.status,
               ep.receipt_id, r.receipt_ref, ep.created_by, ep.created_at, ep.updated_at
          FROM sales.expected_payment ep
          LEFT JOIN sales.payment_receipt r ON r.receipt_id = ep.receipt_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ExpectedPaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExpectedPaymentDto> findByTicket(long ticketId) {
        return jdbc.query(SELECT + " WHERE ep.ticket_id = :t AND ep.status <> 'CANCELLED'"
                + " ORDER BY ep.expected_week, ep.expected_payment_id",
            Map.of("t", ticketId), ExpectedPaymentRepository::map);
    }

    /** The row, only when it belongs to {@code ticketId}. */
    public Optional<ExpectedPaymentDto> findOnTicket(long ticketId, long id) {
        return jdbc.query(SELECT + " WHERE ep.ticket_id = :t AND ep.expected_payment_id = :id",
            Map.of("t", ticketId, "id", id), ExpectedPaymentRepository::map).stream().findFirst();
    }

    public long insert(long ticketId, String kind, BigDecimal amount, LocalDate week, long createdBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, :kind, :amount, :week, :by)
            RETURNING expected_payment_id
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("kind", kind)
                .addValue("amount", amount).addValue("week", week).addValue("by", createdBy), Long.class);
    }

    /** Edits an OPEN row; 0 when it is not OPEN (or not on this ticket). */
    public int updateOpen(long ticketId, long id, String kind, BigDecimal amount, LocalDate week) {
        return jdbc.update("""
            UPDATE sales.expected_payment
               SET kind = :kind, amount = :amount, expected_week = :week, updated_at = now()
             WHERE expected_payment_id = :id AND ticket_id = :t AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("id", id).addValue("kind", kind)
                .addValue("amount", amount).addValue("week", week));
    }

    /** OPEN → CANCELLED; 0 when it is not OPEN (or not on this ticket). */
    public int cancelOpen(long ticketId, long id) {
        return jdbc.update("""
            UPDATE sales.expected_payment SET status = 'CANCELLED', updated_at = now()
             WHERE expected_payment_id = :id AND ticket_id = :t AND status = 'OPEN'
            """, Map.of("t", ticketId, "id", id));
    }

    /**
     * A matched Express receipt marks the deal's OPEN expected payment it most likely is RECEIVED: one whose amount is
     * within the matcher's tolerance (±1% or ±฿10) first, then the nearest expected week to the receipt's date, then
     * the oldest row. Returns the row marked, or empty when the deal has no OPEN row (the receipt still counts).
     * Any kind: the matcher decides the receipt's kind itself, so it does not narrow on it.
     */
    public Optional<Long> markNearestOpenReceived(long ticketId, BigDecimal amount, LocalDate receiptDate,
                                                  long receiptId) {
        return markNearestOpenReceived(ticketId, null, amount, receiptDate, receiptId);
    }

    /**
     * The same rule, narrowed to {@code kinds} (null = any). A payment account records BY HAND has a kind account
     * chose, so it may only settle an expectation of that kind (slice 5, plan task 5.5) — see
     * {@link #expectedKindsFor}. Until slice 5 only the Express matcher set RECEIVED (V212's own comment predates it).
     */
    public Optional<Long> markNearestOpenReceived(long ticketId, Collection<String> kinds, BigDecimal amount,
                                                  LocalDate receiptDate, long receiptId) {
        if (kinds != null && kinds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("t", ticketId).addValue("amount", amount)
            .addValue("d", receiptDate).addValue("r", receiptId);
        String kindFilter = "";
        if (kinds != null) {
            kindFilter = " AND kind IN (:kinds)";
            params.addValue("kinds", kinds);
        }
        return jdbc.query("""
            UPDATE sales.expected_payment SET status = 'RECEIVED', receipt_id = :r, updated_at = now()
             WHERE expected_payment_id = (
                   SELECT expected_payment_id FROM sales.expected_payment
                    WHERE ticket_id = :t AND status = 'OPEN'%s
                    ORDER BY (abs(amount - :amount) <= GREATEST(amount * 0.01, 10)) DESC,
                             abs(expected_week - CAST(:d AS date)), expected_payment_id
                    LIMIT 1
                    FOR UPDATE)
            RETURNING expected_payment_id
            """.formatted(kindFilter), params, (rs, n) -> rs.getLong(1)).stream().findFirst();
    }

    /**
     * Which expected-payment kinds a receipt of {@code receiptKind} can settle: a DEPOSIT receipt only a DEPOSIT
     * expectation; a BALANCE receipt an ON_DELIVERY or BALANCE one; an ADJUSTMENT (a correction or refund, not cash the
     * rep expected) none.
     */
    public static Set<String> expectedKindsFor(String receiptKind) {
        return switch (receiptKind == null ? "" : receiptKind) {
            case "DEPOSIT" -> Set.of("DEPOSIT");
            case "BALANCE" -> Set.of("ON_DELIVERY", "BALANCE");
            default -> Set.of();
        };
    }

    private static ExpectedPaymentDto map(ResultSet rs, int rowNum) throws SQLException {
        long receipt = rs.getLong("receipt_id");
        Long receiptId = rs.wasNull() ? null : receipt;
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ExpectedPaymentDto(rs.getLong("expected_payment_id"), rs.getLong("ticket_id"), rs.getString("kind"),
            rs.getBigDecimal("amount"), rs.getObject("expected_week", LocalDate.class), rs.getString("status"),
            receiptId, rs.getString("receipt_ref"), rs.getLong("created_by"),
            created == null ? null : created.toInstant(), updated == null ? null : updated.toInstant());
    }
}
