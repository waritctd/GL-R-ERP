package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code sales.billing_note}/{@code sales.billing_note_line} (V189, GLA-99 step
 * 3). Mirrors {@link th.co.glr.hr.deposit.RemainingInvoiceRepository}'s own DRAFT/ISSUED/SUPERSEDED
 * compare-and-set write shape, plus a fourth (CANCELLED) and fifth (SETTLED, reached either
 * automatically — {@link #reconcileSettlementForCustomer} — or explicitly via {@link
 * #markSettled}, owner ruling C1) terminal state and the per-line {@code note_status}
 * denormalization V189's own header comment explains —
 * see that migration for why a Postgres partial index cannot enforce "no double billing" without
 * it. Live-uniqueness (B1) is per REVISION CHAIN, not {@code (customer_id, type)} — {@link
 * #findLiveDraftForRevision} is the one query that still enforces a live-uniqueness rule; {@code
 * findLiveIssued}/{@code findLiveDraft} (customer+type scoped) were REMOVED, not merely unused, per
 * B1's own instruction ("issue() must find its predecessor via the chain, never via
 * findLiveIssued(customer, type)").
 */
@Repository
public class BillingNoteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BillingNoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_BN = """
        SELECT id, customer_id, type, base_number, version, doc_number, status, superseded_by_id,
               revision_of_id, cancel_reason, cancelled_by_id, cancelled_by_name, cancelled_at,
               bill_date, payment_due_note, payment_appointment_date, received_by_name, received_at, note,
               customer_name, customer_tax_id, customer_branch, customer_address,
               total_amount,
               created_by_id, created_by_name, created_at, updated_at,
               issued_by_id, issued_by_name, issued_at,
               settled_by_id, settled_by_name, settled_at
          FROM sales.billing_note
        """;

    /** Reads every note for this customer — self-healing first: any ISSUED note whose every
     * referenced source document is now fully paid flips to {@code SETTLED} (B2) before the SELECT
     * runs, so a caller never observes a stale ISSUED status this same read could have corrected.
     * See {@link #reconcileSettlementForCustomer} for the mechanism and why it lives here rather
     * than behind a payment-recording hook. */
    public List<BillingNoteDocumentDto> findByCustomer(long customerId) {
        reconcileSettlementForCustomer(customerId);
        List<BillingNoteDocumentDto> rows = jdbc.query(
            SELECT_BN + " WHERE customer_id = :id ORDER BY COALESCE(base_number, ''), type, version",
            Map.of("id", customerId), (rs, n) -> mapRow(rs));
        return withLines(rows);
    }

    /** Self-healing read — see {@link #findByCustomer}'s own Javadoc; this is the single-row
     * equivalent {@link BillingNoteService#get}/{@code #issue}/{@code #revise}/etc. all go
     * through, so an operation gated on this note's OWN current status (e.g. {@code
     * requireIssued}) sees a just-settled note as {@code SETTLED} rather than a now-stale
     * {@code ISSUED}. */
    public Optional<BillingNoteDocumentDto> findById(long id) {
        reconcileSettlementForNote(id);
        List<BillingNoteDocumentDto> rows = jdbc.query(
            SELECT_BN + " WHERE id = :id", Map.of("id", id), (rs, n) -> mapRow(rs));
        return withLines(rows).stream().findFirst();
    }

    /** At most one live correction DRAFT may exist per predecessor being corrected (V189's own
     * {@code ux_billing_note_revision_of_draft}) — used by {@link BillingNoteService#revise} to
     * refuse starting a second one with a clear 409 rather than a raw constraint violation. */
    public Optional<BillingNoteDocumentDto> findLiveDraftForRevision(long revisionOfId) {
        List<BillingNoteDocumentDto> rows = jdbc.query(
            SELECT_BN + " WHERE revision_of_id = :r AND status = 'DRAFT'",
            Map.of("r", revisionOfId), (rs, n) -> mapRow(rs));
        return withLines(rows).stream().findFirst();
    }

    /** One (source_type, source_id)'s currently-live claim, if any — which note holds it and that
     * note's own doc_number (null while still DRAFT), for {@link BillingNoteService#candidates}'
     * own "report which note" requirement. Single-row form kept for {@code
     * BillingNoteService.resolveLine}'s own one-selection-at-a-time check (private method, hence
     * {@code @code} rather than a broken {@code @link}); {@link
     * #findLiveClaimsForCustomer} is the batched form {@code candidates()} itself uses (review nit
     * — this per-row version was an N+1 there). */
    public Optional<ClaimedBy> findLiveClaim(String sourceType, long sourceId) {
        return jdbc.query("""
            SELECT bn.id, bn.doc_number
              FROM sales.billing_note_line l
              JOIN sales.billing_note bn ON bn.id = l.billing_note_id
             WHERE l.source_type = :t AND l.source_id = :id AND l.note_status IN ('DRAFT', 'ISSUED')
             LIMIT 1
            """, Map.of("t", sourceType, "id", sourceId),
            rs -> {
                if (!rs.next()) return Optional.<ClaimedBy>empty();
                return Optional.of(new ClaimedBy(rs.getLong("id"), rs.getString("doc_number")));
            });
    }

    public record ClaimedBy(long billingNoteId, String docNumber) {}

    /** Every currently-live claim (note_status DRAFT or ISSUED) across this customer's own billing
     * notes, keyed {@code sourceType + ":" + sourceId} — ONE query for {@link
     * BillingNoteService#candidates} to check every candidate row against, instead of one {@link
     * #findLiveClaim} call per row (review nit, GLA-99 step 3 round 1). */
    public Map<String, ClaimedBy> findLiveClaimsForCustomer(long customerId) {
        List<Map.Entry<String, ClaimedBy>> rows = jdbc.query("""
            SELECT l.source_type, l.source_id, bn.id AS note_id, bn.doc_number
              FROM sales.billing_note_line l
              JOIN sales.billing_note bn ON bn.id = l.billing_note_id
             WHERE bn.customer_id = :customerId AND l.source_id IS NOT NULL
               AND l.note_status IN ('DRAFT', 'ISSUED')
            """, Map.of("customerId", customerId), (rs, n) -> Map.entry(
                rs.getString("source_type") + ":" + rs.getLong("source_id"),
                new ClaimedBy(rs.getLong("note_id"), rs.getString("doc_number"))));
        Map<String, ClaimedBy> byKey = new HashMap<>();
        for (Map.Entry<String, ClaimedBy> row : rows) {
            byKey.put(row.getKey(), row.getValue());
        }
        return byKey;
    }

    // ── Settlement (owner ruling C1, 2026-09-20 — REPLACES this migration's original B2 rule) ──

    /** The outstanding-amount expression for a REMAINING_INVOICE source, shared (de-duplicated,
     * review nit round 2) between {@link #SETTLING_NOTE_IDS_SQL} and {@link
     * #candidatesForCustomer}'s own query — both must compute the SAME figure the SAME way, or a
     * document could settle under one definition while still showing as a candidate under the
     * other. Expects a {@code ri} alias in scope. See {@link #candidatesForCustomer}'s own Javadoc
     * for the documented BALANCE/ADJUSTMENT-per-TICKET approximation this inherits (S8, round 2:
     * settlement must ALSO restrict {@code ri} to {@code status = 'ISSUED'} — done by the caller's
     * own JOIN/subquery condition, not baked into this fragment, since {@code
     * candidatesForCustomer} already filters it in its FROM clause). */
    private static final String REMAINING_INVOICE_OUTSTANDING_EXPR = """
        ri.grand_total - COALESCE((
            SELECT SUM(CASE WHEN pr.kind = 'ADJUSTMENT' THEN -pr.amount ELSE pr.amount END)
              FROM sales.payment_receipt pr
             WHERE pr.ticket_id = ri.ticket_id AND pr.kind IN ('BALANCE', 'ADJUSTMENT')
        ), 0)""";

    /** The DEPOSIT_NOTICE counterpart of {@link #REMAINING_INVOICE_OUTSTANDING_EXPR} — expects a
     * {@code dn} alias in scope. */
    private static final String DEPOSIT_NOTICE_OUTSTANDING_EXPR = """
        dn.total_payable - COALESCE((
            SELECT SUM(CASE WHEN pr.kind = 'ADJUSTMENT' THEN -pr.amount ELSE pr.amount END)
              FROM sales.payment_receipt pr
             WHERE pr.deposit_notice_id = dn.deposit_notice_id
        ), 0)""";

    /**
     * ISSUED -&gt; SETTLED for every note in scope that qualifies under C1, releasing (note_status
     * -&gt; RELEASED) exactly the notes that settle — the SAME transition {@link #cancel}'s own
     * release already performs, just reached automatically instead of by a caller action.
     *
     * <p><b>Where the recompute lives, and why (honest-and-cheap over clever):</b> this runs
     * lazily on every read that can observe a note's own status ({@link #findByCustomer}, {@link
     * #findById}, {@link #candidatesForCustomer}) rather than from a hook wired into {@code
     * TicketService#recordPayment}/{@code #confirmDepositPaid}/{@code #confirmFinalPayment}. A
     * payment-recording hook would need {@code TicketService} — already one of this codebase's
     * largest, most business-critical classes, entirely unaware of this feature today — to learn
     * about billing notes, AND would need to stay correct across every current and future payment
     * code path that lands a receipt row, which is exactly the kind of coupling that silently rots
     * (a new payment path added later and simply never wired to the hook). Recompute-on-read has
     * no such surface: every path that can OBSERVE a note's status already goes through one of the
     * three read methods above, so there is no second place this can go stale. The query itself is
     * cheap — always filtered to {@code status = 'ISSUED'} first, so a DRAFT/CANCELLED/SUPERSEDED/
     * already-SETTLED read costs one indexed no-op lookup, not a scan.
     *
     * <p><b>C1 (owner ruling, 2026-09-20) — replaces the original "MANUAL lines never block
     * settlement" rule with a NARROWER one:</b> a note settles automatically ONLY when it has AT
     * LEAST ONE non-MANUAL line AND every non-MANUAL line's source is currently fully paid. An
     * all-MANUAL note (the ค่าขนส่ง case) can never satisfy "at least one non-MANUAL line", so it
     * never auto-settles — it stays ISSUED until a human calls {@link #markSettled}. The ORIGINAL
     * rule (still visible in this class's git history) let an all-MANUAL note settle on its very
     * next read after being issued purely because {@code bool_and} over an empty-of-non-MANUAL-
     * lines condition is vacuously true; that was the bug C1 exists to close, caught before this
     * migration ever shipped to any database.
     *
     * <p><b>S3 (round 2) — a MISSING source row must NOT count as paid.</b> {@code
     * billing_note_line} carries no FK to {@code remaining_invoice}/{@code deposit_notice} BY
     * DESIGN (V189's own header comment), so a scalar subquery that simply reads the source's own
     * outstanding figure returns SQL NULL when the row is gone — and {@code COALESCE(NULL, 0) <= 0}
     * is TRUE, silently treating "I could not find this document" as "this document is paid",
     * settling the note AND releasing its still-genuinely-unpaid sibling lines (a real double-
     * billing path). This query instead LEFT JOINs the source explicitly and requires the join to
     * have MATCHED (and requires {@code status = 'ISSUED'}, S8) before it will ever treat a
     * non-MANUAL line as eligible — a missing or non-ISSUED source makes the {@code bool_and} below
     * false, so the WHOLE note stays ISSUED and unsettled rather than silently releasing anything.
     */
    public List<Long> reconcileSettlementForCustomer(long customerId) {
        List<Long> ids = jdbc.query(
            String.format(SETTLING_NOTE_IDS_SQL, "bn.customer_id = :scope",
                REMAINING_INVOICE_OUTSTANDING_EXPR, DEPOSIT_NOTICE_OUTSTANDING_EXPR),
            Map.of("scope", customerId), (rs, n) -> rs.getLong("id"));
        return settle(ids);
    }

    /** Single-note form of {@link #reconcileSettlementForCustomer} — used by {@link #findById}
     * (customerId is not known there without an extra round trip; this scopes by the note's own
     * id instead, same query shape). */
    public List<Long> reconcileSettlementForNote(long noteId) {
        List<Long> ids = jdbc.query(
            String.format(SETTLING_NOTE_IDS_SQL, "bn.id = :scope",
                REMAINING_INVOICE_OUTSTANDING_EXPR, DEPOSIT_NOTICE_OUTSTANDING_EXPR),
            Map.of("scope", noteId), (rs, n) -> rs.getLong("id"));
        return settle(ids);
    }

    /** Every ISSUED note, within {@code %1$s}'s own scope, that satisfies C1 — see {@link
     * #reconcileSettlementForCustomer}'s own Javadoc for the exact rule and the S3/S8 fixes this
     * query embeds (LEFT JOIN + {@code status = 'ISSUED'} rather than a NULL-hiding scalar
     * subquery, plus the {@code bool_or} requiring at least one non-MANUAL line). {@code %2$s}/
     * {@code %3$s} are the shared outstanding-amount expressions — substituted via {@link
     * String#format}, deliberately NOT via {@code """ + EXPR + """} text-block concatenation:
     * adjacent text-block literals strip TRAILING whitespace per line and LEADING whitespace by a
     * common-indentation calculation, either of which can silently eat the separator between
     * "THEN" and the expression's own first token — exactly the "syntax error at or near
     * 'THENri'" this class shipped with once, caught by the very first test run against a real
     * Postgres. {@code String.format} substitutes the placeholder verbatim, with no such risk. */
    private static final String SETTLING_NOTE_IDS_SQL = """
        SELECT bnl.billing_note_id AS id
          FROM sales.billing_note bn
          JOIN sales.billing_note_line bnl ON bnl.billing_note_id = bn.id AND bnl.note_status = 'ISSUED'
          LEFT JOIN sales.remaining_invoice ri
                 ON bnl.source_type = 'REMAINING_INVOICE' AND ri.id = bnl.source_id AND ri.status = 'ISSUED'
          LEFT JOIN sales.deposit_notice dn
                 ON bnl.source_type = 'DEPOSIT_NOTICE' AND dn.deposit_notice_id = bnl.source_id AND dn.status = 'ISSUED'
         WHERE bn.status = 'ISSUED' AND %1$s
         GROUP BY bnl.billing_note_id
        HAVING bool_and(
            bnl.source_type = 'MANUAL'
            OR (
                CASE bnl.source_type
                    WHEN 'REMAINING_INVOICE' THEN ri.id IS NOT NULL
                    WHEN 'DEPOSIT_NOTICE' THEN dn.deposit_notice_id IS NOT NULL
                    ELSE FALSE
                END
                AND COALESCE(CASE bnl.source_type
                        WHEN 'REMAINING_INVOICE' THEN %2$s
                        WHEN 'DEPOSIT_NOTICE' THEN %3$s
                        ELSE NULL
                    END, 0) <= 0
            )
        )
        -- C1: an all-MANUAL note can never auto-settle — needs at least one non-MANUAL line before
        -- "every non-MANUAL line is paid" (the bool_and above) can mean anything at all.
        AND bool_or(bnl.source_type <> 'MANUAL')
        """;

    /** Package-private (not private) so {@code BillingNoteServiceIntegrationTest} can exercise the
     * exact guarded statement directly — a deterministic, CAS-style reproduction of the B2f race
     * (round 2: "settle() writes outside a transaction with no status guard, so a concurrent cancel
     * makes a plain GET return 500") without spinning up real threads. A genuine multi-threaded
     * test here would be close to vacuous anyway: Postgres serializes the two writers on the SAME
     * row under read-committed (the second UPDATE blocks on the first's row lock, then re-evaluates
     * its WHERE clause against the just-committed row), so the interleaving this method must
     * survive is fully deterministic once you sequence "cancel commits, THEN settle's own UPDATE
     * runs against the now-CANCELLED row" — which is exactly what a direct call after a real
     * {@link BillingNoteService#cancel} reproduces, no timing/threading flakiness involved.
     *
     * <p>ONE statement (a chained writable CTE — the exact shape PostgreSQL's own docs use for
     * "DELETE ... RETURNING, then INSERT ... FROM that"), not the original TWO separate {@code
     * jdbc.update} calls with no status guard on the first: that shape let a concurrent {@link
     * #cancel} land between "reconcile decided this note is settleable" and this method's own
     * UPDATE, stomping {@code CANCELLED -> SETTLED} and leaving {@code cancel_reason}/{@code
     * cancelled_by_id}/{@code cancelled_at} all non-null on a SETTLED row — violating {@code
     * chk_billing_note_cancel_fields} at write time and turning the very next plain GET into an
     * unhandled 500 (the bug's own observed symptom). The {@code WHERE ... AND status = 'ISSUED'}
     * guard on the FIRST (inner) UPDATE, combined with Postgres re-evaluating that WHERE clause
     * against the latest COMMITTED row once a concurrently-locked row's writer finishes, makes the
     * whole statement a clean no-op — for BOTH the status flip and the line release, atomically,
     * since the second UPDATE only ever touches rows the first one actually flipped — for any id
     * whose status changed since reconcile's own SELECT ran. Never a constraint violation, never a
     * partial stomp. */
    List<Long> settle(List<Long> ids) {
        if (ids.isEmpty()) {
            return ids;
        }
        List<Long> releasedFor = jdbc.query("""
            WITH settled AS (
                UPDATE sales.billing_note
                   SET status = 'SETTLED', updated_at = now()
                 WHERE id IN (:ids) AND status = 'ISSUED'
                RETURNING id
            )
            UPDATE sales.billing_note_line
               SET note_status = 'RELEASED'
             WHERE note_status = 'ISSUED' AND billing_note_id IN (SELECT id FROM settled)
            RETURNING billing_note_id
            """, Map.of("ids", ids), (rs, n) -> rs.getLong("billing_note_id"));
        return releasedFor.stream().distinct().toList();
    }

    /** C1's own explicit settlement path (a human marking an all-MANUAL note paid) — the ONLY
     * caller-triggered transition into SETTLED, and the ONLY one that records who/when ({@code
     * settled_by_id}/{@code settled_by_name}/{@code settled_at}; the automatic path above
     * deliberately leaves all three NULL, see {@link #reconcileSettlementForCustomer}'s own
     * Javadoc). Same guarded chained-CTE shape as {@link #settle} and for the IDENTICAL B2f reason
     * — a concurrent cancel must never be stomped here either. Returns the note's own id, non-empty
     * only if the guarded UPDATE actually matched (i.e. the note was ISSUED at the moment this
     * ran); {@link BillingNoteService#markSettled} maps empty to a 409. */
    List<Long> markSettled(long id, long actorId, String actorName) {
        return jdbc.query("""
            WITH settled AS (
                UPDATE sales.billing_note
                   SET status = 'SETTLED', updated_at = now(),
                       settled_by_id = :actorId, settled_by_name = :actorName, settled_at = now()
                 WHERE id = :id AND status = 'ISSUED'
                RETURNING id
            )
            UPDATE sales.billing_note_line
               SET note_status = 'RELEASED'
             WHERE note_status = 'ISSUED' AND billing_note_id IN (SELECT id FROM settled)
            RETURNING billing_note_id
            """, new MapSqlParameterSource().addValue("id", id).addValue("actorId", actorId).addValue("actorName", actorName),
            (rs, n) -> rs.getLong("billing_note_id")).stream().distinct().toList();
    }

    /**
     * Every ISSUED remaining invoice / deposit notice across this customer's deals, with its
     * VAT-inclusive OUTSTANDING amount already computed (GLA-107 answer 2: doc total minus
     * payments recorded against it) — the raw candidate pool {@link BillingNoteService#candidates}
     * filters (outstanding &gt; 0, not already claimed by a live note) and annotates.
     *
     * <p><b>The remaining-invoice half is a documented approximation.</b> {@code
     * sales.payment_receipt} has no {@code remaining_invoice_id} column — only {@code
     * deposit_notice_id} (V53) — so a BALANCE/ADJUSTMENT receipt cannot be matched to a specific
     * remaining invoice directly. This sums every BALANCE/ADJUSTMENT receipt on the SAME ticket
     * instead, which is exact under the invariant V188 enforces (at most one ISSUED remaining
     * invoice per ticket at a time) but would double-count across a ticket's full history of
     * superseded remaining invoices if that invariant ever changes. Building the real per-document
     * ledger link is GLA-107 answer 2's own "separate follow-up" (the remaining invoice's
     * multi-deduction change), explicitly out of scope for this step — see the implementation plan.
     *
     * <p>The deposit-notice half has no such gap: {@code deposit_notice_id} is a real column on
     * {@code payment_receipt}, so its outstanding figure is exact.
     *
     * <p>{@code due_date} is read from {@code sales.ticket.due_date} (V53's own billing fields) —
     * neither source document type carries its own due date, so the deal's billing due date is the
     * best available default; a caller may override it per line when adding to a draft.
     */
    private static final String CANDIDATES_SQL = """
        SELECT 'REMAINING_INVOICE' AS source_type, ri.id AS source_id, ri.ticket_id,
               ri.doc_number, ri.doc_date, t.due_date,
               %1$s AS outstanding_amount
          FROM sales.remaining_invoice ri
          JOIN sales.ticket t ON t.ticket_id = ri.ticket_id
         WHERE t.customer_id = :customerId AND ri.status = 'ISSUED'
        UNION ALL
        SELECT 'DEPOSIT_NOTICE', dn.deposit_notice_id, dn.ticket_id,
               dn.doc_number, dn.issue_date, t.due_date,
               %2$s
          FROM sales.deposit_notice dn
          JOIN sales.ticket t ON t.ticket_id = dn.ticket_id
         WHERE t.customer_id = :customerId AND dn.status = 'ISSUED'
        ORDER BY doc_date NULLS LAST, doc_number
        """;

    public List<CandidateRow> candidatesForCustomer(long customerId) {
        // Self-healing first (B2/C1) — a document a just-SETTLED note released must be visible as
        // a candidate again in the SAME read that observes the settlement, not one read later.
        reconcileSettlementForCustomer(customerId);
        // String.format placeholders, not text-block concatenation — see SETTLING_NOTE_IDS_SQL's
        // own Javadoc for why ("THENri" — adjacent text blocks silently ate a separator once).
        return jdbc.query(String.format(CANDIDATES_SQL, REMAINING_INVOICE_OUTSTANDING_EXPR, DEPOSIT_NOTICE_OUTSTANDING_EXPR),
            Map.of("customerId", customerId), (rs, n) -> new CandidateRow(
                rs.getString("source_type"), rs.getLong("source_id"), rs.getLong("ticket_id"),
                rs.getString("doc_number"), localDate(rs, "doc_date"), localDate(rs, "due_date"),
                rs.getBigDecimal("outstanding_amount")));
    }

    public record CandidateRow(String sourceType, long sourceId, long ticketId, String docNumber,
        LocalDate docDate, LocalDate dueDate, BigDecimal outstandingAmount) {}

    private List<BillingNoteDocumentDto> withLines(List<BillingNoteDocumentDto> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(BillingNoteDocumentDto::id).toList();
        Map<Long, List<BillingNoteLineDto>> byParent = new LinkedHashMap<>();
        jdbc.query("""
            SELECT id, billing_note_id, seq, source_type, source_id, ticket_id,
                   doc_number, doc_date, due_date, amount, note
              FROM sales.billing_note_line
             WHERE billing_note_id IN (:ids)
             ORDER BY billing_note_id, seq
            """, Map.of("ids", ids), rs -> {
                byParent.computeIfAbsent(rs.getLong("billing_note_id"), k -> new ArrayList<>())
                    .add(new BillingNoteLineDto(
                        rs.getLong("id"), rs.getLong("billing_note_id"), rs.getInt("seq"),
                        rs.getString("source_type"), (Long) rs.getObject("source_id"), (Long) rs.getObject("ticket_id"),
                        rs.getString("doc_number"), localDate(rs, "doc_date"), localDate(rs, "due_date"),
                        rs.getBigDecimal("amount"), rs.getString("note")));
            });
        return rows.stream().map(r -> withLines(r, byParent.getOrDefault(r.id(), List.of()))).toList();
    }

    private static BillingNoteDocumentDto withLines(BillingNoteDocumentDto r, List<BillingNoteLineDto> lines) {
        return new BillingNoteDocumentDto(r.id(), r.customerId(), r.type(), r.baseNumber(), r.version(),
            r.docNumber(), r.status(), r.supersededById(), r.revisionOfId(), r.cancelReason(), r.cancelledById(),
            r.cancelledByName(), r.cancelledAt(), r.billDate(), r.paymentDueNote(), r.paymentAppointmentDate(),
            r.receivedByName(), r.receivedAt(), r.note(), r.customerName(), r.customerTaxId(), r.customerBranch(),
            r.customerAddress(), r.totalAmount(), r.createdById(), r.createdByName(), r.createdAt(), r.updatedAt(),
            r.issuedById(), r.issuedByName(), r.issuedAt(), r.settledById(), r.settledByName(), r.settledAt(), lines);
    }

    private static BillingNoteDocumentDto mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BillingNoteDocumentDto(
            rs.getLong("id"), rs.getLong("customer_id"), rs.getString("type"),
            rs.getString("base_number"), rs.getInt("version"), rs.getString("doc_number"), rs.getString("status"),
            (Long) rs.getObject("superseded_by_id"), (Long) rs.getObject("revision_of_id"),
            rs.getString("cancel_reason"), (Long) rs.getObject("cancelled_by_id"), rs.getString("cancelled_by_name"),
            offsetDateTime(rs, "cancelled_at"),
            localDate(rs, "bill_date"), rs.getString("payment_due_note"), localDate(rs, "payment_appointment_date"),
            rs.getString("received_by_name"), offsetDateTime(rs, "received_at"), rs.getString("note"),
            rs.getString("customer_name"), rs.getString("customer_tax_id"), rs.getString("customer_branch"),
            rs.getString("customer_address"),
            rs.getBigDecimal("total_amount"),
            (Long) rs.getObject("created_by_id"), rs.getString("created_by_name"),
            offsetDateTime(rs, "created_at"), offsetDateTime(rs, "updated_at"),
            (Long) rs.getObject("issued_by_id"), rs.getString("issued_by_name"), offsetDateTime(rs, "issued_at"),
            (Long) rs.getObject("settled_by_id"), rs.getString("settled_by_name"), offsetDateTime(rs, "settled_at"),
            List.of());
    }

    private static LocalDate localDate(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static OffsetDateTime offsetDateTime(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    // ── Writes ────────────────────────────────────────────────────────────────────────────────

    /** {@code revisionOfId} (B1) is the ISSUED note being corrected — {@code null} for a brand-new
     * draft starting its own chain. See {@link BillingNoteDocumentDto#revisionOfId}'s own Javadoc. */
    public long insertDraft(long customerId, String type, Long revisionOfId, LocalDate billDate,
            String paymentDueNote, String note, String customerName, String customerTaxId,
            String customerBranch, String customerAddress, long actorId, String actorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.billing_note
                (customer_id, type, status, revision_of_id, bill_date, payment_due_note, note,
                 customer_name, customer_tax_id, customer_branch, customer_address,
                 created_by_id, created_by_name)
            VALUES (:customerId, :type, 'DRAFT', :revisionOfId, :billDate, :paymentDueNote, :note,
                    :customerName, :customerTaxId, :customerBranch, :customerAddress,
                    :actorId, :actorName)
            """,
            new MapSqlParameterSource()
                .addValue("customerId", customerId).addValue("type", type).addValue("revisionOfId", revisionOfId)
                .addValue("billDate", billDate).addValue("paymentDueNote", paymentDueNote).addValue("note", note)
                .addValue("customerName", customerName).addValue("customerTaxId", customerTaxId)
                .addValue("customerBranch", customerBranch).addValue("customerAddress", customerAddress)
                .addValue("actorId", actorId).addValue("actorName", actorName),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    /** One resolved line ready to insert — {@link BillingNoteService} does the resolving
     * (candidate lookup / outstanding computation / manual-field validation); this repository only
     * persists the result. */
    public record ResolvedLine(String sourceType, Long sourceId, Long ticketId, String docNumber,
        LocalDate docDate, LocalDate dueDate, BigDecimal amount, String note) {}

    /** Replaces every line (DRAFT-only by convention — the caller never invokes this on an
     * ISSUED/SUPERSEDED/CANCELLED row), renumbering {@code seq} from 1, and returns the new sum of
     * {@code amount} across all of them. New rows start {@code note_status = 'DRAFT'}, matching
     * the parent's own current status. */
    public BigDecimal replaceLines(long billingNoteId, List<ResolvedLine> lines) {
        jdbc.update("DELETE FROM sales.billing_note_line WHERE billing_note_id = :id", Map.of("id", billingNoteId));
        int seq = 1;
        BigDecimal total = BigDecimal.ZERO;
        for (ResolvedLine l : lines) {
            // Match NUMERIC(15,2) storage before summing, so the header equals the stored lines.
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            jdbc.update("""
                INSERT INTO sales.billing_note_line
                    (billing_note_id, seq, source_type, source_id, ticket_id, doc_number, doc_date, due_date,
                     amount, note, note_status)
                VALUES (:parent, :seq, :sourceType, :sourceId, :ticketId, :docNumber, :docDate, :dueDate,
                        :amount, :note, 'DRAFT')
                """,
                new MapSqlParameterSource()
                    .addValue("parent", billingNoteId).addValue("seq", seq++)
                    .addValue("sourceType", l.sourceType()).addValue("sourceId", l.sourceId())
                    .addValue("ticketId", l.ticketId()).addValue("docNumber", l.docNumber())
                    .addValue("docDate", l.docDate()).addValue("dueDate", l.dueDate())
                    .addValue("amount", amount).addValue("note", l.note()));
            total = total.add(amount);
        }
        jdbc.update("UPDATE sales.billing_note SET total_amount = :t, updated_at = now() WHERE id = :id",
            Map.of("t", total, "id", billingNoteId));
        return total;
    }

    public int updateDraftFields(long id, LocalDate billDate, String paymentDueNote, String note) {
        return jdbc.update("""
            UPDATE sales.billing_note
               SET bill_date = :billDate, payment_due_note = :paymentDueNote, note = :note, updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource()
                .addValue("id", id).addValue("billDate", billDate)
                .addValue("paymentDueNote", paymentDueNote).addValue("note", note));
    }

    public int updateCustomerSnapshot(long id, String customerName, String customerTaxId,
            String customerBranch, String customerAddress) {
        return jdbc.update("""
            UPDATE sales.billing_note
               SET customer_name = :customerName, customer_tax_id = :customerTaxId,
                   customer_branch = :customerBranch, customer_address = :customerAddress, updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource()
                .addValue("id", id).addValue("customerName", customerName).addValue("customerTaxId", customerTaxId)
                .addValue("customerBranch", customerBranch).addValue("customerAddress", customerAddress));
    }

    /** Compare-and-set DRAFT -> ISSUED, and promotes this note's OWN lines from {@code DRAFT} to
     * {@code ISSUED} in the same statement set (their claim on their source documents stays live,
     * just under the ISSUED banner now). Returns rows affected on the parent; 0 means someone else
     * issued/deleted this DRAFT first. */
    public int issue(long id, String baseNumber, int version, String docNumber, LocalDate issueDate,
            long actorId, String actorName) {
        int rows = jdbc.update("""
            UPDATE sales.billing_note
               SET status = 'ISSUED', base_number = :baseNumber, version = :version, doc_number = :docNumber,
                   bill_date = COALESCE(bill_date, :issueDate),
                   issued_by_id = :actorId, issued_by_name = :actorName, issued_at = now(), updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("baseNumber", baseNumber)
                .addValue("version", version).addValue("docNumber", docNumber).addValue("issueDate", issueDate)
                .addValue("actorId", actorId).addValue("actorName", actorName));
        if (rows > 0) {
            jdbc.update("UPDATE sales.billing_note_line SET note_status = 'ISSUED' "
                + "WHERE billing_note_id = :id AND note_status = 'DRAFT'", Map.of("id", id));
        }
        return rows;
    }

    public int supersede(long oldId, long newId) {
        return jdbc.update("""
            UPDATE sales.billing_note
               SET status = 'SUPERSEDED', superseded_by_id = :newId, updated_at = now()
             WHERE id = :oldId AND status = 'ISSUED'
            """, new MapSqlParameterSource().addValue("oldId", oldId).addValue("newId", newId));
    }

    /** ISSUED -> CANCELLED, releasing every one of this note's own lines (note_status ->
     * 'RELEASED') in the same call — spec: "lines released for re-billing". */
    public int cancel(long id, String reason, long actorId, String actorName) {
        int rows = jdbc.update("""
            UPDATE sales.billing_note
               SET status = 'CANCELLED', cancel_reason = :reason,
                   cancelled_by_id = :actorId, cancelled_by_name = :actorName, cancelled_at = now(), updated_at = now()
             WHERE id = :id AND status = 'ISSUED'
            """, new MapSqlParameterSource().addValue("id", id).addValue("reason", reason)
                .addValue("actorId", actorId).addValue("actorName", actorName));
        if (rows > 0) {
            releaseLines(id);
        }
        return rows;
    }

    /** Releases this note's own lines' live claim (note_status -> 'RELEASED') without changing the
     * PARENT's own status — used by {@link BillingNoteService#revise} the instant a correction
     * DRAFT is created, so the predecessor's sources become claimable again by that same new draft
     * (or by anything else, if the correction is abandoned — see {@link #restoreLinesToIssued}). */
    public void releaseLines(long billingNoteId) {
        jdbc.update("UPDATE sales.billing_note_line SET note_status = 'RELEASED' WHERE billing_note_id = :id",
            Map.of("id", billingNoteId));
    }

    /** The inverse of {@link #releaseLines} — restores a predecessor's lines to {@code ISSUED} when
     * its in-progress correction DRAFT is deleted before being issued, so the predecessor keeps
     * claiming its own sources exactly as it did before the correction was ever started.
     *
     * <p><b>S4 (Opus review, GLA-99 step 3 round 2):</b> guarded on the predecessor STILL being
     * {@code ISSUED} at the moment this runs — {@link BillingNoteService#cancel} allows cancelling
     * a note while a correction draft on it is still open (no guard against that today, by design),
     * so a caller deleting an abandoned correction on an ALREADY-CANCELLED predecessor must not
     * blindly restore that predecessor's lines to a live claim: doing so would permanently lock the
     * referenced source documents from ever being billed again by anything, since a CANCELLED note
     * is never revised further. The {@code EXISTS} subquery reads the predecessor's CURRENT
     * committed status, not a value captured earlier in the caller's own request, so this is also
     * race-proof against a cancel landing concurrently with this exact call. */
    public void restoreLinesToIssued(long billingNoteId) {
        jdbc.update("""
            UPDATE sales.billing_note_line
               SET note_status = 'ISSUED'
             WHERE billing_note_id = :id AND note_status = 'RELEASED'
               AND EXISTS (SELECT 1 FROM sales.billing_note bn WHERE bn.id = :id AND bn.status = 'ISSUED')
            """, Map.of("id", billingNoteId));
    }

    public int markReceived(long id, String receivedByName, LocalDate paymentAppointmentDate) {
        return jdbc.update("""
            UPDATE sales.billing_note
               SET received_by_name = :receivedByName, received_at = now(),
                   payment_appointment_date = :apptDate, updated_at = now()
             WHERE id = :id AND status = 'ISSUED'
            """, new MapSqlParameterSource().addValue("id", id).addValue("receivedByName", receivedByName)
                .addValue("apptDate", paymentAppointmentDate));
    }

    public int deleteDraft(long id) {
        return jdbc.update("DELETE FROM sales.billing_note WHERE id = :id AND status = 'DRAFT'", Map.of("id", id));
    }
}
