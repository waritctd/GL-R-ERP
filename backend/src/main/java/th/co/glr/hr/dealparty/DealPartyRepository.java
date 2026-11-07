package th.co.glr.hr.dealparty;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.dealparty.DealPartyDtos.DealPartyDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OpenDealDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OpenDealPartyDto;
import th.co.glr.hr.dealquotation.WastageCalculator;

/** {@code sales.deal_party} (V204) plus the few ticket writes a bid outcome needs. Plain JDBC, like its neighbours. */
@Repository
public class DealPartyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DealPartyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One row per party with the customer/contact names and the party's LATEST live quotation (DEAL_DIRECT or
     * PRICING_REQUEST, not SUPERSEDED/CANCELLED) read alongside, so the deal page lists each ผู้รับเหมา with its
     * document and amount in one round trip. Roles order DESIGNER, OWNER, BUYER; within a role, creation order.
     */
    private static final String SELECT = """
        SELECT p.party_id, p.ticket_id, p.customer_id, c.name AS customer_name,
               p.contact_id, NULLIF(TRIM(CONCAT_WS(' ', ct.first_name, ct.last_name)), '') AS contact_name,
               p.role, p.status, p.not_awarded_reason, p.is_headline, p.split_ticket_id, st.code AS split_ticket_code,
               lq.quotation_id AS latest_quotation_id, lq.number AS latest_quotation_number,
               lq.doc_status AS latest_quotation_status, lq.total_amount AS latest_subtotal,
               lq.document_language AS latest_language,
               p.added_by, p.created_at, p.updated_at
          FROM sales.deal_party p
          JOIN customers.customer c ON c.customer_id = p.customer_id
          LEFT JOIN customers.contact ct ON ct.contact_id = p.contact_id
          LEFT JOIN sales.ticket st ON st.ticket_id = p.split_ticket_id
          LEFT JOIN LATERAL (
               SELECT quotation_id, number, doc_status, total_amount, document_language
                 FROM sales.quotation q
                WHERE q.party_id = p.party_id
                  AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED')
                ORDER BY q.quotation_id DESC
                LIMIT 1) lq ON TRUE
        """;

    private static final String ORDER = """
         ORDER BY CASE p.role WHEN 'DESIGNER' THEN 0 WHEN 'OWNER' THEN 1 ELSE 2 END, p.party_id
        """;

    public List<DealPartyDto> findByTicket(long ticketId) {
        return jdbc.query(SELECT + " WHERE p.ticket_id = :ticketId " + ORDER,
            Map.of("ticketId", ticketId), (rs, n) -> map(rs));
    }

    public Optional<DealPartyDto> findById(long partyId) {
        return jdbc.query(SELECT + " WHERE p.party_id = :id ", Map.of("id", partyId), (rs, n) -> map(rs))
            .stream().findFirst();
    }

    public Optional<DealPartyDto> findByTicketCustomerRole(long ticketId, long customerId, String role) {
        return jdbc.query(SELECT + " WHERE p.ticket_id = :ticketId AND p.customer_id = :customerId AND p.role = :role ",
            Map.of("ticketId", ticketId, "customerId", customerId, "role", role), (rs, n) -> map(rs))
            .stream().findFirst();
    }

    public long insert(long ticketId, long customerId, Long contactId, String role, String status, boolean headline,
                       Long addedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.deal_party (ticket_id, customer_id, contact_id, role, status, is_headline, added_by)
            VALUES (:ticketId, :customerId, :contactId, :role, :status, :headline, :addedBy)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId).addValue("customerId", customerId).addValue("contactId", contactId)
                .addValue("role", role).addValue("status", status).addValue("headline", headline)
                .addValue("addedBy", addedBy),
            keys, new String[]{"party_id"});
        return keys.getKey().longValue();
    }

    public int updateStatus(long partyId, String status) {
        return jdbc.update("""
            UPDATE sales.deal_party SET status = :status, not_awarded_reason = NULL, updated_at = now()
             WHERE party_id = :id
            """, Map.of("id", partyId, "status", status));
    }

    public int markNotAwarded(long partyId, String reason) {
        return jdbc.update("""
            UPDATE sales.deal_party SET status = 'NOT_AWARDED', not_awarded_reason = :reason, is_headline = FALSE,
                   updated_at = now()
             WHERE party_id = :id
            """, Map.of("id", partyId, "reason", reason));
    }

    public int markAwarded(long partyId) {
        return jdbc.update("""
            UPDATE sales.deal_party SET status = 'AWARDED', not_awarded_reason = NULL, updated_at = now()
             WHERE party_id = :id
            """, Map.of("id", partyId));
    }

    public int markAwardedSplit(long partyId, long splitTicketId) {
        return jdbc.update("""
            UPDATE sales.deal_party SET status = 'AWARDED_SPLIT', split_ticket_id = :split, is_headline = FALSE,
                   not_awarded_reason = NULL, updated_at = now()
             WHERE party_id = :id
            """, Map.of("id", partyId, "split", splitTicketId));
    }

    public int updateContact(long partyId, Long contactId) {
        return jdbc.update("UPDATE sales.deal_party SET contact_id = :contactId, updated_at = now() WHERE party_id = :id",
            new MapSqlParameterSource().addValue("id", partyId).addValue("contactId", contactId));
    }

    /** Exactly one headline per deal (partial unique index): clear the others first, in the same statement batch. */
    public void setHeadline(long ticketId, long partyId) {
        jdbc.update("UPDATE sales.deal_party SET is_headline = FALSE, updated_at = now() WHERE ticket_id = :ticketId AND is_headline",
            Map.of("ticketId", ticketId));
        jdbc.update("UPDATE sales.deal_party SET is_headline = TRUE, updated_at = now() WHERE party_id = :id AND ticket_id = :ticketId",
            Map.of("id", partyId, "ticketId", ticketId));
    }

    public int delete(long partyId) {
        return jdbc.update("DELETE FROM sales.deal_party WHERE party_id = :id", Map.of("id", partyId));
    }

    public boolean hasAnyQuotation(long partyId) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sales.quotation WHERE party_id = :id)",
            Map.of("id", partyId), Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    /** The party's newest APPROVED direct quotation, the document a bid outcome confirms the order from. */
    public Optional<Long> findLatestApprovedDirectQuotationId(long partyId) {
        return jdbc.query("""
            SELECT quotation_id FROM sales.quotation
             WHERE party_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'
             ORDER BY quotation_id DESC LIMIT 1
            """, Map.of("id", partyId), (rs, n) -> rs.getLong("quotation_id")).stream().findFirst();
    }

    // ── ticket writes a bid outcome needs ───────────────────────────────────────────────────

    /** The winner becomes the deal's bill-to: customer, contact AND title (a quotation-first deal is titled after its customer). */
    public int setBillTo(long ticketId, long customerId, String customerName, Long contactId) {
        return jdbc.update("""
            UPDATE sales.ticket
               SET customer_id = :customerId, customer_name = :customerName, contact_id = :contactId,
                   title = :customerName, updated_at = now()
             WHERE ticket_id = :ticketId
            """, new MapSqlParameterSource()
                .addValue("ticketId", ticketId).addValue("customerId", customerId)
                .addValue("customerName", customerName).addValue("contactId", contactId));
    }

    public int countChildren(long parentTicketId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket WHERE parent_ticket_id = :p",
            Map.of("p", parentTicketId), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Fork a child deal for a second winner: the parent's project, owner, keyer, channel, policies and tracking
     * copied; the child's bill-to is the winner; {@code quotation_only} TRUE so the confirmation path that follows
     * flips it exactly as it does for a quotation-first deal; {@code parent_ticket_id} links back.
     */
    public long forkChild(long parentTicketId, String code, long customerId, String customerName, Long contactId,
                          long keyedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.ticket
                (code, title, status, priority, created_by, keyed_by, customer_name, customer_id, project_id, contact_id,
                 note, entry_channel, next_follow_up_at, quotation_only, parent_ticket_id,
                 tender_requirement, deposit_policy, win_probability, designer_name, owner_name)
            SELECT :code, :customerName, 'draft', t.priority, t.created_by, :keyedBy, :customerName, :customerId,
                   t.project_id, :contactId, t.note, t.entry_channel, t.next_follow_up_at, TRUE, t.ticket_id,
                   t.tender_requirement, t.deposit_policy, t.win_probability, t.designer_name, t.owner_name
              FROM sales.ticket t
             WHERE t.ticket_id = :parentId
            """, new MapSqlParameterSource()
                .addValue("code", code).addValue("customerName", customerName).addValue("customerId", customerId)
                .addValue("contactId", contactId).addValue("keyedBy", keyedBy).addValue("parentId", parentTicketId),
            keys, new String[]{"ticket_id"});
        return keys.getKey().longValue();
    }

    /** Move a party's documents to its child deal (and to the child's own party row). */
    public int moveQuotations(long fromPartyId, long toTicketId, long toPartyId) {
        return jdbc.update("""
            UPDATE sales.quotation SET ticket_id = :toTicket, party_id = :toParty, updated_at = now()
             WHERE party_id = :fromParty
            """, Map.of("fromParty", fromPartyId, "toTicket", toTicketId, "toParty", toPartyId));
    }

    // ── the editor's "this project already has your deal" prompt ────────────────────────────

    /**
     * The actor's (or, for {@code allReps}, anyone's) ACTIVE, pre-outcome deals whose project name matches
     * {@code nameKey} ({@code customers.project.name_key}: lower-cased, whitespace-collapsed). Newest first.
     */
    public List<OpenDealDto> findOpenDealsForProject(String nameKey, long actorId, boolean allReps) {
        List<OpenDealDto> deals = jdbc.query("""
            SELECT t.ticket_id, t.code, p.name AS project_name, t.sales_stage, t.customer_name,
                   (SELECT COUNT(*) FROM sales.deal_party b WHERE b.ticket_id = t.ticket_id AND b.role = 'BUYER') AS buyer_count
              FROM sales.ticket t
              JOIN customers.project p ON p.project_id = t.project_id
             WHERE p.name_key = :nameKey
               AND t.lifecycle = 'ACTIVE'
               AND t.status NOT IN ('closed', 'cancelled')
               AND t.parent_ticket_id IS NULL
               AND t.sales_stage IN ('LEAD_APPROACH','PRESENTATION','SPEC_APPROVED','QUOTE_DESIGN_SIDE','QUOTE_OWNER',
                                     'OWNER_SIGNOFF','AWAITING_BUYER','QUOTE_BUYER','NEGOTIATION')
               AND NOT EXISTS (SELECT 1 FROM sales.deal_party a WHERE a.ticket_id = t.ticket_id AND a.status = 'AWARDED')
               AND (:allReps OR t.created_by = :actorId OR t.keyed_by = :actorId)
             ORDER BY t.ticket_id DESC
             LIMIT 10
            """, new MapSqlParameterSource().addValue("nameKey", nameKey).addValue("actorId", actorId).addValue("allReps", allReps),
            (rs, n) -> new OpenDealDto(rs.getLong("ticket_id"), rs.getString("code"), rs.getString("project_name"),
                rs.getString("sales_stage"), rs.getString("customer_name"), rs.getInt("buyer_count"), List.of()));
        return deals.stream().map(d -> new OpenDealDto(d.id(), d.code(), d.projectName(), d.salesStage(), d.customerName(),
            d.buyerCount(), jdbc.query("""
                SELECT p.party_id, c.name AS customer_name, p.role, p.status
                  FROM sales.deal_party p JOIN customers.customer c ON c.customer_id = p.customer_id
                 WHERE p.ticket_id = :ticketId
                 ORDER BY CASE p.role WHEN 'DESIGNER' THEN 0 WHEN 'OWNER' THEN 1 ELSE 2 END, p.party_id
                """, Map.of("ticketId", d.id()),
                (rs, n) -> new OpenDealPartyDto(rs.getLong("party_id"), rs.getString("customer_name"),
                    rs.getString("role"), rs.getString("status"))))).toList();
    }

    /** The same normalisation as {@code customers.project.name_key} (V204), so the two can never disagree. */
    public static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static DealPartyDto map(ResultSet rs) throws SQLException {
        BigDecimal subtotal = rs.getBigDecimal("latest_subtotal");
        BigDecimal amount = null;
        if (subtotal != null) {
            String language = rs.getString("latest_language");
            amount = subtotal.add(WastageCalculator.vat(subtotal,
                language == null ? WastageCalculator.DOCUMENT_LANGUAGE_TH : language));
        }
        return new DealPartyDto(
            rs.getLong("party_id"), rs.getLong("ticket_id"), rs.getLong("customer_id"), rs.getString("customer_name"),
            nullableLong(rs, "contact_id"), rs.getString("contact_name"),
            rs.getString("role"), rs.getString("status"), rs.getString("not_awarded_reason"), rs.getBoolean("is_headline"),
            nullableLong(rs, "split_ticket_id"), rs.getString("split_ticket_code"),
            nullableLong(rs, "latest_quotation_id"), rs.getString("latest_quotation_number"),
            rs.getString("latest_quotation_status"), amount,
            nullableLong(rs, "added_by"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
