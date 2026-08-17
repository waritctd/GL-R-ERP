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
        SELECT r.import_request_id, r.ticket_id, t.code AS ticket_code, r.brand, r.version, r.status,
               r.doc_number, r.issue_date, r.customer_name, r.project_name, r.requested_by_name,
               r.required_by_note, r.deposit_received_date, r.vessel_eta_note, r.checked_by_name,
               r.checked_date, r.approved_by_name, r.approved_date, r.created_by_id,
               r.created_by_name, r.issued_by_id, r.issued_by_name, r.superseded_by_id,
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
                   qty, unit, note
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
                        rs.getString("note")));
            });
        return rows.stream()
            .map(r -> withItems(r, byParent.getOrDefault(r.id(), List.of())))
            .toList();
    }

    private static ImportRequestDto withItems(ImportRequestDto r, List<ImportRequestItemDto> items) {
        return new ImportRequestDto(r.id(), r.ticketId(), r.ticketCode(), r.brand(), r.version(),
            r.status(), r.docNumber(), r.issueDate(), r.customerName(), r.projectName(),
            r.requestedByName(), r.requiredByNote(), r.depositReceivedDate(), r.vesselEtaNote(),
            r.checkedByName(), r.checkedDate(), r.approvedByName(), r.approvedDate(),
            r.createdById(), r.createdByName(), r.issuedById(), r.issuedByName(),
            r.supersededById(), r.createdAt(), r.updatedAt(), r.issuedAt(), 0, items);
    }

    private static ImportRequestDto mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ImportRequestDto(
            rs.getLong("import_request_id"), rs.getLong("ticket_id"), rs.getString("ticket_code"),
            rs.getString("brand"), rs.getInt("version"), rs.getString("status"),
            rs.getString("doc_number"), localDate(rs, "issue_date"),
            rs.getString("customer_name"), rs.getString("project_name"),
            rs.getString("requested_by_name"), rs.getString("required_by_note"),
            localDate(rs, "deposit_received_date"), rs.getString("vessel_eta_note"),
            rs.getString("checked_by_name"), localDate(rs, "checked_date"),
            rs.getString("approved_by_name"), localDate(rs, "approved_date"),
            (Long) rs.getObject("created_by_id"), rs.getString("created_by_name"),
            (Long) rs.getObject("issued_by_id"), rs.getString("issued_by_name"),
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

    public long insertDraft(long ticketId, String brand, int version,
                            ImportRequestQueryRepository.TicketSnapshot snap,
                            long actorId, String actorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, brand, version, status, customer_name, project_name,
                 requested_by_name, required_by_note, deposit_received_date,
                 created_by_id, created_by_name)
            VALUES (:ticketId, :brand, :version, 'DRAFT', :customer, :project,
                    :rep, :requiredBy, :depositDate, :actorId, :actorName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId).addValue("brand", brand).addValue("version", version)
                .addValue("customer", snap.customerName()).addValue("project", snap.projectName())
                .addValue("rep", snap.requestedByName())
                .addValue("requiredBy", snap.requiredByNote())
                .addValue("depositDate", snap.depositReceivedDate())
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
                    (import_request_id, ticket_item_id, seq, code, size, qty, unit, note)
                VALUES (:parent, :ticketItemId, :seq, :code, :size, :qty, :unit, :note)
                """,
                new MapSqlParameterSource()
                    .addValue("parent", importRequestId)
                    .addValue("ticketItemId", it.ticketItemId())
                    .addValue("seq", seq++)
                    .addValue("code", it.code()).addValue("size", it.size())
                    .addValue("qty", it.qty()).addValue("unit", it.unit())
                    .addValue("note", it.note()));
        }
    }

    /** Body fields — draft only; the WHERE clause enforces that, not the caller. */
    public int updateDraftBody(long id, String projectName, String customerName, String repName) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET project_name = COALESCE(:project, project_name),
                   customer_name = COALESCE(:customer, customer_name),
                   requested_by_name = COALESCE(:rep, requested_by_name),
                   updated_at = now()
             WHERE import_request_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("project", projectName).addValue("customer", customerName)
                .addValue("rep", repName));
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
     * Compare-and-set DRAFT → ISSUED. Returns rows affected; 0 means someone else issued or
     * superseded it first, and the caller must NOT treat that as success — the same guard
     * {@code DepositNoticeRepository.issue} uses, and for the same reason: a second UPDATE would
     * re-mint a number onto an already-issued controlled document.
     */
    public int issue(long id, String docNumber, LocalDate issueDate, long actorId, String actorName) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET status = 'ISSUED', doc_number = :num, issue_date = :issueDate,
                   issued_by_id = :actorId, issued_by_name = :actorName,
                   issued_at = now(), updated_at = now()
             WHERE import_request_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("num", docNumber)
                .addValue("issueDate", issueDate).addValue("actorId", actorId)
                .addValue("actorName", actorName));
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

    public int highestVersion(long ticketId, String brand) {
        Integer v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) FROM sales.import_request
             WHERE ticket_id = :id AND brand = :brand
            """, Map.of("id", ticketId, "brand", brand), Integer.class);
        return v == null ? 0 : v;
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

    public int setRequiredByNote(long ticketId, String note) {
        return jdbc.update(
            "UPDATE sales.ticket SET required_by_note = :note, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("id", ticketId).addValue("note", note));
    }
}
