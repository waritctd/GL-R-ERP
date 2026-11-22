package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.common.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.common.ApiException;
import org.springframework.http.HttpStatus;

@Repository
public class TicketRepository {
    /** The AWARDED buyer of the deal at {@code t.ticket_id} (owner rule #65: the customer comes from the quotation): the
     * customer of the party of its live (APPROVED/ISSUED/SENT/ACCEPTED) quotation whose party is AWARDED — the same
     * quotation -> party -> customer hop {@code DepositNoticeRepository#QUOTATION_CUSTOMER_JOIN} makes. A scalar
     * subselect, so the list and the board read it in their own statement; NULL before any party is awarded. */
    public static final String AWARDED_BUYER_SQL = """
        (SELECT ac.name
           FROM sales.quotation aq
           JOIN sales.deal_party ad ON ad.party_id = aq.party_id AND ad.status IN ('AWARDED', 'AWARDED_SPLIT')
           JOIN customers.customer ac ON ac.customer_id = ad.customer_id
          WHERE aq.ticket_id = t.ticket_id AND aq.origin IS NOT NULL
            AND aq.doc_status IN ('APPROVED', 'ISSUED', 'SENT', 'ACCEPTED')
          ORDER BY aq.quotation_id DESC LIMIT 1)""";

    private static final String SUMMARY_SELECT = """
        SELECT t.ticket_id,
               t.code, t.type, t.title, t.status, t.priority,
               t.created_by,
               NULLIF(TRIM(CONCAT_WS(' ', ec.first_name_th, ec.last_name_th)), '') AS created_by_name,
               t.assigned_to,
               NULLIF(TRIM(CONCAT_WS(' ', ea.first_name_th, ea.last_name_th)), '') AS assigned_to_name,
               t.customer_name, t.customer_id, t.project_id, t.contact_id,
               p.name AS project_name,
               NULLIF(TRIM(CONCAT_WS(' ', ct.first_name, ct.last_name)), '') AS contact_name,
               t.note,
               t.created_at, t.updated_at, t.closed_at,
               COUNT(ti.item_id) AS item_count,
               t.has_edits,
               t.payment_status, t.fulfillment_status,
               t.sales_stage, t.lost_reason, t.lost_at, t.stage_updated_at,
               t.lifecycle, t.tender_requirement, t.deposit_policy,
               t.deposit_policy_reason, t.entry_channel,
               t.billing_date, t.due_date, t.credit_term_days,
               t.last_follow_up_at, t.next_follow_up_at,
               t.close_confirmed_at,
               NULLIF(TRIM(CONCAT_WS(' ', ecc.first_name_th, ecc.last_name_th)), '') AS close_confirmed_by_name,
               t.cancel_reason, t.cancelled_at,
               t.reopened_at, t.reopen_count,
               t.win_probability, t.designer_name, t.owner_name, t.buyer_name,
               t.quotation_only, t.keyed_by, t.pause_return_on, t.estimated_value, t.expected_quarter,
               t.parent_ticket_id, t.child_kind,
               """ + AWARDED_BUYER_SQL + """
 AS awarded_buyer,
               (SELECT pt.code FROM sales.ticket pt WHERE pt.ticket_id = t.parent_ticket_id) AS parent_code,
               (SELECT COUNT(*) FROM sales.ticket ao
                 WHERE ao.child_kind = 'ADD_ON'
                   AND ao.parent_ticket_id = CASE WHEN t.child_kind = 'ADD_ON' THEN t.parent_ticket_id ELSE t.ticket_id END
               ) AS add_on_count,
               (SELECT MAX(da.activity_date) FROM sales.deal_activity da
                 WHERE da.ticket_id = t.ticket_id) AS last_activity_at,
               -- Movement range (inclusive Asia/Bangkok calendar days); both FALSE when no range is given.
               (:movementFrom::date IS NOT NULL AND EXISTS (
                  SELECT 1 FROM sales.ticket_event te
                   WHERE te.ticket_id = t.ticket_id AND te.kind = 'STAGE_CHANGED'
                     AND te.created_at >= (:movementFrom::date)::timestamp AT TIME ZONE 'Asia/Bangkok'
                     AND te.created_at < ((:movementTo::date) + 1)::timestamp AT TIME ZONE 'Asia/Bangkok'
               )) AS stage_changed_in_range,
               (:movementFrom::date IS NOT NULL AND EXISTS (
                  SELECT 1 FROM sales.deal_activity da
                   WHERE da.ticket_id = t.ticket_id
                     AND da.activity_date BETWEEN :movementFrom::date AND :movementTo::date
               )) AS activity_in_range
          FROM sales.ticket t
          JOIN hr.employee ec ON ec.employee_id = t.created_by
          LEFT JOIN hr.employee ea ON ea.employee_id = t.assigned_to
          LEFT JOIN hr.employee ecc ON ecc.employee_id = t.close_confirmed_by
          LEFT JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          LEFT JOIN customers.contact ct ON ct.contact_id = t.contact_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final QuietRule quietRule;

    // ── Role-scoped list membership (Phase B) ────────────────────────────────
    // Mirrors frontend/src/features/tickets/salesViewScope.js#dealInScope, which is
    // presentation-only there ("never a security boundary" per its own doc comment) —
    // this is the actual enforcement. Applied only when the caller's role is "import"
    // or "account"; every other viewer role (sales/ceo/sales_manager) is unaffected.
    //
    // import: an active (non-terminal, non-DRAFT-only... DRAFT counts as non-terminal
    // here — see PricingRequestStatus) pricing request exists for the deal, OR the deal
    // has reached ORDER_RECEIVED or later in the pipeline. A closed/lost/cancelled
    // deal is never in scope, even at a late stage (mirrors dealInScope's `closed` guard).
    //
    // ── Why ORDER_RECEIVED and not PROCUREMENT ────────────────────────────────
    // A deal is only ever written to PROCUREMENT by TicketService.issueImportRequest itself, so a
    // PROCUREMENT floor would hide the deal from import until after it had done the very thing it
    // needed the deal in order to do.
    //
    // A deal awaiting its IR sits at ORDER_RECEIVED (confirmOrder) or DEPOSIT_RECEIVED (payment
    // reconciliation), both BELOW PROCUREMENT; and by then the customer has accepted, so its
    // pricing request is QUOTATION_ACCEPTED — one of the three terminal values below. Neither
    // disjunct would match, import's list would be empty and its worklist would render
    // "all import work complete" while IRs were outstanding.
    //
    // ORDER_RECEIVED is the correct floor, not DEPOSIT_RECEIVED: issueImportRequest accepts
    // DEPOSIT_NOTICE_ISSUED and the deposit-bypass policy's CUSTOMER_CONFIRMED as well as
    // DEPOSIT_PAID, and a deal in either of those payment states is still standing at
    // ORDER_RECEIVED — the stage only advances to DEPOSIT_RECEIVED once money actually lands.
    // A DEPOSIT_RECEIVED floor would hide every bypass-policy deal.
    //
    // This floor widens what the import role may read; it is an authorization change.
    // See TicketScopeIntegrationTest#importSeesADealAwaitingItsImportRequest, which builds the
    // real pre-IR state against real Postgres through the real service, and its sibling refusals
    // which prove the widening did not become "import sees everything".
    private static final List<String> IMPORT_STAGE_SCOPE =
        DealStage.ORDER.subList(DealStage.indexOf(DealStage.ORDER_RECEIVED), DealStage.ORDER.size());
    // Mirrors th.co.glr.hr.pricingrequest.PricingRequestStatus's three terminal values. Not imported directly to
    // avoid a new ticket-package -> pricingrequest-package repository dependency for three literals; keep in sync
    // if that enum changes.
    private static final List<String> PRICING_REQUEST_TERMINAL_STATUSES =
        List.of("CANCELLED", "SUPERSEDED", "QUOTATION_ACCEPTED");
    // account: a deposit or final-payment confirmation is awaited, or the deal is overdue on an outstanding
    // balance. Mirrors TicketEventKind's payment-status values.
    private static final List<String> ACCOUNT_PENDING_PAYMENT_STATUSES =
        List.of("DEPOSIT_NOTICE_ISSUED", "AWAITING_FINAL_PAYMENT");
    // account: every live order from ORDER_RECEIVED (S10) through CLOSED_PAID (S20), by stage
    // alone. The payment-status disjunct above misses a deal sitting at DEPOSIT_PAID through
    // PROCUREMENT (S12-S17), a deposit-bypass deal (null / CUSTOMER_CONFIRMED), FULLY_PAID-but-not-
    // close-confirmed, and CLOSED_PAID history -- all of which the account worklist must show. The
    // payment-status disjuncts stay as a UNION so nothing already listed disappears. CLOSED_PAID is
    // deliberately IN (history); a `closed` STATUS (CEO close-confirm) is NOT excluded either --
    // only lost / cancelled deals are, matching the import scope's terminal guard minus 'closed'.
    private static final List<String> ACCOUNT_ORDER_STAGE_SCOPE =
        DealStage.ORDER.subList(DealStage.indexOf(DealStage.ORDER_RECEIVED), DealStage.ORDER.size());

    /**
     * The message prefix of the EDITED event a reassignment writes on the deal's timeline
     * (DealQuotationService#moveOwnerToSalesRep). VisitRepository reads it to tell "the deal moved after this visit
     * was planned" from "the visit's owner never owned the deal", so the two must share this constant.
     * Known limitation: detection is a {@code LIKE prefix%} on the stored message, so editing this Thai prefix would
     * stop matching events already stored. A structured event field would need a migration, deliberately avoided.
     */
    public static final String OWNER_CHANGED_MESSAGE_PREFIX = "เปลี่ยนเจ้าของงาน: ";

    /**
     * A deal quotation's พนักงานขาย moves the deal's owner (Ploy 2026-11-13): the old owner becomes keyer unless one is
     * already set. One guarded statement: no move to the current owner, after a commission record exists, or on a
     * finished deal (lifecycle CANCELLED/COMPLETED/CLOSED_LOST or status closed/cancelled). Returns rows moved (0 or 1).
     */
    public int moveOwnerToSalesRep(long ticketId, long repId) {
        return jdbc.update("""
            UPDATE sales.ticket SET keyed_by = COALESCE(keyed_by, created_by), created_by = :rep
             WHERE ticket_id = :id AND created_by <> :rep
               AND lifecycle NOT IN ('CANCELLED', 'COMPLETED', 'CLOSED_LOST')
               AND status NOT IN ('closed', 'cancelled')
               AND NOT EXISTS (SELECT 1 FROM sales.commission_record WHERE source_ticket_id = :id)
            """, new MapSqlParameterSource().addValue("id", ticketId).addValue("rep", repId));
    }

    /** Tests' convenience: the default {@value QuietRule#DEFAULT_QUIET_DAYS}-day quiet rule. */
    /** Live {@code can_manage_all_sales_documents} read, for the services that gate on deal ownership and hold no EmployeeAuthRepository. */
    public boolean holdsAllSalesDocumentsGrant(th.co.glr.hr.auth.UserPrincipal actor) {
        return th.co.glr.hr.auth.EmployeeAuthRepository.canManageAllSalesDocuments(jdbc, actor);
    }

    /** Live {@code can_issue_tax_invoice} read, for services that hold no EmployeeAuthRepository (AttachmentController). */
    public boolean holdsTaxInvoiceGrant(long employeeId) {
        return th.co.glr.hr.auth.EmployeeAuthRepository.canIssueTaxInvoice(jdbc, employeeId);
    }

    /** Live {@code can_read_all_sales} read, for services that hold no EmployeeAuthRepository (AttachmentController). */
    /** The showroom team's grant: reads every SHOWROOM no-project order. */
    public boolean holdsShowroomOrdersGrant(long employeeId) {
        return th.co.glr.hr.auth.EmployeeAuthRepository.canSeeShowroomOrders(jdbc, employeeId);
    }

    public boolean holdsReadAllSalesGrant(long employeeId) {
        return th.co.glr.hr.auth.EmployeeAuthRepository.canReadAllSales(jdbc, employeeId);
    }

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new QuietRule(jdbc, QuietRule.DEFAULT_QUIET_DAYS));
    }

    @Autowired
    public TicketRepository(NamedParameterJdbcTemplate jdbc, QuietRule quietRule) {
        this.jdbc = jdbc;
        this.quietRule = quietRule;
    }

    public List<TicketSummaryDto> findSummaries(String status, Long createdByFilter) {
        return findSummaries(status, createdByFilter, null);
    }

    public List<TicketSummaryDto> findSummaries(String status, Long createdByFilter, PageRequest page) {
        return findSummaries(status, null, createdByFilter, null, page);
    }

    /**
     * @param salesStage optional {@link DealStage} filter (e.g. {@code CLOSED_PAID}), additive to
     *                    {@code status} — used by the commission "Linked Deal" picker to list
     *                    only deals that have actually reached final payment.
     * @param actorRole when {@code "import"} or {@code "account"}, applies that role's list-scoping
     *                  predicate (see the class-level comment above); any other value (including
     *                  null) applies no extra filter.
     */
    public List<TicketSummaryDto> findSummaries(String status, String salesStage, Long createdByFilter,
                                                String actorRole, PageRequest page) {
        return findSummaries(status, salesStage, createdByFilter, actorRole, page, null, null);
    }

    /** As above, plus the optional inclusive Bangkok-day movement range ({@code movementFrom}/{@code movementTo}, both or neither). */
    public List<TicketSummaryDto> findSummaries(String status, String salesStage, Long createdByFilter,
                                                String actorRole, PageRequest page,
                                                LocalDate movementFrom, LocalDate movementTo) {
        StringBuilder sql = new StringBuilder(SUMMARY_SELECT).append("""
             WHERE (:status::varchar IS NULL OR t.status = :status)
               AND (:salesStage::varchar IS NULL OR t.sales_stage = :salesStage)
               AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy OR t.keyed_by = :createdBy)
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("status", status)
            .addValue("salesStage", salesStage)
            .addValue("createdBy", createdByFilter)
            .addValue("movementFrom", movementFrom, Types.DATE)
            .addValue("movementTo", movementTo, Types.DATE);
        appendRoleScope(sql, params, actorRole);
        // The ticket_id tiebreaker is load-bearing, not decoration: created_at alone is not a
        // total order, and this query is paged with LIMIT/OFFSET below. Ties make each page a
        // separate arbitrary slice, so a row can appear on two pages while another never appears
        // at all (with every ticket sharing one created_at, size=1 returned ticket 1 on both page 0
        // and page 1 and never ticket 2). Ties are not a seed artefact; tickets created in one
        // batch tie the same way in production.
        sql.append("""
             GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                      ea.first_name_th, ea.last_name_th,
                      p.name, ct.first_name, ct.last_name,
                      ecc.first_name_th, ecc.last_name_th
             ORDER BY t.created_at DESC, t.ticket_id DESC
            """);
        if (page != null) {
            sql.append(" LIMIT :limit OFFSET :offset");
            params.addValue("limit", page.size());
            params.addValue("offset", page.offset());
        }
        return enrichSummaries(jdbc.query(sql.toString(), params, (rs, rowNum) -> mapSummary(rs)));
    }

    public int countSummaries(String status, Long createdByFilter) {
        return countSummaries(status, null, createdByFilter, null);
    }

    public int countSummaries(String status, String salesStage, Long createdByFilter, String actorRole) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*) FROM sales.ticket t
             WHERE (:status::varchar IS NULL OR t.status = :status)
               AND (:salesStage::varchar IS NULL OR t.sales_stage = :salesStage)
               AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy OR t.keyed_by = :createdBy)
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("status", status)
            .addValue("salesStage", salesStage)
            .addValue("createdBy", createdByFilter);
        appendRoleScope(sql, params, actorRole);
        Integer total = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return total == null ? 0 : total;
    }

    /** Is this deal inside the import role's list scope — i.e. would GET /api/tickets return it to an import
     * caller? Runs the list's OWN predicate (#appendRoleScope with "import") against a single id rather than
     * restating it, so the by-id read and the worklist can never disagree about what import may see. */
    public boolean isInImportScope(long ticketId) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM sales.ticket t WHERE t.ticket_id = :ticketId");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ticketId", ticketId);
        appendRoleScope(sql, params, "import");
        Integer n = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return n != null && n > 0;
    }

    /** Additive WHERE fragment for import/account list-scoping — see the class-level comment. */
    private void appendRoleScope(StringBuilder sql, MapSqlParameterSource params, String actorRole) {
        if ("import".equals(actorRole)) {
            sql.append("""
                   AND t.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED')
                   AND t.status NOT IN ('closed', 'cancelled')
                   AND (
                     EXISTS (
                       SELECT 1 FROM sales.pricing_request pr
                        WHERE pr.ticket_id = t.ticket_id
                          AND pr.status NOT IN (:prTerminalStatuses)
                     )
                     OR t.sales_stage IN (:importStages)
                   )
                """);
            params.addValue("prTerminalStatuses", PRICING_REQUEST_TERMINAL_STATUSES);
            params.addValue("importStages", IMPORT_STAGE_SCOPE);
        } else if ("account".equals(actorRole)) {
            sql.append(" AND ").append(accountScopePredicate()).append(' ');
            addAccountScopeParams(params);
        }
    }

    /**
     * Account's list scope as a parenthesised predicate over {@code t} ({@code sales.ticket}): the one text
     * both the account deal list and the dashboard totals run. Needs {@link #addAccountScopeParams}.
     */
    public static String accountScopePredicate() {
        return """
            (
              (t.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED')
               AND t.status <> 'cancelled'
               AND t.sales_stage IN (:accountOrderStages))
              OR t.payment_status IN (:accountPendingStatuses)
              OR (""" + paymentOverduePredicate("t.ticket_id") + "))\n";
    }

    public static void addAccountScopeParams(MapSqlParameterSource params) {
        params.addValue("accountOrderStages", ACCOUNT_ORDER_STAGE_SCOPE);
        params.addValue("accountPendingStatuses", ACCOUNT_PENDING_PAYMENT_STATUSES);
    }

    /** Is this deal inside the account role's list scope? */
    public boolean isInAccountScope(long ticketId) {
        // Trailing space is load-bearing: the account fragment appended below is a text block that starts
        // flush with "AND (", so without it the SQL reads ":idAND".
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sales.ticket t WHERE t.ticket_id = :id ");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", ticketId);
        appendRoleScope(sql, params, "account");
        Integer n = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return n != null && n > 0;
    }

    /** A lost or cancelled deal; the one predicate every "dead deal" check shares. */
    public static final String DEAD_DEAL_SQL = "(t.lifecycle IN ('CLOSED_LOST', 'CANCELLED') OR t.status = 'cancelled')";

    public boolean isDeadDeal(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.ticket_id = :id AND " + DEAD_DEAL_SQL + ")",
            Map.of("id", ticketId), Boolean.class));
    }

    /**
     * READ-ONLY widening of {@link #isInImportScope}: a lost or cancelled deal that still
     * has an ISSUED ใบขอซื้อ (IR) is outside import's list scope, but import must be able to OPEN it (the link in the
     * "deal lost/cancelled" notice). Callers OR this with {@link #isInImportScope} for DETAIL reads only -- never for
     * the list, a count or a write, which keep the strict predicate.
     */
    public boolean isDeadDealWithIssuedImportRequest(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM sales.ticket t
               WHERE t.ticket_id = :id
                 AND %s
                 AND EXISTS (SELECT 1 FROM sales.import_request ir WHERE ir.ticket_id = t.ticket_id AND ir.status = 'ISSUED'))
            """.formatted(DEAD_DEAL_SQL), Map.of("id", ticketId), Boolean.class));
    }

    /** Account's counterpart of {@link #isDeadDealWithIssuedImportRequest}: a lost/cancelled deal with an ISSUED deposit notice. */
    public boolean isDeadDealWithIssuedDepositNotice(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM sales.ticket t
               WHERE t.ticket_id = :id
                 AND %s
                 AND EXISTS (SELECT 1 FROM sales.deposit_notice d WHERE d.ticket_id = t.ticket_id AND d.status = 'ISSUED'))
            """.formatted(DEAD_DEAL_SQL), Map.of("id", ticketId), Boolean.class));
    }

    /**
     * Lean accessor for the commission-submission gate: resolves just the deal's current
     * pipeline stage without pulling a full ticket-detail load. Empty means the ticket does not
     * exist; {@code sales_stage} itself is {@code NOT NULL} (V50), so a present row always yields a
     * non-empty stage.
     */
    public Optional<String> findSalesStage(long ticketId) {
        try {
            String stage = jdbc.queryForObject(
                "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
                Map.of("id", ticketId), String.class);
            return Optional.ofNullable(stage);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Ticket ownership (created_by) for a BATCH of ticket ids in ONE query — used by {@code
     * BillingNoteService#list} to check a `sales` rep's own ownership across every ticket
     * referenced by a whole customer's worth of billing notes, instead of one {@link #findById}
     * (a heavy join-everything load) per referenced ticket per note. Missing ids are simply
     * absent from the returned map, never an error. */
    public Map<Long, java.util.Set<Long>> findOwnerAndKeyerIds(java.util.Collection<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, java.util.Set<Long>> byId = new java.util.HashMap<>();
        jdbc.query("SELECT ticket_id, created_by, keyed_by FROM sales.ticket WHERE ticket_id IN (:ids)",
            Map.of("ids", ticketIds),
            rs -> {
                java.util.Set<Long> people = new java.util.HashSet<>();
                people.add(rs.getLong("created_by"));
                long keyed = rs.getLong("keyed_by");
                if (!rs.wasNull()) people.add(keyed);
                byId.put(rs.getLong("ticket_id"), people);
            });
        return byId;
    }

    public Optional<TicketDto> findById(long id) {
        Optional<TicketSummaryDto> summary = findSummaryById(id);
        if (summary.isEmpty()) return Optional.empty();
        List<TicketItemDto> items = findItemsByTicketId(id);
        List<TicketEventDto> events = findEventsByTicketId(id);
        List<QuotationDto> quotations = findQuotationsByTicketId(id);
        QuotationDto quotation = quotations.isEmpty() ? null : quotations.get(0);
        return Optional.of(new TicketDto(summary.get(), items, events, quotation, quotations));
    }

    public boolean existsById(long ticketId) {
        Boolean result = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.ticket WHERE ticket_id = :id)",
            Map.of("id", ticketId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public String nextTicketCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.ticket_code_seq')", Map.of(), Long.class);
        return "DL-" + Year.now() + "-" + String.format("%04d", seq);
    }

    public String nextQuotationCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.quotation_code_seq')", Map.of(), Long.class);
        return "QT-" + Year.now() + "-" + String.format("%04d", seq);
    }

    /** May {@code employeeId} own a deal? Same predicate as the print-name / owner picker's option list. */
    public boolean isEligibleDealOwner(long employeeId) {
        return th.co.glr.hr.commission.QuotationDisplayNameEligibility.isEligible(
            jdbc, employeeId, th.co.glr.hr.auth.DivisionAccessPolicy.SALES_DIVISION_CODE);
    }

    /** The actor owns (and keyed) the deal -- the shape every pre-owner caller uses. */
    public long create(CreateTicketRequest request, String code, long actorId, String actorName) {
        return create(request, code, actorId, actorId, actorName);
    }

    @Transactional
    public long create(CreateTicketRequest request, String code, long ownerId, long actorId, String actorName) {
        String priority = (request.priority() != null && !request.priority().isBlank())
            ? request.priority() : Priority.DEFAULT;
        // Every deal begins as a DRAFT at the lead stage, regardless of whether products were attached at creation
        // time.
        boolean hasItems = request.items() != null && !request.items().isEmpty();
        String status = TicketStatus.DRAFT;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.ticket
                (code, title, status, priority, created_by, keyed_by, customer_name, customer_id, project_id, contact_id, note, entry_channel,
                 next_follow_up_at, quotation_only, designer_name, estimated_value)
            VALUES
                (:code, :title, :status, :priority, :createdBy, :keyedBy, :customerName, :customerId, :projectId, :contactId, :note, :entryChannel,
                 :nextFollowUpAt, :quotationOnly, :designerName, :estimatedValue)
            """,
            new MapSqlParameterSource()
                // The short create form's ผู้ออกแบบ and มูลค่าโดยประมาณ (2026-11-16); both null from every other caller.
                // The estimate has its own column (V220 estimated_value); imported_value stays the pipeline import's.
                .addValue("designerName", request.designerName() == null || request.designerName().isBlank()
                    ? null : request.designerName().trim())
                .addValue("estimatedValue", request.estimatedValue(), java.sql.Types.NUMERIC)
                .addValue("code", code)
                .addValue("title", request.title())
                .addValue("status", status)
                .addValue("priority", priority)
                .addValue("createdBy", ownerId)
                .addValue("keyedBy", actorId)
                .addValue("customerName", request.customerName())
                .addValue("customerId", request.customerId())
                .addValue("projectId", request.projectId())
                .addValue("contactId", request.contactId())
                .addValue("note", request.note())
                // Null stays legal (a draft may genuinely not have a date yet); it simply leaves the deal un-advanceable until
                // one is set, which is the pre-existing behaviour.
                .addValue("nextFollowUpAt", request.nextFollowUpAt())
                // Only the quotation editor's inline create sends TRUE; null/false (every
                // other caller) is an ordinary pipeline deal. See CreateTicketRequest#quotationOnly.
                .addValue("quotationOnly", Boolean.TRUE.equals(request.quotationOnly()))
                // V144: an omitted channel lands UNSPECIFIED ("not stated"), not DESIGNER_LED.
                .addValue("entryChannel", request.entryChannel() != null && !request.entryChannel().isBlank()
                    ? request.entryChannel() : EntryChannel.UNSPECIFIED),
            keyHolder, new String[]{"ticket_id"});
        long ticketId = keyHolder.getKey().longValue();
        if (hasItems) insertItems(ticketId, request.items());
        // V204: the deal's customer is its first party (BUYER until a quotation says otherwise), and the headline.
        // Every deal therefore has at least one sales.deal_party row, as the V204 backfill gave every older one.
        if (request.customerId() != null) {
            jdbc.update("""
                INSERT INTO sales.deal_party (ticket_id, customer_id, contact_id, role, status, is_headline, added_by)
                VALUES (:ticketId, :customerId, :contactId, 'BUYER', 'ACTIVE', TRUE, :actorId)
                """, new MapSqlParameterSource().addValue("ticketId", ticketId).addValue("customerId", request.customerId())
                    .addValue("contactId", request.contactId()).addValue("actorId", actorId));
        }
        addEvent(ticketId, actorId, actorName, TicketEventKind.CREATED, null, TicketStatus.DRAFT, null);
        return ticketId;
    }

    public void addEvent(long ticketId, long actorId, String actorName,
                         String kind, String fromStatus, String toStatus, String message) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, null,
            null, null);
    }

    public void addEventWithSnapshot(long ticketId, long actorId, String actorName,
                                     String kind, String fromStatus, String toStatus,
                                     String message, String itemSnapshotJson) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message,
            itemSnapshotJson, null, null);
    }

    /** Record an event AND the document it produced (V58), so "which receipt did this payment event write" is a
     * query rather than a timestamp match. */
    public void addEventWithDocument(long ticketId, long actorId, String actorName,
                                     String kind, String fromStatus, String toStatus,
                                     String message, String documentType, Long documentId) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, null,
            documentType, documentId);
    }

    private void addEventInternal(long ticketId, long actorId, String actorName,
                                   String kind, String fromStatus, String toStatus,
                                   String message, String itemSnapshotJson,
                                   String documentType, Long documentId) {
        if (toStatus != null) {
            // Logging an event does NOT write sales.ticket.status. TicketStatus.isValid(toStatus) is
            // a MEMBERSHIP check, not a transition guard, so writing the column here would let any
            // event carrying any valid status overwrite it from any current state, while
            // DepositNoticeService and TicketService.requireClosePrerequisites read that same column
            // as a guard. Every genuine transition goes through #transitionStatus, which validates
            // the edge against TicketStatus.ALLOWED and compare-and-sets on the expected value.
            //
            // The updated_at / closed_at / pickup side-effects fire for every event, including
            // closed_at, which keys off toStatus being CLOSED/CANCELLED rather than off the row's
            // current value, so a caller that transitions then logs gets the same closed_at.
            // (CLOSED and CANCELLED are members of VALUES by construction, so `closing` needs no
            // isValid check.)
            boolean closing =
                TicketStatus.CLOSED.equals(toStatus) || TicketStatus.CANCELLED.equals(toStatus);
            boolean isPickup = TicketEventKind.PICKED_UP.equals(kind);
            jdbc.update("""
                UPDATE sales.ticket
                   SET updated_at = now(),
                       closed_at = CASE WHEN :closing THEN now() ELSE closed_at END,
                       assigned_to = CASE WHEN :isPickup THEN :actorId ELSE assigned_to END
                 WHERE ticket_id = :id
                """,
                new MapSqlParameterSource()
                    .addValue("closing", closing)
                    .addValue("isPickup", isPickup)
                    .addValue("actorId", actorId)
                    .addValue("id", ticketId));
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId)
            .addValue("actorId", actorId)
            .addValue("actorName", actorName)
            .addValue("kind", kind)
            .addValue("fromStatus", fromStatus)
            .addValue("toStatus", toStatus)
            .addValue("message", message)
            .addValue("documentType", documentType)
            .addValue("documentId", documentId);

        if (itemSnapshotJson == null) {
            jdbc.update("""
                INSERT INTO sales.ticket_event
                    (ticket_id, actor_id, actor_name, kind, from_status, to_status, message,
                     related_document_type, related_document_id)
                VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message,
                        :documentType, :documentId)
                """, params);
            return;
        }

        params.addValue("itemSnapshot", itemSnapshotJson);
        jdbc.update("""
            INSERT INTO sales.ticket_event
                (ticket_id, actor_id, actor_name, kind, from_status, to_status, message, item_snapshot,
                 related_document_type, related_document_id)
            VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message,
                    :itemSnapshot::jsonb, :documentType, :documentId)
            """, params);
    }

    public void replaceItems(long ticketId, List<TicketItemRequest> items) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId));
        insertItems(ticketId, items);
    }

    // Used by TicketService.editItems: unlike replaceItems (which is for proposePrice's
    // intentional wholesale price replacement), this preserves each item's pricing fields
    // (proposedPrice/approvedPrice/calcedCost/calcedPrice/calcConfigVersion/manualPrice/
    // manualOverrideReason/currency) exactly as TicketService merged them — sales editing
    // descriptive fields must never silently discard import's proposed price or CEO's
    // approved/manual price.
    @Transactional
    public void replaceItemsPreservingPricing(long ticketId, List<TicketItemDto> items) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId));
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            TicketItemDto item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("factory", item.factory())
                .addValue("qty", item.qty())
                .addValue("qtySqm", item.qtySqm())
                .addValue("unitBasis", item.unitBasis())
                .addValue("rawPrice", item.rawPrice())
                .addValue("rawCurrency", item.rawCurrency())
                .addValue("rawUnit", item.rawUnit())
                .addValue("proposedPrice", item.proposedPrice())
                .addValue("approvedPrice", item.approvedPrice())
                .addValue("currency", item.currency())
                .addValue("sortOrder", i)
                .addValue("calcedCost", item.calcedCost())
                .addValue("calcedPrice", item.calcedPrice())
                .addValue("calcConfigVersion", item.calcConfigVersion())
                .addValue("manualPrice", item.manualPrice())
                .addValue("manualOverrideReason", item.manualOverrideReason())
                .addValue("qtyDelivered", item.qtyDelivered())
                .addValue("qtyFromStock", item.qtyFromStock())
                .addValue("stockNote", item.stockNote())
                .addValue("catalogPriceId", item.catalogPriceId())
                .addValue("catalogProductCode", item.catalogProductCode())
                // V148: this DELETE+re-INSERT gives every edited item a brand-new item_id, so the LOGICAL weight a
                // sales_manager set survives an edit only because TicketService.mergeEditedItemsPreservingPricing carries
                // item.weightMultiplier forward from the prior row.
                .addValue("weightMultiplier", item.weightMultiplier())
                // V183: unlike weightMultiplier (guarded, "prior always wins"), sourcedFromStock is
                // sales-writable -- TicketService.mergeEditedItemsPreservingPricing resolves it from the
                // request with a null-safe fallback to the prior row. It also resolves stockSalePrice to
                // NULL (walk 4); this method just persists whatever it was handed.
                .addValue("sourcedFromStock", item.sourcedFromStock())
                .addValue("stockSalePrice", item.stockSalePrice());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory,
                 qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                 proposed_price, approved_price, currency, sort_order,
                 calced_cost, calced_price, calc_config_version,
                 manual_price, manual_override_reason, qty_delivered, qty_from_stock, stock_note,
                 catalog_price_id, catalog_product_code, weight_multiplier,
                 sourced_from_stock, stock_sale_price)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :unitBasis, :rawPrice, :rawCurrency, :rawUnit,
                    :proposedPrice, :approvedPrice, :currency, :sortOrder,
                    :calcedCost, :calcedPrice, :calcConfigVersion,
                    :manualPrice, :manualOverrideReason, :qtyDelivered, :qtyFromStock, :stockNote,
                    :catalogPriceId, :catalogProductCode, :weightMultiplier,
                    :sourcedFromStock, :stockSalePrice)
            """, batch);
    }

    public void approveItemPrices(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET approved_price = proposed_price
             WHERE ticket_id = :id AND proposed_price IS NOT NULL
            """, Map.of("id", ticketId));
    }

    @Transactional
    public QuotationDto createQuotation(long ticketId, String number, long issuedById, BigDecimal totalAmount) {
        return createQuotation(ticketId, number, issuedById, totalAmount, QuotationRecipient.UNSPECIFIED,
            null, null, null, null, null, null, null, null);
    }

    @Transactional
    public QuotationDto createQuotation(long ticketId, String number, long issuedById, BigDecimal totalAmount,
                                        String recipientType, String recipientLabel, String paymentTerms,
                                        String leadTime, String deliveryTerms, LocalDate validityDate,
                                        LocalDate offerDate, Integer depositPct, Integer deliveryDays) {
        List<Long> parentIds = jdbc.query("""
            SELECT quotation_id
              FROM sales.quotation
             WHERE ticket_id = :ticketId AND recipient_type = :recipientType
             ORDER BY quotation_version DESC, quotation_id DESC
             LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType),
            (rs, rowNum) -> rs.getLong("quotation_id"));
        Long parentQuotationId = parentIds.isEmpty() ? null : parentIds.get(0);

        // Supersede only this recipient chain; accepted/rejected/cancelled rows remain as history.
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE ticket_id = :ticketId
               AND recipient_type = :recipientType
               AND doc_status NOT IN ('SUPERSEDED','ACCEPTED','REJECTED','CANCELLED')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType));

        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0)
              FROM sales.quotation
             WHERE ticket_id = :ticketId AND recipient_type = :recipientType
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType),
            Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // generateQuotation() issues and transitions the ticket to QUOTATION_ISSUED in one step (no separate draft
        // phase like deposit notices), so the row starts ISSUED.
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status,
                 recipient_type, recipient_label, payment_terms, lead_time, delivery_terms,
                 validity_date, parent_quotation_id, offer_date, deposit_pct, delivery_days)
            VALUES (:ticketId, :number, :issuedBy, :totalAmount, 'THB', :version, 'ISSUED',
                    :recipientType, :recipientLabel, :paymentTerms, :leadTime, :deliveryTerms,
                    :validityDate, :parentQuotationId, :offerDate, :depositPct, :deliveryDays)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", issuedById)
                .addValue("totalAmount", totalAmount)
                .addValue("version", nextVersion)
                .addValue("recipientType", recipientType)
                .addValue("recipientLabel", recipientLabel)
                .addValue("paymentTerms", paymentTerms)
                .addValue("leadTime", leadTime)
                .addValue("deliveryTerms", deliveryTerms)
                .addValue("validityDate", validityDate)
                .addValue("parentQuotationId", parentQuotationId)
                .addValue("offerDate", offerDate)
                .addValue("depositPct", depositPct != null ? java.math.BigDecimal.valueOf(depositPct).divide(java.math.BigDecimal.valueOf(100)) : null)
                .addValue("deliveryDays", deliveryDays));

        int versionToFind = nextVersion;
        return findQuotationsByTicketId(ticketId).stream()
            .filter(q -> q.quotationVersion() == versionToFind && recipientType.equals(q.recipientType()))
            .findFirst()
            .orElseThrow();
    }

    public boolean markQuotationStatus(long ticketId, long quotationId, String status) {
        String timestampAssignment = switch (status) {
            case QuotationStatus.SENT -> "sent_at = now(),";
            case QuotationStatus.ACCEPTED -> "accepted_at = now(),";
            case QuotationStatus.REJECTED -> "rejected_at = now(),";
            default -> "";
        };
        int rows = jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = :status,
                   %s
                   issued_at = issued_at
             WHERE quotation_id = :quotationId AND ticket_id = :ticketId
            """.formatted(timestampAssignment),
            new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("quotationId", quotationId)
                .addValue("ticketId", ticketId));
        return rows > 0;
    }

    // --- quotation snapshot (V49: freeze issued quotations at issue time) ---

    // Frozen customer/project header for an issued quotation. All fields nullable — pre-V49 quotations have no
    // header snapshot and the caller must fall back to a live customer/ticket lookup.
    public record QuotationHeaderSnapshot(String customerName, String customerAddress,
                                          String customerTaxId, String customerPhone,
                                          String projectName) {}

    // Called once, in the same transaction as createQuotation, from TicketService.generateQuotation. Only priced
    // items (approvedPrice != null) are snapshotted — unpriced items never contribute to the rendered quotation,
    // mirroring the total-amount calculation in TicketService.generateQuotation itself.
    public void insertQuotationItems(long quotationId, List<TicketItemDto> items) {
        List<TicketItemDto> priced = items.stream().filter(it -> it.approvedPrice() != null).toList();
        if (priced.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[priced.size()];
        for (int i = 0; i < priced.size(); i++) {
            TicketItemDto item = priced.get(i);
            BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
            BigDecimal amount = item.approvedPrice().multiply(qty);
            batch[i] = new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", i + 1)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("qty", item.qty())
                .addValue("qtySqm", item.qtySqm())
                .addValue("unitBasis", item.unitBasis())
                .addValue("rawUnit", item.rawUnit())
                .addValue("unitPrice", item.approvedPrice())
                .addValue("amount", amount);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, brand, model, color, texture, size,
                 qty, qty_sqm, unit_basis, raw_unit, unit_price, amount)
            VALUES (:quotationId, :seq, :brand, :model, :color, :texture, :size,
                    :qty, :qtySqm, :unitBasis, :rawUnit, :unitPrice, :amount)
            """, batch);
    }

    public void updateQuotationHeader(long quotationId, String customerName, String customerAddress,
                                      String customerTaxId, String customerPhone, String projectName) {
        jdbc.update("""
            UPDATE sales.quotation
               SET customer_name    = :customerName,
                   customer_address = :customerAddress,
                   customer_tax_id  = :customerTaxId,
                   customer_phone   = :customerPhone,
                   project_name     = :projectName
             WHERE quotation_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("customerName", customerName)
                .addValue("customerAddress", customerAddress)
                .addValue("customerTaxId", customerTaxId)
                .addValue("customerPhone", customerPhone)
                .addValue("projectName", projectName)
                .addValue("id", quotationId));
    }

    // Empty list = no snapshot exists for this quotation (either it predates V49, or generateQuotation ran with
    // zero priced items) — the caller falls back to live data.
    public List<TicketItemDto> findQuotationItemsByQuotationId(long quotationId, long ticketId) {
        return jdbc.query("""
            SELECT quotation_item_id, seq, brand, model, color, texture, size,
                   qty, qty_sqm, unit_basis, raw_unit, unit_price
              FROM sales.quotation_item
             WHERE quotation_id = :id
             ORDER BY seq
            """,
            Map.of("id", quotationId),
            (rs, rowNum) -> new TicketItemDto(
                rs.getLong("quotation_item_id"),
                ticketId,
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("color"),
                rs.getString("texture"),
                rs.getString("size"),
                null, // factory — not part of the render, not snapshotted
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("qty_sqm"),
                null, null, // rawPrice, rawCurrency — not used by QuotationRenderer
                rs.getString("raw_unit"),
                null, // proposedPrice — meaningless post-issue
                rs.getBigDecimal("unit_price"), // approvedPrice = the frozen unit price
                null, // currency — not snapshotted (unused by QuotationRenderer)
                rs.getInt("seq"),
                null, null, null, // calcedCost, calcedPrice, calcConfigVersion
                rs.getString("unit_basis"),
                null, null // manualPrice, manualOverrideReason
            ));
    }

    public Optional<QuotationHeaderSnapshot> findQuotationHeaderSnapshot(long quotationId) {
        try {
            QuotationHeaderSnapshot snap = jdbc.queryForObject("""
                SELECT customer_name, customer_address, customer_tax_id, customer_phone, project_name
                  FROM sales.quotation
                 WHERE quotation_id = :id
                """,
                Map.of("id", quotationId),
                (rs, rowNum) -> new QuotationHeaderSnapshot(
                    rs.getString("customer_name"),
                    rs.getString("customer_address"),
                    rs.getString("customer_tax_id"),
                    rs.getString("customer_phone"),
                    rs.getString("project_name")
                ));
            return Optional.ofNullable(snap);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // --- private helpers ---

    /** {@link #findSummaryById} for many ids in one round trip (empty input, empty output). */
    public List<TicketSummaryDto> findSummariesByIds(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<TicketSummaryDto> rows = jdbc.query(
            SUMMARY_SELECT + """
             WHERE t.ticket_id IN (:ids)
             GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                      ea.first_name_th, ea.last_name_th,
                      p.name, ct.first_name, ct.last_name,
                      ecc.first_name_th, ecc.last_name_th
            """,
            new MapSqlParameterSource("ids", ids)
                .addValue("movementFrom", null, Types.DATE)
                .addValue("movementTo", null, Types.DATE),
            (rs, rowNum) -> mapSummary(rs));
        return enrichSummaries(rows);
    }

    public Optional<TicketSummaryDto> findSummaryById(long id) {
        try {
            TicketSummaryDto summary = jdbc.queryForObject(
                SUMMARY_SELECT + """
                 WHERE t.ticket_id = :id
                 GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                          ea.first_name_th, ea.last_name_th,
                          p.name, ct.first_name, ct.last_name,
                          ecc.first_name_th, ecc.last_name_th
                """,
                new MapSqlParameterSource("id", id)
                    .addValue("movementFrom", null, Types.DATE)
                    .addValue("movementTo", null, Types.DATE),
                (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary).flatMap(x -> enrichSummaries(List.of(x)).stream().findFirst());
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ราคาตั้ง on the deal's item rows. pp.price/currency/
    // price_unit/sqm_per_piece resolve the SAME 4 inputs TicketCreateModal's catalog picker stashes
    // as UI-only state on a not-yet-saved item (catalogPrice/catalogCurrency/catalogPriceUnit/
    // sqmPerPiece) -- see TicketItemDto's own doc comment for why the field names match exactly.
    //
    // MUST be a LEFT JOIN. catalog_price_id is nullable by design in two independent ways (V110's
    // own header): a "custom" line never had a catalog identity, and ON DELETE SET NULL clears it
    // when a catalog price is retired. An INNER JOIN would silently drop both kinds of row out of
    // the items list -- a line item vanishing from a deal, not merely a missing price. The factory
    // join (origin country for the PCR form's seeded ประเทศต้นทาง) is LEFT for the same reason.
    private List<TicketItemDto> findItemsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT ti.item_id, ti.ticket_id, ti.brand, ti.model, ti.color, ti.texture, ti.size, ti.factory,
                   ti.qty, ti.qty_sqm, ti.unit_basis, ti.raw_price, ti.raw_currency, ti.raw_unit,
                   ti.proposed_price, ti.approved_price, ti.currency, ti.sort_order,
                   ti.calced_cost, ti.calced_price, ti.calc_config_version,
                   ti.manual_price, ti.manual_override_reason,
                   ti.qty_delivered, ti.qty_from_stock, ti.stock_note,
                   ti.qty_not_delivered, ti.not_delivered_reason,
                   ti.catalog_price_id, ti.catalog_product_code, ti.weight_multiplier,
                   ti.sourced_from_stock, ti.stock_sale_price,
                   pp.price AS catalog_price, pp.currency AS catalog_currency,
                   pp.price_unit AS catalog_price_unit, pp.sqm_per_piece AS catalog_sqm_per_piece,
                   pp.thickness_mm AS catalog_thickness_mm, pp.pcs_per_box AS catalog_pcs_per_box,
                   f.country AS catalog_origin_country_code
              FROM sales.ticket_item ti
              LEFT JOIN price_catalog.product_prices pp ON pp.price_id = ti.catalog_price_id
              LEFT JOIN price_catalog.factories      f  ON f.factory_id = pp.factory_id
             WHERE ti.ticket_id = :id
             ORDER BY ti.sort_order, ti.item_id
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> {
                int calcConfigVersionRaw = rs.getInt("calc_config_version");
                Integer calcConfigVersion = rs.wasNull() ? null : calcConfigVersionRaw;
                long catalogPriceIdRaw = rs.getLong("catalog_price_id");
                Long catalogPriceId = rs.wasNull() ? null : catalogPriceIdRaw;
                return new TicketItemDto(
                    rs.getLong("item_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("brand"),
                    rs.getString("model"),
                    rs.getString("color"),
                    rs.getString("texture"),
                    rs.getString("size"),
                    rs.getString("factory"),
                    rs.getBigDecimal("qty"),
                    rs.getBigDecimal("qty_sqm"),
                    rs.getBigDecimal("raw_price"),
                    rs.getString("raw_currency"),
                    rs.getString("raw_unit"),
                    rs.getBigDecimal("proposed_price"),
                    rs.getBigDecimal("approved_price"),
                    rs.getString("currency"),
                    rs.getInt("sort_order"),
                    rs.getBigDecimal("calced_cost"),
                    rs.getBigDecimal("calced_price"),
                    calcConfigVersion,
                    rs.getString("unit_basis"),
                    rs.getBigDecimal("manual_price"),
                    rs.getString("manual_override_reason"),
                    rs.getBigDecimal("qty_delivered"),
                    rs.getBigDecimal("qty_from_stock"),
                    rs.getString("stock_note"),
                    catalogPriceId,
                    rs.getString("catalog_product_code"),
                    catalogPriceId != null ? "catalog" : "custom",
                    rs.getBigDecimal("catalog_price"),
                    rs.getString("catalog_currency"),
                    rs.getString("catalog_price_unit"),
                    rs.getBigDecimal("catalog_sqm_per_piece"),
                    rs.getInt("weight_multiplier"),
                    rs.getBoolean("sourced_from_stock"),
                    rs.getBigDecimal("stock_sale_price"),
                    rs.getBigDecimal("catalog_thickness_mm"),
                    rs.getBigDecimal("catalog_pcs_per_box"),
                    rs.getString("catalog_origin_country_code"),
                    rs.getBigDecimal("qty_not_delivered"),
                    rs.getString("not_delivered_reason")
                );
            });
    }

    /**
     * Fallback so "รายการสินค้า" does not show 0 รายการ while a deal is still in the pricing/quotation
     * phase: {@code sales.ticket_item} stays empty until order confirmation writes it (see
     * {@code OrderConfirmationService#reconcileTicketItems}), so a deal still working its
     * pricing-request/quotation chain has no rows for {@link #findItemsByTicketId} to return even
     * though its real items already exist on {@code sales.quotation_item}. When
     * {@code ticket_item} is empty, the READ path (only -- see {@link TicketDto#fromPricingChain}'s
     * own Javadoc on why this must not reach {@link #findById}/mutation call sites) falls back to
     * the deal's live quotation's line items, rendered READ-ONLY.
     *
     * <p>"Live" is a display pick over new-engine rows only: ACCEPTED beats ISSUED beats APPROVED,
     * across both PRICING_REQUEST and DEAL_DIRECT origins, most recent id wins a tie. It is NOT the
     * ticket-route deposit notice's pick ({@code DepositNoticeRepository#findGoverningQuotation}),
     * which also reads legacy rows and counts a DEAL_DIRECT approval
     * as the answer, level with ACCEPTED. The two agree on a ticket with one live new-engine
     * quotation. They differ when the ticket also has a live legacy quotation. Two DEAL_DIRECT
     * cases exist only on pre-lock data (a DEAL_DIRECT quotation beside a pricing-request one,
     * which {@code DirectQuotationLocks} now refuses): an ACCEPTED quotation followed by a newer
     * DEAL_DIRECT APPROVED one ({@code DealQuotationRepository#supersedeOtherLiveOnTicket} never
     * supersedes an ACCEPTED row), where this shows the ACCEPTED lines and a ticket-route draft
     * bills the DEAL_DIRECT one; and older data that still holds both a
     * DEAL_DIRECT APPROVED and a PRICING_REQUEST ISSUED row.
     * The UI drafts any deal with a QUOTATION_ACCEPTED pricing request through the
     * pricing-request route, which bills that request's accepted quotation instead.
     *
     * <p>Only brand/model/color/texture/size/qty/qtySqm/unitBasis/rawUnit are mapped -- pricing
     * fields (proposedPrice/approvedPrice/calcedCost/manualPrice/...) are deliberately left at
     * their TicketItemDto defaults (null / not-from-stock / weight 1) because these rows are NOT
     * committed ticket_item rows and carry none of that ticket-native pricing-approval history;
     * showing a borrowed price under those columns would misrepresent it as something CEO/import
     * approved on THIS ticket. qty_sqm/unit_basis are selected for completeness but are null in
     * practice today -- neither CustomerQuotationRepository#insertItems nor
     * DealQuotationRepository's item INSERT writes those two legacy columns (both write qty +
     * raw_unit only), so the grid's SQM sub-line simply does not render for a fallback row; this
     * is an accepted, pre-existing quotation_item data gap.
     */
    public List<TicketItemDto> findPricingChainFallbackItems(long ticketId) {
        List<Long> liveQuotationId = jdbc.query("""
            SELECT quotation_id FROM sales.quotation
             WHERE ticket_id = :ticketId
               AND origin IN ('PRICING_REQUEST', 'DEAL_DIRECT')
               AND doc_status IN ('ACCEPTED', 'ISSUED', 'APPROVED')
             ORDER BY CASE doc_status
                          WHEN 'ACCEPTED' THEN 0
                          WHEN 'ISSUED'   THEN 1
                          ELSE 2
                      END, quotation_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId), (rs, i) -> rs.getLong("quotation_id"));
        if (liveQuotationId.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
            SELECT quotation_item_id, seq, brand, model, color, texture, size,
                   qty, qty_sqm, unit_basis, raw_unit
              FROM sales.quotation_item
             WHERE quotation_id = :quotationId
             ORDER BY seq
            """, Map.of("quotationId", liveQuotationId.get(0)), (rs, i) -> new TicketItemDto(
                rs.getLong("quotation_item_id"),
                ticketId,
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("color"),
                rs.getString("texture"),
                rs.getString("size"),
                null, // factory -- sales.quotation_item has no factory column
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("qty_sqm"),
                null, null, // rawPrice/rawCurrency -- not a costing input on a borrowed row
                rs.getString("raw_unit"),
                null, null, // proposedPrice/approvedPrice -- see method Javadoc
                null, // currency
                rs.getInt("seq"),
                null, null, null, // calcedCost/calcedPrice/calcConfigVersion
                rs.getString("unit_basis"),
                null, null, // manualPrice/manualOverrideReason
                null, null, null, // qtyDelivered/qtyFromStock/stockNote
                null, null // catalogPriceId/catalogProductCode
            ));
    }

    public void updateItemCalcResults(long itemId, BigDecimal calcedCost,
                                      BigDecimal calcedPrice, int configVersion,
                                      BigDecimal proposedPrice) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET calced_cost         = :calcedCost,
                   calced_price        = :calcedPrice,
                   calc_config_version = :version,
                   proposed_price      = :proposedPrice
             WHERE item_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("calcedCost", calcedCost)
                .addValue("calcedPrice", calcedPrice)
                .addValue("version", configVersion)
                .addValue("proposedPrice", proposedPrice)
                .addValue("id", itemId));
    }

    private List<TicketEventDto> findEventsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT event_id, ticket_id, actor_id, actor_name, kind,
                   from_status, to_status, message, created_at,
                   item_snapshot::text AS item_snapshot
              FROM sales.ticket_event
             WHERE ticket_id = :id
             ORDER BY created_at ASC, event_id ASC
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> new TicketEventDto(
                rs.getLong("event_id"),
                rs.getLong("ticket_id"),
                rs.getLong("actor_id"),
                rs.getString("actor_name"),
                rs.getString("kind"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("item_snapshot")
            ));
    }

    private List<QuotationDto> findQuotationsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT q.quotation_id, q.ticket_id, q.number, q.issued_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS issued_by_name,
                   q.issued_at, q.pdf_path, q.total_amount, q.currency,
                   q.quotation_version, q.doc_status,
                   q.recipient_type, q.recipient_label, q.payment_terms, q.lead_time,
                   q.delivery_terms, q.validity_date, q.sent_at, q.accepted_at,
                   q.rejected_at, q.parent_quotation_id,
                   q.offer_date, q.deposit_pct, q.delivery_days
              FROM sales.quotation q
              JOIN hr.employee e ON e.employee_id = q.issued_by
             WHERE q.ticket_id = :id
               -- Quotation v2 (direct deal quotation, V165): direct-flow rows have their own
               -- listing (th.co.glr.hr.dealquotation.DealQuotationRepository) and their own panel
               -- on the ticket-detail documents tab -- excluded here so they do not also show up
               -- in the legacy pricing-chain quotation list (DealLegacyQuotations.jsx).
               --
               -- GLA-123 slice S1 fix (Opus review M1, 2026-09-20): narrowed from
               -- `origin IS DISTINCT FROM 'DEAL_DIRECT'` to `origin IS NULL`. That expression
               -- excluded DEAL_DIRECT but, being a negation, ALSO matched the NEW
               -- 'PRICING_REQUEST' origin (neither NULL nor 'DEAL_DIRECT'), so those rows leaked
               -- into this LEGACY list too -- DealLegacyQuotations.jsx renders a row's items as
               -- unit_price x qty against LEGACY columns, which a PRICING_REQUEST row's TILE-shaped
               -- data does not populate the same way, and a leaked DRAFT could become this ticket's
               -- `latestQuotation`. PRICING_REQUEST rows now have their own surface (the ใบเสนอ
               -- ราคาลูกค้า panel on PricingRequestDetailPage.jsx) — see that page's own comment.
               AND q.origin IS NULL
             ORDER BY q.issued_at DESC, q.quotation_id DESC
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> {
                Timestamp sentAt = rs.getTimestamp("sent_at");
                Timestamp acceptedAt = rs.getTimestamp("accepted_at");
                Timestamp rejectedAt = rs.getTimestamp("rejected_at");
                long parentRaw = rs.getLong("parent_quotation_id");
                Long parentQuotationId = rs.wasNull() ? null : parentRaw;
                java.math.BigDecimal depositPctDecimal = rs.getBigDecimal("deposit_pct");
                Integer depositPct = depositPctDecimal == null ? null
                    : (int) Math.round(depositPctDecimal.doubleValue() * 100);
                int deliveryDaysRaw = rs.getInt("delivery_days");
                Integer deliveryDays = rs.wasNull() ? null : deliveryDaysRaw;
                return new QuotationDto(
                    rs.getLong("quotation_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("number"),
                    rs.getLong("issued_by"),
                    rs.getString("issued_by_name"),
                    rs.getTimestamp("issued_at").toInstant(),
                    rs.getString("pdf_path"),
                    rs.getBigDecimal("total_amount"),
                    rs.getString("currency"),
                    rs.getInt("quotation_version"),
                    rs.getString("doc_status"),
                    rs.getString("recipient_type"),
                    rs.getString("recipient_label"),
                    rs.getString("payment_terms"),
                    rs.getString("lead_time"),
                    rs.getString("delivery_terms"),
                    rs.getObject("validity_date", LocalDate.class),
                    sentAt != null ? sentAt.toInstant() : null,
                    acceptedAt != null ? acceptedAt.toInstant() : null,
                    rejectedAt != null ? rejectedAt.toInstant() : null,
                    parentQuotationId,
                    rs.getObject("offer_date", LocalDate.class),
                    depositPct,
                    deliveryDays
                );
            });
    }

    /** Marks every line the sales rep declared จากสต็อก as fully covered from stock (qty_from_stock = qty). */
    public void coverSourcedFromStockLines(long ticketId) {
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = qty WHERE ticket_id = :ticketId AND sourced_from_stock",
            Map.of("ticketId", ticketId));
    }

    /**
     * Legacy = the order was confirmed from a direct quotation issued before the quotation carried the
     * import/stock choice (2026-11-07, Bangkok date): the rep still has to make it in the stage-10 dialog.
     */
    public boolean isLegacyStockSourceDeal(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation q
                 WHERE q.quotation_id = (
                        SELECT e.related_document_id FROM sales.ticket_event e
                         WHERE e.ticket_id = :ticketId AND e.kind = :kind
                           AND e.related_document_type = 'QUOTATION' AND e.related_document_id IS NOT NULL
                         ORDER BY e.created_at DESC, e.event_id DESC LIMIT 1)
                   AND q.origin = 'DEAL_DIRECT'
                   AND (q.issued_at AT TIME ZONE 'Asia/Bangkok')::date < DATE '2026-11-07')
               AND NOT EXISTS (SELECT 1 FROM sales.ticket_item i WHERE i.ticket_id = :ticketId AND i.sourced_from_stock)
            """, Map.of("ticketId", ticketId, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION), Boolean.class));
    }

    /** Latches the procurement-source decision; false when it was already decided (the once-only guard). */
    public boolean markProcurementSourceDecided(long ticketId, long actorId) {
        return jdbc.update("""
            UPDATE sales.ticket
               SET procurement_source_decided_at = now(), procurement_source_decided_by = :actorId
             WHERE ticket_id = :ticketId AND procurement_source_decided_at IS NULL
            """, Map.of("ticketId", ticketId, "actorId", actorId)) > 0;
    }

    public Optional<String> quotationNumber(long quotationId) {
        return jdbc.query("SELECT number FROM sales.quotation WHERE quotation_id = :id", Map.of("id", quotationId),
            (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    public Optional<Instant> findProcurementSourceDecidedAt(long ticketId) {
        return jdbc.query("SELECT procurement_source_decided_at FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), (rs, n) -> rs.getTimestamp(1))
            .stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    private void insertItems(long ticketId, List<TicketItemRequest> items) {
        if (items.isEmpty()) {
            return;
        }
        // Single batched round-trip instead of one INSERT per item; the datasource has reWriteBatchedInserts=true so
        // this collapses into one multi-row statement (issue #29).
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            TicketItemRequest item = items.get(i);
            String currency = (item.currency() != null && !item.currency().isBlank()) ? item.currency() : "THB";
            String unitBasis = (item.unitBasis() != null && !item.unitBasis().isBlank())
                ? item.unitBasis() : "PIECE";
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("factory", item.factory())
                .addValue("qty", item.qty())
                .addValue("qtySqm", item.qtySqm())
                .addValue("unitBasis", unitBasis)
                .addValue("rawPrice", item.rawPrice())
                .addValue("rawCurrency", item.rawCurrency())
                .addValue("rawUnit", item.rawUnit())
                .addValue("proposedPrice", item.proposedPrice())
                .addValue("currency", currency)
                .addValue("sortOrder", i)
                .addValue("catalogPriceId", item.catalogPriceId())
                .addValue("catalogProductCode", item.catalogProductCode())
                // sourcedFromStock is a nullable Boolean: a request that never mentions it means "not from
                // stock", same as false. The sales-typed price is gone (walk 4): the column always stays NULL.
                .addValue("sourcedFromStock", Boolean.TRUE.equals(item.sourcedFromStock()))
                .addValue("stockSalePrice", null);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory,
                 qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                 proposed_price, currency, sort_order, catalog_price_id, catalog_product_code,
                 sourced_from_stock, stock_sale_price)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :unitBasis, :rawPrice, :rawCurrency, :rawUnit,
                    :proposedPrice, :currency, :sortOrder, :catalogPriceId, :catalogProductCode,
                    :sourcedFromStock, :stockSalePrice)
            """, batch);
    }

    private TicketSummaryDto mapSummary(ResultSet rs) throws SQLException {
        long assignedToRaw = rs.getLong("assigned_to");
        Long assignedToId = rs.wasNull() ? null : assignedToRaw;
        long customerIdRaw = rs.getLong("customer_id");
        Long customerId = rs.wasNull() ? null : customerIdRaw;
        long projectIdRaw = rs.getLong("project_id");
        Long projectId = rs.wasNull() ? null : projectIdRaw;
        long contactIdRaw = rs.getLong("contact_id");
        Long contactId = rs.wasNull() ? null : contactIdRaw;
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new TicketSummaryDto(
            rs.getLong("ticket_id"),
            rs.getString("code"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getLong("created_by"),
            rs.getString("created_by_name"),
            assignedToId,
            rs.getString("assigned_to_name"),
            rs.getString("customer_name"),
            customerId,
            projectId,
            rs.getString("project_name"),
            contactId,
            rs.getString("contact_name"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            closedAt != null ? closedAt.toInstant() : null,
            rs.getInt("item_count"),
            rs.getBoolean("has_edits"),
            rs.getString("payment_status"),
            rs.getString("fulfillment_status"),
            rs.getString("sales_stage"),
            rs.getString("lost_reason"),
            rs.getTimestamp("lost_at") != null ? rs.getTimestamp("lost_at").toInstant() : null,
            rs.getTimestamp("stage_updated_at").toInstant(),
            rs.getString("lifecycle"),
            rs.getString("tender_requirement"),
            rs.getString("deposit_policy"),
            rs.getString("deposit_policy_reason"),
            rs.getString("entry_channel"),
            rs.getObject("billing_date", LocalDate.class),
            rs.getObject("due_date", LocalDate.class),
            (Integer) rs.getObject("credit_term_days"),
            rs.getObject("last_follow_up_at", LocalDate.class),
            rs.getObject("next_follow_up_at", LocalDate.class),
            PaymentStage.NOT_REQUIRED,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            false,
            rs.getTimestamp("close_confirmed_at") != null
                ? rs.getTimestamp("close_confirmed_at").toInstant() : null,
            rs.getString("close_confirmed_by_name"),
            false,
            rs.getString("cancel_reason"),
            rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null,
            (Integer) rs.getObject("win_probability"),
            rs.getString("designer_name"),
            rs.getString("owner_name"),
            rs.getString("buyer_name"),
            false, // stale — recomputed by enrichSummaries, which alone knows deal_activity
            false, // commissionRecorded — recomputed by enrichSummaries, which alone queries commission_record
            rs.getTimestamp("reopened_at") != null ? rs.getTimestamp("reopened_at").toInstant() : null,
            rs.getInt("reopen_count"),
            rs.getBoolean("quotation_only")
        ).withKeyedById(rs.getObject("keyed_by", Long.class))
            .withMovement(rs.getBoolean("stage_changed_in_range"), rs.getBoolean("activity_in_range"),
                rs.getObject("last_activity_at", LocalDate.class))
            .withPauseReturnOn(rs.getObject("pause_return_on", LocalDate.class))
            .withEstimatedValue(rs.getBigDecimal("estimated_value"))
            .withExpectedQuarter(rs.getString("expected_quarter"))
            .withParent(rs.getObject("parent_ticket_id", Long.class), rs.getString("parent_code"),
                rs.getString("child_kind"), rs.getInt("add_on_count"))
            .withAwardedBuyerName(rs.getString("awarded_buyer"));
    }

    /** One row's worth of the enrichment facts {@link SummaryFactsSql#SELECT} computes for a page of deals. */
    private record SummaryFacts(BigDecimal payable, BigDecimal paid, boolean balanceReceipt, boolean stale,
                                PaymentDue due, boolean hasInvoice, CommissionAndLiveQuotation extras,
                                String receiptSource, String paymentStatus) {
        /** A deal with no facts row (deleted between the two statements): nothing owed, nothing flagged. */
        static final SummaryFacts NONE = new SummaryFacts(BigDecimal.ZERO, BigDecimal.ZERO, false, false, null, false,
            new CommissionAndLiveQuotation(false, null, Set.of(), null), null, null);
    }

    private Map<Long, SummaryFacts> summaryFacts(List<Long> ids) {
        Map<Long, SummaryFacts> byId = new HashMap<>();
        jdbc.query(SummaryFactsSql.SELECT,
            Map.of("ids", ids.toArray(Long[]::new), "quietDays", quietRule.quietDays()), rs -> {
            BigDecimal payable = rs.getBigDecimal("payable");
            BigDecimal paid = rs.getBigDecimal("paid");
            long quotationId = rs.getLong("quotation_id");
            LiveDirectQuotationDto live = rs.wasNull() ? null
                : new LiveDirectQuotationDto(quotationId, rs.getString("number"),
                    rs.getString("doc_status"), rs.getString("recipient_type"));
            // basis null = legacy / free-text terms: nothing structured, so no PaymentDue (as paymentTermsRowSql).
            PaymentDue due = null;
            String basis = rs.getString("basis");
            if (basis != null) {
                int days = rs.getInt("days");
                java.sql.Date delivered = rs.getDate("delivered_on");
                int credit = rs.getInt("credit_days");
                Integer creditDays = rs.wasNull() ? null : credit;
                due = new PaymentDue(delivered == null ? null : delivered.toLocalDate().plusDays(days), basis, creditDays);
            }
            byId.put(rs.getLong("ticket_id"), new SummaryFacts(
                payable == null ? BigDecimal.ZERO : payable, paid == null ? BigDecimal.ZERO : paid,
                rs.getBoolean("balance_receipt"), rs.getBoolean("is_stale"), due,
                rs.getBoolean("has_invoice"),
                new CommissionAndLiveQuotation(rs.getBoolean("commission_recorded"), live,
                    readTextArray(rs, "quoted_recipients"), rs.getBigDecimal("deposit_due")),
                rs.getString("receipt_source"), rs.getString("payment_status")));
        });
        return byId;
    }

    /**
     * When a deal's balance is due and whether it is overdue — exactly what {@link TicketSummaryDto#paymentDueDate} and
     * {@link TicketSummaryDto#overdue} serve (the finance worklist's due chip). {@code dueDate} is null until the deal
     * is fully delivered or when its quotation has no structured term.
     */
    public record PaymentDueState(LocalDate dueDate, boolean overdue) {}

    /**
     * {@link PaymentDueState} for a set of deals in ONE statement — the very statement and decision
     * {@link #enrichSummaries} runs for the summary, so the board's due chip (SalesBoardRepository, slice 6.2) can
     * never disagree with the finance worklist's. A deal id with no row is simply absent from the map.
     */
    public Map<Long, PaymentDueState> paymentDueStates(java.util.Collection<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PaymentDueState> out = new HashMap<>();
        LocalDate today = quietRule.bangkokToday();
        summaryFacts(List.copyOf(ticketIds)).forEach((id, f) -> out.put(id, paymentDueState(f, f.paymentStatus(), today)));
        return out;
    }

    /** What the customer still owes: payable minus paid, never below zero. */
    private static BigDecimal outstanding(BigDecimal payable, BigDecimal paid) {
        BigDecimal outstanding = payable.subtract(paid);
        return outstanding.signum() < 0 ? BigDecimal.ZERO : outstanding;
    }

    /**
     * THE overdue decision (Java side), shared by {@link #enrichSummary} and {@link #paymentDueStates}. OVERDUE comes
     * from the payable quotation's terms + the delivery date, never from the stored billing due_date; an unpaid
     * deposit is never overdue. {@link #paymentOverduePredicate} is its SQL twin for the list scope and dashboard.
     * {@code today} is the Asia/Bangkok calendar date ({@link QuietRule#bangkokToday()} — the same clock source the
     * quiet rule uses, injectable in tests; the system clock in production, exactly the old {@code LocalDate.now(BANGKOK)}).
     * Due ON {@code today} is not overdue; the Bangkok day after is.
     */
    private static PaymentDueState paymentDueState(SummaryFacts f, String paymentStatus, LocalDate today) {
        PaymentDue due = f.due();
        boolean overdue = due != null && due.date() != null
            && today.isAfter(due.date())
            && outstanding(f.payable(), f.paid()).signum() > 0
            && !"DEPOSIT_NOTICE_ISSUED".equals(paymentStatus);
        return new PaymentDueState(due == null ? null : due.date(), overdue);
    }

    private List<TicketSummaryDto> enrichSummaries(List<TicketSummaryDto> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, SummaryFacts> facts = summaryFacts(rows.stream().map(TicketSummaryDto::id).toList());
        // A deal deleted between the two statements has no facts row: keep it with zero facts so page totals hold.
        return rows.stream().map(s -> enrichSummary(s, facts.getOrDefault(s.id(), SummaryFacts.NONE))).toList();
    }

    private TicketSummaryDto enrichSummary(TicketSummaryDto s, SummaryFacts f) {
        BigDecimal payable = f.payable();
        BigDecimal paid = f.paid();
        BigDecimal outstanding = outstanding(payable, paid);
        String stage = derivePaymentStage(s, payable, paid, outstanding, f.balanceReceipt());
        // The one overdue decision (paymentDueState), shared with paymentDueStates — the board's due chip.
        PaymentDue due = f.due();
        boolean overdue = paymentDueState(f, s.paymentStatus(), quietRule.bangkokToday()).overdue();
        // Staleness only means anything for a deal actively being worked — a DRAFT that hasn't
        // started or a CLOSED_LOST/CANCELLED/COMPLETED deal is never flagged.
        boolean stale = DealLifecycle.ACTIVE.equals(s.lifecycle()) && f.stale();
        CommissionAndLiveQuotation facts = f.extras();
        return new TicketSummaryDto(
            s.id(), s.code(), s.type(), s.title(), s.status(), s.priority(),
            s.createdById(), s.createdByName(), s.assignedToId(), s.assignedToName(),
            s.customerName(), s.customerId(), s.projectId(), s.projectName(),
            s.contactId(), s.contactName(), s.note(),
            s.createdAt(), s.updatedAt(), s.closedAt(), s.itemCount(), s.hasEdits(),
            s.paymentStatus(), s.fulfillmentStatus(), s.salesStage(), s.lostReason(), s.lostAt(),
            s.stageUpdatedAt(), s.lifecycle(), s.tenderRequirement(), s.depositPolicy(),
            s.depositPolicyReason(), s.entryChannel(), s.billingDate(), s.dueDate(),
            s.creditTermDays(), s.lastFollowUpAt(), s.nextFollowUpAt(),
            stage, payable, paid, outstanding, overdue,
            s.closeConfirmedAt(), s.closeConfirmedByName(), f.hasInvoice(),
            s.cancelReason(), s.cancelledAt(),
            s.winProbabilityOverride(), s.designerName(), s.ownerName(), s.buyerName(), stale,
            facts.commissionRecorded(), s.reopenedAt(), s.reopenCount(), s.quotationOnly(),
            due == null ? null : due.date(), due == null ? null : due.basis(),
            due == null ? null : due.creditDays(),
            facts.liveDirectQuotation(), facts.quotedRecipients(),
            "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus()) ? facts.depositDue() : null,
            s.keyedById(), s.stageChangedInRange(), s.activityInRange(), s.lastActivityAt(), s.pauseReturnOn(),
            s.estimatedValue(), s.expectedQuarter(), f.receiptSource(),
            s.parentTicketId(), s.parentCode(), s.childKind(), s.addOnCount())
            .withAwardedBuyerName(s.awardedBuyerName());
    }

    /** An INVOICE document is on file. The invoice is produced by an external system and uploaded here, so its
     * presence — not its contents — is what the close gate can check. */
    public boolean hasInvoiceAttachment(long ticketId) {
        Boolean value = jdbc.queryForObject("SELECT " + hasInvoiceAttachmentSql(":id"),
            Map.of("id", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** Boolean SQL fragment: an INVOICE attachment exists on the deal at {@code ticketIdRef}. */
    private static String hasInvoiceAttachmentSql(String ticketIdRef) {
        return """
            EXISTS(SELECT 1 FROM sales.attachment a
                    WHERE a.ticket_id = %s AND a.attach_type = 'INVOICE'
                      AND (a.delivery_request_id IS NULL
                           OR EXISTS (SELECT 1 FROM sales.delivery_request r
                                       WHERE r.delivery_request_id = a.delivery_request_id AND r.status = 'INVOICED')))
            """.formatted(ticketIdRef);
    }

    /** Boolean SQL fragment: a BALANCE receipt exists on the deal at {@code ticketIdRef}. */
    private static String hasBalanceReceiptSql(String ticketIdRef) {
        return """
            EXISTS(SELECT 1 FROM sales.payment_receipt
                    WHERE ticket_id = %s AND kind = 'BALANCE')
            """.formatted(ticketIdRef);
    }

    /**
     * Boolean SQL fragment: the deal is quiet under the one shared {@link QuietRule} (needs {@code :quietDays}).
     * {@code ticketAlias} must be the {@code sales.ticket} alias, not a {@code ticket_id} reference.
     */
    private static String isStaleSql(String ticketAlias) {
        return QuietRule.quietSql(ticketAlias);
    }

    /** Backs TicketSummaryDto#commissionRecorded — a DELIBERATE second copy of
     * CommissionRepository.hasActiveCommissionForTicket's predicate, not a re-derivation. If you change one
     * predicate you MUST change the other, and that test is what catches it if you forget. */
    public static String hasRecordedCommission(String ticketIdRef) {
        return """
            EXISTS(
                SELECT 1 FROM sales.commission_record
                 WHERE source_ticket_id = %s
                   AND kind = 'SALE'
                   AND status NOT IN ('VOID', 'REJECTED')
            )
        """.formatted(ticketIdRef);
    }

    private record CommissionAndLiveQuotation(boolean commissionRecorded,
                                              LiveDirectQuotationDto liveDirectQuotation,
                                              Set<String> quotedRecipients,
                                              BigDecimal depositDue) {}

    /** A Postgres {@code text[]} as a {@link Set}; empty for SQL NULL, which is what
     * {@code array_agg} returns over no rows. */
    private static Set<String> readTextArray(ResultSet rs, String column)
            throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return Set.of();
        }
        return Set.of((String[]) array.getArray());
    }

    /** ฝ่ายบัญชี signs off that the deal is ready to close; CEO verification still required. */
    public void confirmClose(long ticketId, long actorId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET close_confirmed_by = :actor, close_confirmed_at = now(), updated_at = now()
             WHERE ticket_id = :id
            """, Map.of("id", ticketId, "actor", actorId));
    }

    /** Withdraw the confirmation so the deal leaves the CEO's verification queue. */
    public void clearCloseConfirmation(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET close_confirmed_by = NULL, close_confirmed_at = NULL, updated_at = now()
             WHERE ticket_id = :id
            """, Map.of("id", ticketId));
    }

    private String derivePaymentStage(TicketSummaryDto s, BigDecimal payable, BigDecimal paid,
                                      BigDecimal outstanding, boolean balanceReceipt) {
        // The chip follows the payment track. Before the order is confirmed
        // (no payment_status, nothing paid, stage before ORDER_RECEIVED) there is no payment step to speak of;
        // a legacy deal already past that with a null track keeps the derivation below.
        if (s.paymentStatus() == null && paid.signum() <= 0
                && DealStage.indexOf(s.salesStage()) < DealStage.indexOf(DealStage.ORDER_RECEIVED)) {
            return PaymentStage.NOT_STARTED;
        }
        if (payable.signum() <= 0) {
            return PaymentStage.NOT_REQUIRED;
        }
        if (paid.compareTo(payable) >= 0) {
            return PaymentStage.FULLY_PAID;
        }
        if (paid.signum() > 0) {
            if (balanceReceipt) {
                return PaymentStage.PARTIALLY_PAID;
            }
            return "AWAITING_FINAL_PAYMENT".equals(s.paymentStatus())
                ? PaymentStage.BALANCE_PENDING : PaymentStage.DEPOSIT_RECEIVED;
        }
        if (DepositPolicy.bypassesDepositNotice(s.depositPolicy())) {
            // A 0% quotation: nothing to collect up front, the whole amount is the balance.
            return "CUSTOMER_CONFIRMED".equals(s.paymentStatus()) ? PaymentStage.NO_DEPOSIT
                : outstanding.signum() > 0 ? PaymentStage.BALANCE_PENDING : PaymentStage.NOT_REQUIRED;
        }
        if ("CUSTOMER_CONFIRMED".equals(s.paymentStatus()) || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            return PaymentStage.DEPOSIT_PENDING;
        }
        return outstanding.signum() > 0 ? PaymentStage.BALANCE_PENDING : PaymentStage.NOT_REQUIRED;
    }

    /** Live (non-CANCELLED) PO counts for one deal — the raw input to the import-status rollup. */
    public record PurchaseOrderRollup(int live, int received, int pastOpen) {}

    /** One query, three filtered counts — everything #deriveImportStatus needs from sales.factory_purchase_order. */
    public PurchaseOrderRollup purchaseOrderRollup(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE status <> 'CANCELLED')              AS live,
                   COUNT(*) FILTER (WHERE status = 'RECEIVED')                AS received,
                   COUNT(*) FILTER (WHERE status IN ('SHIPPING', 'RECEIVED')) AS past_open
              FROM sales.factory_purchase_order
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId),
            (rs, rowNum) -> new PurchaseOrderRollup(
                rs.getInt("live"), rs.getInt("received"), rs.getInt("past_open")));
    }

    /** Derives import fulfilment from live purchase orders; cancelled orders are excluded and no live orders yields
     * null. */
    public String deriveImportStatus(long ticketId) {
        PurchaseOrderRollup r = purchaseOrderRollup(ticketId);
        if (r.live() == 0) {
            return null;
        }
        if (r.received() == r.live()) {
            return FulfilmentStatus.GOODS_RECEIVED;
        }
        if (r.pastOpen() > 0) {
            return FulfilmentStatus.SHIPPING;
        }
        return null;
    }

    /** Whether this deal is PO-tracked at all (>= 1 live, non-CANCELLED PO) — the refusal guard
     * TicketService#markShipping/#markGoodsReceived use to refuse a ticket-level write that would bypass the
     * per-factory PO record. */
    public boolean hasLivePurchaseOrders(long ticketId) {
        return purchaseOrderRollup(ticketId).live() > 0;
    }

    /**
     * Whether this deal has at least one live (DRAFT or ISSUED, not SUPERSEDED) row in the per-factory
     * {@code sales.import_request} aggregate (V184) — the same refuse-don't-delegate guard {@link #hasLivePurchaseOrders} provides
     * for the (dormant) PO path, used by {@code TicketService#markIrSent}/{@code #markShipping}/
     * {@code #markGoodsReceived} to refuse a ticket-level click that cannot say which factory it
     * concerns once the deal is actually tracked per-factory.
     */
    public boolean hasLiveImportRequests(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.import_request WHERE ticket_id = :ticketId AND status <> 'SUPERSEDED'
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** What reached (or is on its way to) the warehouse for one deal line, over the ISSUED IR lines that point at it. */
    public record LineArrival(int irLines, BigDecimal received, BigDecimal inProgress, int zeroReceivedLots) {}

    public LineArrival lineArrival(long ticketId, long itemId) {
        return jdbc.queryForObject("""
            SELECT COUNT(DISTINCT i.import_request_item_id) AS ir_lines,
                   COALESCE(SUM(CASE WHEN r.import_step = 'RECEIVED' AND COALESCE(l.received_qty, l.qty) > 0
                                     THEN COALESCE(l.received_qty, l.qty) END), 0) AS received,
                   COALESCE(SUM(CASE WHEN r.import_step <> 'RECEIVED' THEN l.qty END), 0) AS in_progress,
                   COUNT(CASE WHEN r.import_step = 'RECEIVED' AND COALESCE(l.received_qty, l.qty) = 0 THEN 1 END) AS zeros
              FROM sales.import_request_item i
              JOIN sales.import_request ir ON ir.import_request_id = i.import_request_id
                                          AND ir.ticket_id = :t AND ir.status = 'ISSUED'
              LEFT JOIN sales.shipment_lot l ON l.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE i.ticket_item_id = :item
            """, Map.of("t", ticketId, "item", itemId), (rs, n) -> new LineArrival(rs.getInt("ir_lines"),
                rs.getBigDecimal("received"), rs.getBigDecimal("in_progress"), rs.getInt("zeros")));
    }

    /** Sets the marked-not-delivered quantity of a line (never beyond what is not yet delivered); 0 rows = refused. */
    public int setNotDelivered(long ticketId, long itemId, BigDecimal qty, String reason, long byId) {
        return jdbc.update("""
            UPDATE sales.ticket_item
               SET qty_not_delivered = :q, not_delivered_reason = :reason, not_delivered_by = :by, not_delivered_at = now()
             WHERE ticket_id = :t AND item_id = :item AND qty_delivered + :q <= qty
            """, new MapSqlParameterSource("t", ticketId).addValue("item", itemId).addValue("q", qty)
                .addValue("reason", reason).addValue("by", byId));
    }

    public int clearNotDelivered(long ticketId, long itemId) {
        return jdbc.update("""
            UPDATE sales.ticket_item
               SET qty_not_delivered = 0, not_delivered_reason = NULL, not_delivered_by = NULL, not_delivered_at = NULL
             WHERE ticket_id = :t AND item_id = :item AND qty_not_delivered > 0
            """, Map.of("t", ticketId, "item", itemId));
    }

    /** Locks sales.ticket's own row FOR UPDATE for the remainder of the caller's transaction. */
    public String lockTicketForUpdate(long ticketId) {
        return jdbc.query("SELECT lifecycle FROM sales.ticket WHERE ticket_id = :id FOR UPDATE",
            Map.of("id", ticketId), (rs, n) -> rs.getString(1)).stream().findFirst().orElse(null);
    }

    /**
     * One finalized quotation per deal, across BOTH คำขอราคา origins:
     * {@code true} when {@code sales.quotation} already carries an {@code ACCEPTED} row for this
     * ticket, regardless of {@code origin} — the legacy chain ({@code origin IS NULL}), the new
     * PricingRequest-chain engine ({@code origin = 'PRICING_REQUEST'}) and the direct engine
     * ({@code origin = 'DEAL_DIRECT'}, which in practice never reaches ACCEPTED because it never
     * joins this pipeline) all write the ONE shared {@code sales.quotation} table, so a single
     * origin-agnostic EXISTS check is the whole guard; see {@code
     * CustomerQuotationService#recordOutcome} and {@code DealQuotationService#recordOutcome},
     * both of which call this AFTER {@link #lockTicketForUpdate} serializes concurrent callers
     * (possibly one on each side) against each other, and BEFORE their own compare-and-set UPDATE.
     */
    public boolean hasAcceptedQuotation(long ticketId) {
        Boolean value = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.quotation WHERE ticket_id = :ticketId AND doc_status = 'ACCEPTED')",
            Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public void updateSalesStage(long ticketId, String stage) {
        jdbc.update(
            "UPDATE sales.ticket SET sales_stage = :s, stage_updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", stage).addValue("id", ticketId));
    }

    public void markDealLost(long ticketId, String reason) {
        jdbc.update(
            "UPDATE sales.ticket SET lost_reason = :r, lost_at = now(), lifecycle = :lifecycle, stage_updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("r", reason)
                .addValue("lifecycle", DealLifecycle.CLOSED_LOST)
                .addValue("id", ticketId));
    }

    /** Record why the opportunity went away. Lifecycle is set separately by the caller. */
    public void cancelDeal(long ticketId, String reason) {
        jdbc.update(
            "UPDATE sales.ticket SET cancel_reason = :r, cancelled_at = now(), updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("r", reason)
                .addValue("id", ticketId));
    }

    /**
     * Reopen a lost deal — **without erasing why it was lost**.
     *
     * lost_reason/lost_at stay readable (nulling them would leave a row
     * indistinguishable from one that was never lost) and reopened_at /
     * reopen_count record that the deal came back, so "reopened deals we
     * previously lost on PRICE" is a plain query.
     *
     * lifecycle=ACTIVE is what makes the deal live again; `lost_reason != null`
     * no longer implies "currently lost" — check the lifecycle for that.
     */
    public void clearDealLost(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET lifecycle = :lifecycle,
                   reopened_at = now(),
                   reopen_count = reopen_count + 1,
                   stage_updated_at = now()
             WHERE ticket_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("lifecycle", DealLifecycle.ACTIVE)
                .addValue("id", ticketId));
    }

    public void updateLifecycle(long ticketId, String lifecycle) {
        jdbc.update(
            "UPDATE sales.ticket SET lifecycle = :lifecycle, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("lifecycle", lifecycle).addValue("id", ticketId));
    }

    /** V220 มูลค่าโดยประมาณ: written by the tracking update (null = cleared). */
    public void updateEstimatedValue(long ticketId, java.math.BigDecimal estimatedValue) {
        jdbc.update(
            "UPDATE sales.ticket SET estimated_value = :v, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("v", estimatedValue, java.sql.Types.NUMERIC).addValue("id", ticketId));
    }

    /** V220 วันที่จะกลับมาดู: written by hold / dormant (null = no date) and cleared by resume. */
    public void updatePauseReturnOn(long ticketId, LocalDate pauseReturnOn) {
        jdbc.update(
            "UPDATE sales.ticket SET pause_return_on = :on, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("on", pauseReturnOn, java.sql.Types.DATE).addValue("id", ticketId));
    }

    public void updateTenderRequirement(long ticketId, String value) {
        jdbc.update(
            "UPDATE sales.ticket SET tender_requirement = :value, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("value", value).addValue("id", ticketId));
    }

    public void updateDepositPolicy(long ticketId, String policy, String reason, long setBy) {
        jdbc.update("""
            UPDATE sales.ticket
               SET deposit_policy = :policy,
                   deposit_policy_reason = :reason,
                   deposit_policy_set_by = :setBy,
                   updated_at = now()
             WHERE ticket_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("policy", policy)
                .addValue("reason", reason)
                .addValue("setBy", setBy)
                .addValue("id", ticketId));
    }

    /**
     * The system's only write to {@code deposit_policy} (see {@code TicketService
     * #followQuotationDepositPolicy}). A compare-and-set: it moves the row from {@code expected} to
     * {@code policy} only while no person has set the policy ({@code deposit_policy_set_by IS NULL};
     * {@link #updateDepositPolicy} always records one) and the payment track is still at
     * CUSTOMER_CONFIRMED. It leaves {@code deposit_policy_set_by} NULL, so the row stays the
     * system's. Returns the rowcount; 0 means leave the policy alone, e.g. a waiver committed first.
     */
    public int syncDepositPolicyFromQuotation(long ticketId, String expected, String policy, String reason) {
        // Dropping to NOT_REQUIRED is refused once a deposit notice is ISSUED: money was asked for.
        String noIssuedNotice = DepositPolicy.NOT_REQUIRED.equals(policy) ? """
               AND NOT EXISTS (SELECT 1 FROM sales.deposit_notice d
                                WHERE d.ticket_id = sales.ticket.ticket_id AND d.status = 'ISSUED')
            """ : "";
        return jdbc.update("""
            UPDATE sales.ticket
               SET deposit_policy = :policy, deposit_policy_reason = :reason,
                   deposit_policy_set_by = NULL, updated_at = now()
             WHERE ticket_id = :id
               AND deposit_policy = :expected
               AND deposit_policy_set_by IS NULL
               AND payment_status = 'CUSTOMER_CONFIRMED'
            """ + noIssuedNotice,
            new MapSqlParameterSource()
                .addValue("policy", policy)
                .addValue("reason", reason)
                .addValue("expected", expected)
                .addValue("id", ticketId));
    }

    public void updateEntryChannel(long ticketId, String value) {
        jdbc.update(
            "UPDATE sales.ticket SET entry_channel = :value, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("value", value).addValue("id", ticketId));
    }

    public long insertPaymentReceipt(long ticketId, String kind, BigDecimal amount, long recordedBy,
                                     Instant receivedAt, String note, Long depositNoticeId, String receiptRef) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.payment_receipt
                (ticket_id, kind, amount, currency, received_at, recorded_by, note, deposit_notice_id, receipt_ref)
            VALUES
                (:ticketId, :kind, :amount, 'THB', COALESCE(CAST(:receivedAt AS timestamptz), now()), :recordedBy, :note,
                 :depositNoticeId, :receiptRef)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("kind", kind)
                .addValue("amount", amount)
                .addValue("receivedAt", receivedAt == null ? null : Timestamp.from(receivedAt))
                .addValue("recordedBy", recordedBy)
                .addValue("note", note)
                .addValue("depositNoticeId", depositNoticeId)
                .addValue("receiptRef", receiptRef),
            keys, new String[]{"receipt_id"});
        return keys.getKey().longValue();
    }

    /**
     * THE one rule for what a receipt does to the deal's expected payments (spec 2026-11-15 §7.7, review M1) — called
     * for a receipt account typed ({@code TicketService#recordPayment}, ยืนยันรับมัดจำ, ยืนยันรับชำระส่วนที่เหลือ) and
     * for an uploaded Express one ({@code TicketService#recordExpressReceipt}) alike, so the two can never disagree.
     *
     * <p>Which OPEN rows the amount fills first (owner ruling N5 — it replaced the fixed DEPOSIT, ON_DELIVERY, BALANCE
     * order, which paid a far-off deposit row before this week's on-delivery one):
     * <ul>
     *   <li>the deal HAS an ISSUED deposit notice (a DRAFT, CANCELLED or SUPERSEDED one is no notice — the same test as
     *       {@code TicketService#expressReceiptKind}): first the rows matching the receipt's kind — a DEPOSIT
     *       receipt fills DEPOSIT rows; a BALANCE (any non-deposit) receipt fills ON_DELIVERY rows, then BALANCE rows —
     *       each by expected week then oldest; whatever overflows goes to the remaining OPEN rows of any kind by
     *       expected week;</li>
     *   <li>the deal has NO deposit notice (bypass policy): every OPEN row purely by expected week, then oldest,
     *       whatever its kind (a rep-entered DEPOSIT row still gets closed by its earliest receipt).</li>
     * </ul>
     * The receipt's own kind keeps its meaning for the payment track only. A row turns RECEIVED (linking this receipt)
     * only when its cumulative {@code received_amount} reaches its amount minus the matcher's tolerance (±1% or ±฿10,
     * whichever is wider); otherwise it stays OPEN with the rest still expected. What a completed row does not need
     * carries to the next row in that order. ADJUSTMENT receipts deliberately touch no expected-payment row: a refund
     * lowers the received cash, and the rows stay closed.
     */
    @SuppressWarnings("unused") // receiptDate: kept for callers and for a future tie-break
    public void applyReceiptToExpectedPayments(long ticketId, String receiptKind, BigDecimal amount,
                                               java.time.LocalDate receiptDate, long receiptId) {
        if (amount == null || amount.signum() <= 0 || receiptKind == null || "ADJUSTMENT".equals(receiptKind)) {
            return;
        }
        boolean hasNotice = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.deposit_notice d
                            WHERE d.ticket_id = :t AND d.status = 'ISSUED')
            """, Map.of("t", ticketId), Boolean.class)); // same predicate as TicketService#expressReceiptKind
        String order;
        if (!hasNotice) {
            order = "expected_week, expected_payment_id";
        } else if ("DEPOSIT".equals(receiptKind)) {
            order = "CASE kind WHEN 'DEPOSIT' THEN 0 ELSE 1 END, expected_week, expected_payment_id";
        } else {
            order = "CASE kind WHEN 'ON_DELIVERY' THEN 0 WHEN 'BALANCE' THEN 1 ELSE 2 END,"
                + " expected_week, expected_payment_id";
        }
        BigDecimal left = amount;
        while (left.signum() > 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT expected_payment_id, amount, received_amount FROM sales.expected_payment"
                    + " WHERE ticket_id = :t AND status = 'OPEN' ORDER BY " + order + " LIMIT 1 FOR UPDATE",
                new MapSqlParameterSource().addValue("t", ticketId));
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> row = rows.get(0);
            BigDecimal rowAmount = (BigDecimal) row.get("amount");
            BigDecimal got = (BigDecimal) row.get("received_amount");
            BigDecimal applied = left.min(rowAmount.subtract(got));
            BigDecimal newGot = got.add(applied);
            BigDecimal tolerance = rowAmount.multiply(new BigDecimal("0.01")).max(BigDecimal.TEN);
            boolean complete = newGot.compareTo(rowAmount.subtract(tolerance)) >= 0;
            jdbc.update("""
                UPDATE sales.expected_payment
                   SET received_amount = :got, status = :status, receipt_id = :receipt, updated_at = now()
                 WHERE expected_payment_id = :id
                """, new MapSqlParameterSource().addValue("got", newGot)
                    .addValue("status", complete ? "RECEIVED" : "OPEN")
                    .addValue("receipt", complete ? receiptId : null)
                    .addValue("id", ((Number) row.get("expected_payment_id")).longValue()));
            left = left.subtract(applied);
            if (!complete) {
                return;
            }
        }
    }

    /**
     * Review H1, reverse order: the Express document number of an EXPRESS receipt already on this deal with the exact
     * same amount, received within ±{@code days} days of {@code day} (Bangkok) — the payment account is about to type
     * may be that one. Nearest first.
     */
    public java.util.Optional<String> findExpressTwin(long ticketId, BigDecimal amount, java.time.LocalDate day,
                                                       int days) {
        return jdbc.query("""
            SELECT receipt_ref FROM sales.payment_receipt
             WHERE ticket_id = :t AND source = 'EXPRESS' AND amount = :a
               AND abs((received_at AT TIME ZONE 'Asia/Bangkok')::date - CAST(:d AS date)) <= :days
             ORDER BY abs((received_at AT TIME ZONE 'Asia/Bangkok')::date - CAST(:d AS date)), receipt_id
             LIMIT 1
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("a", amount).addValue("d", day)
                .addValue("days", days), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** The deal's DEPOSIT-kind receipts, net of nothing (ADJUSTMENT is its own kind). */
    public BigDecimal sumDepositReceipts(long ticketId) {
        BigDecimal v = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM sales.payment_receipt WHERE ticket_id = :t AND kind = 'DEPOSIT'",
            Map.of("t", ticketId), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Net deposit money held: DEPOSIT receipts minus ADJUSTMENT (refund) receipts, signed like SIGNED_RECEIPT_SUM. */
    public BigDecimal netDepositReceived(long ticketId) {
        BigDecimal v = jdbc.queryForObject("SELECT " + SIGNED_RECEIPT_SUM
            + " FROM sales.payment_receipt WHERE ticket_id = :t AND kind IN ('DEPOSIT', 'ADJUSTMENT')",
            Map.of("t", ticketId), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** An uploaded Express receipt (V213): source EXPRESS, receipt_ref = the Express document number. */
    public long insertExpressReceipt(long ticketId, String kind, BigDecimal amount, long recordedBy, Instant receivedAt,
                                     String expressDocNo, String customerName) {
        return insertSourcedReceipt(ticketId, kind, amount, recordedBy, receivedAt, expressDocNo, customerName, "EXPRESS");
    }

    /** One expected-payment row's progress, for the before/after read around a receipt. */
    public record ExpectedState(BigDecimal received, String status) {}

    /** Every non-cancelled expected payment of the deal: id to progress. */
    public Map<Long, ExpectedState> expectedSnapshot(long ticketId) {
        Map<Long, ExpectedState> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT expected_payment_id, received_amount, status FROM sales.expected_payment
             WHERE ticket_id = :t AND status <> 'CANCELLED' ORDER BY expected_payment_id
            """, Map.of("t", ticketId))) {
            out.put(((Number) r.get("expected_payment_id")).longValue(),
                new ExpectedState((BigDecimal) r.get("received_amount"), (String) r.get("status")));
        }
        return out;
    }

    /** What a receipt did to the expected payments, as JSON {@code [{id, added, closed}]} (null when it touched none). */
    public static String expectedDeltasJson(Map<Long, ExpectedState> before, Map<Long, ExpectedState> after) {
        StringBuilder sb = new StringBuilder("[");
        for (var e : after.entrySet()) {
            ExpectedState b = before.get(e.getKey());
            BigDecimal was = b == null ? BigDecimal.ZERO : b.received();
            boolean closed = e.getValue().status().equals("RECEIVED") && (b == null || !b.status().equals("RECEIVED"));
            BigDecimal added = e.getValue().received().subtract(was);
            if (added.signum() == 0 && !closed) {
                continue;
            }
            sb.append(sb.length() > 1 ? "," : "").append("{\"id\":").append(e.getKey()).append(",\"added\":")
                .append(added.toPlainString()).append(",\"closed\":").append(closed).append('}');
        }
        return sb.length() == 1 ? null : sb.append(']').toString();
    }

    /**
     * Deletes one TAX_INVOICE receipt of this deal (never another source), the only receipt delete in the system (a void).
     * Same transaction, in this order: Express rows queued as its twin keep their place in the queue with the suggestion
     * cleared; the expected payments move back by exactly the deltas stored when the invoice was confirmed (never below 0,
     * the rows the receipt closed reopen and lose their receipt, rows now cancelled or gone are skipped); then the delete.
     */
    public void deleteTaxInvoiceReceipt(long ticketId, long receiptId, String deltasJson) {
        jdbc.update("""
            UPDATE sales.receipt_import_row
               SET suggested_receipt_id = NULL,
                   reason = CASE WHEN reason = 'POSSIBLE_MANUAL_DUPLICATE' THEN 'NO_CANDIDATE' ELSE reason END
             WHERE suggested_receipt_id = :r
            """, Map.of("r", receiptId));
        if (deltasJson != null) {
            for (Map<String, Object> d : jdbc.queryForList("""
                SELECT (e->>'id')::bigint AS id, (e->>'added')::numeric AS added, (e->>'closed')::boolean AS closed
                  FROM jsonb_array_elements(CAST(:j AS jsonb)) e
                """, Map.of("j", deltasJson))) {
                jdbc.update("""
                    UPDATE sales.expected_payment
                       SET received_amount = GREATEST(0, received_amount - :added),
                           status = CASE WHEN :closed AND status = 'RECEIVED' AND receipt_id = :r THEN 'OPEN' ELSE status END,
                           receipt_id = CASE WHEN :closed AND status = 'RECEIVED' AND receipt_id = :r THEN NULL ELSE receipt_id END,
                           updated_at = now()
                     WHERE expected_payment_id = :id AND ticket_id = :t AND status <> 'CANCELLED'
                    """, new MapSqlParameterSource().addValue("added", d.get("added")).addValue("closed", d.get("closed"))
                        .addValue("r", receiptId).addValue("id", d.get("id")).addValue("t", ticketId));
            }
        }
        int n = jdbc.update("DELETE FROM sales.payment_receipt WHERE receipt_id = :r AND ticket_id = :t AND source = 'TAX_INVOICE'",
            Map.of("t", ticketId, "r", receiptId));
        if (n != 1) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.CONFLICT, "ไม่พบรายการรับเงินของใบกำกับนี้");
        }
    }

    /** A void's payment-status step back: {@code expected} to {@code previous} only if the row still holds {@code expected}. */
    public int retreatPaymentStatus(long ticketId, String expected, String previous) {
        return jdbc.update("""
            UPDATE sales.ticket SET payment_status = :prev, updated_at = now()
             WHERE ticket_id = :id AND payment_status IS NOT DISTINCT FROM :expected
            """, new MapSqlParameterSource().addValue("id", ticketId).addValue("expected", expected).addValue("prev", previous));
    }

    /** {@code source}: EXPRESS (an uploaded Express row) or TAX_INVOICE (a confirmed round tax invoice, V236). */
    public long insertSourcedReceipt(long ticketId, String kind, BigDecimal amount, long recordedBy, Instant receivedAt,
                                     String docNo, String customerName, String source) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.payment_receipt
                (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref, source, customer_name)
            VALUES
                (:ticketId, :kind, :amount, 'THB', COALESCE(CAST(:receivedAt AS timestamptz), now()), :recordedBy,
                 :ref, :source, :customerName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("kind", kind)
                .addValue("amount", amount)
                .addValue("receivedAt", receivedAt == null ? null : Timestamp.from(receivedAt))
                .addValue("recordedBy", recordedBy)
                .addValue("ref", docNo)
                .addValue("source", source)
                .addValue("customerName", customerName),
            keys, new String[]{"receipt_id"});
        return keys.getKey().longValue();
    }

    public List<PaymentReceiptDto> findReceiptsByTicket(long ticketId) {
        return jdbc.query("""
            SELECT r.receipt_id, r.ticket_id, r.kind, r.amount, r.currency,
                   r.received_at, r.recorded_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS recorded_by_name,
                   r.note, r.deposit_notice_id, r.receipt_ref, r.created_at, r.source, r.customer_name
              FROM sales.payment_receipt r
              JOIN hr.employee e ON e.employee_id = r.recorded_by
             WHERE r.ticket_id = :ticketId
             ORDER BY r.received_at ASC, r.receipt_id ASC
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> {
                long depositNoticeRaw = rs.getLong("deposit_notice_id");
                Long depositNoticeId = rs.wasNull() ? null : depositNoticeRaw;
                return new PaymentReceiptDto(
                    rs.getLong("receipt_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("kind"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getLong("recorded_by"),
                    rs.getString("recorded_by_name"),
                    rs.getString("note"),
                    depositNoticeId,
                    rs.getString("receipt_ref"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("source"),
                    rs.getString("customer_name")
                );
            });
    }

    public BigDecimal sumPaid(long ticketId) {
        BigDecimal value = jdbc.queryForObject(paidAmountSelect(":ticketId"),
            Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * The PRE-VAT quotation total (the payable amount itself is VAT-inclusive). COMMISSION reads this and only this: commission is
     * computed on the ex-VAT figure and must not move when payable does.
     */
    public BigDecimal payableAmountExVat(long ticketId) {
        BigDecimal value = jdbc.queryForObject(payableAmountExVatSelect(":ticketId"),
            Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal payableAmount(long ticketId) {
        BigDecimal value = jdbc.queryForObject(payableAmountSelect(":ticketId"),
            Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * ONE rule for "the answered quotation" (payableAmountSelect, paymentTermsRowSql and
     * {@code FinanceDealRepository#findPayableQuotation} all use it): ACCEPTED, or an APPROVED DEAL_DIRECT quotation
     * that a DEAL_PROMOTED_FROM_QUOTATION event names -- confirm-order leaves a DEAL_DIRECT quotation APPROVED, and
     * the event (related QUOTATION = q.quotation_id) is the per-quotation fact that its order was confirmed. An
     * APPROVED, unconfirmed one is not payable. Table alias {@code q} must be sales.quotation.
     */
    public static final String ANSWERED_QUOTATION_SQL =
        "(q.doc_status = 'ACCEPTED' OR (q.doc_status = 'APPROVED' AND q.origin = 'DEAL_DIRECT'"
        + " AND EXISTS (SELECT 1 FROM sales.ticket_event pe WHERE pe.ticket_id = q.ticket_id"
        + " AND pe.kind = '" + TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION + "'"
        + " AND pe.related_document_type = 'QUOTATION' AND pe.related_document_id = q.quotation_id)))";

    /**
     * Once a remaining invoice is ISSUED, what the customer owes follows the ISSUED documents -- the deposit PART plus
     * the remaining invoice's grand_total -- because each tax invoice rounds its own VAT, so the sum may sit a satang
     * away from the deal's computed total. The deposit part is the invoice's FROZEN pre-VAT deposit_deduction made
     * VAT-inclusive (the notice's exact total_payable when the deduction equals the notice amount, else
     * ROUND(deduction * 1.07, 2); no stored deduction -> the linked notice's total; no notice -> 0), so receipts and notice edits cannot move it.
     * Trailing comma included; VAT-inclusive variant only (the ex-VAT figure stays the quotation's subtotal).
     */
    private static String issuedDocumentsPayable(String ticketIdRef) {
        return "(SELECT COALESCE(CASE WHEN ri.deposit_deduction IS NULL OR ri.deposit_deduction = dn.deposit_amount"
            + " THEN dn.total_payable"
            + " ELSE ROUND(ri.deposit_deduction * 1.07, 2) END, 0)"
            + " + ri.grand_total FROM sales.remaining_invoice ri"
            + " LEFT JOIN sales.deposit_notice dn ON dn.deposit_notice_id = ri.deposit_notice_id"
            + " WHERE ri.ticket_id = " + ticketIdRef + " AND ri.status = 'ISSUED' AND ri.grand_total IS NOT NULL"
            + " ORDER BY ri.issued_at DESC, ri.id DESC LIMIT 1),";
    }

    /** Full "amount payable" SELECT statement, correlated on ticketIdRef (either a bind parameter like ":ticketId"
     * for a standalone query, or an outer-query column reference like "t.ticket_id" for use as a parenthesized
     * scalar subquery inside a bigger SQL statement — see #appendRoleScope). */
    public static String payableAmountSelect(String ticketIdRef) {
        return payableAmountSelect(ticketIdRef, true);
    }

    /** Same SELECT, PRE-VAT — what {@code payableAmountSelect} returned before payable became VAT-inclusive. */
    public static String payableAmountExVatSelect(String ticketIdRef) {
        return payableAmountSelect(ticketIdRef, false);
    }

    /** The VAT rate every quotation document applies (Thai documents; an EN document has none). Read from
     * th.co.glr.hr.dealquotation.WastageCalculator -- the same constant the quotation's own grand total uses --
     * never a second "0.07" here. */
    private static final String THAI_VAT_RATE =
        th.co.glr.hr.dealquotation.WastageCalculator.vatRateFor(
            th.co.glr.hr.dealquotation.WastageCalculator.DOCUMENT_LANGUAGE_TH).toPlainString();

    /** Every branch is made VAT-inclusive the same way the quotation DTOs compute their grand total: total_amount
     * is the persisted SUBTOTAL for every origin, VAT = round2, rate 0 for an EN document. */
    private static String payableAmountSelect(String ticketIdRef, boolean inclVat) {
        String quotationAmount = inclVat
            ? "q.total_amount + ROUND(q.total_amount * CASE WHEN q.document_language = 'EN' THEN 0 ELSE " + THAI_VAT_RATE + " END, 2)"
            : "q.total_amount";
        String itemsSum = "COALESCE(SUM(COALESCE(ti.approved_price, 0) * ti.qty), 0)";
        String itemsAmount = inclVat
            ? "(SELECT " + itemsSum + " + ROUND(" + itemsSum + " * " + THAI_VAT_RATE + ", 2)"
            : "(SELECT " + itemsSum;
        return """
            SELECT COALESCE(
                ISSUED_DOCUMENTS
                (SELECT QUOTATION_AMOUNT
                   FROM sales.quotation q
                  WHERE q.ticket_id = %1$s AND ANSWERED
                  ORDER BY CASE WHEN q.doc_status = 'ACCEPTED' THEN 0 ELSE 1 END,
                           CASE q.recipient_type
                               WHEN 'BUYER' THEN 0
                               WHEN 'OWNER' THEN 1
                               ELSE 2
                           END,
                           q.accepted_at DESC NULLS LAST,
                           q.issued_at DESC,
                           q.quotation_id DESC
                  LIMIT 1),
                (SELECT QUOTATION_AMOUNT
                   FROM sales.quotation q
                  WHERE q.ticket_id = %1$s AND q.doc_status IN ('ISSUED','SENT')
                  ORDER BY CASE q.recipient_type
                               WHEN 'BUYER' THEN 0
                               WHEN 'OWNER' THEN 1
                               ELSE 2
                           END,
                           q.issued_at DESC,
                           q.quotation_id DESC
                  LIMIT 1),
                (SELECT d.total_payable
                   FROM sales.deposit_notice d
                  WHERE d.ticket_id = %1$s AND d.status = 'ISSUED'
                  ORDER BY d.version DESC, d.deposit_notice_id DESC
                  LIMIT 1),
                ITEMS_AMOUNT
                   FROM sales.ticket_item ti
                  WHERE ti.ticket_id = %1$s),
                0
            )
            """.formatted(ticketIdRef)
            .replace("ISSUED_DOCUMENTS", inclVat ? issuedDocumentsPayable(ticketIdRef) : "")
            .replace("ANSWERED", ANSWERED_QUOTATION_SQL)
            .replace("QUOTATION_AMOUNT", quotationAmount)
            .replace("ITEMS_AMOUNT", itemsAmount);
    }

    /** Every payment-due calculation uses the Bangkok calendar, explicitly -- never the server default zone. */
    private static final java.time.ZoneId BANGKOK = java.time.ZoneId.of("Asia/Bangkok");

    /** Bangkok "today" in SQL, matching {@link #BANGKOK} in Java. */
    private static final String BANGKOK_TODAY_SQL = "(now() AT TIME ZONE 'Asia/Bangkok')::date";

    /**
     * One row (or none) describing WHEN the balance is due, from the payable quotation's own terms
     * The quotation is the same one {@link #payableAmountSelect} picks (accepted
     * first, else issued/sent; same recipient ordering); a deposit-notice / ticket-item payable has no terms,
     * so no row. Terms:
     * <ul>
     *   <li>{@code remainder_mode = CREDIT} with {@code credit_days} N: due = delivery date + N
     *       ({@code CREDIT_FROM_DELIVERY});</li>
     *   <li>a zero-deposit {@code full_payment_term = CREDIT} with {@code credit_days} N: identical to the
     *       CREDIT remainder (due = delivery date + N, {@code CREDIT_FROM_DELIVERY});</li>
     *   <li>any other non-null {@code remainder_mode} (the remainder is due before/on delivery), or a
     *       zero-deposit {@code full_payment_term} (BEFORE_DELIVERY / ON_DELIVERY / ON_OR_BEFORE_DELIVERY):
     *       due = the delivery date ({@code ON_DELIVERY});</li>
     *   <li>anything else (legacy / free-text terms): no row -- never overdue.</li>
     * </ul>
     * The delivery date is the Bangkok calendar date of the LATEST delivery record, and only once the deal is
     * {@code FULLY_DELIVERED}; until then there is no due date. Columns: days, basis, credit_days, delivered_on.
     */
    private static String paymentTermsRowSql(String ticketIdRef) {
        return """
            SELECT tq.days, tq.basis, tq.credit_days, dv.delivered_on
              FROM (SELECT CASE
                               WHEN (q.remainder_mode = 'CREDIT' OR q.full_payment_term = 'CREDIT') AND q.credit_days IS NOT NULL THEN q.credit_days::int
                               WHEN q.full_payment_term IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_OR_BEFORE_DELIVERY') THEN 0
                               WHEN q.remainder_mode IS NOT NULL AND q.remainder_mode <> 'CREDIT' THEN 0
                           END AS days,
                           CASE
                               WHEN (q.remainder_mode = 'CREDIT' OR q.full_payment_term = 'CREDIT') AND q.credit_days IS NOT NULL THEN 'CREDIT_FROM_DELIVERY'
                               WHEN q.full_payment_term IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_OR_BEFORE_DELIVERY') THEN 'ON_DELIVERY'
                               WHEN q.remainder_mode IS NOT NULL AND q.remainder_mode <> 'CREDIT' THEN 'ON_DELIVERY'
                           END AS basis,
                           CASE WHEN (q.remainder_mode = 'CREDIT' OR q.full_payment_term = 'CREDIT') AND q.credit_days IS NOT NULL THEN q.credit_days::int END AS credit_days
                      FROM sales.quotation q
                     WHERE q.ticket_id = %1$s AND (ANSWERED OR q.doc_status IN ('ISSUED', 'SENT'))
                     ORDER BY CASE q.doc_status WHEN 'ACCEPTED' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,
                              CASE q.recipient_type
                                  WHEN 'BUYER' THEN 0
                                  WHEN 'OWNER' THEN 1
                                  ELSE 2
                              END,
                              CASE WHEN q.doc_status = 'ACCEPTED' THEN q.accepted_at END DESC NULLS LAST,
                              q.issued_at DESC,
                              q.quotation_id DESC
                     LIMIT 1) tq
             CROSS JOIN (SELECT (MAX(dr.delivered_at) AT TIME ZONE 'Asia/Bangkok')::date AS delivered_on
                           FROM sales.delivery_record dr
                          WHERE dr.ticket_id = %1$s
                            AND (SELECT t2.fulfillment_status FROM sales.ticket t2 WHERE t2.ticket_id = %1$s)
                                = 'FULLY_DELIVERED') dv
            """.formatted(ticketIdRef).replace("ANSWERED", ANSWERED_QUOTATION_SQL);
    }

    /** The derived due DATE as a scalar subquery (NULL when there is none) -- the list-scope predicate's twin. */
    private static String paymentDueDateScalarSql(String ticketIdRef) {
        return "(SELECT x.delivered_on + x.days FROM (" + paymentTermsRowSql(ticketIdRef) + ") x)";
    }

    /**
     * The ONE overdue rule, as a boolean SQL fragment: derived due date is past (Bangkok), the deal is not still
     * on the deposit notice, and payable minus paid is positive. The account list scope and the dashboard's
     * {@code payment_overdue} count both use it, so they cannot disagree.
     */
    public static String paymentOverduePredicate(String ticketIdRef) {
        return "(" + paymentDueDateScalarSql(ticketIdRef) + " < " + BANGKOK_TODAY_SQL
            + " AND (SELECT tp.payment_status FROM sales.ticket tp WHERE tp.ticket_id = " + ticketIdRef
            + ") IS DISTINCT FROM 'DEPOSIT_NOTICE_ISSUED' AND ((" + payableAmountSelect(ticketIdRef)
            + ") - (" + paidAmountSelect(ticketIdRef) + ")) > 0)";
    }

    /** When the balance is due and on what basis; see {@link #paymentTermsRowSql}. */
    public record PaymentDue(LocalDate date /* null until delivered */, String basis, Integer creditDays) {}

    /** Full "amount paid" SELECT statement — see {@link #payableAmountSelect} for the correlation contract. */
    public static String paidAmountSelect(String ticketIdRef) {
        return """
            SELECT %2$s
              FROM sales.payment_receipt
             WHERE ticket_id = %1$s
            """.formatted(ticketIdRef, SIGNED_RECEIPT_SUM);
    }

    /**
     * The one sign rule for money received: DEPOSIT and BALANCE add, ADJUSTMENT (refund / credit note)
     * subtracts. Shared by the per-deal paid amount and the dashboard's company-wide cash received so
     * the two can never count a receipt differently.
     */
    public static final String SIGNED_RECEIPT_SUM =
        "COALESCE(SUM(CASE WHEN kind = 'ADJUSTMENT' THEN -amount ELSE amount END), 0)";

    public record DepositNoticePaymentInfo(long id, BigDecimal depositAmount, BigDecimal totalPayable) {}

    /** True when the deal has at least one ISSUED ใบขอซื้อ (IR) row: import has work in flight on it. */
    public boolean hasIssuedImportRequest(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.import_request WHERE ticket_id = :id AND status = 'ISSUED')",
            Map.of("id", ticketId), Boolean.class));
    }

    public Optional<DepositNoticePaymentInfo> latestIssuedDepositNotice(long ticketId) {
        List<DepositNoticePaymentInfo> rows = jdbc.query("""
            SELECT deposit_notice_id, deposit_amount, total_payable
              FROM sales.deposit_notice
             WHERE ticket_id = :ticketId AND status = 'ISSUED'
             ORDER BY version DESC, deposit_notice_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId),
            (rs, rowNum) -> new DepositNoticePaymentInfo(
                rs.getLong("deposit_notice_id"),
                rs.getBigDecimal("deposit_amount"),
                rs.getBigDecimal("total_payable")
            ));
        return rows.stream().findFirst();
    }

    public void updateBilling(long ticketId, LocalDate billingDate, LocalDate dueDate, Integer creditTermDays,
                              LocalDate lastFollowUpAt, LocalDate nextFollowUpAt) {
        jdbc.update("""
            UPDATE sales.ticket
               SET billing_date = :billingDate,
                   due_date = :dueDate,
                   credit_term_days = :creditTermDays,
                   last_follow_up_at = :lastFollowUpAt,
                   next_follow_up_at = :nextFollowUpAt,
                   updated_at = now()
             WHERE ticket_id = :ticketId
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("billingDate", billingDate)
                .addValue("dueDate", dueDate)
                .addValue("creditTermDays", creditTermDays)
                .addValue("lastFollowUpAt", lastFollowUpAt)
                .addValue("nextFollowUpAt", nextFollowUpAt));
    }

    @Transactional
    public void reserveStock(long ticketId, List<StockReservationRequest.Line> lines) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            StockReservationRequest.Line line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("itemId", line.itemId())
                .addValue("qtyFromStock", line.qtyFromStock())
                .addValue("note", line.note());
        }
        jdbc.batchUpdate("""
            UPDATE sales.ticket_item
               SET qty_from_stock = :qtyFromStock,
                   stock_note = :note
             WHERE ticket_id = :ticketId AND item_id = :itemId
            """, batch);
    }

    /** V148 (per-item stock-commission weighting): the sales_manager/CEO-approved counterpart to #reserveStock
     * above -- same absolute-SET-keyed-on-(ticket_id,item_id) shape, this is the only production writer of
     * sales.ticket_item.weight_multiplier. */
    @Transactional
    public void updateItemWeightMultipliers(long ticketId, List<ItemWeightMultiplierRequest.Line> lines) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            ItemWeightMultiplierRequest.Line line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("itemId", line.itemId())
                .addValue("weightMultiplier", line.weightMultiplier());
        }
        jdbc.batchUpdate("""
            UPDATE sales.ticket_item
               SET weight_multiplier = :weightMultiplier
             WHERE ticket_id = :ticketId AND item_id = :itemId
            """, batch);
    }

    /**
     * When the order was confirmed: the latest confirm-order-from-quotation event ({@code DEAL_PROMOTED_FROM_QUOTATION}),
     * else the latest {@code CUSTOMER_CONFIRMED} (the pricing-request route). The ONE definition: the document chain
     * (DocumentChainRepository#findOrderConfirmedAt) and the delivery-date floor (TicketService, slice 6d) both run
     * this statement. Binds {@code :id}, {@code :promoted}, {@code :confirmed} — see {@link #orderConfirmedAtParams}.
     */
    public static final String ORDER_CONFIRMED_AT_SQL = """
        SELECT created_at FROM sales.ticket_event
         WHERE ticket_id = :id AND kind IN (:promoted, :confirmed)
         ORDER BY (kind = :promoted) DESC, created_at DESC, event_id DESC
         LIMIT 1
        """;

    public static MapSqlParameterSource orderConfirmedAtParams(long ticketId) {
        return new MapSqlParameterSource().addValue("id", ticketId)
            .addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)
            .addValue("confirmed", TicketEventKind.CUSTOMER_CONFIRMED);
    }

    /** {@link #ORDER_CONFIRMED_AT_SQL}; empty when the deal has no confirmation event. */
    public Optional<java.time.Instant> findOrderConfirmedAt(long ticketId) {
        return jdbc.query(ORDER_CONFIRMED_AT_SQL, orderConfirmedAtParams(ticketId),
            (rs, n) -> rs.getTimestamp("created_at").toInstant()).stream().findFirst();
    }

    /**
     * {@code deliveredAt} (slice 6d) is the moment the goods went, for a back-dated delivery; {@code null} stamps the
     * row with the insert time ({@code now()}), exactly as before. {@code created_at} is always the insert time.
     */
    @Transactional
    public long insertDeliveryRecord(long ticketId, String source, long deliveredBy, String note,
                                     String recipientName, List<RecordDeliveryRequest.Line> lines,
                                     java.time.Instant deliveredAt, Long deliveryRequestId, String noInvoiceNote) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_by, note, recipient_name, delivered_at,
                                               delivery_request_id, no_invoice_note)
            VALUES (:ticketId, :source, :deliveredBy, :note, :recipientName,
                    COALESCE(CAST(:deliveredAt AS timestamptz), now()), :roundId, :noInvoiceNote)
            """,
            new MapSqlParameterSource()
                .addValue("roundId", deliveryRequestId)
                .addValue("noInvoiceNote", noInvoiceNote)
                .addValue("ticketId", ticketId)
                .addValue("source", source)
                .addValue("deliveredBy", deliveredBy)
                .addValue("note", note)
                .addValue("recipientName", recipientName)
                .addValue("deliveredAt",
                    deliveredAt == null ? null : deliveredAt.atOffset(java.time.ZoneOffset.UTC),
                    java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
            keyHolder,
            new String[] {"delivery_id"});
        long deliveryId = keyHolder.getKey().longValue();
        for (RecordDeliveryRequest.Line line : lines) {
            jdbc.update("""
                INSERT INTO sales.delivery_record_item (delivery_id, item_id, qty)
                VALUES (:deliveryId, :itemId, :qty)
                """,
                new MapSqlParameterSource()
                    .addValue("deliveryId", deliveryId)
                    .addValue("itemId", line.itemId())
                    .addValue("qty", line.qty()));
            int updated = jdbc.update("""
                UPDATE sales.ticket_item
                   SET qty_delivered = qty_delivered + :qty
                 WHERE ticket_id = :ticketId
                   AND item_id = :itemId
                   AND qty_delivered + :qty <= qty - qty_not_delivered
                """,
                new MapSqlParameterSource()
                    .addValue("ticketId", ticketId)
                    .addValue("itemId", line.itemId())
                    .addValue("qty", line.qty()));
            if (updated != 1) {
                throw new org.springframework.dao.DataIntegrityViolationException("Delivery exceeds ordered quantity");
            }
        }
        return deliveryId;
    }

    /** One covered line of an INVOICED delivery round: what the invoice covers and what was delivered against the round. */
    public record RoundLine(long itemId, BigDecimal covered, BigDecimal delivered, BigDecimal itemLeft) {
        public RoundLine(long itemId, BigDecimal covered, BigDecimal delivered) {
            this(itemId, covered, delivered, null);
        }

        /** What the round still covers, capped at what the item itself still needs (goods that already left count). */
        public BigDecimal remaining() {
            BigDecimal left = covered.subtract(delivered).max(BigDecimal.ZERO);
            return itemLeft == null ? left : left.min(itemLeft.max(BigDecimal.ZERO));
        }
    }

    /** An INVOICED round of the order with its lines (the invoice's coverage). */
    public record InvoicedRound(long id, int roundNo, String invoiceNumber, List<RoundLine> lines) {
        public boolean hasRemaining() {
            return lines.stream().anyMatch(l -> l.remaining().signum() > 0);
        }
    }

    /** The status of delivery round {@code rid} when it belongs to this order, else empty. */
    public java.util.Optional<String> findRoundStatus(long ticketId, long rid) {
        return jdbc.query("SELECT status FROM sales.delivery_request WHERE delivery_request_id = :r AND ticket_id = :t",
            Map.of("r", rid, "t", ticketId), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** The order's INVOICED rounds, oldest first, each with covered and delivered quantities per line. */
    public List<InvoicedRound> findInvoicedRounds(long ticketId) {
        return findRounds(ticketId, null);
    }

    /** One sent round of the order (any non-draft status) with its lines, for the per-round quantity cap. */
    public java.util.Optional<InvoicedRound> findRound(long ticketId, long rid) {
        return findRounds(ticketId, rid).stream().findFirst();
    }

    private List<InvoicedRound> findRounds(long ticketId, Long onlyRid) {
        Map<Long, List<RoundLine>> byRound = new java.util.LinkedHashMap<>();
        Map<Long, int[]> meta = new java.util.HashMap<>();
        Map<Long, String> numbers = new java.util.HashMap<>();
        jdbc.query("""
            SELECT r.delivery_request_id AS rid, r.round_no, r.invoice_number, l.item_id, l.qty AS covered,
                   COALESCE((SELECT SUM(dri.qty) FROM sales.delivery_record_item dri
                               JOIN sales.delivery_record dr ON dr.delivery_id = dri.delivery_id
                              WHERE dr.delivery_request_id = r.delivery_request_id AND dri.item_id = l.item_id), 0) AS delivered,
                   ti.qty - ti.qty_not_delivered - COALESCE(ti.qty_delivered, 0) AS item_left
              FROM sales.delivery_request r
              JOIN sales.delivery_request_line l ON l.delivery_request_id = r.delivery_request_id
              JOIN sales.ticket_item ti ON ti.item_id = l.item_id
             WHERE r.ticket_id = :t AND (r.delivery_request_id = :rid OR (:rid IS NULL AND r.status = 'INVOICED'))
             ORDER BY r.round_no, l.item_id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", ticketId).addValue("rid", onlyRid, java.sql.Types.BIGINT), rs -> {
                long rid = rs.getLong("rid");
                byRound.computeIfAbsent(rid, k -> new java.util.ArrayList<>())
                    .add(new RoundLine(rs.getLong("item_id"), rs.getBigDecimal("covered"), rs.getBigDecimal("delivered"),
                        rs.getBigDecimal("item_left")));
                meta.put(rid, new int[] {rs.getInt("round_no")});
                numbers.put(rid, rs.getString("invoice_number"));
            });
        return byRound.entrySet().stream()
            .map(e -> new InvoicedRound(e.getKey(), meta.get(e.getKey())[0], numbers.get(e.getKey()), e.getValue())).toList();
    }

    public List<DeliveryRecordDto> findDeliveriesByTicket(long ticketId) {
        List<DeliveryRecordDto> records = jdbc.query("""
            SELECT dr.delivery_id, dr.ticket_id, dr.source, dr.delivered_at, dr.delivered_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS delivered_by_name,
                   dr.note, dr.recipient_name, dr.created_at, dr.no_invoice_note
              FROM sales.delivery_record dr
              JOIN hr.employee e ON e.employee_id = dr.delivered_by
             WHERE dr.ticket_id = :ticketId
             ORDER BY dr.delivered_at ASC, dr.delivery_id ASC
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> new DeliveryRecordDto(
                rs.getLong("delivery_id"),
                rs.getLong("ticket_id"),
                rs.getString("source"),
                rs.getTimestamp("delivered_at").toInstant(),
                rs.getLong("delivered_by"),
                rs.getString("delivered_by_name"),
                rs.getString("note"),
                rs.getString("recipient_name"),
                rs.getTimestamp("created_at").toInstant(),
                findDeliveryItems(rs.getLong("delivery_id")),
                rs.getString("no_invoice_note")
            ));
        return records;
    }

    private List<DeliveryRecordItemDto> findDeliveryItems(long deliveryId) {
        return jdbc.query("""
            SELECT delivery_item_id, item_id, qty
              FROM sales.delivery_record_item
             WHERE delivery_id = :deliveryId
             ORDER BY delivery_item_id
            """,
            Map.of("deliveryId", deliveryId),
            (rs, rowNum) -> new DeliveryRecordItemDto(
                rs.getLong("delivery_item_id"),
                rs.getLong("item_id"),
                rs.getBigDecimal("qty")
            ));
    }

    public BigDecimal sumOrdered(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(qty), 0)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal sumDelivered(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(qty_delivered), 0)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public boolean allLinesFullyDelivered(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT COALESCE(bool_and(qty_delivered + qty_not_delivered >= qty), false)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }


    public boolean hasDeliveries(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.delivery_record
                 WHERE ticket_id = :ticketId
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** Whether the deal's imported goods ever physically reached OUR warehouse — a permanent fact from the
     * GOODS_RECEIVED event, NOT the current fulfillment_status (which gets overwritten to
     * PARTIALLY_DELIVERED/FULLY_DELIVERED once deliveries start). */
    public boolean hasReceivedGoods(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.ticket_event
                 WHERE ticket_id = :ticketId AND kind = 'GOODS_RECEIVED'
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** Whether any lot of the deal's ISSUED import requests is on a RECEIVED shipment round with something received
     * (COALESCE(received_qty, qty) &gt; 0): goods physically in the warehouse, even if the rest has not arrived. */
    public boolean hasReceivedLot(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.shipment_lot l
                  JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
                  JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
                  JOIN sales.import_request_item i ON i.import_request_item_id = l.import_request_item_id
                 WHERE ir.ticket_id = :t AND ir.status = 'ISSUED' AND r.import_step = 'RECEIVED'
                   AND i.ticket_item_id IS NOT NULL -- a lot no deal line can claim must not open the warehouse gate
                   AND COALESCE(l.received_qty, l.qty) > 0)
            """, Map.of("t", ticketId), Boolean.class));
    }

    /** Per deal line an ISSUED IR line points at: the IR quantity and how much of it sits on RECEIVED rounds (lot
     * quantities as planned; a short arrival is NOT subtracted, so it can never stop a deal from being delivered). */
    public record LineReceipt(BigDecimal irQty, BigDecimal wantedQty, BigDecimal onRounds, BigDecimal arrived, long receivedLots, boolean lotReturned, boolean sameUnit) {}
    // receivedLots: the line's lots on RECEIVED rounds, INCLUDING ones that received 0 (the GOODS_RECEIVED bypass is for lines with none)

    /** {@code dealHasLots}: any ISSUED IR of the deal has a lot at all; without it nothing is capped (pre-portal, stock-only
     * and lot-less deals behave as before). */
    public record LotReceipts(boolean dealHasLots, Map<Long, LineReceipt> byItem, boolean unlinkedGoods) {}

    /** An ISSUED IR line that points at no deal line: goods the deal ordered but cannot deliver. */
    public record UnlinkedGoods(String docNumber, String product, BigDecimal qty, String unit) {}

    public List<UnlinkedGoods> unlinkedGoods(long ticketId) {
        return jdbc.query("""
            SELECT ir.doc_number, iri.qty, NULLIF(btrim(iri.unit), '') AS unit,
                   COALESCE(NULLIF(btrim(concat_ws(' ', iri.brand, iri.model)), ''), iri.code) AS product
              FROM sales.import_request ir
              JOIN sales.import_request_item iri ON iri.import_request_id = ir.import_request_id
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED' AND iri.ticket_item_id IS NULL
             ORDER BY ir.import_request_id, iri.seq
            """, Map.of("t", ticketId), (rs, n) -> new UnlinkedGoods(rs.getString("doc_number"), rs.getString("product"),
                rs.getBigDecimal("qty"), rs.getString("unit")));
    }

    public LotReceipts lotReceipts(long ticketId) {
        Map<Long, LineReceipt> byItem = new java.util.HashMap<>();
        boolean[] anyLots = {false};
        boolean[] unlinked = {false};
        jdbc.query("""
            SELECT iri.ticket_item_id AS item_id, SUM(iri.qty) AS ir_qty,
                   COALESCE(SUM((SELECT SUM(l.qty) FROM sales.shipment_lot l
                                   JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
                                  WHERE l.import_request_item_id = iri.import_request_item_id
                                    AND r.import_step = 'RECEIVED'
                                    AND COALESCE(l.received_qty, l.qty) > 0)), 0) AS on_rounds, -- a lot that received 0 holds nothing to deliver
                   COALESCE(SUM((SELECT SUM(COALESCE(l4.received_qty, l4.qty)) FROM sales.shipment_lot l4
                                   JOIN sales.shipment_round r4 ON r4.shipment_round_id = l4.shipment_round_id
                                  WHERE l4.import_request_item_id = iri.import_request_item_id
                                    AND r4.import_step = 'RECEIVED')), 0) AS arrived,
                   COALESCE(SUM((SELECT COUNT(*) FROM sales.shipment_lot l3
                                   JOIN sales.shipment_round r3 ON r3.shipment_round_id = l3.shipment_round_id
                                  WHERE l3.import_request_item_id = iri.import_request_item_id
                                    AND r3.import_step = 'RECEIVED')), 0) AS received_lots,
                   COALESCE(SUM((SELECT COUNT(*) FROM sales.shipment_lot l2
                                  WHERE l2.import_request_item_id = iri.import_request_item_id)), 0) AS lots,
                   COALESCE(BOOL_OR(ti.had_returned_lot), false) AS lot_returned,
                   COALESCE(SUM(COALESCE(w.wanted_qty, iri.qty)), 0) AS wanted_qty, -- less what was marked not delivered
                   -- a blank IR unit is the deal line's own (a portal IR copies ti.unit, usually NULL). raw_unit is a price basis,
                   -- never the quantity's unit: a line counted in pieces has ti.unit, or no unit text at all.
                   COALESCE(BOOL_AND(NULLIF(btrim(iri.unit), '') IS NULL
                                     OR (ti.unit_basis = 'PIECE'
                                         AND regexp_replace(lower(btrim(iri.unit)), '^(pcs?|pieces?)$', 'แผ่น')
                                             = regexp_replace(lower(COALESCE(NULLIF(btrim(ti.unit), ''), 'แผ่น')), '^(pcs?|pieces?)$', 'แผ่น'))), false) AS same_unit
              FROM sales.import_request ir
              JOIN sales.import_request_item iri ON iri.import_request_id = ir.import_request_id
              LEFT JOIN sales.ticket_item ti ON ti.item_id = iri.ticket_item_id
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = iri.import_request_item_id
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED'
             GROUP BY iri.ticket_item_id
            """, Map.of("t", ticketId), rs -> {
                if (rs.getObject("item_id") == null) { // an unlinked line still counts toward dealHasLots
                    unlinked[0] = true;
                } else {
                    byItem.put(rs.getLong("item_id"), new LineReceipt(rs.getBigDecimal("ir_qty"), rs.getBigDecimal("wanted_qty"), rs.getBigDecimal("on_rounds"), rs.getBigDecimal("arrived"), rs.getLong("received_lots"), rs.getBoolean("lot_returned"), rs.getBoolean("same_unit")));
                }
                if (rs.getLong("lots") > 0 || rs.getBoolean("lot_returned")) { // a returned last lot keeps the deal capped
                    anyLots[0] = true;
                }
            });
        return new LotReceipts(anyLots[0], byItem, unlinked[0]);
    }

    /** Validated ticket-status transition — the ONE way sales.ticket.status is written (ticket creation's INSERT
     * sets the initial draft and is not a transition). The SERVICE must turn that into a 409 — never re-SELECT to
     * build a nicer message, which would reintroduce the very race the WHERE clause closes. */
    public int transitionStatus(long ticketId, String expected, String next) {
        if (!TicketStatus.canTransition(expected, next)) {
            throw new IllegalStateException(
                "Illegal ticket status transition: " + expected + " -> " + next);
        }
        return jdbc.update("""
            UPDATE sales.ticket
               SET status = :next, updated_at = now()
             WHERE ticket_id = :id AND status = :expected
            """,
            new MapSqlParameterSource()
                .addValue("id", ticketId)
                .addValue("expected", expected)
                .addValue("next", next));
    }

    /**
     * The ONE deliberate bridge write outside the legacy ticket status state
     * machine — see {@code th.co.glr.hr.orderconfirmation.OrderConfirmationService}'s class
     * Javadoc for the full reasoning. Since {@link th.co.glr.hr.ticket.TicketService#submit}
     * permanently 409s, a deal driven entirely through the new PricingRequest chain can
     * never leave {@link TicketStatus#DRAFT} via the legacy flow, so this guarded compare-and-set
     * (FROM {@code draft} only) can never collide with a live legacy transition. A ticket that
     * already carries a real legacy status (created before the pricing-request chain, or otherwise mid-flow) is
     * never silently overwritten — 0 rows signals "not eligible", which the caller turns into a
     * 409 unless the current status already happens to be {@code quotation_issued} (an idempotent
     * replay of this same bridge).
     *
     * <p>Expressed as a {@link #transitionStatus} call, so this bridge is subject to the same
     * machine as every other write and {@code draft -> quotation_issued} is a DECLARED edge
     * instead of an untracked exception. The {@code expected} value is hardcoded to
     * {@code draft}, so the edge check is a constant that always passes and a non-draft row
     * still yields 0 rows, never a throw.
     */
    public int markQuotationIssuedForOrderConfirmation(long ticketId) {
        return transitionStatus(ticketId, TicketStatus.DRAFT, TicketStatus.QUOTATION_ISSUED);
    }

    /**
     * Quotation ↔ deal linking — does this deal have a LIVE direct quotation: a
     * {@code DEAL_DIRECT} row in {@code DRAFT}, {@code PENDING_APPROVAL} or {@code APPROVED}? The one
     * predicate behind {@link DirectQuotationLocks#requireNoLiveDirectQuotation}. CANCELLED,
     * SUPERSEDED and REJECTED rows are not live; PRICING_REQUEST and legacy rows never count.
     */
    public boolean hasLiveDirectQuotation(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.quotation
                           WHERE ticket_id = :ticketId
            """ + LIVE_DIRECT_QUOTATION_PREDICATE + ")",
            Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /**
     * The ONE definition of a "live" direct quotation, shared as SQL text by
     * {@link #hasLiveDirectQuotation} (the lock), {@link #findLiveDirectQuotations} (the refusal)
     * and {@link #liveDirectQuotationSelect} ({@link TicketSummaryDto#liveDirectQuotation}), so they can never
     * disagree about which rows count. Appended after a {@code WHERE ticket_id = :ticketId} clause.
     */
    private static final String LIVE_DIRECT_QUOTATION_PREDICATE = """
                             AND origin = 'DEAL_DIRECT'
                             AND doc_status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
        """;

    /**
     * The NEWEST live direct quotation's row for {@code ticketIdRef} (highest {@code quotation_id}): the row
     * {@link TicketSummaryDto#liveDirectQuotation} serves for a whole page in one statement. A deal can hold
     * several live rows (an APPROVED parent and its DRAFT revision, a reorder clone); the client acts on the newest.
     */
    private static String liveDirectQuotationSelect(String ticketIdRef) {
        return """
            SELECT quotation_id, number, doc_status, recipient_type
              FROM sales.quotation
             WHERE ticket_id = %s
        """.formatted(ticketIdRef) + LIVE_DIRECT_QUOTATION_PREDICATE + """
             ORDER BY quotation_id DESC
             LIMIT 1
        """;
    }

    /** {@link #findLiveDirectQuotation} narrowed to ONE deal party (V204): the create-time refusal is per
     * ผู้รับเหมา, so a second contractor may hold its own live document on the same deal. */
    public Optional<LiveDirectQuotationDto> findLiveDirectQuotationForParty(long ticketId, long partyId) {
        List<LiveDirectQuotationDto> rows = jdbc.query("""
            SELECT quotation_id, number, doc_status, recipient_type
              FROM sales.quotation
             WHERE ticket_id = :ticketId AND party_id = :partyId
        """ + LIVE_DIRECT_QUOTATION_PREDICATE + """
             ORDER BY quotation_id DESC
             LIMIT 1
        """, Map.of("ticketId", ticketId, "partyId", partyId),
            (rs, rowNum) -> new LiveDirectQuotationDto(rs.getLong("quotation_id"), rs.getString("number"),
                rs.getString("doc_status"), rs.getString("recipient_type")));
        return rows.stream().findFirst();
    }

    /** Every live direct quotation on the deal, newest first (same predicate as the lock). */
    public List<LiveDirectQuotationDto> findLiveDirectQuotations(long ticketId) {
        return jdbc.query("""
            SELECT quotation_id, number, doc_status, recipient_type
              FROM sales.quotation
             WHERE ticket_id = :ticketId
        """ + LIVE_DIRECT_QUOTATION_PREDICATE + """
             ORDER BY quotation_id DESC
        """, Map.of("ticketId", ticketId),
            (rs, rowNum) -> new LiveDirectQuotationDto(rs.getLong("quotation_id"), rs.getString("number"),
                rs.getString("doc_status"), rs.getString("recipient_type")));
    }

    /**
     * Quotation ↔ deal linking — one pricing route per deal: does
     * this deal have a LIVE pricing request, i.e. any {@code sales.pricing_request} whose status is
     * neither {@code CANCELLED} nor {@code SUPERSEDED}? A private {@code DRAFT} and a finished
     * {@code QUOTATION_ACCEPTED} both count. The one predicate behind
     * {@link DirectQuotationLocks#requireNoLivePricingRequest}.
     *
     * <p>Deliberately NOT {@link #PRICING_REQUEST_TERMINAL_STATUSES} (import's list scope), which also
     * treats {@code QUOTATION_ACCEPTED} as finished: for that worklist an accepted request is done,
     * but for "which pricing route is this deal on" it is very much the route in use.
     */
    public boolean hasLivePricingRequest(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.pricing_request
                           WHERE ticket_id = :ticketId
                             AND status NOT IN ('CANCELLED', 'SUPERSEDED'))
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /**
     * Clears the V193 {@code quotation_only} provenance flag, as a compare-and-set
     * ({@code TRUE -> FALSE}). The flag is provenance only
     * (nothing reads it), and this is called BEST-EFFORT by
     * {@code DealQuotationService#confirmOrderFromDirectQuotation}, under {@link #lockTicketForUpdate}:
     * 1 on a quotation-first deal, 0 on an ordinary one — the caller ignores the rowcount. Never sets
     * the flag back to TRUE.
     *
     * @return the rowcount (0 or 1).
     */
    public int markPromotedToPipeline(long ticketId) {
        return jdbc.update("""
            UPDATE sales.ticket
               SET quotation_only = FALSE, updated_at = now()
             WHERE ticket_id = :id AND quotation_only = TRUE
            """, Map.of("id", ticketId));
    }

    /** The note of every system-driven stage move ({@code TicketService#autoAdvanceStage}); the undo finds them by it. */
    public static final String AUTO_STAGE_NOTE = "อัตโนมัติจากขั้นตอนของดีล";

    // ── Undo of an order confirmation (DealQuotationService#undoOrderConfirmation) ──

    /** The deal-level facts that stop an order confirmation being undone; see {@code DealQuotationService#undoRefusal}. */
    public record UndoFacts(boolean bidOrSplit, boolean nonDraftDepositNotice, boolean paymentReceipt,
                            boolean remainingInvoice, boolean importRequest, boolean deliveryRecord,
                            boolean manualStockDecision, boolean prePortalSettledByExpress) {}

    /** One read of every downstream row an undo must not orphan. "Bid or split" = the bid path wrote its
     * BID_OUTCOME_RECORDED right AFTER the promotion event in the same transaction (DealPartyService#recordBidOutcome),
     * so an outcome recorded EARLIER on the deal does not count; or the deal is a split child (DEAL_SPLIT_FROM_PARENT)
     * or has split children (a child with no kind counts as a split; only ADD_ON children are exempt: an undo
     * deletes only this deal's own ticket_item rows, never a quotation item an add-on reuses). "Manual stock decision" = a STOCK_RESERVED or
     * PROCUREMENT_SOURCE_DECIDED event after the (latest) promotion event, or the V208 latch; the confirmation's own
     * cover of จากสต็อก lines writes neither. */
    public UndoFacts undoConfirmationFacts(long ticketId) {
        return jdbc.queryForObject("""
            SELECT
              (EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'BID_OUTCOME_RECORDED'
                        AND event_id > COALESCE((SELECT MAX(event_id) FROM sales.ticket_event
                                                  WHERE ticket_id = :t AND kind = 'DEAL_PROMOTED_FROM_QUOTATION'), 0))
               OR EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'DEAL_SPLIT_FROM_PARENT')
               OR EXISTS(SELECT 1 FROM sales.ticket WHERE parent_ticket_id = :t
                          AND COALESCE(child_kind, 'BID_SPLIT') = 'BID_SPLIT')) AS bid_or_split,
              EXISTS(SELECT 1 FROM sales.deposit_notice WHERE ticket_id = :t AND status <> 'DRAFT') AS notice,
              EXISTS(SELECT 1 FROM sales.payment_receipt WHERE ticket_id = :t
                      AND NOT (source = 'PRE_PORTAL' AND kind = 'DEPOSIT')) AS receipt,
              EXISTS(SELECT 1 FROM sales.payment_receipt r WHERE r.ticket_id = :t AND r.source = 'PRE_PORTAL'
                      AND (r.express_doc_no IS NOT NULL
                           OR EXISTS(SELECT 1 FROM sales.receipt_import_row w
                                      WHERE w.receipt_id = r.receipt_id AND w.status = 'RESOLVED'))) AS pre_portal_settled,
              EXISTS(SELECT 1 FROM sales.remaining_invoice WHERE ticket_id = :t) AS invoice,
              EXISTS(SELECT 1 FROM sales.import_request WHERE ticket_id = :t) AS import_req,
              EXISTS(SELECT 1 FROM sales.delivery_record WHERE ticket_id = :t) AS delivery,
              (EXISTS(SELECT 1 FROM sales.ticket_event e WHERE e.ticket_id = :t
                        AND e.kind IN ('STOCK_RESERVED', 'PROCUREMENT_SOURCE_DECIDED')
                        AND e.event_id > COALESCE((SELECT MAX(event_id) FROM sales.ticket_event
                                                    WHERE ticket_id = :t AND kind = 'DEAL_PROMOTED_FROM_QUOTATION'), 0))
               OR EXISTS(SELECT 1 FROM sales.ticket WHERE ticket_id = :t
                          AND procurement_source_decided_at IS NOT NULL)) AS manual_stock
            """, Map.of("t", ticketId), (rs, n) -> new UndoFacts(rs.getBoolean("bid_or_split"), rs.getBoolean("notice"),
                rs.getBoolean("receipt"), rs.getBoolean("invoice"), rs.getBoolean("import_req"),
                rs.getBoolean("delivery"), rs.getBoolean("manual_stock"), rs.getBoolean("pre_portal_settled")));
    }

    /** A delivery round already forwarded to the invoice issuer (or invoiced): an undo never touches one. */
    public boolean hasForwardedDeliveryRound(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.delivery_request WHERE ticket_id = :t AND status IN ('FORWARDED','INVOICED'))",
            Map.of("t", ticketId), Boolean.class));
    }

    /** Undo of the order confirmation: drops the order's DRAFT/SENT/RETURNED rounds with their lines (their files stay on the
     * deal, unlinked, uploader_side kept). Returns the round numbers removed. */
    public List<Integer> deleteOpenDeliveryRounds(long ticketId) {
        List<Integer> nos = jdbc.queryForList(
            "SELECT round_no FROM sales.delivery_request WHERE ticket_id = :t AND status IN ('DRAFT','SENT','RETURNED') ORDER BY round_no",
            Map.of("t", ticketId), Integer.class);
        jdbc.update("""
            UPDATE sales.attachment SET delivery_request_id = NULL
             WHERE delivery_request_id IN (SELECT delivery_request_id FROM sales.delivery_request
                                            WHERE ticket_id = :t AND status IN ('DRAFT','SENT','RETURNED'))
            """, Map.of("t", ticketId));
        jdbc.update("DELETE FROM sales.delivery_request WHERE ticket_id = :t AND status IN ('DRAFT','SENT','RETURNED')", Map.of("t", ticketId));
        return nos;
    }

    /** The confirmation's own event: who pressed it and when. */
    public record PromotionEvent(long eventId, String actorName, Instant at) {}

    public Optional<PromotionEvent> findPromotionEvent(long ticketId) {
        return jdbc.query("""
            SELECT event_id, actor_name, created_at FROM sales.ticket_event
             WHERE ticket_id = :t AND kind = :kind ORDER BY event_id DESC LIMIT 1
            """, Map.of("t", ticketId, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION),
            (rs, n) -> new PromotionEvent(rs.getLong("event_id"), rs.getString("actor_name"),
                rs.getTimestamp("created_at").toInstant())).stream().findFirst();
    }

    /** The STAGE_CHANGED note of the move a first send makes on a deal that was not ordered in the portal (undone with the confirm). */
    public static final String PRE_PORTAL_SEND_STAGE_NOTE = "ขอออกใบกำกับจากงานที่เดินมาก่อนใช้ระบบ";
    /** The note of the event that marks a deal as ordered before the portal; an undo tells "marked by a send" (the event is
     * newer than the promotion) from "created as สั่งซื้อแล้ว" (older) by it. */
    public static final String PRE_PORTAL_MARK_NOTE = "pre_portal → true — งานที่เดินมาก่อนใช้ระบบ";

    /** True when the pre-portal marking was made after the promotion event: by a send, not at creation. Deals marked
     * before the marking event existed (none of it at all) are recognised by their policy note. */
    public boolean prePortalMarkedAfter(long ticketId, long afterEventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'POLICY_CHANGED' AND message = :mark
                           AND event_id > :after)
                OR (NOT EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'POLICY_CHANGED' AND message = :mark)
                    AND EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'POLICY_CHANGED' AND event_id > :after
                                AND starts_with(message, :policy)))
            """, Map.of("t", ticketId, "after", afterEventId, "mark", PRE_PORTAL_MARK_NOTE,
                "policy", followedPolicyNote(DepositPolicy.NOT_REQUIRED, PRE_PORTAL_POLICY_REASON)), Boolean.class));
    }

    /** The `from` stage of the earliest automatic stage move written after {@code afterEventId}. */
    public Optional<String> firstAutoStageFromAfter(long ticketId, long afterEventId) {
        return jdbc.query("""
            SELECT from_status FROM sales.ticket_event
             WHERE ticket_id = :t AND kind = 'STAGE_CHANGED' AND message IN (:note, :send) AND event_id > :after
             ORDER BY event_id LIMIT 1
            """, Map.of("t", ticketId, "note", AUTO_STAGE_NOTE, "send", PRE_PORTAL_SEND_STAGE_NOTE, "after", afterEventId),
            (rs, n) -> rs.getString("from_status")).stream().findFirst();
    }

    /** When the deal last moved stage before {@code beforeEventId}: the best available `stage_updated_at` to restore. */
    public Optional<Instant> latestStageChangeAtBefore(long ticketId, long beforeEventId) {
        return jdbc.query("""
            SELECT created_at FROM sales.ticket_event
             WHERE ticket_id = :t AND kind = 'STAGE_CHANGED' AND event_id < :before ORDER BY event_id DESC LIMIT 1
            """, Map.of("t", ticketId, "before", beforeEventId),
            (rs, n) -> rs.getTimestamp("created_at").toInstant()).stream().findFirst();
    }

    // The system's deposit-policy flip (TicketService#followQuotationDepositPolicy) writes its POLICY_CHANGED note and the
    // undo finds it again: both build the text HERE, so the writer and the matcher cannot drift.
    /** The reason (also stored in {@code deposit_policy_reason}) of a policy that follows a quotation's deposit percent. */
    public static String followedPolicyReason(String quotationNumber, Integer depositPercent) {
        return "ใบเสนอราคา " + quotationNumber
            + (depositPercent != null && depositPercent == 0 ? " ระบุไม่รับมัดจำ (ชำระเต็มจำนวน)" : " ระบุมัดจำ " + depositPercent + "%");
    }

    /** The POLICY_CHANGED note of that flip. */
    public static String followedPolicyNote(String policy, String reason) {
        return "deposit_policy → " + policy + " — " + reason;
    }

    private static final String FOLLOWED_POLICY_NOTE_MATCH = "(message = :noDepositNote OR message LIKE :depositNoteLike ESCAPE '\\')";

    /** Binds {@link #FOLLOWED_POLICY_NOTE_MATCH} for this quotation: the exact no-deposit note, or the deposit note up to
     * its percent, with {@code %}, {@code _} and {@code \} in the number matched literally. */
    private static MapSqlParameterSource followedPolicyNoteParams(MapSqlParameterSource params, String quotationNumber) {
        String withOnePercent = followedPolicyNote(DepositPolicy.REQUIRED, followedPolicyReason(quotationNumber, 1));
        String upToPercent = withOnePercent.substring(0, withOnePercent.length() - "1%".length());
        return params
            .addValue("noDepositNote", followedPolicyNote(DepositPolicy.NOT_REQUIRED, followedPolicyReason(quotationNumber, 0)))
            .addValue("depositNoteLike", upToPercent.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }

    /** A system POLICY_CHANGED written after the promotion, for this quotation (a person's waiver has another note). */
    public boolean hasSystemPolicyChangeAfter(long ticketId, long afterEventId, String quotationNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'POLICY_CHANGED'
                           AND event_id > :after AND
            """ + FOLLOWED_POLICY_NOTE_MATCH + ")",
            followedPolicyNoteParams(new MapSqlParameterSource().addValue("t", ticketId).addValue("after", afterEventId),
                quotationNumber), Boolean.class));
    }

    /** Why a pre-portal deal's deposit policy is NOT_REQUIRED: the deposit was paid outside the portal. */
    public static final String PRE_PORTAL_POLICY_REASON = "งานที่เดินมาก่อนใช้ระบบ: มัดจำรับแล้วนอกระบบ";
    private static final String PRE_PORTAL_WAS = " · เดิม ";

    /** The POLICY_CHANGED note of the pre-portal marking; it names the policy it replaced so an undo can put it back. */
    public static String prePortalPolicyNote(String policyBefore) {
        return followedPolicyNote(DepositPolicy.NOT_REQUIRED, PRE_PORTAL_POLICY_REASON) + PRE_PORTAL_WAS + policyBefore;
    }

    /** The deposit policy the pre-portal marking replaced (read from its POLICY_CHANGED after the promotion), if it changed one. */
    public Optional<String> prePortalPolicyBefore(long ticketId, long afterEventId) {
        String prefix = followedPolicyNote(DepositPolicy.NOT_REQUIRED, PRE_PORTAL_POLICY_REASON) + PRE_PORTAL_WAS;
        return jdbc.query("""
            SELECT substr(message, :len + 1) FROM sales.ticket_event
             WHERE ticket_id = :t AND kind = 'POLICY_CHANGED' AND event_id > :after AND starts_with(message, :prefix)
             ORDER BY event_id DESC LIMIT 1
            """, Map.of("t", ticketId, "after", afterEventId, "prefix", prefix, "len", prefix.length()),
            (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** Puts the deposit policy back as the system's own (no person, no reason); the caller holds the ticket lock. Returns the rows changed. */
    public int restoreDepositPolicy(long ticketId, String policy) {
        return jdbc.update("""
            UPDATE sales.ticket SET deposit_policy = :p, deposit_policy_reason = NULL, deposit_policy_set_by = NULL, updated_at = now()
             WHERE ticket_id = :t
            """, Map.of("t", ticketId, "p", policy));
    }

    /** Undo of the order confirmation on a pre-portal deal: the PRE_PORTAL receipt goes, the expected payments it filled
     * reopen (nothing else can have filled them: the undo refuses when any other money exists), Express rows that
     * suggested it lose the suggestion, and the flag clears. Returns the amount of the receipt removed. */
    public BigDecimal removePrePortal(long ticketId) {
        jdbc.update("""
            UPDATE sales.receipt_import_row
               SET suggested_receipt_id = NULL,
                   reason = CASE WHEN reason = 'POSSIBLE_MANUAL_DUPLICATE' THEN 'NO_CANDIDATE' ELSE reason END
             WHERE suggested_receipt_id IN (SELECT receipt_id FROM sales.payment_receipt WHERE ticket_id = :t AND source = 'PRE_PORTAL')
            """, Map.of("t", ticketId));
        jdbc.update("""
            UPDATE sales.expected_payment
               SET received_amount = 0, status = 'OPEN', receipt_id = NULL, updated_at = now()
             WHERE ticket_id = :t AND status <> 'CANCELLED' AND (received_amount > 0 OR receipt_id IS NOT NULL)
            """, Map.of("t", ticketId));
        BigDecimal amount = sumPrePortalReceipts(ticketId);
        jdbc.update("DELETE FROM sales.payment_receipt WHERE ticket_id = :t AND source = 'PRE_PORTAL'", Map.of("t", ticketId));
        jdbc.update("UPDATE sales.ticket SET pre_portal = FALSE, updated_at = now() WHERE ticket_id = :t", Map.of("t", ticketId));
        return amount;
    }

    /** A person's deposit-policy change (waiver) after the promotion: a deposit_policy POLICY_CHANGED that is not the
     * system's own flip for this quotation nor the pre-portal marking. entry_channel and other POLICY_CHANGED notes do not count. */
    public boolean hasPersonDepositPolicyChangeAfter(long ticketId, long afterEventId, String quotationNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'POLICY_CHANGED'
                           AND event_id > :after AND starts_with(message, 'deposit_policy →')
                           AND NOT starts_with(message, :prePortalPrefix) AND NOT
            """ + FOLLOWED_POLICY_NOTE_MATCH + ")",
            followedPolicyNoteParams(new MapSqlParameterSource().addValue("t", ticketId).addValue("after", afterEventId)
                .addValue("prePortalPrefix", followedPolicyNote(DepositPolicy.NOT_REQUIRED, PRE_PORTAL_POLICY_REASON)),
                quotationNumber), Boolean.class));
    }

    /** Deletes the deal's DRAFT deposit notices and their items (no ON DELETE CASCADE); returns the notices deleted. */
    public int deleteDraftDepositNotices(long ticketId) {
        jdbc.update("""
            DELETE FROM sales.deposit_notice_item WHERE deposit_notice_id IN
              (SELECT deposit_notice_id FROM sales.deposit_notice WHERE ticket_id = :t AND status = 'DRAFT')
            """, Map.of("t", ticketId));
        return jdbc.update("DELETE FROM sales.deposit_notice WHERE ticket_id = :t AND status = 'DRAFT'", Map.of("t", ticketId));
    }

    public void deleteItems(long ticketId) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketId));
    }

    /** The ticket half of an undo, a compare-and-set from quotation_issued (0 = lost a race). A null stage / stamp
     * leaves that column alone; fulfilment is cleared only when it is FROM_STOCK. */
    public int revertOrderConfirmation(long ticketId, String restoreStage, Instant restoreStageUpdatedAt) {
        return jdbc.update("""
            UPDATE sales.ticket
               SET status = 'draft', payment_status = NULL,
                   fulfillment_status = CASE WHEN fulfillment_status = 'FROM_STOCK' THEN NULL ELSE fulfillment_status END,
                   sales_stage = COALESCE(:stage, sales_stage),
                   stage_updated_at = COALESCE(:at, stage_updated_at),
                   updated_at = now()
             WHERE ticket_id = :t AND status = 'quotation_issued'
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("stage", restoreStage)
                .addValue("at", restoreStageUpdatedAt == null ? null : java.sql.Timestamp.from(restoreStageUpdatedAt),
                    java.sql.Types.TIMESTAMP));
    }

    /** Deletes what the confirmation wrote: its promotion event and, after it, CUSTOMER_CONFIRMED, the automatic stage
     * moves and the system POLICY_CHANGED for this quotation. The auto-cancel CANCELLED events stay. */
    public void deleteConfirmationEvents(long ticketId, long promotionEventId, String quotationNumber) {
        jdbc.update("""
            DELETE FROM sales.ticket_event
             WHERE ticket_id = :t
               AND (event_id = :promo
                    OR (event_id > :promo AND (kind = 'CUSTOMER_CONFIRMED'
                        OR (kind = 'STAGE_CHANGED' AND message IN (:note, :send))
                        OR (kind = 'POLICY_CHANGED' AND (message = :mark OR
            """ + FOLLOWED_POLICY_NOTE_MATCH + ")))))",
            followedPolicyNoteParams(new MapSqlParameterSource().addValue("t", ticketId).addValue("promo", promotionEventId)
                .addValue("note", AUTO_STAGE_NOTE).addValue("send", PRE_PORTAL_SEND_STAGE_NOTE).addValue("mark", PRE_PORTAL_MARK_NOTE), quotationNumber));
    }

    /** An ORDER_CONFIRMATION_UNDONE event naming this quotation exists (the undo's idempotent replay). */
    public boolean hasOrderConfirmationUndoneEvent(long ticketId, long quotationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event WHERE ticket_id = :t AND kind = :kind
                           AND related_document_type = 'QUOTATION' AND related_document_id = :q)
            """, Map.of("t", ticketId, "kind", TicketEventKind.ORDER_CONFIRMATION_UNDONE, "q", quotationId), Boolean.class));
    }

    /**
     * The quotation whose confirm-order made this deal an order: the one the deal's
     * DEAL_PROMOTED_FROM_QUOTATION event names (the same per-quotation fact {@link #ANSWERED_QUOTATION_SQL} reads).
     * Empty when the order was never confirmed from a direct quotation.
     */
    public Optional<Long> findOrderConfirmedQuotationId(long ticketId) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT " + confirmedQuotationIdSql(":ticketId"),
            Map.of("ticketId", ticketId, "promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION), Long.class));
    }

    /** The quotation the deal's order was confirmed from (its latest DEAL_PROMOTED_FROM_QUOTATION event), as a scalar
     * subquery for the ticket id expression given; needs the {@code :promoted} parameter. The ONE statement for it. */
    public static String confirmedQuotationIdSql(String ticketIdExpr) {
        return "(SELECT related_document_id FROM sales.ticket_event WHERE ticket_id = " + ticketIdExpr
            + " AND kind = :promoted AND related_document_type = 'QUOTATION' AND related_document_id IS NOT NULL"
            + " ORDER BY created_at DESC, event_id DESC LIMIT 1)";
    }

    /** The DEAL_PROMOTED_FROM_QUOTATION event names the quotation whose confirm-order made the deal an order. */
    public boolean isOrderConfirmedFromQuotation(long ticketId, long quotationId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event
                           WHERE ticket_id = :ticketId AND kind = :kind
                             AND related_document_type = 'QUOTATION' AND related_document_id = :quotationId)
            """, Map.of("ticketId", ticketId, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION,
                "quotationId", quotationId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** Was this deal ordered before the portal (V237)? Gates that need a confirmed order or a paid deposit skip it. */
    public boolean isPrePortal(long ticketId) {
        Boolean v = jdbc.queryForObject("SELECT pre_portal FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Boolean.class);
        return Boolean.TRUE.equals(v);
    }

    /** The deposit entered as pre-portal receipts (source PRE_PORTAL), for the cap against the quotation total. */
    public BigDecimal sumPrePortalReceipts(long ticketId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM sales.payment_receipt WHERE ticket_id = :id AND source = 'PRE_PORTAL'",
            Map.of("id", ticketId), BigDecimal.class);
    }

    public void markPrePortal(long ticketId) {
        jdbc.update("UPDATE sales.ticket SET pre_portal = TRUE WHERE ticket_id = :id", Map.of("id", ticketId));
    }

    /**
     * Has this deal's order already been confirmed from a direct quotation
     * ({@link TicketEventKind#DEAL_PROMOTED_FROM_QUOTATION}, written exactly once per confirmation by
     * {@code DealQuotationService#confirmOrderFromDirectQuotation})? The discriminator for that
     * method's idempotent replay: if the event exists, a replay returns the current state and
     * writes nothing.
     */
    public boolean hasPromotionFromQuotationEvent(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sales.ticket_event
                           WHERE ticket_id = :ticketId AND kind = :kind)
            """, Map.of("ticketId", ticketId, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION),
            Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /** Validated payment-track advance. Multi-hop is a WALK, not a SKIP: intermediate states are traversed and
     * validated by PaymentTrack#stepsBetween, never jumped over. */
    public int advancePaymentStatus(long ticketId, String policy, String expected, String next) {
        // stepsBetween throws IllegalStateException for an illegal edge, BEFORE any SQL runs. Adversarial review found
        // three legacy-data routes that reached it. Deliberately NOT a global @ExceptionHandler(IllegalStateException)
        // — that would swallow genuine programming errors across the whole app.
        try {
            PaymentTrack.stepsBetween(policy, expected, next);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "สถานะการชำระเงินของงานนี้ไม่ถูกต้อง กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        // WALK, and PERSIST every hop: a multi-hop advance writes each intermediate
        // state in turn rather than jumping straight to the target. confirmFinalPayment on a
        // REQUIRED deal goes DEPOSIT_PAID -> AWAITING_FINAL_PAYMENT -> FULLY_PAID, and the row
        // genuinely sits at AWAITING_FINAL_PAYMENT in between so the deal's history shows it.
        // A single jump-write would leave no trace that the state was ever reached.
        //
        // Each hop is its own compare-and-set chained off the previous one, so a lost race stops
        // the walk immediately and returns 0 for the caller to turn into a 409 — it never
        // continues writing from a state it did not actually observe. All hops run inside the
        // caller's transaction, so a failure part-way rolls the whole walk back in production.
        // NOTE: @Transactional is inert in this repo's hand-wired integration tests, so the suite
        // does NOT prove that atomicity.
        List<String> hops = PaymentTrack.stepsBetween(policy, expected, next);
        String from = expected;
        int rows = 0;
        for (String hop : hops) {
            rows = jdbc.update("""
                UPDATE sales.ticket
                   SET payment_status = :next, updated_at = now()
                 WHERE ticket_id = :id AND payment_status IS NOT DISTINCT FROM :expected
                """,
                new MapSqlParameterSource()
                    .addValue("next", hop)
                    .addValue("id", ticketId)
                    .addValue("expected", from));
            if (rows == 0) {
                return 0;
            }
            from = hop;
        }
        return rows;
    }

    /** Test-seed / migration use only — bypasses PaymentTrack entirely (a blind, unguarded UPDATE: no
     * compare-and-set, no validated edge, no updated_at touch). No production code may call this; every real write
     * goes through #advancePaymentStatus. */
    public void updatePaymentStatusUnchecked(long ticketId, String paymentStatus) {
        jdbc.update(
            "UPDATE sales.ticket SET payment_status = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", paymentStatus).addValue("id", ticketId));
    }

    public void updateFulfillmentStatus(long ticketId, String fulfillmentStatus) {
        jdbc.update(
            "UPDATE sales.ticket SET fulfillment_status = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", fulfillmentStatus).addValue("id", ticketId));
    }

    public void updateItemManualPrice(long itemId, BigDecimal manualPrice, String reason) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET manual_price           = :manualPrice,
                   manual_override_reason = :reason,
                   proposed_price         = :manualPrice
             WHERE item_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("manualPrice", manualPrice)
                .addValue("reason", reason)
                .addValue("id", itemId));
    }

    public void setHasEdits(long ticketId, boolean value) {
        jdbc.update(
            "UPDATE sales.ticket SET has_edits = :v, updated_at = now() WHERE ticket_id = :id",
            Map.of("id", ticketId, "v", value));
    }

    // ── ticket_item <-> pricing-request-chain quantity reconciliation ─────────
    //
    // sales.ticket_item.qty is set exactly ONCE, at ticket creation (insertItems above), and is
    // never written by the PricingRequest chain (which deliberately never touches this table's
    // legacy price columns, and that discipline silently extended to qty
    // too). A pricing_request_item's own requested_qty can differ from the ticket_item it traces
    // back to via source_ticket_item_id from the very first submission, and can diverge further
    // still after a customer-change revision (PricingRequestService.createCustomerChangeRevision
    // is reachable even from QUOTATION_ACCEPTED — see that method's own status guard). Called
    // from OrderConfirmationService.confirmOrder, the one bridge point that always runs exactly
    // once before any delivery machinery (reserveStock/completeDelivery, both keyed on this same
    // qty column) becomes reachable for the deal.

    /** Reconciles ONE existing ticket_item row's qty/qty_sqm to whatever the pricing-request chain actually settled
     * on. The caller converts that into a clean 409; this method never suppresses or works around it. */
    public boolean reconcileItemQty(long ticketId, long itemId, BigDecimal qty, BigDecimal qtySqm,
                                    BigDecimal qtyFromStock) {
        // qty_from_stock moves in the same statement as qty so chk_ticket_item_qty_from_stock never sees a
        // half-written row. A null qtyFromStock leaves the existing value alone (only stock lines are written).
        return jdbc.update("""
            UPDATE sales.ticket_item
               SET qty = :qty, qty_sqm = :qtySqm, qty_from_stock = COALESCE(CAST(:qtyFromStock AS numeric), qty_from_stock)
             WHERE ticket_id = :ticketId AND item_id = :itemId
               AND (qty IS DISTINCT FROM :qty
                    OR (CAST(:qtyFromStock AS numeric) IS NOT NULL
                        AND qty_from_stock IS DISTINCT FROM CAST(:qtyFromStock AS numeric)))
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("itemId", itemId)
                .addValue("qty", qty)
                .addValue("qtySqm", qtySqm)
                .addValue("qtyFromStock", qtyFromStock)) > 0;
    }

    /**
     * Closes out any {@code ticket_item} row that WAS part of this pricing-request chain (some
     * revision in the {@code rootPricingRequestId} chain had a {@code pricing_request_item} whose
     * {@code source_ticket_item_id} points at it) but is absent from the CURRENTLY-accepted
     * revision ({@code currentPricingRequestId}) — a line the customer dropped entirely via a
     * customer-change revision.
     *
     * <p>Why this exists: {@link #reconcileItemQty} only
     * touches items still PRESENT in the current revision, so a dropped line's {@code ticket_item}
     * row keeps its stale {@code qty} forever. {@code TicketService.completeDelivery}/{@code
     * reserveStock} iterate {@code ticket.items()} unconditionally (no filter by "is this item
     * part of the accepted pricing request") — a stale row with open quantity
     * ({@code qty > qty_delivered}) makes {@code completeDelivery}'s "any remaining?" check see a
     * phantom undeliverable balance FOREVER, since nobody can ever deliver units of a product the
     * customer no longer ordered. The deal can never reach {@code FulfilmentStatus.FULLY_DELIVERED}
     * / {@code DealStage.DELIVERED}. This is a deal-blocking correctness bug, not a cosmetic gap.
     *
     * <p>Closes by setting {@code qty = qty_delivered} (never below — the boundary case is always
     * legal under {@code chk_ticket_item_qty_delivered}/{@code chk_ticket_item_qty_from_stock},
     * V54): a line with nothing yet delivered closes to zero remaining; a line ALREADY PARTIALLY
     * delivered before being dropped keeps exactly what was truly delivered as history, closing
     * only the open remainder — it never fabricates a false "fully delivered" claim beyond what
     * actually happened, and never needs to touch the CHECK-constraint boundary the way a naive
     * "set qty to 0" would.
     *
     * <p>Deliberately scoped to the {@code rootPricingRequestId} CHAIN, not the whole ticket — a
     * ticket can carry independent pricing requests for different recipients (designer/owner/
     * buyer; 0..N pricing requests per deal), and this must never touch a
     * ticket_item that legitimately belongs only to a DIFFERENT recipient's quote just because it
     * is absent from the one being confirmed right now.
     *
     * @return the number of ticket_item rows closed — the caller uses this only to decide whether
     *         a "quantities reconciled" event is worth a distinct log line, never for correctness.
     */
    public int closeOutDroppedChainItems(long ticketId, long rootPricingRequestId, long currentPricingRequestId) {
        return jdbc.update("""
            UPDATE sales.ticket_item ti
               SET qty = ti.qty_delivered, qty_not_delivered = 0
             WHERE ti.ticket_id = :ticketId
               AND ti.qty IS DISTINCT FROM ti.qty_delivered
               AND EXISTS (
                   SELECT 1
                     FROM sales.pricing_request_item pri
                     JOIN sales.pricing_request pr ON pr.pricing_request_id = pri.pricing_request_id
                    WHERE pri.source_ticket_item_id = ti.item_id
                      AND (pr.pricing_request_id = :rootId OR pr.root_pricing_request_id = :rootId)
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM sales.pricing_request_item pri2
                    WHERE pri2.source_ticket_item_id = ti.item_id
                      AND pri2.pricing_request_id = :currentId
               )
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("rootId", rootPricingRequestId)
                .addValue("currentId", currentPricingRequestId));
    }

    /** Inserts a brand-new ticket_item row for a pricing_request_item that has NO sourceTicketItemId — a line added
     * entirely within a customer-change revision, with no original ticket_item to reconcile against. */
    public long insertReconciledItem(long ticketId, String brand, String model, String color, String texture,
                                     String size, String factory, BigDecimal qty, BigDecimal qtySqm,
                                     String unitBasis, BigDecimal qtyFromStock) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory, qty, qty_sqm, unit_basis,
                 qty_from_stock, currency, sort_order)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory, :qty, :qtySqm,
                    :unitBasis, COALESCE(CAST(:qtyFromStock AS numeric), 0), 'THB',
                    (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM sales.ticket_item WHERE ticket_id = :ticketId))
            RETURNING item_id
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("brand", brand)
                .addValue("model", model)
                .addValue("color", color)
                .addValue("texture", texture)
                .addValue("size", size)
                .addValue("factory", factory)
                .addValue("qty", qty)
                .addValue("qtySqm", qtySqm)
                .addValue("unitBasis", unitBasis)
                .addValue("qtyFromStock", qtyFromStock),
            Long.class);
    }

    // ── Deal tracking + activity (V83) ──────

    /**
     * Writes {@code value} into the deal's party-name column for {@code recipientType} (DESIGNER → designer_name,
     * OWNER → owner_name, BUYER → buyer_name) only when that column is null or blank. The column is chosen by a
     * switch over the three codes, never from input. Returns rows written (0 or 1).
     */
    /**
     * The party name a quotation actually carries for its recipient (review H1): DESIGNER → the designer
     * directory entry behind the quotation's D.Co. ({@code unit_code}); BUYER → the customer of the quotation's
     * BUYER party (the bidder it is addressed to). OWNER and UNSPECIFIED have no such source — empty. The bill-to
     * {@code customer_name} is deliberately NOT a source: it names whoever is billed, not the designer.
     */
    public Optional<String> partyNameFromQuotation(long quotationId, String recipientType) {
        String sql;
        if (QuotationRecipient.DESIGNER.equals(recipientType)) {
            sql = """
                SELECT NULLIF(TRIM(d.name), '') FROM sales.quotation q
                  JOIN sales.designer d ON d.code = TRIM(q.unit_code)
                 WHERE q.quotation_id = :id
                """;
        } else if (QuotationRecipient.BUYER.equals(recipientType)) {
            sql = """
                SELECT NULLIF(TRIM(c.name), '') FROM sales.quotation q
                  JOIN sales.deal_party p ON p.party_id = q.party_id AND p.role = 'BUYER'
                  JOIN customers.customer c ON c.customer_id = p.customer_id
                 WHERE q.quotation_id = :id
                """;
        } else {
            return Optional.empty();
        }
        return jdbc.queryForList(sql, Map.of("id", quotationId), String.class).stream()
            .filter(java.util.Objects::nonNull).findFirst();
    }

    public int fillPartyNameIfBlank(long ticketId, String recipientType, String value) {
        String column = switch (recipientType) {
            case QuotationRecipient.DESIGNER -> "designer_name";
            case QuotationRecipient.OWNER -> "owner_name";
            case QuotationRecipient.BUYER -> "buyer_name";
            default -> throw new IllegalArgumentException("Not a deal party: " + recipientType);
        };
        return jdbc.update("UPDATE sales.ticket SET " + column + " = :v, updated_at = now()"
            + " WHERE ticket_id = :id AND COALESCE(TRIM(" + column + "), '') = ''",
            new MapSqlParameterSource().addValue("v", value).addValue("id", ticketId));
    }

    /** V209: the rep's expected close quarter ({@code YYYY-Qn}), or null to clear. */
    public void updateExpectedQuarter(long ticketId, String expectedQuarter) {
        jdbc.update("UPDATE sales.ticket SET expected_quarter = :q, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("q", expectedQuarter).addValue("id", ticketId));
    }

    /** Slice 6.4: the deal's next follow-up date and nothing else (a call's or a visit's ตามครั้งถัดไป / ตามต่อ). Also
     * used by {@link #updateTracking}'s caller to set or clear the date, since updateTracking no longer writes it. */
    public void updateNextFollowUpAt(long ticketId, LocalDate nextFollowUpAt) {
        jdbc.update("UPDATE sales.ticket SET next_follow_up_at = :d, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("d", nextFollowUpAt).addValue("id", ticketId));
    }

    public void updateTracking(long ticketId, Integer winProbabilityOverride, String designerName,
                               String ownerName, String buyerName) {
        jdbc.update("""
            UPDATE sales.ticket
               SET win_probability  = :winProbability,
                   designer_name    = :designerName,
                   owner_name       = :ownerName,
                   buyer_name       = :buyerName,
                   updated_at       = now()
             WHERE ticket_id = :ticketId
            """,
            new MapSqlParameterSource()
                .addValue("winProbability", winProbabilityOverride)
                .addValue("designerName", designerName)
                .addValue("ownerName", ownerName)
                .addValue("buyerName", buyerName)
                .addValue("ticketId", ticketId));
    }

    public void updateCustomer(long ticketId, long customerId, long projectId, Long contactId,
                               String customerName) {
        jdbc.update("""
            UPDATE sales.ticket
               SET customer_id   = :customerId,
                   project_id    = :projectId,
                   contact_id    = :contactId,
                   customer_name = :customerName,
                   updated_at    = now()
             WHERE ticket_id = :ticketId
            """,
            new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("projectId", projectId)
                .addValue("contactId", contactId)
                .addValue("customerName", customerName)
                .addValue("ticketId", ticketId));
        // V204: a deal with exactly one party (every pre-party deal) moves that party with it; a deal with several
        // parties keeps them, the customer change being a bill-to correction, not a bidder change.
        jdbc.update("""
            UPDATE sales.deal_party SET customer_id = :customerId, contact_id = :contactId, updated_at = now()
             WHERE ticket_id = :ticketId
               AND (SELECT COUNT(*) FROM sales.deal_party p WHERE p.ticket_id = :ticketId) = 1
            """, new MapSqlParameterSource().addValue("ticketId", ticketId).addValue("customerId", customerId)
                .addValue("contactId", contactId));
    }

    /**
     * True once any quotation of the deal has left DRAFT (or been cancelled): from then on the
     * customer is printed on a document that has been, or is being, approved. Origin-agnostic, like
     * {@link #hasAcceptedQuotation}.
     */
    public boolean hasNonDraftQuotation(long ticketId) {
        Boolean value = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.quotation WHERE ticket_id = :ticketId"
                + " AND doc_status NOT IN ('DRAFT','CANCELLED'))",
            Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public long insertDealActivity(long ticketId, LocalDate activityDate, String kind, String note,
                                   long createdById) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, note, created_by_id)
            VALUES (:ticketId, :activityDate, :kind, :note, :createdById)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("activityDate", activityDate)
                .addValue("kind", kind)
                .addValue("note", note)
                .addValue("createdById", createdById),
            keys, new String[]{"id"});
        return keys.getKey().longValue();
    }

    public List<DealActivityDto> findActivitiesByTicket(long ticketId) {
        return jdbc.query("""
            SELECT da.id, da.ticket_id, da.activity_date, da.kind, da.note, da.created_by_id,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS created_by_name,
                   da.created_at
              FROM sales.deal_activity da
              JOIN hr.employee e ON e.employee_id = da.created_by_id
             WHERE da.ticket_id = :ticketId
             ORDER BY da.activity_date DESC, da.id DESC
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> new DealActivityDto(
                rs.getLong("id"),
                rs.getLong("ticket_id"),
                rs.getObject("activity_date", LocalDate.class),
                rs.getString("kind"),
                rs.getString("note"),
                rs.getLong("created_by_id"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant()
            ));
    }

    /** The stage-advance gate's second half: at least one deal_activity row logged at/after the ticket's most
     * recent STAGE_CHANGED event. */
    public boolean hasActivitySinceLastStageChange(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.deal_activity da
                 WHERE da.ticket_id = :ticketId
                   AND da.created_at >= COALESCE(
                       (SELECT MAX(te.created_at)
                          FROM sales.ticket_event te
                         WHERE te.ticket_id = :ticketId AND te.kind = 'STAGE_CHANGED'),
                       (SELECT t.created_at FROM sales.ticket t WHERE t.ticket_id = :ticketId)
                   )
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /**
     * Every per-deal fact {@code mapSummary} cannot answer from {@code sales.ticket}, for a whole page of
     * deals in ONE statement (no query per row). Each column reuses the single-deal SQL builders with
     * {@code t.ticket_id} as the correlation, so the list and the single-deal helpers cannot drift.
     * A holder class: it initialises on first use, after {@code TicketRepository}'s statics are all set.
     */
    private static final class SummaryFactsSql {
        /**
         * TicketSummaryDto#receiptSource for the deal at {@code t}: EXPRESS when every receipt came from the Express
         * file (source, V213) or is linked to an Express document (express_doc_no, V214); MANUAL when none is; MIXED
         * when some are; NULL with no receipt at all ({@code bool_and} of no rows is NULL, so COUNT decides first).
         * Owner ruling (fix round 1): the chip says how the money RECEIVED was recorded, so a refund / credit note
         * (kind ADJUSTMENT) never counts, nor would a zero amount (impossible today: V53's CHECK amount > 0; there is no
         * void / reversal column on payment_receipt).
         */
        static final String RECEIPT_SOURCE_SQL = """
            (SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                         WHEN bool_and(pr.source = 'EXPRESS' OR pr.express_doc_no IS NOT NULL) THEN 'EXPRESS'
                         WHEN bool_and(pr.source = 'TAX_INVOICE') THEN 'TAX_INVOICE'
                         WHEN bool_and(pr.source = 'PRE_PORTAL') THEN 'PRE_PORTAL'
                         WHEN NOT bool_or(pr.source IN ('TAX_INVOICE', 'PRE_PORTAL')) THEN
                              CASE WHEN bool_or(pr.source = 'EXPRESS' OR pr.express_doc_no IS NOT NULL) THEN 'MIXED' ELSE 'MANUAL' END
                         ELSE concat_ws('+',
                              CASE WHEN bool_or(pr.source = 'EXPRESS' OR pr.express_doc_no IS NOT NULL) THEN 'EXPRESS' END,
                              CASE WHEN bool_or(pr.source NOT IN ('EXPRESS', 'TAX_INVOICE', 'PRE_PORTAL') AND pr.express_doc_no IS NULL) THEN 'MANUAL' END,
                              CASE WHEN bool_or(pr.source = 'PRE_PORTAL' AND pr.express_doc_no IS NULL) THEN 'PRE_PORTAL' END,
                              CASE WHEN bool_or(pr.source = 'TAX_INVOICE') THEN 'TAX_INVOICE' END) END
               FROM sales.payment_receipt pr
              WHERE pr.ticket_id = t.ticket_id AND pr.kind <> 'ADJUSTMENT' AND pr.amount > 0)""";

        static final String SELECT = "SELECT t.ticket_id, ("
            + payableAmountSelect("t.ticket_id") + ") AS payable, ("
            + paidAmountSelect("t.ticket_id") + ") AS paid, "
            + hasBalanceReceiptSql("t.ticket_id") + " AS balance_receipt, "
            + isStaleSql("t") + " AS is_stale, "
            + hasInvoiceAttachmentSql("t.ticket_id") + " AS has_invoice, "
            + hasRecordedCommission("t.ticket_id") + """
             AS commission_recorded,
            pt.days, pt.basis, pt.credit_days, pt.delivered_on,
            q.quotation_id, q.number, q.doc_status, q.recipient_type,
            (SELECT array_agg(DISTINCT r.recipient_type)
               FROM sales.quotation r
              WHERE r.ticket_id = t.ticket_id
                AND r.doc_status <> 'CANCELLED') AS quoted_recipients,
            (SELECT d.total_payable FROM sales.deposit_notice d
              WHERE d.ticket_id = t.ticket_id AND d.status = 'ISSUED'
              ORDER BY d.version DESC, d.deposit_notice_id DESC LIMIT 1) AS deposit_due,
            """ + RECEIPT_SOURCE_SQL + """
             AS receipt_source,
            t.payment_status
              FROM sales.ticket t
              LEFT JOIN LATERAL (
            """ + paymentTermsRowSql("t.ticket_id") + """
              ) pt ON true
              LEFT JOIN LATERAL (
            """ + liveDirectQuotationSelect("t.ticket_id") + """
              ) q ON true
             WHERE t.ticket_id = ANY(:ids)
            """;
    }
}
