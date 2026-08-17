package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;

/**
 * The STORED ใบขอซื้อ lifecycle, against real Postgres through the real service and repository.
 *
 * <p>These are WRITES — unlike the preview endpoints, which are read-only — so per CLAUDE.md they
 * need real-DB authz evidence, written wrong-way-round. Mockito cannot reach any of this: a mocked
 * repository would pass happily while the {@code UPDATE} and the two partial unique indexes did
 * something else.
 *
 * <p>What the lifecycle has to get right, and what each is pinned by:
 * <ul>
 *   <li>one form per brand, drafts not duplicated — {@link #createDrafts_isOnePerBrand_andSkipsBrandsAlreadyCovered}
 *   <li>a number minted only at issue, never on a draft — {@link #issue_mintsTheNumberAndFreezesTheDate}
 *   <li>an override that does NOT advance the sequence — {@link #issue_withAnOverride_doesNotBurnASequenceValue}
 *   <li>the previous version stays ISSUED until its replacement issues — {@link #revise_leavesThePreviousVersionIssuedUntilTheReplacementIsIssued}
 *   <li>an issued form's body is frozen — {@link #issuedForm_bodyIsFrozen_butTheImportFooterStillMoves}
 * </ul>
 */
class StoredImportRequestIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private ImportRequestRepository stored;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;

    private long ticketId;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        stored = new ImportRequestRepository(jdbc);
        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored);

        ownerId = insertEmployee("STORED-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("STORED-OTH"), "sales");
        importUser = principal(insertEmployee("STORED-IMP"), "import");
        ceoUser = principal(insertEmployee("STORED-CEO"), "ceo");
        accountUser = principal(insertEmployee("STORED-ACC"), "account");

        ticketId = insertTicket("IRSTORE-1", "บริษัท ยู่ฮุย อินทีเรีย จำกัด");
        insertItem(ticketId, "Padana", "Lithos Nero Nat", "60x60 cm", "308", "pcs", 0);
        insertItem(ticketId, "Padana", "Terrazzo White Nat", "30x60 cm", "2370", "pcs", 1);
        insertItem(ticketId, "LEA", "Ceppo Grigio", "60x120 cm", "44", "pcs", 2);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    @Test
    void createDrafts_isOnePerBrand_andSkipsBrandsAlreadyCovered() {
        List<ImportRequestDto> first = service.createDrafts(ticketId, importUser);
        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(ImportRequestStatus.DRAFT);
            // chk_import_request_issued_fields: a DRAFT carries neither.
            assertThat(r.docNumber()).isNull();
            assertThat(r.issueDate()).isNull();
        });
        assertThat(first).extracting(ImportRequestDto::brand)
            .containsExactlyInAnyOrder("Padana", "LEA");
        // The Padana form carries BOTH Padana lines and neither LEA line.
        assertThat(byBrand(first, "Padana").items()).extracting(i -> i.code())
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");

        // Calling again must not duplicate. Every brand is covered, so it refuses rather than
        // reporting success having created nothing.
        assertThatThrownBy(() -> service.createDrafts(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(service.list(ticketId, importUser)).hasSize(2);
    }

    @Test
    void issue_mintsTheNumberAndFreezesTheDate() {
        long id = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();

        ImportRequestDto issued = service.issue(id, null, importUser);

        assertThat(issued.status()).isEqualTo(ImportRequestStatus.ISSUED);
        // IR + Buddhist year + 3-digit sequence, the owner's own IR69068 convention.
        assertThat(issued.docNumber()).matches("IR\\d{2}\\d{3}");
        assertThat(issued.issueDate()).isNotNull();
        assertThat(issued.issuedByName()).isNotNull();

        // Issuing twice is a conflict, not a second number on the same controlled document.
        assertThatThrownBy(() -> service.issue(id, null, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The owner asked for the number to be overridable. The property worth pinning is the one that is
     * easy to get wrong: an override must NOT advance the shared sequence, or a hand-typed number
     * would silently consume a value and the next minted form would skip it.
     */
    @Test
    void issue_withAnOverride_doesNotBurnASequenceValue() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, importUser);
        int seqBefore = sequenceValue();

        service.issue(byBrand(drafts, "Padana").id(),
            new IssueImportRequestRequest("IR69068"), importUser);
        assertThat(sequenceValue()).isEqualTo(seqBefore);

        // ...and the next MINTED number is unaffected by the override having happened.
        ImportRequestDto minted = service.issue(byBrand(drafts, "LEA").id(), null, importUser);
        assertThat(minted.docNumber()).isNotEqualTo("IR69068");
        assertThat(sequenceValue()).isEqualTo(seqBefore + 1);
    }

    @Test
    void issue_withAnAlreadyUsedOverride_isRefused() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, importUser);
        service.issue(byBrand(drafts, "Padana").id(),
            new IssueImportRequestRequest("IR69068"), importUser);

        assertThatThrownBy(() -> service.issue(byBrand(drafts, "LEA").id(),
                new IssueImportRequestRequest("IR69068"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The reason V154 uses TWO partial unique indexes instead of one over "not superseded". A
     * correction is prepared while the previous version is still the live request — the factory may
     * already be acting on it — and only loses that status when the replacement is actually issued.
     */
    @Test
    void revise_leavesThePreviousVersionIssuedUntilTheReplacementIsIssued() {
        long v1 = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();
        service.issue(v1, null, importUser);

        ImportRequestDto v2 = service.revise(v1, importUser);
        assertThat(v2.status()).isEqualTo(ImportRequestStatus.DRAFT);
        assertThat(v2.version()).isEqualTo(2);
        // The body was copied from what was actually sent, not re-read from the deal.
        assertThat(v2.items()).extracting(i -> i.code())
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");
        // v1 is STILL the live issued form at this point. This is the assertion the single-index
        // design made impossible to satisfy.
        assertThat(service.get(v1, importUser).status()).isEqualTo(ImportRequestStatus.ISSUED);

        service.issue(v2.id(), null, importUser);

        ImportRequestDto superseded = service.get(v1, importUser);
        assertThat(superseded.status()).isEqualTo(ImportRequestStatus.SUPERSEDED);
        // Named, not merely flagged — a reader of the archived PDF can find its replacement.
        assertThat(superseded.supersededById()).isEqualTo(v2.id());
    }

    @Test
    void issuedForm_bodyIsFrozen_butTheImportFooterStillMoves() {
        long id = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();
        service.issue(id, null, importUser);

        // Body: refused. An issued controlled document does not get edited.
        assertThatThrownBy(() -> service.update(id,
                new UpdateImportRequestRequest("แก้โครงการ", null, null, null, null, null, null, null, null),
                importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Footer: allowed, because those blocks are filled in BY HAND after the form is raised.
        ImportRequestDto after = service.update(id, new UpdateImportRequestRequest(
            null, null, null, "ETA ปลายเดือน", "ธันย์ยพร", LocalDate.of(2026, 3, 7), null, null, null),
            importUser);
        assertThat(after.vesselEtaNote()).isEqualTo("ETA ปลายเดือน");
        assertThat(after.checkedByName()).isEqualTo("ธันย์ยพร");
    }

    @Test
    void deleteDraft_removesADraftButNeverAnIssuedForm() {
        List<ImportRequestDto> drafts = service.createDrafts(ticketId, importUser);
        long lea = byBrand(drafts, "LEA").id();
        long padana = byBrand(drafts, "Padana").id();
        service.issue(padana, null, importUser);

        service.deleteDraft(lea, importUser);
        assertThat(service.list(ticketId, importUser)).extracting(ImportRequestDto::id)
            .containsExactly(padana);

        assertThatThrownBy(() -> service.deleteDraft(padana, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aStoredFormRendersFromItsOwnSnapshot_notFromTheDeal() throws Exception {
        long id = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();
        service.issue(id, new IssueImportRequestRequest("IR69068"), importUser);

        // The deal changes AFTER the form was issued...
        jdbc.update("UPDATE sales.ticket SET customer_name = 'ลูกค้าใหม่' WHERE ticket_id = :id",
            Map.of("id", ticketId));

        String text = textOf(service.renderStored(id, importUser));
        assertThat(text).contains("IR69068").contains("Lithos Nero Nat");
        // ...and the issued document still says what it said when it was signed.
        assertThat(text).contains("ยู่ฮุย").doesNotContain("ลูกค้าใหม่");
    }

    // ── authorisation, asked the wrong way round ─────────────────────────────────────────────

    @Test
    void salesCannotRaiseOrIssueAnImportRequest() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();

        long id = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();
        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(id, importUser).status()).isEqualTo(ImportRequestStatus.DRAFT);
    }

    @Test
    void accountCannotRaiseAnImportRequest() {
        assertThatThrownBy(() -> service.createDrafts(ticketId, accountUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stored.findByTicket(ticketId)).isEmpty();
    }

    @Test
    void ceoCanRaiseAndIssue() {
        long id = byBrand(service.createDrafts(ticketId, ceoUser), "Padana").id();
        assertThat(service.issue(id, null, ceoUser).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    // ── กำหนดวันที่ต้องการของ — SALES's field, a DIFFERENT gate ────────────────────────────────

    @Test
    void requiredByNote_isSetByTheOwningRep_andSnapshottedOntoTheFormAtIssue() {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", DealStage.ORDER_RECEIVED).addValue("id", ticketId));

        service.setRequiredByNote(ticketId, new SetRequiredByNoteRequest("Within 21/5/26"), owner);

        long id = byBrand(service.createDrafts(ticketId, importUser), "Padana").id();
        assertThat(service.get(id, importUser).requiredByNote()).isEqualTo("Within 21/5/26");
    }

    /** Import is refused this one DELIBERATELY: it is Sales's field, not import's. */
    @Test
    void requiredByNote_isRefusedToImport_andToANonOwningRep() {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", DealStage.ORDER_RECEIVED).addValue("id", ticketId));

        assertThatThrownBy(() -> service.setRequiredByNote(
                ticketId, new SetRequiredByNoteRequest("x"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.setRequiredByNote(
                ticketId, new SetRequiredByNoteRequest("x"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(requiredByNoteOnDeal()).isNull();
    }

    /** "when the order is already confirmed" — the owner's own wording for the floor. */
    @Test
    void requiredByNote_isRefusedBeforeTheOrderIsConfirmed() {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", DealStage.NEGOTIATION).addValue("id", ticketId));

        assertThatThrownBy(() -> service.setRequiredByNote(
                ticketId, new SetRequiredByNoteRequest("Within 21/5/26"), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(requiredByNoteOnDeal()).isNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static ImportRequestDto byBrand(List<ImportRequestDto> rows, String brand) {
        return rows.stream().filter(r -> brand.equals(r.brand())).findFirst().orElseThrow();
    }

    private int sequenceValue() {
        Integer v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(last_seq), 0) FROM sales.document_sequence
             WHERE doc_type = 'IMPORT_REQUEST'
            """, Map.of(), Integer.class);
        return v == null ? 0 : v;
    }

    private String requiredByNoteOnDeal() {
        return jdbc.queryForObject("SELECT required_by_note FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stored@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertTicket(String code, String customerName) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name)
            VALUES (:code, 'ทดสอบใบขอซื้อ', :by, :customer)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("customer", customerName), Long.class);
    }

    private void insertItem(long ticket, String brand, String model, String size, String qty,
                            String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder));
    }
}
