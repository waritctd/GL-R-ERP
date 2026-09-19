package th.co.glr.hr.importrequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestItemDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.ImportRequestItemInput;

/**
 * Persistence for {@code sales.import_request} (V154) — the STORED ใบขอซื้อ.
 *
 * <p><strong>Writes only.</strong> Every read of the DEAL — the snapshot an IR freezes, and the
 * deal's lines grouped by brand — belongs to {@link ImportRequestQueryRepository}, which is the one
 * production has been exercising. This class briefly carried its own copies of both; they are gone
 * rather than reconciled, because two readers of the same rows is how the two drift (and the copy
 * here had {@code e.nick_name} for a column really called {@code nickname}, which nothing caught
 * until the other one was executed against real Postgres).
 */
@Repository
public class ImportRequestRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_IR = """
        SELECT r.import_request_id, r.ticket_id, t.code AS ticket_code, r.brand,
               r.factory_id, r.factory_name, r.version, r.status,
               r.doc_number, r.issue_date, r.first_issued_date,
               r.customer_name, r.project_name, r.requested_by_name,
               r.required_by_note, r.deposit_received_date, r.vessel_eta_note, r.checked_by_name,
               r.checked_date, r.approved_by_name, r.approved_date,
               r.import_step, r.import_step_at, r.import_step_by_id, r.import_step_by_name,
               r.import_step_note, r.lead_time_min_days, r.lead_time_max_days,
               r.email_to, r.email_subject, r.email_body, r.email_sent_at, r.email_sent_by_id,
               r.email_sent_by_name,
               r.created_by_id,
               r.created_by_name, r.issued_by_id, r.issued_by_name, r.issued_by_email,
               r.superseded_by_id,
               r.created_at, r.updated_at, r.issued_at
          FROM sales.import_request r
          JOIN sales.ticket t ON t.ticket_id = r.ticket_id
        """;

    public List<ImportRequestDto> findByTicket(long ticketId) {
        List<ImportRequestDto> rows = jdbc.query(
            SELECT_IR + " WHERE r.ticket_id = :id ORDER BY r.brand, r.version",
            Map.of("id", ticketId), (rs, n) -> mapRow(rs));
        return withItems(rows);
    }

    public Optional<ImportRequestDto> findById(long id) {
        List<ImportRequestDto> rows = jdbc.query(
            SELECT_IR + " WHERE r.import_request_id = :id",
            Map.of("id", id), (rs, n) -> mapRow(rs));
        return withItems(rows).stream().findFirst();
    }

    /**
     * Loads the items for every row in ONE query rather than per row. Not premature optimisation: a
     * deal with several brand IRs would otherwise issue one query per form on a page that always
     * renders all of them.
     */
    private List<ImportRequestDto> withItems(List<ImportRequestDto> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> ids = rows.stream().map(ImportRequestDto::id).toList();
        Map<Long, List<ImportRequestItemDto>> byParent = new LinkedHashMap<>();
        jdbc.query("""
            SELECT import_request_item_id, import_request_id, ticket_item_id, seq, code, size,
                   qty, unit, note, color, texture, brand, model
              FROM sales.import_request_item
             WHERE import_request_id IN (:ids)
             ORDER BY import_request_id, seq
            """, Map.of("ids", ids), rs -> {
                byParent.computeIfAbsent(rs.getLong("import_request_id"), k -> new ArrayList<>())
                    .add(new ImportRequestItemDto(
                        rs.getLong("import_request_item_id"),
                        rs.getLong("import_request_id"),
                        (Long) rs.getObject("ticket_item_id"),
                        rs.getInt("seq"),
                        rs.getString("code"),
                        rs.getString("size"),
                        rs.getBigDecimal("qty"),
                        rs.getString("unit"),
                        rs.getString("note"),
                        rs.getString("color"),
                        rs.getString("texture"),
                        rs.getString("brand"),
                        rs.getString("model")));
            });
        return rows.stream()
            .map(r -> withItems(r, byParent.getOrDefault(r.id(), List.of())))
            .toList();
    }

    private static ImportRequestDto withItems(ImportRequestDto r, List<ImportRequestItemDto> items) {
        return new ImportRequestDto(r.id(), r.ticketId(), r.ticketCode(), r.brand(),
            r.factoryId(), r.factoryName(), r.version(),
            r.status(), r.docNumber(), r.issueDate(), r.firstIssuedDate(), r.customerName(), r.projectName(),
            r.requestedByName(), r.requiredByNote(), r.depositReceivedDate(), r.vesselEtaNote(),
            r.checkedByName(), r.checkedDate(), r.approvedByName(), r.approvedDate(),
            r.importStep(), r.importStepAt(), r.importStepById(), r.importStepByName(), r.importStepNote(),
            r.leadTimeMinDays(), r.leadTimeMaxDays(), r.expectedArrivalFrom(), r.expectedArrivalTo(),
            r.emailTo(), r.emailSubject(), r.emailBody(), r.emailSentAt(), r.emailSentById(), r.emailSentByName(),
            r.createdById(), r.createdByName(), r.issuedById(), r.issuedByName(), r.issuedByEmail(),
            r.supersededById(), r.createdAt(), r.updatedAt(), r.issuedAt(), 0, items);
    }

    /**
     * {@code expectedArrivalFrom}/{@code To} are DERIVED, not columns — left {@code null} here (the
     * same placeholder discipline {@code pageCount}'s {@code 0} already uses) and filled in by
     * {@link ImportRequestService#withPageCount}, the one place both derived fields are computed.
     */
    private static ImportRequestDto mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ImportRequestDto(
            rs.getLong("import_request_id"), rs.getLong("ticket_id"), rs.getString("ticket_code"),
            rs.getString("brand"), (Long) rs.getObject("factory_id"), rs.getString("factory_name"),
            rs.getInt("version"), rs.getString("status"),
            rs.getString("doc_number"), localDate(rs, "issue_date"), localDate(rs, "first_issued_date"),
            rs.getString("customer_name"), rs.getString("project_name"),
            rs.getString("requested_by_name"), rs.getString("required_by_note"),
            localDate(rs, "deposit_received_date"), rs.getString("vessel_eta_note"),
            rs.getString("checked_by_name"), localDate(rs, "checked_date"),
            rs.getString("approved_by_name"), localDate(rs, "approved_date"),
            rs.getString("import_step"), localDate(rs, "import_step_at"),
            (Long) rs.getObject("import_step_by_id"), rs.getString("import_step_by_name"),
            rs.getString("import_step_note"),
            (Integer) rs.getObject("lead_time_min_days"), (Integer) rs.getObject("lead_time_max_days"),
            null, null,
            rs.getString("email_to"), rs.getString("email_subject"), rs.getString("email_body"),
            rs.getTimestamp("email_sent_at") == null ? null : rs.getTimestamp("email_sent_at").toInstant(),
            (Long) rs.getObject("email_sent_by_id"), rs.getString("email_sent_by_name"),
            (Long) rs.getObject("created_by_id"), rs.getString("created_by_name"),
            (Long) rs.getObject("issued_by_id"), rs.getString("issued_by_name"), rs.getString("issued_by_email"),
            (Long) rs.getObject("superseded_by_id"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant(),
            0, List.of());
    }

    private static LocalDate localDate(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    // ── Writes ────────────────────────────────────────────────────────────────────────────────

    /**
     * @param brandLabel the display-snapshot "Brand" header — the distinct brand(s) of this
     *                   factory's lines on the deal, already joined by the caller (V184: brand is no
     *                   longer the grouping key, {@code factoryId} is; see that column's comment).
     * @param leadTimeMinDays (V184, owner decision 09-18 #2) either both this and {@code
     *                   leadTimeMaxDays} are null or both are set — the CALLER decides which:
     *                   {@code createDrafts} passes the factory-country autofill ({@code
     *                   LeadTimeDefaults}), {@code revise} passes the predecessor's carried-forward
     *                   actual values. This method does not derive them itself, so a test can see
     *                   exactly what each caller chose.
     * @param leadTimeMaxDays see {@code leadTimeMinDays}.
     * @param vesselEtaNote (REVIEW ROUND 2, S-E) {@code null} for a brand-new draft ({@code
     *                   createDrafts} — nothing to carry, {@link ImportRequestService#issue} derives
     *                   one fresh), or the predecessor's own value CARRIED FORWARD unchanged for a
     *                   revision ({@code revise}) — so a CEO-typed custom note survives a correction
     *                   instead of being silently reset to null and re-derived from scratch.
     * @param requiredByNoteOverride (REVIEW ROUND 3, item 2 — S-F regression) {@code null}/blank for
     *                   a brand-new draft ({@code createDrafts} — nothing of its OWN to carry, the
     *                   row falls back to {@code snap.requiredByNote()} exactly as before), or the
     *                   predecessor's own {@code requiredByNote} CARRIED FORWARD for a revision
     *                   ({@code revise}) — without this, a revision fell all the way back to the
     *                   DEAL's current value, silently dropping anything the rep had typed directly
     *                   onto the form itself.
     */
    public long insertDraft(long ticketId, long factoryId, String factoryName, String brandLabel,
                            int version, ImportRequestQueryRepository.TicketSnapshot snap,
                            Integer leadTimeMinDays, Integer leadTimeMaxDays, String vesselEtaNote,
                            String requiredByNoteOverride, long actorId, String actorName) {
        String requiredBy = requiredByNoteOverride != null && !requiredByNoteOverride.isBlank()
            ? requiredByNoteOverride : snap.requiredByNote();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, brand, version, status, customer_name,
                 project_name, requested_by_name, required_by_note, deposit_received_date,
                 lead_time_min_days, lead_time_max_days, vessel_eta_note,
                 created_by_id, created_by_name)
            VALUES (:ticketId, :factoryId, :factoryName, :brand, :version, 'DRAFT', :customer,
                    :project, :rep, :requiredBy, :depositDate,
                    :leadTimeMin, :leadTimeMax, :vesselEtaNote, :actorId, :actorName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("factoryId", factoryId).addValue("factoryName", factoryName)
                .addValue("brand", brandLabel).addValue("version", version)
                .addValue("customer", snap.customerName()).addValue("project", snap.projectName())
                .addValue("rep", snap.requestedByName())
                .addValue("requiredBy", requiredBy)
                .addValue("depositDate", snap.depositReceivedDate())
                .addValue("leadTimeMin", leadTimeMinDays).addValue("leadTimeMax", leadTimeMaxDays)
                .addValue("vesselEtaNote", vesselEtaNote)
                .addValue("actorId", actorId).addValue("actorName", actorName),
            keys, new String[] {"import_request_id"});
        return keys.getKey().longValue();
    }

    /** Replaces every line, renumbering {@code seq} from 1 in the order supplied. */
    public void replaceItems(long importRequestId, List<ImportRequestItemInput> items) {
        jdbc.update("DELETE FROM sales.import_request_item WHERE import_request_id = :id",
            Map.of("id", importRequestId));
        int seq = 1;
        for (ImportRequestItemInput it : items) {
            jdbc.update("""
                INSERT INTO sales.import_request_item
                    (import_request_id, ticket_item_id, seq, code, size, qty, unit, note, color, texture,
                     brand, model)
                VALUES (:parent, :ticketItemId, :seq, :code, :size, :qty, :unit, :note, :color, :texture,
                        :brand, :model)
                """,
                new MapSqlParameterSource()
                    .addValue("parent", importRequestId)
                    .addValue("ticketItemId", it.ticketItemId())
                    .addValue("seq", seq++)
                    .addValue("code", it.code()).addValue("size", it.size())
                    .addValue("qty", it.qty()).addValue("unit", it.unit())
                    .addValue("note", it.note())
                    .addValue("color", it.color()).addValue("texture", it.texture())
                    .addValue("brand", it.brand()).addValue("model", it.model()));
        }
    }

    /**
     * Body fields — draft only; the WHERE clause enforces that, not the caller.
     *
     * @param requiredByNote (REVIEW ROUND 2, S-F) this FORM's own "กำหนดวันที่ต้องการของ" —
     *                       separate from {@code sales.ticket.required_by_note} (the DEAL-level
     *                       value {@link #insertDraft} snapshots FROM at creation). COALESCE like
     *                       every other field here: absent leaves the existing value alone.
     */
    public int updateDraftBody(long id, String projectName, String customerName, String repName,
                               String requiredByNote) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET project_name = COALESCE(:project, project_name),
                   customer_name = COALESCE(:customer, customer_name),
                   requested_by_name = COALESCE(:rep, requested_by_name),
                   required_by_note = COALESCE(:requiredBy, required_by_note),
                   updated_at = now()
             WHERE import_request_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("project", projectName).addValue("customer", customerName)
                .addValue("rep", repName).addValue("requiredBy", requiredByNote));
    }

    /**
     * Sets lead time (V184, owner decision 09-18 #2) directly — not COALESCE, unlike {@link
     * #updateDraftBody}/{@link #updateFooter} — because both callers ({@code
     * ImportRequestService#update} for the DRAFT-stage owning-rep/CEO edit, {@code #setLeadTime}
     * for the post-issue import/CEO edit) have already validated a COMPLETE {@code (min, max)} pair
     * before calling this, per {@code chk_import_request_lead_time}'s own "both or neither" pairing
     * — there is no partial write for this method to guard against. {@code status <> 'SUPERSEDED'}
     * is the same archived-document backstop {@link #updateFooter} applies.
     */
    public int updateLeadTime(long id, int leadTimeMinDays, int leadTimeMaxDays) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET lead_time_min_days = :min, lead_time_max_days = :max, updated_at = now()
             WHERE import_request_id = :id AND status <> 'SUPERSEDED'
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("min", leadTimeMinDays).addValue("max", leadTimeMaxDays));
    }

    /**
     * The import-owned footer. Allowed on an ISSUED form as well as a draft — those four fields are
     * filled in by hand AFTER the form is raised (that is the whole point of the printed approval
     * block), so restricting them to DRAFT would make them unusable. SUPERSEDED is excluded: it is an
     * archived document.
     */
    public int updateFooter(long id, String vesselEtaNote, String checkedBy, LocalDate checkedDate,
                            String approvedBy, LocalDate approvedDate) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET vessel_eta_note = COALESCE(:eta, vessel_eta_note),
                   checked_by_name = COALESCE(:checkedBy, checked_by_name),
                   checked_date = COALESCE(:checkedDate, checked_date),
                   approved_by_name = COALESCE(:approvedBy, approved_by_name),
                   approved_date = COALESCE(:approvedDate, approved_date),
                   updated_at = now()
             WHERE import_request_id = :id AND status <> 'SUPERSEDED'
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("eta", vesselEtaNote).addValue("checkedBy", checkedBy)
                .addValue("checkedDate", checkedDate).addValue("approvedBy", approvedBy)
                .addValue("approvedDate", approvedDate));
    }

    /**
     * Compare-and-set DRAFT → ISSUED, ALSO writing the per-factory progress step (V184). Returns
     * rows affected; 0 means someone else issued or superseded it first, and the caller must NOT
     * treat that as success — the same guard {@code DepositNoticeRepository.issue} uses, and for the
     * same reason: a second UPDATE would re-mint a number onto an already-issued controlled document.
     *
     * @param importStep on a FIRST issue for this (deal, factory), {@code ImportRequestStep.CONTACTED}
     *                   with {@code importStepAt}=today and the issuing actor; on a REVISION issue,
     *                   the predecessor's own current step fields, copied forward by the caller (which
     *                   must have locked that predecessor row — see {@link #lockIssuedPredecessor}
     *                   — before calling this, so the two writes are consistent within one
     *                   transaction).
     * @param firstIssuedDate (REVIEW ROUND 1, S7) today on a FIRST issue, or the predecessor's own
     *                   {@code firstIssuedDate} carried forward unchanged on a revision — the caller
     *                   decides which, same discipline as {@code importStep}.
     * @param requiredByNote (REVIEW ROUND 2, S-F) this form's OWN "กำหนดวันที่ต้องการของ", set
     *                   UNCONDITIONALLY (not COALESCE) — the caller ({@code
     *                   ImportRequestService#issue}) has already resolved it to either the draft's
     *                   own already-typed value or a fresh re-snapshot from the deal, so there is no
     *                   "leave alone" case for this write to support.
     * @param emailTo/emailSubject/emailBody the order-email draft (owner decision 09-18 #3 §B),
     *                   (re)generated by the caller ({@code ImportRequestService#buildEmailDraft})
     *                   at every issue — a revision's issue REPLACES the previous draft rather than
     *                   carrying it forward, since the line/date detail it quotes may have changed.
     */
    public int issue(long id, String docNumber, LocalDate issueDate, long actorId, String actorName,
                     String actorEmail, String importStep, LocalDate importStepAt, Long importStepById,
                     String importStepByName, String importStepNote, LocalDate firstIssuedDate,
                     String requiredByNote, String emailTo, String emailSubject, String emailBody) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET status = 'ISSUED', doc_number = :num, issue_date = :issueDate,
                   first_issued_date = :firstIssuedDate, required_by_note = :requiredByNote,
                   issued_by_id = :actorId, issued_by_name = :actorName, issued_by_email = :actorEmail,
                   issued_at = now(), updated_at = now(),
                   import_step = :importStep, import_step_at = :importStepAt,
                   import_step_by_id = :importStepById, import_step_by_name = :importStepByName,
                   import_step_note = :importStepNote,
                   email_to = :emailTo, email_subject = :emailSubject, email_body = :emailBody,
                   email_sent_at = NULL, email_sent_by_id = NULL, email_sent_by_name = NULL
             WHERE import_request_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("num", docNumber)
                .addValue("issueDate", issueDate).addValue("firstIssuedDate", firstIssuedDate)
                .addValue("requiredByNote", requiredByNote)
                .addValue("actorId", actorId)
                .addValue("actorName", actorName)
                .addValue("actorEmail", actorEmail)
                .addValue("importStep", importStep).addValue("importStepAt", importStepAt)
                .addValue("importStepById", importStepById).addValue("importStepByName", importStepByName)
                .addValue("importStepNote", importStepNote)
                .addValue("emailTo", emailTo).addValue("emailSubject", emailSubject)
                .addValue("emailBody", emailBody));
    }

    public int supersede(long oldId, long newId) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET status = 'SUPERSEDED', superseded_by_id = :newId, updated_at = now()
             WHERE import_request_id = :oldId AND status = 'ISSUED'
            """, new MapSqlParameterSource().addValue("oldId", oldId).addValue("newId", newId));
    }

    public int deleteDraft(long id) {
        return jdbc.update(
            "DELETE FROM sales.import_request WHERE import_request_id = :id AND status = 'DRAFT'",
            Map.of("id", id));
    }

    public int highestVersion(long ticketId, long factoryId) {
        Integer v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) FROM sales.import_request
             WHERE ticket_id = :id AND factory_id = :factoryId
            """, Map.of("id", ticketId, "factoryId", factoryId), Integer.class);
        return v == null ? 0 : v;
    }

    /** One (deal, factory)'s step fields, as read for carry-forward onto a revision's issue. */
    public record PredecessorStep(long id, String importStep, LocalDate importStepAt,
                                  Long importStepById, String importStepByName, String importStepNote,
                                  LocalDate firstIssuedDate) {}

    /**
     * Locks — {@code FOR UPDATE}, for the remainder of the caller's transaction — the currently
     * ISSUED row for this (deal, factory), if any, and returns its step fields to carry forward onto
     * a revision. The lock is what makes "read the predecessor's step, then supersede it, then issue
     * the replacement with the copied step" safe against a concurrent {@code advanceStep} on the same
     * predecessor row racing in between — without it, a step advance landing between the read and the
     * supersede would be silently lost (the same race V154's own {@code issue} Javadoc already
     * documents for the number-minting half of this same method, now also covering the step half).
     *
     * <p>Empty when this is the FIRST issue for this (deal, factory) — nothing to lock or copy, and
     * the caller sets {@code CONTACTED} instead.
     */
    public Optional<PredecessorStep> lockIssuedPredecessor(long ticketId, long factoryId) {
        return jdbc.query("""
            SELECT import_request_id, import_step, import_step_at, import_step_by_id,
                   import_step_by_name, import_step_note, first_issued_date
              FROM sales.import_request
             WHERE ticket_id = :ticketId AND factory_id = :factoryId AND status = 'ISSUED'
             FOR UPDATE
            """, Map.of("ticketId", ticketId, "factoryId", factoryId), rs -> {
                if (!rs.next()) {
                    return Optional.<PredecessorStep>empty();
                }
                return Optional.of(new PredecessorStep(
                    rs.getLong("import_request_id"), rs.getString("import_step"),
                    localDate(rs, "import_step_at"), (Long) rs.getObject("import_step_by_id"),
                    rs.getString("import_step_by_name"), rs.getString("import_step_note"),
                    localDate(rs, "first_issued_date")));
            });
    }

    /**
     * Compare-and-set step advance: only succeeds {@code WHERE status = 'ISSUED' AND import_step =
     * :expected}. 0 rows means either the row is not the live ISSUED version (SUPERSEDED/DRAFT —
     * advancing a superseded row must 409, not silently do nothing) or another caller already moved
     * the step past what this caller last saw — both are legitimate 409s, not successes, matching
     * every other compare-and-set in this class ({@link #issue}, {@link #supersede}).
     */
    public int advanceStep(long id, String expectedCurrentStep, String targetStep,
                           LocalDate eventDate, long actorId, String actorName, String note) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET import_step = :target, import_step_at = :eventDate,
                   import_step_by_id = :actorId, import_step_by_name = :actorName,
                   import_step_note = :note, updated_at = now()
             WHERE import_request_id = :id AND status = 'ISSUED' AND import_step = :expected
            """, new MapSqlParameterSource().addValue("id", id).addValue("target", targetStep)
                .addValue("eventDate", eventDate).addValue("actorId", actorId)
                .addValue("actorName", actorName).addValue("note", note)
                .addValue("expected", expectedCurrentStep));
    }

    /** Every ISSUED row for a deal — the rollup's raw input (V184). */
    public record IssuedFactoryRow(long id, long factoryId, String importStep) {}

    public List<IssuedFactoryRow> findIssuedByTicket(long ticketId) {
        return jdbc.query("""
            SELECT import_request_id, factory_id, import_step
              FROM sales.import_request
             WHERE ticket_id = :id AND status = 'ISSUED'
            """, Map.of("id", ticketId), (rs, n) -> new IssuedFactoryRow(
                rs.getLong("import_request_id"), rs.getLong("factory_id"), rs.getString("import_step")));
    }

    /**
     * Next {@code IR<yy><nnn>} for the Buddhist year, from the shared {@code sales.document_sequence}
     * V29 deliberately kept generic. Format mirrors the owner's own IR69068 = IR + 2569 + 068, and
     * the mechanism mirrors {@code DepositNoticeRepository.nextDocNumber} exactly.
     *
     * <p>Must be called INSIDE the issuing transaction so a refused issue rolls the number back
     * rather than burning it — the same property that method documents.
     */
    public String nextDocNumber(int yearTh) {
        jdbc.update("""
            INSERT INTO sales.document_sequence (doc_type, year_th, last_seq)
            VALUES ('IMPORT_REQUEST', :y, 0) ON CONFLICT DO NOTHING
            """, Map.of("y", yearTh));
        Integer seq = jdbc.queryForObject("""
            UPDATE sales.document_sequence SET last_seq = last_seq + 1
             WHERE doc_type = 'IMPORT_REQUEST' AND year_th = :y
            RETURNING last_seq
            """, Map.of("y", yearTh), Integer.class);
        return String.format("IR%02d%03d", yearTh % 100, seq);
    }

    public boolean docNumberExists(String docNumber) {
        Boolean found = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.import_request WHERE doc_number = :n)",
            Map.of("n", docNumber), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /**
     * Edits the drafted order-email (owner decision 09-18 #3 §B) — allowed while it has not yet been
     * marked sent. COALESCE semantics match {@link #updateFooter}: an absent field is left alone.
     *
     * <p><b>Cheap nit (REVIEW ROUND 2):</b> the {@code WHERE} clause now ALSO states {@code status =
     * 'ISSUED' AND email_sent_at IS NULL} — the same two conditions the service already checks
     * before calling this, restated here as a backstop rather than relied on solely at the service
     * layer, matching {@link #advanceStep}'s/{@link #issue}'s own compare-and-set discipline: a race
     * that marks the draft sent (or supersedes/re-drafts the row) between the service's check and
     * this write now loses 0 rows here instead of silently overwriting a draft that is no longer the
     * one the caller thought they were editing.
     */
    public int updateEmailDraft(long id, String emailTo, String emailSubject, String emailBody) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET email_to = COALESCE(:emailTo, email_to),
                   email_subject = COALESCE(:emailSubject, email_subject),
                   email_body = COALESCE(:emailBody, email_body),
                   updated_at = now()
             WHERE import_request_id = :id AND status = 'ISSUED' AND email_sent_at IS NULL
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("emailTo", emailTo).addValue("emailSubject", emailSubject)
                .addValue("emailBody", emailBody));
    }

    /**
     * Compare-and-set mark-sent: only succeeds {@code WHERE email_sent_at IS NULL}, so a second call
     * 409s (idempotent, per owner decision 09-18 #3 §B) rather than silently re-stamping who/when.
     *
     * <p><b>Cheap nit (REVIEW ROUND 2):</b> {@code AND status = 'ISSUED'} added alongside the
     * pre-existing {@code email_sent_at IS NULL} — the service already refuses a non-ISSUED row
     * before calling this, but restating it here means a row superseded by a concurrent revision
     * between that check and this write also loses 0 rows here rather than stamping "sent" onto a
     * row that is no longer the live version.
     */
    public int markEmailSent(long id, long actorId, String actorName) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET email_sent_at = now(), email_sent_by_id = :actorId, email_sent_by_name = :actorName,
                   updated_at = now()
             WHERE import_request_id = :id AND status = 'ISSUED' AND email_sent_at IS NULL
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("actorId", actorId).addValue("actorName", actorName));
    }

    public int setRequiredByNote(long ticketId, String note) {
        return jdbc.update(
            "UPDATE sales.ticket SET required_by_note = :note, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("id", ticketId).addValue("note", note));
    }
}
