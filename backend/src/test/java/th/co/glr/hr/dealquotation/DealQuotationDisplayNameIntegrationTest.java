package th.co.glr.hr.dealquotation;

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
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.commission.CommissionRepOptionDto;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * V179 (owner feedback #4, 2026-09-14) — the ผู้พิมพ์/พนักงานขาย print-name override. Real
 * Postgres, through the real {@link DealQuotationService}/{@link DealQuotationRepository}. See
 * that migration and {@code DealQuotationDtos}' Javadoc for the feature's shape.
 *
 * <p>Written wrong-way-round where it matters: {@link
 * #settingSalesRepDisplayId_doesNotChangeOwnershipAccessOrCommissionAttribution} asserts the real
 * {@code salesRepId} and the edit-access decision are UNCHANGED by the display override — this is
 * a print-only feature, not a re-assignment of the deal.
 */
class DealQuotationDisplayNameIntegrationTest extends AbstractPostgresIntegrationTest {
    private DealQuotationRepository quotationRepository;
    private DealQuotationService quotationService;

    private long salesDivisionRepId;   // active, sales division ("SA") — eligible by division
    private long grantHolderId;        // active, NOT sales division, can_create_quotation grant
    private long ineligibleId;         // active, no division match, no grant
    private long inactiveEligibleId;   // sales division, but is_active = FALSE
    private long otherSalesRepId;      // owns a DIFFERENT deal (wrong-way-round test)

    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal salesManagerActor;

    private long ticketId;

    @BeforeEach
    void wire() {
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        TicketService ticketService = new TicketService(tickets, notifications, new ObjectMapper(), customers,
            new QuotationRenderer(), null, new EmployeeAuthRepository(jdbc));
        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {}
        };
        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        quotationService = new DealQuotationService(quotationRepository, tickets, customers, contacts,
            notifications, new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");

        // Real sales-division code ("SA"), matching DivisionAccessPolicy.SALES_DIVISION_CODE —
        // NOT the "SALES" literal some older fixtures in this package use, which does not match.
        salesDivisionRepId = createEmployee(employees, "พนักงานขาย ดิสเพลย์", "sales-display@glr.co.th",
            "SA", "ฝ่ายขาย", null);
        otherSalesRepId = createEmployee(employees, "พนักงานขาย อื่นดิสเพลย์", "sales-other-display@glr.co.th",
            "SA", "ฝ่ายขาย", null);
        grantHolderId = createEmployee(employees, "คิวซี ดิสเพลย์", "qc-display@glr.co.th",
            "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", grantHolderId));
        ineligibleId = createEmployee(employees, "พนักงานทั่วไป ดิสเพลย์", "other-display@glr.co.th",
            "OTHER", "ฝ่ายอื่น", null);
        inactiveEligibleId = createEmployee(employees, "พนักงานขาย ปลดประจำการ", "inactive-display@glr.co.th",
            "SA", "ฝ่ายขาย", null);
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            Map.of("id", inactiveEligibleId));

        salesActor = actor(salesDivisionRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        salesManagerActor = actor(salesDivisionRepId, "sales_manager"); // role only, for the options-list gate

        CustomerDto customer = customers.create(
            "บริษัท V179 จำกัด", "0100000000179", "179 ถนนทดสอบ", "สนญ.", "02-179-0179");
        ProjectDto project = projects.create(customer.id(), "โครงการ V179");
        ContactDto contact = contacts.create(customer.id(), "สมหญิง", "ใจดี", "จัดซื้อ", "c@customer.test",
            "081-000-0179");
        ticketId = ticketService.create(new CreateTicketRequest("ดีล V179", "NORMAL", customer.name(),
            customer.id(), project.id(), contact.id(), null, null, null), salesActor).summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Repository — the eligible union itself
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void findEligibleOptions_includesSalesDivisionAndGrantHolder_excludesIneligibleAndInactive() {
        List<CommissionRepOptionDto> options =
            quotationRepository.findEligibleQuotationDisplayNameOptions(DivisionAccessPolicy.SALES_DIVISION_CODE);
        List<Long> ids = options.stream().map(CommissionRepOptionDto::id).toList();

        assertThat(ids).as("active sales-division employee is included").contains(salesDivisionRepId);
        assertThat(ids).as("active non-sales-division can_create_quotation grant holder is included")
            .contains(grantHolderId);
        assertThat(ids).as("active employee outside the sales division with no grant is excluded")
            .doesNotContain(ineligibleId);
        assertThat(ids).as("inactive employee is excluded even though their division would otherwise qualify")
            .doesNotContain(inactiveEligibleId);
    }

    @Test
    void isEligible_agreesWithTheOptionsList_forEveryCase() {
        String sa = DivisionAccessPolicy.SALES_DIVISION_CODE;
        assertThat(quotationRepository.isEligibleQuotationDisplayName(salesDivisionRepId, sa)).isTrue();
        assertThat(quotationRepository.isEligibleQuotationDisplayName(grantHolderId, sa)).isTrue();
        assertThat(quotationRepository.isEligibleQuotationDisplayName(ineligibleId, sa)).isFalse();
        assertThat(quotationRepository.isEligibleQuotationDisplayName(inactiveEligibleId, sa)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Service — create/update validation
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_withSalesDivisionDisplayId_succeedsAndRoundTrips() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(grantHolderId, salesDivisionRepId), salesActor);
        assertThat(created.printedByDisplayId()).isEqualTo(grantHolderId);
        assertThat(created.printedByDisplayName()).isEqualTo("คิวซี ดิสเพลย์");
        assertThat(created.salesRepDisplayId()).isEqualTo(salesDivisionRepId);
        assertThat(created.salesRepDisplayName()).isEqualTo("พนักงานขาย ดิสเพลย์");

        DealQuotationDto reread = quotationService.get(created.id(), salesActor);
        assertThat(reread.printedByDisplayId()).isEqualTo(grantHolderId);
        assertThat(reread.salesRepDisplayId()).isEqualTo(salesDivisionRepId);
    }

    @Test
    void create_withGrantHolderDisplayId_succeeds_evenThoughNotSalesDivision() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(grantHolderId, null), salesActor);
        assertThat(created.printedByDisplayId()).isEqualTo(grantHolderId);
        assertThat(created.salesRepDisplayId()).isNull();
    }

    @Test
    void create_withIneligibleActiveEmployeeDisplayId_isBadRequest() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(ineligibleId, null), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withInactiveEmployeeDisplayId_isBadRequest() {
        assertThatThrownBy(() -> quotationService.create(ticketId,
            upsertRequest(null, inactiveEligibleId), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_withValidDisplayIds_succeedsAndRoundTrips() {
        DealQuotationDto created = quotationService.create(ticketId, upsertRequest(null, null), salesActor);
        DealQuotationDto updated = quotationService.update(created.id(),
            upsertRequest(grantHolderId, salesDivisionRepId), salesActor);
        assertThat(updated.printedByDisplayId()).isEqualTo(grantHolderId);
        assertThat(updated.salesRepDisplayId()).isEqualTo(salesDivisionRepId);

        // Clearing back to null (real names) on a later save.
        DealQuotationDto cleared = quotationService.update(created.id(), upsertRequest(null, null), salesActor);
        assertThat(cleared.printedByDisplayId()).isNull();
        assertThat(cleared.salesRepDisplayId()).isNull();
    }

    @Test
    void update_withIneligibleDisplayId_isBadRequest() {
        DealQuotationDto created = quotationService.create(ticketId, upsertRequest(null, null), salesActor);
        assertThatThrownBy(() -> quotationService.update(created.id(),
            upsertRequest(ineligibleId, null), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_withInactiveDisplayId_isBadRequest() {
        DealQuotationDto created = quotationService.create(ticketId, upsertRequest(null, null), salesActor);
        assertThatThrownBy(() -> quotationService.update(created.id(),
            upsertRequest(null, inactiveEligibleId), salesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRevision_copiesBothDisplayIdsVerbatim() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(grantHolderId, salesDivisionRepId), salesActor);
        DealQuotationDto submitted = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(submitted.id(),
            new DealQuotationRequests.ApproveRequest(null), actor(salesDivisionRepId, "sales_manager"));

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.printedByDisplayId()).isEqualTo(grantHolderId);
        assertThat(revision.printedByDisplayName()).isEqualTo("คิวซี ดิสเพลย์");
        assertThat(revision.salesRepDisplayId()).isEqualTo(salesDivisionRepId);
        assertThat(revision.salesRepDisplayName()).isEqualTo("พนักงานขาย ดิสเพลย์");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round: this is PRINT-ONLY — ownership/access/commission are untouched
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The owner's explicit boundary: setting {@code salesRepDisplayId} must NOT change who owns
     * the deal (real {@code salesRepId}/{@code createdById}), and must NOT let the display-named
     * employee edit a deal they otherwise could not. {@code otherSalesActor} owns a DIFFERENT
     * ticket — being NAMED as the sales-rep display on {@code salesActor}'s quotation must not
     * grant them edit access to it.
     */
    @Test
    void settingSalesRepDisplayId_doesNotChangeOwnershipAccessOrCommissionAttribution() {
        DealQuotationDto created = quotationService.create(ticketId,
            upsertRequest(null, otherSalesRepId), salesActor);
        assertThat(created.salesRepDisplayId()).isEqualTo(otherSalesRepId);
        // The REAL owner/commission-driving fields are untouched.
        assertThat(created.salesRepId()).isEqualTo(salesDivisionRepId);
        assertThat(created.createdById()).isEqualTo(salesDivisionRepId);

        // otherSalesActor is now the DISPLAYED sales rep on this document, but does not own the
        // deal (ticket.createdBy is salesDivisionRepId) and holds no grant — requireEditAccess
        // must still refuse them.
        assertThatThrownBy(() -> quotationService.update(created.id(),
            upsertRequest(null, otherSalesRepId), otherSalesActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Options endpoint gate
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void findQuotationDisplayNameOptions_listsTheEligibleUnion_forAnEditRoleActor() {
        List<CommissionRepOptionDto> options = quotationService.findQuotationDisplayNameOptions(salesActor);
        List<Long> ids = options.stream().map(CommissionRepOptionDto::id).toList();
        assertThat(ids).contains(salesDivisionRepId, grantHolderId);
        assertThat(ids).doesNotContain(ineligibleId, inactiveEligibleId);
    }

    @Test
    void findQuotationDisplayNameOptions_salesManagerMayList() {
        List<CommissionRepOptionDto> options = quotationService.findQuotationDisplayNameOptions(salesManagerActor);
        assertThat(options.stream().map(CommissionRepOptionDto::id).toList())
            .contains(salesDivisionRepId, grantHolderId);
    }

    @Test
    void findQuotationDisplayNameOptions_grantHolderMayList_evenOutsideSalesRoles() {
        UserPrincipal grantActor = actor(grantHolderId, "qc");
        List<CommissionRepOptionDto> options = quotationService.findQuotationDisplayNameOptions(grantActor);
        assertThat(options).isNotEmpty();
    }

    @Test
    void findQuotationDisplayNameOptions_plainEmployee_isForbidden() {
        UserPrincipal plainActor = actor(ineligibleId, "employee");
        assertThatThrownBy(() -> quotationService.findQuotationDisplayNameOptions(plainActor))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private UpsertDealQuotationRequest upsertRequest(Long printedByDisplayId, Long salesRepDisplayId) {
        ItemInput item = new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            null, null, "หมายเหตุทดสอบ", null, null, null, printedByDisplayId, salesRepDisplayId, List.of(item));
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh, String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
