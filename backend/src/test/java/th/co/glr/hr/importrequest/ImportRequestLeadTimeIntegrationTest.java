package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Lead time / expected arrival (V184, owner decision 09-18 #2 — "they also need to automatically
 * fill estimations of how many days it'll take for the item to arrive like the direct quote
 * form") — against real Postgres, through the real service.
 *
 * <p>Autofill correctness is plumbing ({@link LeadTimeDefaults} is a pure, already-unit-testable
 * map), but WHERE it is applied (draft creation, carried across a revision) and WHO may change it
 * afterwards is authz-and-lifecycle-shaped, so per CLAUDE.md this needs real-DB evidence.
 */
class ImportRequestLeadTimeIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-leadtime-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("LT-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("LT-OTH"), "sales");
        importUser = principal(insertEmployee("LT-IMP"), "import");
        ceoUser = principal(insertEmployee("LT-CEO"), "ceo");
    }

    // ── autofill on DRAFT creation ────────────────────────────────────────────────────────────

    @Test
    void draftAutofillsLeadTimeFromTheFactorysCountry_it() {
        assertAutofill("IT", 75, 90);
    }

    @Test
    void draftAutofillsLeadTimeFromTheFactorysCountry_cn() {
        assertAutofill("CN", 30, 45);
    }

    @Test
    void draftAutofillsLeadTimeFromTheFactorysCountry_th() {
        assertAutofill("TH", 3, 7);
    }

    /** 'ZZ' (อื่นๆ) and any other unmapped code get NO default — the rep must type it in. */
    @Test
    void draftLeavesLeadTimeNull_forZzAndUnmappedCodes() {
        long zzFactory = insertFactory("ZZ Factory", "ZZ", "Farawaystan");
        long unmappedFactory = insertFactory("Unmapped Factory", "PL", null);

        long ticketZz = insertTicket("LT-ZZ");
        insertItem(ticketZz, "ZZ Factory", "Line", 0);
        ImportRequestDto zzDraft = service.createDrafts(ticketZz, null, owner).get(0);
        assertThat(zzDraft.leadTimeMinDays()).isNull();
        assertThat(zzDraft.leadTimeMaxDays()).isNull();

        long ticketOther = insertTicket("LT-PL");
        insertItem(ticketOther, "Unmapped Factory", "Line", 0);
        ImportRequestDto otherDraft = service.createDrafts(ticketOther, null, owner).get(0);
        assertThat(otherDraft.leadTimeMinDays()).isNull();
        assertThat(otherDraft.leadTimeMaxDays()).isNull();

        assertThat(zzFactory).isNotEqualTo(unmappedFactory); // sanity: two distinct rows used
    }

    private void assertAutofill(String isoCode, int expectedMin, int expectedMax) {
        insertFactory(isoCode + " Factory", isoCode, null);
        long ticketId = insertTicket("LT-" + isoCode);
        insertItem(ticketId, isoCode + " Factory", "Line", 0);

        ImportRequestDto draft = service.createDrafts(ticketId, null, owner).get(0);
        assertThat(draft.leadTimeMinDays()).isEqualTo(expectedMin);
        assertThat(draft.leadTimeMaxDays()).isEqualTo(expectedMax);
    }

    // ── issue refuses while unset ─────────────────────────────────────────────────────────────

    @Test
    void issue_isRefused_whileLeadTimeIsUnset() {
        insertFactory("No Default Factory", "PL", null);
        long ticketId = insertTicket("LT-ISSUE-NULL");
        insertItem(ticketId, "No Default Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Setting it (owning rep, DRAFT) clears the refusal.
        service.update(id, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, 10, 20, null), owner);
        assertThat(service.issue(id, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    // ── editable while DRAFT (owning rep/CEO), and after ISSUE (import/CEO) ──────────────────────

    @Test
    void draftLeadTime_isEditableByTheOwningRepOrCeo_notByANonOwningRepOrImport() {
        insertFactory("Editable Draft Factory", "PL", null);
        long ticketId = insertTicket("LT-DRAFT-EDIT");
        insertItem(ticketId, "Editable Draft Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        // Owning rep: allowed.
        ImportRequestDto afterOwner = service.update(id, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, 15, 25, null), owner);
        assertThat(afterOwner.leadTimeMinDays()).isEqualTo(15);
        assertThat(afterOwner.leadTimeMaxDays()).isEqualTo(25);

        // Non-owning rep: refused (wrong-way-round — this is the case that matters).
        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, 1, 2, null), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Import: refused on the DRAFT-stage route — import edits lead time only AFTER issue.
        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, 1, 2, null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Value untouched by either refused attempt.
        assertThat(service.get(id, owner).leadTimeMinDays()).isEqualTo(15);
    }

    @Test
    void issuedLeadTime_isEditableByImportOrCeo_notBySales() {
        insertFactory("Editable Issued Factory", "IT", null); // autofilled 75-90, satisfies issue gate
        long ticketId = insertTicket("LT-ISSUED-EDIT");
        insertItem(ticketId, "Editable Issued Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        // Import: allowed.
        ImportRequestDto afterImport = service.setLeadTime(id, new SetLeadTimeRequest(20, 40), importUser);
        assertThat(afterImport.leadTimeMinDays()).isEqualTo(20);
        assertThat(afterImport.leadTimeMaxDays()).isEqualTo(40);

        // CEO: allowed.
        ImportRequestDto afterCeo = service.setLeadTime(id, new SetLeadTimeRequest(21, 41), ceoUser);
        assertThat(afterCeo.leadTimeMinDays()).isEqualTo(21);

        // Sales (even the owning rep): refused — post-issue lead time is import/CEO's job now.
        assertThatThrownBy(() -> service.setLeadTime(id, new SetLeadTimeRequest(1, 2), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(id, owner).leadTimeMinDays()).isEqualTo(21);
    }

    // ── bad range ─────────────────────────────────────────────────────────────────────────────

    @Test
    void setLeadTime_refusesAMinGreaterThanMax() {
        insertFactory("Bad Range Factory", "IT", null);
        long ticketId = insertTicket("LT-BADRANGE");
        insertItem(ticketId, "Bad Range Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);

        assertThatThrownBy(() -> service.setLeadTime(id, new SetLeadTimeRequest(50, 10), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void draftUpdate_refusesOneFieldWithoutTheOther() {
        insertFactory("Partial Field Factory", "IT", null);
        long ticketId = insertTicket("LT-PARTIAL");
        insertItem(ticketId, "Partial Field Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, 30, null, null), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── revision carries the values forward ──────────────────────────────────────────────────────

    @Test
    void revision_carriesLeadTimeForward_notReautofilled() {
        insertFactory("Revision Factory", "IT", null); // would autofill 75-90
        long ticketId = insertTicket("LT-REVISE");
        insertItem(ticketId, "Revision Factory", "Line", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        // Import/CEO hand-refine the estimate AFTER issue.
        service.issue(v1, null, owner);
        service.setLeadTime(v1, new SetLeadTimeRequest(60, 65), importUser);

        ImportRequestDto v2 = service.revise(v1, owner);
        // NOT re-autofilled to 75-90 -- carried forward from the predecessor's actual value.
        assertThat(v2.leadTimeMinDays()).isEqualTo(60);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(65);
    }

    // ── vesselEtaNote re-derivation (REVIEW ROUND 2, S-D/S-E) ────────────────────────────────────

    /**
     * S-D: {@code setLeadTime} moves {@code expectedArrivalFrom}/{@code To} (derived from
     * firstIssuedDate+leadTime), so a {@code vesselEtaNote} that is still exactly the AUTO-DERIVED
     * text for the OLD range must move with it, and so must the unsent order-email draft's body
     * (it quotes the same window). A CEO-typed CUSTOM note is left exactly alone.
     */
    @Test
    void setLeadTime_reDerivesTheAutoVesselEtaNote_andRegeneratesTheUnsentEmailBody() {
        insertFactory("ETA Redrive Factory", "IT", null); // autofills 75-90
        long ticketId = insertTicket("LT-ETA-REDERIVE");
        insertItem(ticketId, "ETA Redrive Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        ImportRequestDto issued = service.issue(id, null, owner);
        String autoNoteFor75to90 = issued.vesselEtaNote();
        assertThat(autoNoteFor75to90).isNotBlank();
        assertThat(issued.emailBody()).contains(
            issued.issueDate().plusDays(75).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));

        ImportRequestDto afterLeadTime = service.setLeadTime(id, new SetLeadTimeRequest(10, 20), importUser);
        assertThat(afterLeadTime.vesselEtaNote())
            .isNotEqualTo(autoNoteFor75to90)
            .contains("10").contains("20");
        assertThat(afterLeadTime.emailBody()).contains(
            afterLeadTime.issueDate().plusDays(10).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
            .doesNotContain(
                issued.issueDate().plusDays(75).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    }

    @Test
    void setLeadTime_neverOverwritesACeoTypedCustomVesselEtaNote() {
        insertFactory("ETA Custom Factory", "IT", null);
        long ticketId = insertTicket("LT-ETA-CUSTOM");
        insertItem(ticketId, "ETA Custom Factory", "Line", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(id, null, owner);
        ImportRequestDto ceoTyped = service.update(id, new UpdateImportRequestRequest(
            null, null, null, "Vessel confirmed by phone, mid-December", null, null, null, null,
            null, null, null, null), ceoUser);
        assertThat(ceoTyped.vesselEtaNote()).isEqualTo("Vessel confirmed by phone, mid-December");

        ImportRequestDto afterLeadTime = service.setLeadTime(id, new SetLeadTimeRequest(10, 20), importUser);
        assertThat(afterLeadTime.vesselEtaNote()).isEqualTo("Vessel confirmed by phone, mid-December");
    }

    /**
     * S-E: {@code revise} carries the predecessor's {@code vesselEtaNote} forward; its own
     * {@code issue} re-derives it only when it is STILL exactly the auto-derived text for this
     * draft's own (carried-forward, unedited) range — otherwise (a CEO-typed custom note) it is left
     * exactly alone across the revision.
     */
    @Test
    void revision_carriesVesselEtaNoteForward_andReDerivesOnlyIfStillAutoDerived() {
        insertFactory("ETA Revise Factory", "IT", null); // autofills 75-90
        long ticketId = insertTicket("LT-ETA-REVISE");
        insertItem(ticketId, "ETA Revise Factory", "Line", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        ImportRequestDto issuedV1 = service.issue(v1, null, owner);
        assertThat(issuedV1.vesselEtaNote()).isNotBlank();

        ImportRequestDto v2 = service.revise(v1, owner);
        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);
        // Same anchor (firstIssuedDate carried forward) and same range -- re-derivation reproduces
        // the identical text, so the note stays consistent with issuedV2's own derived window.
        assertThat(issuedV2.vesselEtaNote()).isEqualTo(issuedV1.vesselEtaNote());
        assertThat(issuedV2.firstIssuedDate()).isEqualTo(issuedV1.firstIssuedDate());
    }

    /**
     * REVIEW ROUND 3, item 1 (S-E regression, confirmed against real Postgres): {@code revise}
     * carries v1's auto-derived "(75–90 วัน)" note forward onto v2's draft unchanged; the OWNING REP
     * then edits v2's OWN lead time to 60–70 via {@code update} (a case never exercised by the
     * existing S-E tests above, which only revise WITHOUT touching lead time afterwards). v2's issue
     * must show a 60–70 estimate, not the stale 75–90 text carried in from v1 — the bug was that
     * {@link ImportRequestService#issue}'s old exact-string comparison ("does the stored note equal
     * the derivation for THIS draft's CURRENT range") never matched a note that was auto-derived for
     * a DIFFERENT (predecessor) range, so it was wrongly treated as a CEO-typed custom note and left
     * stale.
     */
    @Test
    void revision_thenEditingLeadTime_thenIssue_showsTheNewRange_notThePredecessors() {
        insertFactory("ETA Revise Then Edit Factory", "IT", null); // autofills 75-90
        long ticketId = insertTicket("LT-ETA-REVISE-EDIT");
        insertItem(ticketId, "ETA Revise Then Edit Factory", "Line", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        ImportRequestDto issuedV1 = service.issue(v1, null, owner);
        assertThat(issuedV1.vesselEtaNote()).contains("75").contains("90");

        ImportRequestDto v2 = service.revise(v1, owner);
        // Sanity: the note WAS carried forward from v1, unchanged, before the edit below.
        assertThat(v2.vesselEtaNote()).isEqualTo(issuedV1.vesselEtaNote());

        service.update(v2.id(), new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, 60, 70, null), owner);

        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);
        assertThat(issuedV2.vesselEtaNote())
            .contains("60").contains("70")
            .doesNotContain("75").doesNotContain("90");
        assertThat(issuedV2.expectedArrivalFrom())
            .isEqualTo(issuedV2.firstIssuedDate().plusDays(60));
        assertThat(issuedV2.expectedArrivalTo())
            .isEqualTo(issuedV2.firstIssuedDate().plusDays(70));
        // The unsent order-email draft must not disagree with the printed form either.
        assertThat(issuedV2.emailBody()).contains("60").doesNotContain("Expected arrival: approx. "
            + issuedV1.firstIssuedDate().plusDays(75)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    }

    @Test
    void revision_carriesACeoTypedCustomVesselEtaNoteForward_untouched() {
        insertFactory("ETA Revise Custom Factory", "IT", null);
        long ticketId = insertTicket("LT-ETA-REVISE-CUSTOM");
        insertItem(ticketId, "ETA Revise Custom Factory", "Line", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(v1, null, owner);
        service.update(v1, new UpdateImportRequestRequest(
            null, null, null, "Confirmed by phone, ETA mid-Jan", null, null, null, null,
            null, null, null, null), ceoUser);

        ImportRequestDto v2 = service.revise(v1, owner);
        ImportRequestDto issuedV2 = service.issue(v2.id(), null, owner);
        assertThat(issuedV2.vesselEtaNote()).isEqualTo("Confirmed by phone, ETA mid-Jan");
    }

    // ── expected arrival derivation ───────────────────────────────────────────────────────────

    @Test
    void expectedArrival_isDerivedFromIssueDatePlusLeadTime_andNullUntilIssued() {
        insertFactory("Arrival Factory", "IT", null);
        long ticketId = insertTicket("LT-ARRIVAL");
        insertItem(ticketId, "Arrival Factory", "Line", 0);
        ImportRequestDto draft = service.createDrafts(ticketId, null, owner).get(0);

        // Null while DRAFT — no issueDate yet even though lead time is already autofilled.
        assertThat(draft.expectedArrivalFrom()).isNull();
        assertThat(draft.expectedArrivalTo()).isNull();

        ImportRequestDto issued = service.issue(draft.id(), null, owner);
        assertThat(issued.expectedArrivalFrom()).isEqualTo(issued.issueDate().plusDays(75));
        assertThat(issued.expectedArrivalTo()).isEqualTo(issued.issueDate().plusDays(90));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-lt@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name, String country, String countryOther) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, country_other, default_currency)
            VALUES (:name, :country, :countryOther, 'EUR') RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country)
                .addValue("countryOther", countryOther), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบ lead time', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String factory, String model, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, '60x60', 10, 'pcs', :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", "Brand")
                .addValue("model", model).addValue("sort", sortOrder).addValue("factory", factory));
    }
}
