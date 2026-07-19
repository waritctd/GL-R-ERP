package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestService.CancelOpenForTicketResult;

class TicketServiceTest {

    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifRepo = mock(NotificationRepository.class);
    private final PriceCalcService priceCalcService = mock(PriceCalcService.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final QuotationRenderer quotationRenderer = new QuotationRenderer();
    private final PricingRequestService pricingRequestService = mock(PricingRequestService.class);
    {
        // Default stub so every markLost/cancel call in this file (most of which
        // don't care about the cascade's own outcome) doesn't NPE on
        // cancelOpenForTicket's now-non-primitive return value — Mockito's default
        // answer for an unstubbed reference-typed method is null, and TicketService
        // calls .hasAbandoned() on the result unconditionally. Tests that DO care
        // about an abandoned row override this per-test.
        when(pricingRequestService.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new CancelOpenForTicketResult(0, List.of()));
    }
    private final TicketService service = new TicketService(
        ticketRepo, notifRepo, priceCalcService, new ObjectMapper(), customerRepo, quotationRenderer,
        pricingRequestService);

    private final UserPrincipal salesActor   = actor(1L, "sales");
    private final UserPrincipal otherSales   = actor(2L, "sales");
    private final UserPrincipal importActor  = actor(3L, "import");
    private final UserPrincipal ceoActor     = actor(4L, "ceo");
    private final UserPrincipal accountActor = actor(5L, "account");
    private final UserPrincipal hrActor      = actor(6L, "hr");
    private final UserPrincipal employeeActor = actor(7L, "employee");
    // sales_manager: read + comment oversight only — a project-manager-style
    // follow-up role for the sales team. Never owns a ticket (cannot create one),
    // so every owner-gated write action denies it via the ownership check alone.
    private final UserPrincipal salesManagerActor = actor(8L, "sales_manager");

    // ── list ──────────────────────────────────────────────────────────────

    @Test
    void list_salesActorFiltersToOwnTickets() {
        service.list(null, salesActor);
        verify(ticketRepo).findSummaries(null, 1L);
    }

    @Test
    void list_nonSalesActorSeesAllTickets() {
        service.list(null, importActor);
        verify(ticketRepo).findSummaries(null, null);
    }

    // ── get ───────────────────────────────────────────────────────────────

    @Test
    void get_salesCanViewOwnTicket() {
        TicketDto ticket = stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertThat(service.get(10L, salesActor)).isEqualTo(ticket);
    }

    @Test
    void get_salesCannotViewOthersTicket() {
        stubTicket(10L, 99L, TicketStatus.DRAFT);
        assertForbidden(() -> service.get(10L, salesActor));
    }

    @Test
    void get_importCanViewAnyTicket() {
        TicketDto ticket = stubTicket(10L, 99L, TicketStatus.SUBMITTED);
        assertThat(service.get(10L, importActor)).isEqualTo(ticket);
    }

    // ── read authz (viewer roles) ─────────────────────────────────────────

    @Test
    void list_rejectsHrAndEmployeeRoles() {
        // Tickets carry customer pricing — only sales/import/ceo/account may read.
        assertForbidden(() -> service.list(null, hrActor));
        assertForbidden(() -> service.list(null, employeeActor));
    }

    @Test
    void get_rejectsHrAndEmployeeRoles() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.get(10L, hrActor));
        assertForbidden(() -> service.get(10L, employeeActor));
    }

    @Test
    void get_accountRoleCanViewAnyTicket() {
        TicketDto ticket = stubTicket(10L, 1L, TicketStatus.QUOTATION_ISSUED);
        assertThat(service.get(10L, accountActor)).isEqualTo(ticket);
    }

    @Test
    void comment_rejectsRolesAndNonOwnersWithoutReadAccess() {
        // comment() returns the full TicketDto — it must not be a side door around
        // get()'s scoping (previously any authenticated user could pull any ticket).
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), hrActor));
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), employeeActor));
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), otherSales));
    }

    @Test
    void quotationFile_rejectsRolesWithoutReadAccess() {
        stubTicket(10L, 1L, TicketStatus.QUOTATION_ISSUED);
        assertForbidden(() -> service.getQuotationXlsx(10L, 1L, hrActor));
        assertForbidden(() -> service.getQuotationPdf(10L, 1L, employeeActor));
    }

    // ── quotation file downloads: issue-time snapshot vs legacy fallback (V49) ──

    @Test
    void getQuotationXlsx_rendersFromSnapshotNotLiveEditedItems() throws Exception {
        // The ticket's LIVE item/customer data has since been edited (a revision after
        // this quotation was issued) — the render must reflect what was true AT ISSUE
        // TIME (the snapshot), not these live values.
        TicketItemDto liveEditedItem = new TicketItemDto(1L, 10L, "EditedBrand", "EditedModel", null, null,
            null, null, new BigDecimal("9"), null, null, null, null, null,
            new BigDecimal("999.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Live Edited Name", null, null, "Live Edited Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            "QUOTE_BUYER", null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        QuotationDto quotation = quotationOf(1L, 10L, "QT-2026-0001");
        TicketDto ticket = new TicketDto(summary, List.of(liveEditedItem), List.of(), quotation, List.of(quotation));
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(ticket));

        TicketItemDto snapshotItem = new TicketItemDto(500L, 10L, "IssueTimeBrand", "IssueTimeModel", null, null,
            null, null, new BigDecimal("2"), null, null, null, "pcs", null,
            new BigDecimal("100.00"), null, 1, null, null, null, "PIECE", null, null);
        when(ticketRepo.findQuotationItemsByQuotationId(1L, 10L)).thenReturn(List.of(snapshotItem));
        when(ticketRepo.findQuotationHeaderSnapshot(1L)).thenReturn(Optional.of(
            new TicketRepository.QuotationHeaderSnapshot("Issue-Time Customer", "Issue-Time Address",
                "1111111111111", "02-999-9999", "Issue-Time Project")));

        byte[] xlsx = service.getQuotationXlsx(10L, 1L, salesActor);

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String itemDesc = sheet.getRow(7).getCell(1).getStringCellValue(); // ITEM_START_ROW = 7
            assertThat(itemDesc).contains("IssueTimeBrand");
            assertThat(itemDesc).doesNotContain("EditedBrand");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()) // B5: customer name
                .isEqualTo("Issue-Time Customer");
        }
    }

    @Test
    void getQuotationPdf_rendersFromSnapshotNotLiveEditedItems() throws Exception {
        TicketItemDto liveEditedItem = new TicketItemDto(1L, 10L, "EditedBrand", "EditedModel", null, null,
            null, null, new BigDecimal("9"), null, null, null, null, null,
            new BigDecimal("999.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Live Edited Name", null, null, "Live Edited Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            "QUOTE_BUYER", null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        QuotationDto quotation = quotationOf(1L, 10L, "QT-2026-0001");
        TicketDto ticket = new TicketDto(summary, List.of(liveEditedItem), List.of(), quotation, List.of(quotation));
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(ticket));

        TicketItemDto snapshotItem = new TicketItemDto(500L, 10L, "IssueTimeBrand", "IssueTimeModel", null, null,
            null, null, new BigDecimal("2"), null, null, null, "pcs", null,
            new BigDecimal("100.00"), null, 1, null, null, null, "PIECE", null, null);
        when(ticketRepo.findQuotationItemsByQuotationId(1L, 10L)).thenReturn(List.of(snapshotItem));
        when(ticketRepo.findQuotationHeaderSnapshot(1L)).thenReturn(Optional.of(
            new TicketRepository.QuotationHeaderSnapshot("Issue-Time Customer", "Issue-Time Address",
                "1111111111111", "02-999-9999", "Issue-Time Project")));

        byte[] pdf = service.getQuotationPdf(10L, 1L, salesActor);

        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("IssueTimeBrand IssueTimeModel");
            assertThat(text).doesNotContain("EditedBrand");
            assertThat(text).contains("เรียน Issue-Time Customer");
            assertThat(text).contains("Project : Issue-Time Project");
        }
    }

    @Test
    void getQuotationXlsx_legacyFallback_rendersLiveDataWhenNoSnapshotRows() throws Exception {
        // Pre-V49 quotation: no quotation_item rows and no header snapshot. Neither
        // findQuotationItemsByQuotationId nor findQuotationHeaderSnapshot is stubbed here —
        // Mockito's default answer returns an empty List / Optional.empty(), exactly what
        // the repository would return for a real pre-V49 row, so the service must fall
        // back to live ticket data rather than rendering a blank document.
        TicketItemDto liveItem = new TicketItemDto(1L, 10L, "LiveBrand", "LiveModel", null, null,
            null, null, new BigDecimal("3"), null, null, null, null, null,
            new BigDecimal("50.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.QUOTATION_ISSUED, "NORMAL",
            1L, "Sales User", null, null, "Live Customer Name", null, null, "Live Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            "QUOTE_BUYER", null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        QuotationDto quotation = quotationOf(2L, 10L, "QT-2026-0002");
        TicketDto ticket = new TicketDto(summary, List.of(liveItem), List.of(), quotation, List.of(quotation));
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(ticket));

        byte[] xlsx = service.getQuotationXlsx(10L, 2L, salesActor);

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            assertThat(sheet.getRow(7).getCell(1).getStringCellValue()).contains("LiveBrand");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("Live Customer Name");
        }
    }

    // ── factory email gate ────────────────────────────────────────────────

    @Test
    void factoryEmail_allowsImportOnExistingTicket() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        service.assertFactoryEmailAllowed(10L, importActor); // must not throw
    }

    @Test
    void factoryEmail_rejectsNonImportRoles() {
        // Previously session-only — an authenticated open mail relay.
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, salesActor));
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, hrActor));
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, employeeActor));
    }

    @Test
    void factoryEmail_rejectsNonExistentTicket() {
        assertThatThrownBy(() -> service.assertFactoryEmailAllowed(99L, importActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── submit (deprecated — superseded by the PricingRequest aggregate) ───

    @Test
    void submit_isDeprecatedAndAlwaysConflicts() {
        // Ticket-level submit() is retired: pricing now starts on a PricingRequest
        // (POST /api/tickets/{id}/pricing-requests + /pricing-requests/{id}/submit).
        // This must 409 unconditionally — regardless of status, role, or ownership —
        // and must not touch the repository or notifications at all.
        stubTicketWithItems(10L, 1L, TicketStatus.DRAFT, List.of(sampleItem()));

        assertConflict(() -> service.submit(10L, salesActor));
        assertConflict(() -> service.submit(10L, otherSales));
        assertConflict(() -> service.submit(10L, importActor));

        verify(ticketRepo, never()).addEvent(anyLong(), anyLong(), anyString(), anyString(), any(), any(), any());
        verify(notifRepo, never()).notifyByRole(any(), anyLong(), any(), any());
    }

    // ── pickup ────────────────────────────────────────────────────────────

    @Test
    void pickup_submittedByImport_transitionsToInReview() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);

        service.pickup(10L, importActor);

        verify(ticketRepo).addEvent(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.PICKED_UP), eq(TicketStatus.SUBMITTED), eq(TicketStatus.IN_REVIEW), isNull());
    }

    @Test
    void pickup_rejectsSalesRole() {
        assertForbidden(() -> service.pickup(10L, salesActor));
    }

    @Test
    void pickup_rejectsCeoRole() {
        assertForbidden(() -> service.pickup(10L, ceoActor));
    }

    @Test
    void pickup_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertConflict(() -> service.pickup(10L, importActor));
    }

    // ── proposePrice ──────────────────────────────────────────────────────

    @Test
    void proposePrice_inReviewByImport_transitionsToPriceProposed() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        ProposePriceRequest req = new ProposePriceRequest(List.of(), "ราคาจาก supplier");

        service.proposePrice(10L, req, importActor);

        verify(ticketRepo).addEventWithSnapshot(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.PRICE_PROPOSED), eq(TicketStatus.IN_REVIEW), eq(TicketStatus.PRICE_PROPOSED),
            eq("ราคาจาก supplier"), anyString());
        verify(notifRepo).notifyByRole(eq("ceo"), eq(10L), anyString(), anyString());
    }

    @Test
    void proposePrice_rejectsCeoRole() {
        assertForbidden(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), ceoActor));
    }

    @Test
    void proposePrice_rejectsSalesRole() {
        assertForbidden(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), salesActor));
    }

    @Test
    void proposePrice_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertConflict(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), importActor));
    }

    // ── approve ───────────────────────────────────────────────────────────

    @Test
    void approve_priceProposedByCeo_transitionsToApproved() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);

        service.approve(10L, ceoActor);

        verify(ticketRepo).approveItemPrices(10L);
        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.APPROVED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.APPROVED), isNull());
        verify(notifRepo).notifyEmployee(eq(1L), eq(10L), anyString(), anyString());
    }

    @Test
    void approve_rejectsSalesRole() {
        assertForbidden(() -> service.approve(10L, salesActor));
    }

    @Test
    void approve_rejectsImportRole() {
        assertForbidden(() -> service.approve(10L, importActor));
    }

    @Test
    void approve_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        assertConflict(() -> service.approve(10L, ceoActor));
    }

    // ── reject (reject loop) ──────────────────────────────────────────────

    @Test
    void reject_priceProposedByCeo_returnsToInReview() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);

        service.reject(10L, new RejectRequest("ราคาสูงเกิน"), ceoActor);

        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.REJECTED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.IN_REVIEW), eq("ราคาสูงเกิน"));
        verify(notifRepo).notifyByRole(eq("import"), eq(10L), anyString(), anyString());
    }

    @Test
    void reject_rejectsSalesRole() {
        assertForbidden(() -> service.reject(10L, new RejectRequest("reason"), salesActor));
    }

    @Test
    void reject_rejectsImportRole() {
        assertForbidden(() -> service.reject(10L, new RejectRequest("reason"), importActor));
    }

    @Test
    void reject_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.APPROVED);
        assertConflict(() -> service.reject(10L, new RejectRequest("reason"), ceoActor));
    }

    // ── generateQuotation ─────────────────────────────────────────────────

    @Test
    void generateQuotation_approvedByOwner_createsQuotationAndTransitions() {
        TicketItemDto item = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(item));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0001");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0001"), eq(1L), eq(new BigDecimal("200.00")),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(99L, 10L, "QT-2026-0001"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0001"), eq(1L), eq(new BigDecimal("200.00")),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull());
        verify(ticketRepo).addEventWithDocument(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.QUOTATION_ISSUED), eq(TicketStatus.APPROVED), eq(TicketStatus.QUOTATION_ISSUED),
            anyString(), eq(RelatedDocumentType.QUOTATION), anyLong());
    }

    @Test
    void generateQuotation_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, TicketStatus.APPROVED);
        assertForbidden(() -> service.generateQuotation(10L, designerQuotation(), otherSales));
    }

    @Test
    void generateQuotation_rejectsCeoRole() {
        assertForbidden(() -> service.generateQuotation(10L, designerQuotation(), ceoActor));
    }

    @Test
    void generateQuotation_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        assertConflict(() -> service.generateQuotation(10L, designerQuotation(), salesActor));
    }

    @Test
    void generateQuotation_allowsReissueFromQuotationIssued() {
        stubTicketWithItems(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of());
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0003");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0003"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(100L, 10L, "QT-2026-0003"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0003"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull());
        verify(ticketRepo).addEventWithDocument(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), anyString(),
            eq(RelatedDocumentType.QUOTATION), anyLong());
    }

    @Test
    void generateQuotation_beforeSpecApproved_doesNotRequireSpecStage() {
        TicketItemDto item = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("1"), null, null, null, null, null,
            new BigDecimal("120.00"), "THB", 0, null, null, null, "PIECE", null, null);
        stubDeal(10L, 1L, TicketStatus.APPROVED, List.of(item), null, null, DealStage.PRESENTATION, null);
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0009");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0009"), eq(1L), eq(new BigDecimal("120.00")),
            eq(QuotationRecipient.OWNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(103L, 10L, "QT-2026-0009",
                QuotationRecipient.OWNER, 1, QuotationStatus.ISSUED));

        service.generateQuotation(10L, new GenerateQuotationRequest(
            QuotationRecipient.OWNER, null, null, null, null, null, null), salesActor);

        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0009"), eq(1L), eq(new BigDecimal("120.00")),
            eq(QuotationRecipient.OWNER), isNull(), isNull(), isNull(), isNull(), isNull());
        verify(ticketRepo).updateSalesStage(10L, DealStage.QUOTE_DESIGN_SIDE);
    }

    @Test
    void generateQuotation_sumsMultipleItemsIncludingFractionalQuantities() {
        TicketItemDto item1 = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto item2 = new TicketItemDto(2L, 10L, "PC002", "Product B", null, null,
            "pcs", null, new BigDecimal("3"), null, null, null, null, null,
            new BigDecimal("50.00"), "THB", 1, null, null, null, "PIECE", null, null);
        TicketItemDto item3 = new TicketItemDto(3L, 10L, "PC003", "Product C", null, null,
            "sqm", null, new BigDecimal("1.5"), null, null, null, null, null,
            new BigDecimal("10.00"), "THB", 2, null, null, null, "SQM", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(item1, item2, item3));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0004");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0004"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(101L, 10L, "QT-2026-0004"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        // (2 x 100.00) + (3 x 50.00) + (1.5 x 10.00) = 200.00 + 150.00 + 15.00 = 365.00
        // (BigDecimal.equals() is scale-sensitive, so use isEqualByComparingTo rather than eq().)
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0004"), eq(1L), total.capture(),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(total.getValue()).isEqualByComparingTo(new BigDecimal("365.00"));
    }

    @Test
    void generateQuotation_treatsUnpricedItemAsZeroNotError() {
        TicketItemDto priced = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto unpriced = new TicketItemDto(2L, 10L, "PC002", "Product B", null, null,
            "pcs", null, new BigDecimal("5"), null, null, null, null, null,
            null, "THB", 1, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(priced, unpriced));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0005");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0005"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(102L, 10L, "QT-2026-0005"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        // The unpriced item (approvedPrice = null) contributes 0 regardless of its quantity, rather
        // than throwing or being skipped from the total silently in a way that hides its qty.
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0005"), eq(1L), total.capture(),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(total.getValue()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    // ── generateQuotation: issue-time snapshot (V49) ──────────────────────

    @Test
    void generateQuotation_snapshotsOnlyPricedItemsAndCustomerHeaderInSameCall() {
        TicketItemDto priced = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto unpriced = new TicketItemDto(2L, 10L, "PC002", "Product B", null, null,
            "pcs", null, new BigDecimal("5"), null, null, null, null, null,
            null, "THB", 1, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(priced, unpriced));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0006");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0006"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(200L, 10L, "QT-2026-0006"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        // insertQuotationItems is handed the FULL item list (priced + unpriced) — the
        // repository itself is responsible for filtering to approvedPrice != null, mirroring
        // how the total-amount calculation above already treats unpriced items as excluded.
        ArgumentCaptor<List<TicketItemDto>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).insertQuotationItems(eq(200L), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).containsExactly(priced, unpriced);

        // No customerId on this ticket (stubTicket leaves it null) — falls back to the
        // ticket's free-text customerName, with no address/tax/phone to snapshot.
        verify(ticketRepo).updateQuotationHeader(eq(200L), eq("Test Customer"),
            isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void generateQuotation_snapshotsCustomerHeaderFromCustomerRepositoryWhenLinked() {
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.APPROVED, "NORMAL",
            1L, "Sales User", null, null, "Free-text Name", 55L, null, "Renovation Project",
            null, null, null, Instant.now(), Instant.now(), null, 0, false, null, null,
            "QUOTE_DESIGN_SIDE", null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        TicketDto ticket = new TicketDto(summary, List.of(), List.of(), null, List.of());
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(ticket));
        when(customerRepo.findById(55L)).thenReturn(Optional.of(
            new CustomerDto(55L, "Real Customer Co., Ltd.", "0105500000000",
                "123 Real Address", "สำนักงานใหญ่", "02-000-0000")));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0007");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0007"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(201L, 10L, "QT-2026-0007"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        // Fidelity rule (Opus review): the frozen NAME is the ticket's display name —
        // that's what toXlsx/toPdf have always printed — so the snapshot must capture
        // it, not the master record's name. Address/taxId/phone DO come from the master
        // record because that's what the live render pulls from CustomerDto.
        verify(ticketRepo).updateQuotationHeader(eq(201L), eq("Free-text Name"),
            eq("123 Real Address"), eq("0105500000000"), eq("02-000-0000"), eq("Renovation Project"));
    }

    @Test
    void generateQuotation_blankTicketNameFallsBackToMasterCustomerName() {
        TicketSummaryDto summary = new TicketSummaryDto(
            10L, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.APPROVED, "NORMAL",
            1L, "Sales User", null, null, null, 55L, null, null,
            null, null, null, Instant.now(), Instant.now(), null, 0, false, null, null,
            "QUOTE_DESIGN_SIDE", null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(
            new TicketDto(summary, List.of(), List.of(), null, List.of())));
        when(customerRepo.findById(55L)).thenReturn(Optional.of(
            new CustomerDto(55L, "Real Customer Co., Ltd.", "0105500000000",
                "123 Real Address", "สำนักงานใหญ่", "02-000-0000")));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0008");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0008"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(202L, 10L, "QT-2026-0008"));

        service.generateQuotation(10L, designerQuotation(), salesActor);

        verify(ticketRepo).updateQuotationHeader(eq(202L), eq("Real Customer Co., Ltd."),
            eq("123 Real Address"), eq("0105500000000"), eq("02-000-0000"), isNull());
    }

    @Test
    void generateQuotation_requiresAmendmentReasonAfterAcceptedVersionOrCustomerConfirmed() {
        QuotationDto accepted = quotationOf(300L, 10L, "QT-2026-0300",
            QuotationRecipient.DESIGNER, 1, QuotationStatus.ACCEPTED);
        stubDealWithQuotations(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.QUOTE_DESIGN_SIDE, DealLifecycle.ACTIVE, List.of(accepted));

        assertBadRequest(() -> service.generateQuotation(10L, designerQuotation(), salesActor));

        GenerateQuotationRequest amendment = new GenerateQuotationRequest(
            QuotationRecipient.DESIGNER, null, null, null, null, null, "แก้ตามแบบล่าสุด");
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0301");
        when(ticketRepo.createQuotation(eq(10L), eq("QT-2026-0301"), eq(1L), any(BigDecimal.class),
            eq(QuotationRecipient.DESIGNER), isNull(), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(quotationOf(301L, 10L, "QT-2026-0301",
                QuotationRecipient.DESIGNER, 2, QuotationStatus.ISSUED));

        service.generateQuotation(10L, amendment, salesActor);

        verify(ticketRepo).addEventWithDocument(eq(10L), eq(1L), anyString(), eq(TicketEventKind.QUOTATION_ISSUED),
            eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED),
            org.mockito.ArgumentMatchers.contains("แก้ตามแบบล่าสุด"),
            eq(RelatedDocumentType.QUOTATION), anyLong());

        stubDealWithQuotations(11L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "CUSTOMER_CONFIRMED", null, DealStage.NEGOTIATION, DealLifecycle.ACTIVE, List.of());
        assertBadRequest(() -> service.generateQuotation(11L, designerQuotation(), salesActor));
    }

    @Test
    void markQuotationLifecycle_validatesLegalTransitionAuthAndLifecycle() {
        QuotationDto issued = quotationOf(400L, 10L, "QT-2026-0400",
            QuotationRecipient.OWNER, 1, QuotationStatus.ISSUED);
        stubDealWithQuotations(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.OWNER_SIGNOFF, DealLifecycle.ACTIVE, List.of(issued));

        service.markQuotationSent(10L, 400L, "ส่งให้เจ้าของแล้ว", salesActor);
        service.markQuotationAccepted(10L, 400L, null, ceoActor);
        service.markQuotationRejected(10L, 400L, "ลูกค้าไม่รับ", salesActor);

        verify(ticketRepo).markQuotationStatus(10L, 400L, QuotationStatus.SENT);
        verify(ticketRepo).markQuotationStatus(10L, 400L, QuotationStatus.ACCEPTED);
        verify(ticketRepo).markQuotationStatus(10L, 400L, QuotationStatus.REJECTED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.QUOTATION_SENT), eq(TicketStatus.QUOTATION_ISSUED),
            eq(TicketStatus.QUOTATION_ISSUED), org.mockito.ArgumentMatchers.contains("OWNER"));

        assertForbidden(() -> service.markQuotationAccepted(10L, 400L, null, otherSales));
        assertForbidden(() -> service.markQuotationAccepted(10L, 400L, null, salesManagerActor));

        QuotationDto accepted = quotationOf(401L, 11L, "QT-2026-0401",
            QuotationRecipient.OWNER, 1, QuotationStatus.ACCEPTED);
        stubDealWithQuotations(11L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.OWNER_SIGNOFF, DealLifecycle.ACTIVE, List.of(accepted));
        assertConflict(() -> service.markQuotationRejected(11L, 401L, null, salesActor));

        stubDealWithQuotations(12L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.OWNER_SIGNOFF, DealLifecycle.ON_HOLD, List.of(issued));
        assertConflict(() -> service.markQuotationSent(12L, 400L, null, salesActor));
    }

    // ── close ─────────────────────────────────────────────────────────────

    @Test
    void close_isNoLongerAvailableToSales() {
        // A deal counts as closed only when goods are delivered, the balance is paid,
        // the invoice is on file, ฝ่ายบัญชี has confirmed and the CEO has verified.
        // The sales owner is not part of that sequence any more.
        stubReadyToConfirm(10L);
        assertForbidden(() -> service.confirmCloseReady(10L, salesActor));
        assertForbidden(() -> service.verifyClose(10L, salesActor));
        stubAwaitingCeo(11L);
        assertForbidden(() -> service.verifyClose(11L, salesActor));
    }

    @Test
    void confirmCloseReady_byAccount_recordsConfirmationWithoutClosing() {
        stubReadyToConfirm(10L);

        service.confirmCloseReady(10L, accountActor);

        verify(ticketRepo).confirmClose(10L, accountActor.id());
        verify(ticketRepo).addEvent(eq(10L), eq(accountActor.id()), anyString(),
            eq(TicketEventKind.CLOSE_CONFIRMED), anyString(), anyString(), anyString());
        // Confirmation alone must not complete the deal.
        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void confirmCloseReady_refusedWithoutInvoiceOnFile() {
        // The invoice is produced externally and uploaded; without it there is nothing
        // to verify against.
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, null, false);

        assertConflict(() -> service.confirmCloseReady(10L, accountActor));

        verify(ticketRepo, never()).confirmClose(anyLong(), anyLong());
    }

    @Test
    void confirmCloseReady_refusedWhenGoodsNotDelivered() {
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.GOODS_RECEIVED, null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    @Test
    void verifyClose_byCeoAfterAccountConfirmation_completesTheDeal() {
        stubAwaitingCeo(10L);

        service.verifyClose(10L, ceoActor);

        verify(ticketRepo).addEvent(eq(10L), eq(ceoActor.id()), anyString(),
            eq(TicketEventKind.CLOSED), eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.CLOSED),
            anyString());
        verify(ticketRepo).updateLifecycle(10L, DealLifecycle.COMPLETED);
    }

    @Test
    void verifyClose_refusedBeforeAccountConfirms() {
        stubReadyToConfirm(10L);
        assertConflict(() -> service.verifyClose(10L, ceoActor));
        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void ceoCannotSignBothHalves() {
        // Two signatures means two people. ACCOUNT_ROLES includes ceo as a money
        // fallback; CLOSE_CONFIRM_ROLES deliberately does not, or the CEO could
        // confirm and verify alone.
        stubReadyToConfirm(10L);
        assertForbidden(() -> service.confirmCloseReady(10L, ceoActor));
    }

    @Test
    void verifyClose_reChecksPrerequisitesSoAStaleConfirmationCannotSlipThrough() {
        // Confirmed, then the deal regressed (refund / returned delivery) before the
        // CEO acted. The CEO verifies, never overrides.
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT",
            FulfilmentStatus.FULLY_DELIVERED, Instant.now(), true);

        assertConflict(() -> service.verifyClose(10L, ceoActor));

        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void revokeCloseConfirmation_clearsTheCeoQueue() {
        stubAwaitingCeo(10L);

        service.revokeCloseConfirmation(10L, "ลูกค้าขอคืนสินค้า", accountActor);

        verify(ticketRepo).clearCloseConfirmation(10L);
        verify(ticketRepo).addEvent(eq(10L), eq(accountActor.id()), anyString(),
            eq(TicketEventKind.CLOSE_CONFIRM_REVOKED), anyString(), anyString(), anyString());
    }

    @Test
    void confirmCloseReady_rejectsWrongStatus() {
        stubCloseDeal(10L, TicketStatus.APPROVED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    // ── dual-track lifecycle (payment + fulfillment) ──────────────────────

    @Test
    void confirmCustomer_quotationIssuedBySales_setsCustomerConfirmed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, null, null);

        service.confirmCustomer(10L, salesActor);

        verify(ticketRepo).updatePaymentStatus(10L, "CUSTOMER_CONFIRMED");
    }

    @Test
    void confirmCustomer_refusesDowngradeWhenPaymentTrackAdvanced() {
        // Re-confirming after the deposit was paid must not reset the payment track —
        // a reset re-arms the deadlock orderings.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);
        assertConflict(() -> service.confirmCustomer(10L, salesActor));
    }

    @Test
    void confirmCustomer_rejectsNonOwnerSales() {
        // Dual-track transitions had role checks but no ownership checks — any sales
        // rep could advance a colleague's payment track.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, null, null);
        assertForbidden(() -> service.confirmCustomer(10L, otherSales));
    }

    // (issueDepositNotice endpoint removed — DepositNoticeService.issue() is now the
    //  single action that advances the payment track to DEPOSIT_NOTICE_ISSUED.)

    @Test
    void close_legacyDocumentIssuedWithNullPayment_stillClosesViaTheTwoSignatures() {
        // Pre-dual-track tickets (paymentStatus never set) predate the delivery and
        // invoice tracks, so those prerequisites are waived — but they still need
        // both signatures. Waiving them entirely would strand this old data.
        stubCloseDeal(10L, TicketStatus.DOCUMENT_ISSUED, null, null, null, false);
        service.confirmCloseReady(10L, accountActor);
        verify(ticketRepo).confirmClose(10L, accountActor.id());

        stubCloseDeal(10L, TicketStatus.DOCUMENT_ISSUED, null, null, Instant.now(), false);
        service.verifyClose(10L, ceoActor);
        verify(ticketRepo).updateLifecycle(10L, DealLifecycle.COMPLETED);
    }

    @Test
    void close_legacyDocumentIssuedMidPaymentTrack_isRefused() {
        // The bypass from the audit: a mid-track ticket flipped to document_issued
        // must not close unpaid.
        stubCloseDeal(10L, TicketStatus.DOCUMENT_ISSUED, "DEPOSIT_NOTICE_ISSUED", null, null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));

        stubCloseDeal(10L, TicketStatus.DOCUMENT_ISSUED, "DEPOSIT_PAID", "SHIPPING", null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    @Test
    void confirmDepositPaid_byAccount_advancesToDepositPaid() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(BigDecimal.ZERO, new BigDecimal("500.00"));

        service.confirmDepositPaid(10L, accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("DEPOSIT"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(5L), isNull(), eq("ยืนยันรับมัดจำ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
        // Fulfillment hasn't reached GOODS_RECEIVED — no early advance.
        verify(ticketRepo, never()).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
    }

    @Test
    void confirmDepositPaid_byCeoFallback_isAllowed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(BigDecimal.ZERO, new BigDecimal("500.00"));
        service.confirmDepositPaid(10L, ceoActor);
        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("DEPOSIT"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(4L), isNull(), eq("ยืนยันรับมัดจำ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
    }

    @Test
    void confirmDepositPaid_rejectsSalesRole() {
        // Money-receipt confirmations moved from sales to ฝ่ายบัญชี (account role).
        assertForbidden(() -> service.confirmDepositPaid(10L, salesActor));
    }

    @Test
    void confirmDepositPaid_rejectsImportRole() {
        assertForbidden(() -> service.confirmDepositPaid(10L, importActor));
    }

    @Test
    void confirmDepositPaid_afterGoodsReceived_advancesToAwaitingFinalPayment() {
        // Goods-first ordering (deadlock B): goods arrived while the deposit was
        // unconfirmed; confirming the deposit must carry payment forward, otherwise
        // AWAITING_FINAL_PAYMENT is unreachable and the ticket can never close.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED,
            "DEPOSIT_NOTICE_ISSUED", "GOODS_RECEIVED");
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(BigDecimal.ZERO, new BigDecimal("500.00"));

        service.confirmDepositPaid(10L, accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("DEPOSIT"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(5L), isNull(), eq("ยืนยันรับมัดจำ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
        verify(ticketRepo).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
        verify(ticketRepo).addEvent(eq(10L), eq(5L), anyString(),
            eq(TicketEventKind.AWAITING_FINAL_PAYMENT), anyString(), anyString(), isNull());
    }

    @Test
    void confirmDepositPaid_rejectsWrongPaymentStatus() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", null);
        assertConflict(() -> service.confirmDepositPaid(10L, accountActor));
    }

    @Test
    void issueImportRequest_fromDepositNoticeIssued_startsFulfillment() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);

        service.issueImportRequest(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_ISSUED");
    }

    @Test
    void issueImportRequest_fromDepositPaid_isAlsoAllowed() {
        // Deposit-first ordering (deadlock A): the customer often pays before import
        // gets to the IR — that must not lock the fulfillment track out forever.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);

        service.issueImportRequest(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_ISSUED");
    }

    @Test
    void issueImportRequest_rejectsReissueOnceFulfillmentStarted() {
        // Re-issuing would downgrade an in-flight fulfillment track back to IR_ISSUED.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        assertConflict(() -> service.issueImportRequest(10L, importActor));
    }

    @Test
    void issueImportRequest_rejectsWhenNoDepositNoticeYet() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", null);
        assertConflict(() -> service.issueImportRequest(10L, importActor));
    }

    @Test
    void issueImportRequest_rejectsSalesRole() {
        assertForbidden(() -> service.issueImportRequest(10L, salesActor));
    }

    @Test
    void markIrSent_thenShipping_walksFulfillmentForward() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "IR_ISSUED");
        service.markIrSent(10L, importActor);
        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_SENT");

        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "IR_SENT");
        service.markShipping(10L, importActor);
        verify(ticketRepo).updateFulfillmentStatus(10L, "SHIPPING");
    }

    @Test
    void markGoodsReceived_withDepositPaid_advancesBothTracks() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");

        service.markGoodsReceived(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "GOODS_RECEIVED");
        verify(ticketRepo).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
    }

    @Test
    void markGoodsReceived_withDepositUnconfirmed_advancesFulfillmentOnly() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", "SHIPPING");

        service.markGoodsReceived(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "GOODS_RECEIVED");
        verify(ticketRepo, never()).updatePaymentStatus(eq(10L), anyString());
    }

    @Test
    void reserveStock_fullCoverage_skipsIrAndStartsDeliveryScheduling() {
        TicketItemDto item = deliveryItem(1L, "100.00", "0.00", "0.00");
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(item),
            "DEPOSIT_PAID", null, DealStage.ORDER_RECEIVED, null);

        service.reserveStock(10L, new StockReservationRequest(List.of(
            new StockReservationRequest.Line(1L, new BigDecimal("100.00"), "พร้อมจากสต็อก"))), importActor);

        verify(ticketRepo).reserveStock(eq(10L), argThat(lines ->
            lines.size() == 1 && lines.get(0).qtyFromStock().compareTo(new BigDecimal("100.00")) == 0));
        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.FROM_STOCK);
        // Full stock coverage has no import journey — goods are ready now, so the
        // deal jumps past PROCUREMENT straight to DELIVERY_SCHEDULING (S18).
        verify(ticketRepo).updateSalesStage(10L, DealStage.DELIVERY_SCHEDULING);
        verify(ticketRepo, never()).updateSalesStage(10L, DealStage.PROCUREMENT);
        verify(ticketRepo).addEvent(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.STOCK_RESERVED), anyString(), anyString(), anyString());
    }

    @Test
    void reserveStock_rejectsQuantityAboveOrdered() {
        TicketItemDto item = deliveryItem(1L, "100.00", "0.00", "0.00");
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(item),
            "DEPOSIT_PAID", null, DealStage.ORDER_RECEIVED, null);

        assertBadRequest(() -> service.reserveStock(10L, new StockReservationRequest(List.of(
            new StockReservationRequest.Line(1L, new BigDecimal("101.00"), null))), importActor));
    }

    @Test
    void recordPartialDelivery_updatesLineProgressAndStatus() {
        TicketItemDto initialItem = deliveryItem(1L, "100.00", "0.00", "0.00");
        TicketDto initial = stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(initialItem),
            "FULLY_PAID", FulfilmentStatus.GOODS_RECEIVED, DealStage.PROCUREMENT, null);
        TicketDto updated = ticketLike(initial, List.of(deliveryItem(1L, "100.00", "40.00", "0.00")),
            FulfilmentStatus.GOODS_RECEIVED);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(initial), Optional.of(updated), Optional.of(updated));

        service.recordPartialDelivery(10L, new RecordDeliveryRequest("WAREHOUSE", "ส่งบางส่วน",
            List.of(new RecordDeliveryRequest.Line(1L, new BigDecimal("40.00")))), importActor);

        verify(ticketRepo).insertDeliveryRecord(eq(10L), eq("WAREHOUSE"), eq(3L), eq("ส่งบางส่วน"),
            argThat(lines -> lines.size() == 1 && lines.get(0).qty().compareTo(new BigDecimal("40.00")) == 0));
        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.PARTIALLY_DELIVERED);
        verify(ticketRepo).addEventWithDocument(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.DELIVERY_RECORDED), anyString(), anyString(), argThat(msg -> msg.contains("40/100")),
            eq(RelatedDocumentType.DELIVERY_RECORD), anyLong());
    }

    @Test
    void recordPartialDelivery_remainingQuantityCompletesDeliveryAndStage() {
        TicketItemDto initialItem = deliveryItem(1L, "100.00", "40.00", "0.00");
        TicketDto initial = stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(initialItem),
            "FULLY_PAID", FulfilmentStatus.PARTIALLY_DELIVERED, DealStage.PROCUREMENT, null);
        TicketDto updated = ticketLike(initial, List.of(deliveryItem(1L, "100.00", "100.00", "0.00")),
            FulfilmentStatus.PARTIALLY_DELIVERED);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(initial), Optional.of(updated), Optional.of(updated));
        // Warehouse availability now keys off the permanent GOODS_RECEIVED event, not a
        // prior delivery-record source (see warehouseDeliveryAvailable — Case 8 fix).
        when(ticketRepo.hasReceivedGoods(10L)).thenReturn(true);

        service.recordPartialDelivery(10L, new RecordDeliveryRequest("WAREHOUSE", "ส่งส่วนที่เหลือ",
            List.of(new RecordDeliveryRequest.Line(1L, new BigDecimal("60.00")))), importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.FULLY_DELIVERED);
        verify(ticketRepo).updateSalesStage(10L, DealStage.DELIVERED);
        verify(ticketRepo).addEventWithDocument(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.DELIVERY_COMPLETED), anyString(), anyString(), anyString(),
            eq(RelatedDocumentType.DELIVERY_RECORD), anyLong());
    }

    @Test
    void recordPartialDelivery_rejectsOverDeliveryAndWrongRoles() {
        TicketItemDto item = deliveryItem(1L, "100.00", "40.00", "0.00");
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(item),
            "FULLY_PAID", FulfilmentStatus.GOODS_RECEIVED, DealStage.PROCUREMENT, null);

        RecordDeliveryRequest over = new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(1L, new BigDecimal("70.00"))));
        assertConflict(() -> service.recordPartialDelivery(10L, over, importActor));
        assertForbidden(() -> service.recordPartialDelivery(10L, over, salesActor));
        assertForbidden(() -> service.recordPartialDelivery(10L, over, accountActor));
        assertForbidden(() -> service.recordPartialDelivery(10L, over, salesManagerActor));
    }

    @Test
    void recordPartialDelivery_rejectsInactiveDeal() {
        TicketItemDto item = deliveryItem(1L, "100.00", "0.00", "0.00");
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(item),
            "FULLY_PAID", FulfilmentStatus.GOODS_RECEIVED, DealStage.PROCUREMENT, null,
            DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);

        assertConflict(() -> service.recordPartialDelivery(10L, new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(1L, new BigDecimal("10.00")))), importActor));
    }

    @Test
    void completeDelivery_deliversRemainingFromStockWhenCovered() {
        TicketItemDto initialItem = deliveryItem(1L, "100.00", "40.00", "100.00");
        TicketDto initial = stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(initialItem),
            "FULLY_PAID", FulfilmentStatus.FROM_STOCK, DealStage.PROCUREMENT, null);
        TicketDto updated = ticketLike(initial, List.of(deliveryItem(1L, "100.00", "100.00", "100.00")),
            FulfilmentStatus.FROM_STOCK);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(initial), Optional.of(updated), Optional.of(updated));

        service.completeDelivery(10L, new CompleteDeliveryRequest("ส่งครบจากสต็อก"), importActor);

        verify(ticketRepo).insertDeliveryRecord(eq(10L), eq("STOCK"), eq(3L), eq("ส่งครบจากสต็อก"),
            argThat(lines -> lines.size() == 1 && lines.get(0).qty().compareTo(new BigDecimal("60.00")) == 0));
        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.FULLY_DELIVERED);
    }

    @Test
    void recordPartialDelivery_stockFirstThenWarehouseRemainder_isAllowed() {
        // Case 8 ordering regression: 40 from stock delivered first flips status to
        // PARTIALLY_DELIVERED; the imported remainder must still be WAREHOUSE-deliverable
        // because goods physically reached the warehouse (GOODS_RECEIVED event), even
        // though the current fulfillment_status is no longer GOODS_RECEIVED.
        TicketItemDto stockDelivered = deliveryItem(1L, "100.00", "40.00", "40.00");
        TicketDto partiallyDelivered = stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED,
            List.of(stockDelivered), "FULLY_PAID", FulfilmentStatus.PARTIALLY_DELIVERED,
            DealStage.PROCUREMENT, null);
        TicketDto fully = ticketLike(partiallyDelivered,
            List.of(deliveryItem(1L, "100.00", "100.00", "40.00")), FulfilmentStatus.PARTIALLY_DELIVERED);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(partiallyDelivered),
            Optional.of(fully), Optional.of(fully));
        // No prior WAREHOUSE delivery record — the goods-received EVENT is the signal.
        when(ticketRepo.hasReceivedGoods(10L)).thenReturn(true);

        service.recordPartialDelivery(10L, new RecordDeliveryRequest("WAREHOUSE", "ส่งของนำเข้าที่เหลือ",
            List.of(new RecordDeliveryRequest.Line(1L, new BigDecimal("60.00")))), importActor);

        verify(ticketRepo).insertDeliveryRecord(eq(10L), eq("WAREHOUSE"), eq(3L), anyString(),
            argThat(lines -> lines.size() == 1 && lines.get(0).qty().compareTo(new BigDecimal("60.00")) == 0));
        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.FULLY_DELIVERED);
    }

    @Test
    void confirmFinalPayment_byAccount_completesPaymentTrack() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));

        service.confirmFinalPayment(10L, accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("BALANCE"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(5L), isNull(), eq("ยืนยันชำระส่วนที่เหลือ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void confirmFinalPayment_byCeoFallback_isAllowed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));
        service.confirmFinalPayment(10L, ceoActor);
        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("BALANCE"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(4L), isNull(), eq("ยืนยันชำระส่วนที่เหลือ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void confirmFinalPayment_rejectsSalesRole() {
        assertForbidden(() -> service.confirmFinalPayment(10L, salesActor));
    }

    @Test
    void confirmFinalPayment_fromDepositPaidBeforeGoodsReceived_isAllowed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));

        service.confirmFinalPayment(10L, accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("BALANCE"),
            argThat(amount -> amount.compareTo(new BigDecimal("500.00")) == 0),
            eq(5L), isNull(), eq("ยืนยันชำระส่วนที่เหลือ"), isNull(), isNull());
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void recordPayment_balanceBringsCumulativePaidToFull() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("1000.00"));

        service.recordPayment(10L,
            new RecordPaymentRequest("BALANCE", new BigDecimal("500.00"), null, "โอนครบ", null, "RC-1", false),
            accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("BALANCE"), eq(new BigDecimal("500.00")),
            eq(5L), isNull(), eq("โอนครบ"), isNull(), eq("RC-1"));
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void recordPayment_rejectsOverpaymentUnlessExplicitlyAllowedWithNote() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("900.00"));

        assertBadRequest(() -> service.recordPayment(10L,
            new RecordPaymentRequest("BALANCE", new BigDecimal("200.00"), null, null, null, null, false),
            accountActor));

        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("900.00"), new BigDecimal("1100.00"));
        service.recordPayment(10L,
            new RecordPaymentRequest("BALANCE", new BigDecimal("200.00"), null, "ลูกค้าโอนเกิน รอหักบิลถัดไป", null, null, true),
            accountActor);

        verify(ticketRepo).insertPaymentReceipt(eq(10L), eq("BALANCE"), eq(new BigDecimal("200.00")),
            eq(5L), isNull(), eq("ลูกค้าโอนเกิน รอหักบิลถัดไป"), isNull(), isNull());
    }

    @Test
    void recordPayment_rejectsNonAccountAndInactiveDeals() {
        assertForbidden(() -> service.recordPayment(10L,
            new RecordPaymentRequest("DEPOSIT", new BigDecimal("100.00"), null, null, null, null, false),
            salesManagerActor));

        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "DEPOSIT_PAID", null,
            DealStage.DEPOSIT_RECEIVED, null, DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);
        assertConflict(() -> service.recordPayment(10L,
            new RecordPaymentRequest("BALANCE", new BigDecimal("100.00"), null, null, null, null, false),
            accountActor));
    }

    @Test
    void recordPayment_duplicateReceiptRefReturnsConflict() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("100.00"));
        when(ticketRepo.insertPaymentReceipt(eq(10L), eq("BALANCE"), eq(new BigDecimal("100.00")),
            eq(5L), isNull(), isNull(), isNull(), eq("DUP")))
            .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertConflict(() -> service.recordPayment(10L,
            new RecordPaymentRequest("BALANCE", new BigDecimal("100.00"), null, null, null, "DUP", false),
            accountActor));
    }

    // ── create (V50: lightweight deal start, required project) ────────────

    @Test
    void create_requiresProject() {
        var request = createRequest(null, List.of());
        assertBadRequest(() -> service.create(request, salesActor));
        verify(ticketRepo, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void create_withoutItems_startsLightweightDraftWithoutNotifying() {
        var request = createRequest(77L, List.of());
        when(ticketRepo.nextTicketCode()).thenReturn("PR-2026-0001");
        when(ticketRepo.create(request, "PR-2026-0001", 1L, "sales")).thenReturn(10L);
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.create(request, salesActor);

        // A lead-stage deal is the rep's private draft — import/CEO not notified yet.
        verify(notifRepo, never()).notifyByRole(anyString(), org.mockito.ArgumentMatchers.anyLong(),
            anyString(), anyString());
    }

    @Test
    void create_withItems_startsAsDraft() {
        // Pricing no longer starts at deal-creation time: products attached here are
        // preliminary deal products, and a PricingRequest must be created + submitted
        // separately before the deal can be priced.
        var request = createRequest(77L, List.of(sampleItemRequest()));
        when(ticketRepo.nextTicketCode()).thenReturn("PR-2026-0001");
        when(ticketRepo.create(request, "PR-2026-0001", 1L, "sales")).thenReturn(10L);
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        TicketDto result = service.create(request, salesActor);

        assertThat(result.summary().status()).isEqualTo(TicketStatus.DRAFT);
    }

    @Test
    void create_withItems_doesNotNotifyImportOrCeo() {
        var request = createRequest(77L, List.of(sampleItemRequest()));
        when(ticketRepo.nextTicketCode()).thenReturn("PR-2026-0001");
        when(ticketRepo.create(request, "PR-2026-0001", 1L, "sales")).thenReturn(10L);
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.create(request, salesActor);

        verify(notifRepo, never()).notifyByRole(any(), anyLong(), any(), any());
    }

    @Test
    void create_rejectsInvalidPriority() {
        // F2: an unvalidated priority reaches chk_ticket_priority in the repository and
        // fails closed (500). The service must reject it up front as a 400 and never write.
        var request = new CreateTicketRequest("Test deal", "urgent", "ลูกค้า", null, 77L, null, null, null, List.of());
        assertBadRequest(() -> service.create(request, salesActor));
        verify(ticketRepo, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void create_allowsValidPriority() {
        var request = new CreateTicketRequest("Test deal", "HIGH", "ลูกค้า", null, 77L, null, null, null, List.of());
        when(ticketRepo.nextTicketCode()).thenReturn("PR-2026-0001");
        when(ticketRepo.create(request, "PR-2026-0001", 1L, "sales")).thenReturn(10L);
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.create(request, salesActor); // must not throw

        verify(ticketRepo).create(request, "PR-2026-0001", 1L, "sales");
    }

    // ── deal pipeline: manual stage updates (V50) ─────────────────────────

    @Test
    void updateStage_ownerCanAdvanceSalesStage() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.PRESENTATION, null);
        service.updateStage(10L, DealStage.SPEC_APPROVED, null, salesActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.SPEC_APPROVED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.STAGE_CHANGED), eq(DealStage.PRESENTATION), eq(DealStage.SPEC_APPROVED), isNull());
    }

    @Test
    void updateStage_salesManagerAndCeoCanAdvanceSalesStage() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.PRESENTATION, null);
        service.updateStage(10L, DealStage.SPEC_APPROVED, null, salesManagerActor);
        service.updateStage(10L, DealStage.OWNER_SIGNOFF, "CEO ปรับตามแผนติดตามดีล", ceoActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.SPEC_APPROVED);
        verify(ticketRepo).updateSalesStage(10L, DealStage.OWNER_SIGNOFF);
    }

    @Test
    void updateStage_rejectsNonOwnerSalesAndWrongRolesPerTarget() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.PRESENTATION, null);
        // another rep's deal
        assertForbidden(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, otherSales));
        // sales cannot set money/import fallback stages
        assertForbidden(() -> service.updateStage(10L, DealStage.DEPOSIT_RECEIVED, null, salesActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.PROCUREMENT, null, salesActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.CLOSED_PAID, null, salesActor));
        // account only money stages; import only PROCUREMENT
        assertForbidden(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, accountActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, importActor));
        service.updateStage(10L, DealStage.DEPOSIT_RECEIVED, "บัญชีแก้ย้อนหลังจากเอกสารรับเงิน", accountActor);
        service.updateStage(10L, DealStage.PROCUREMENT, "นำเข้าเริ่มออก IR แล้ว", importActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.DEPOSIT_RECEIVED);
        verify(ticketRepo).updateSalesStage(10L, DealStage.PROCUREMENT);
    }

    @Test
    void updateStage_rejectsUnknownSameLostAndBackwardWithoutNote() {
        assertBadRequest(() -> service.updateStage(10L, "S3", null, salesActor));
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertConflict(() -> service.updateStage(10L, DealStage.NEGOTIATION, null, salesActor));
        assertBadRequest(() -> service.updateStage(10L, DealStage.PRESENTATION, "  ", salesActor));
        service.updateStage(10L, DealStage.PRESENTATION, "ลูกค้าเปลี่ยนผู้ออกแบบ เริ่มสเปคใหม่", salesActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.PRESENTATION);
        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, DealLostReason.PRICE);
        assertConflict(() -> service.updateStage(11L, DealStage.ORDER_RECEIVED, null, salesActor));
    }

    @Test
    void updateStage_quoteDesignSideBackToSpecApproved_needsNoNote() {
        // S4 → S3 is the business's everyday path (S1 → S2 → S4 → S3 → S5): the
        // designer is quoted before signing off the spec. It is "backward" only
        // because of where the two sit in ORDER, so it must not demand a reason.
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.QUOTE_DESIGN_SIDE, null);

        service.updateStage(10L, DealStage.SPEC_APPROVED, null, salesActor);

        verify(ticketRepo).updateSalesStage(10L, DealStage.SPEC_APPROVED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(), eq(TicketEventKind.STAGE_CHANGED),
            eq(DealStage.QUOTE_DESIGN_SIDE), eq(DealStage.SPEC_APPROVED), isNull());
    }

    @Test
    void updateStage_otherBackwardMovesStillRequireNote() {
        // The exemption is one adjacent pair, not a general relaxation.
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.QUOTE_DESIGN_SIDE, null);
        assertBadRequest(() -> service.updateStage(10L, DealStage.PRESENTATION, null, salesActor));

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.OWNER_SIGNOFF, null);
        assertBadRequest(() -> service.updateStage(11L, DealStage.SPEC_APPROVED, null, salesActor));
    }

    @Test
    void updateStage_multiStepForwardRequiresNote() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.PRESENTATION, null);

        assertBadRequest(() -> service.updateStage(10L, DealStage.OWNER_SIGNOFF, null, salesActor));
        service.updateStage(10L, DealStage.OWNER_SIGNOFF, "ลูกค้าอนุมัติสเปคและเจ้าของเซ็นแล้ว", salesActor);

        verify(ticketRepo).updateSalesStage(10L, DealStage.OWNER_SIGNOFF);
    }

    // ── deal pipeline: lost / reopen (V50) ────────────────────────────────

    @Test
    void markLost_ownerPreservesStageAndRecordsReason() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.markLost(10L, DealLostReason.PRICE, "แพ้ราคาคู่แข่ง", salesActor);
        verify(ticketRepo).markDealLost(10L, DealLostReason.PRICE);
        verify(ticketRepo, never()).updateSalesStage(org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.MARKED_LOST), eq(DealStage.NEGOTIATION), eq(DealStage.NEGOTIATION), anyString());
    }

    // ── dead-deal handling: markLost/cancel cascade, hold/dormant deliberately do not ──

    @Test
    void markLost_cascadesCancelOpenPricingRequestsWithTheDealsOwnReason() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.markLost(10L, DealLostReason.PRICE, "แพ้ราคาคู่แข่ง", salesActor);
        verify(pricingRequestService).cancelOpenForTicket(10L, DealLostReason.PRICE, salesActor);
    }

    @Test
    void cancel_cascadesCancelOpenPricingRequestsWithTheDealsOwnReason() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor);
        verify(pricingRequestService).cancelOpenForTicket(10L, DealCancelReason.OWNER_CANCELLED, salesActor);
    }

    @Test
    void holdAndDormant_doNotCascadeCancelOpenPricingRequests() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.placeOnHold(10L, "รอเจ้าของโครงการ", salesActor);

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, null, DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);
        service.markDormant(11L, null, salesManagerActor);

        verify(pricingRequestService, never())
            .cancelOpenForTicket(org.mockito.ArgumentMatchers.anyLong(), anyString(), any());
    }

    @Test
    void markLost_gatesAndValidation() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertBadRequest(() -> service.markLost(10L, "F2", null, salesActor));
        assertForbidden(() -> service.markLost(10L, DealLostReason.PRICE, null, otherSales));
        assertForbidden(() -> service.markLost(10L, DealLostReason.PRICE, null, importActor));
        assertForbidden(() -> service.markLost(10L, DealLostReason.PRICE, null, accountActor));
        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, DealLostReason.PRICE);
        assertConflict(() -> service.markLost(11L, DealLostReason.LEAD_TIME, null, salesActor));
    }

    @Test
    void reopenedDealIsFullyOperableAndKeepsItsLostReason() {
        // V57 stopped nulling lost_reason on reopen, so a live reopened deal now
        // carries one. Every guard that used `lostReason != null` to mean "is
        // currently lost" had to move to the lifecycle — otherwise reopening a deal
        // silently bricked it: no stage changes, no auto-advance, un-losable again.
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, DealLostReason.PRICE, DealLifecycle.ACTIVE, DepositPolicy.REQUIRED);

        service.updateStage(10L, DealStage.ORDER_RECEIVED, null, salesActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.ORDER_RECEIVED);

        service.markLost(10L, DealLostReason.LEAD_TIME, null, salesActor);
        verify(ticketRepo).markDealLost(10L, DealLostReason.LEAD_TIME);
    }

    @Test
    void stageWritesStillRefusedWhileActuallyLost() {
        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, DealLostReason.PRICE, DealLifecycle.CLOSED_LOST, DepositPolicy.REQUIRED);
        assertConflict(() -> service.updateStage(11L, DealStage.ORDER_RECEIVED, null, salesActor));
        assertConflict(() -> service.markLost(11L, DealLostReason.PRICE, null, salesActor));
    }

    @Test
    void reopenDeal_resumesAtPreservedStage() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, DealLostReason.PROJECT_ON_HOLD);
        service.reopenDeal(10L, "โครงการกลับมาก่อสร้างต่อ", salesActor);
        verify(ticketRepo).clearDealLost(10L);
        verify(ticketRepo, never()).updateSalesStage(org.mockito.ArgumentMatchers.anyLong(), anyString());
        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertConflict(() -> service.reopenDeal(11L, null, salesActor));
    }

    // ── deal lifecycle + policies (V51) ──────────────────────────────────

    @Test
    void lifecycle_holdDormantResume_preserveStageAndAudit() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.placeOnHold(10L, "รอเจ้าของโครงการ", salesActor);
        verify(ticketRepo).updateLifecycle(10L, DealLifecycle.ON_HOLD);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.ON_HOLD), eq(DealStage.NEGOTIATION), eq(DealStage.NEGOTIATION),
            eq("รอเจ้าของโครงการ"));

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, null, DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);
        service.markDormant(11L, null, salesManagerActor);
        verify(ticketRepo).updateLifecycle(11L, DealLifecycle.DORMANT);

        stubDeal(12L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.NEGOTIATION, null, DealLifecycle.DORMANT, DepositPolicy.REQUIRED);
        service.resume(12L, "กลับมาติดตามต่อ", ceoActor);
        verify(ticketRepo).updateLifecycle(12L, DealLifecycle.ACTIVE);
        verify(ticketRepo).addEvent(eq(12L), eq(4L), anyString(),
            eq(TicketEventKind.RESUMED), eq(DealStage.NEGOTIATION), eq(DealStage.NEGOTIATION),
            eq("กลับมาติดตามต่อ"));
    }

    @Test
    void lifecycle_gatesMutationsButAllowsComment() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(sampleItem()), null, null,
            DealStage.PRESENTATION, null, DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);

        assertConflict(() -> service.submit(10L, salesActor));
        assertConflict(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, salesActor));
        service.comment(10L, new CommentRequest("ยังรอลูกค้ากลับมา"), salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.COMMENTED), isNull(), isNull(), eq("ยังรอลูกค้ากลับมา"));
    }

    @Test
    void waiveDeposit_accountOrCeoOnlyAndIssueImportRequestCanBypassNotice() {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null);

        service.waiveDeposit(10L, DepositPolicy.WAIVED, "ลูกค้าเครดิตดี", accountActor);

        verify(ticketRepo).updateDepositPolicy(10L, DepositPolicy.WAIVED, "ลูกค้าเครดิตดี", 5L);
        verify(ticketRepo).addEvent(eq(10L), eq(5L), anyString(),
            eq(TicketEventKind.POLICY_CHANGED), eq(DealStage.ORDER_RECEIVED), eq(DealStage.ORDER_RECEIVED),
            eq("deposit_policy → WAIVED — ลูกค้าเครดิตดี"));
        assertForbidden(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, "sales ขอเอง", salesActor));
        assertBadRequest(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, " ", accountActor));

        stubDeal(11L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null, DealLifecycle.ACTIVE, DepositPolicy.WAIVED);
        service.issueImportRequest(11L, importActor);
        verify(ticketRepo).updateFulfillmentStatus(11L, "IR_ISSUED");

        stubDeal(12L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null, DealLifecycle.ACTIVE, DepositPolicy.REQUIRED);
        assertConflict(() -> service.issueImportRequest(12L, importActor));
    }

    @Test
    void policyActions_validateAndAudit() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.AWAITING_BUYER, null);

        service.setTenderRequirement(10L, TenderRequirement.REQUIRED, salesActor);
        service.setEntryChannel(10L, EntryChannel.OWNER_DIRECT, null, ceoActor);

        verify(ticketRepo).updateTenderRequirement(10L, TenderRequirement.REQUIRED);
        verify(ticketRepo).updateEntryChannel(10L, EntryChannel.OWNER_DIRECT);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.POLICY_CHANGED), eq(DealStage.AWAITING_BUYER), eq(DealStage.AWAITING_BUYER),
            eq("tender_requirement → REQUIRED"));
        assertForbidden(() -> service.setTenderRequirement(10L, TenderRequirement.UNKNOWN, importActor));
        assertBadRequest(() -> service.setEntryChannel(10L, "WALK_IN", null, salesActor));
    }

    @Test
    void entryChannel_ownerAndBuyerDirectPoliciesSupportSkipPaths() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.AWAITING_BUYER, null);
        service.setEntryChannel(10L, EntryChannel.OWNER_DIRECT, null, ceoActor);

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.AWAITING_BUYER, null);
        service.setEntryChannel(11L, EntryChannel.BUYER_DIRECT, null, salesActor);

        verify(ticketRepo).updateEntryChannel(10L, EntryChannel.OWNER_DIRECT);
        verify(ticketRepo).updateEntryChannel(11L, EntryChannel.BUYER_DIRECT);
    }

    @Test
    void actions_requireViewAccessAndReflectLifecycle() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(sampleItem()), null, null,
            DealStage.PRESENTATION, null);

        var actions = service.actions(10L, salesActor);
        List<String> names = actions.availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();

        assertThat(actions.currentState().lifecycle()).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(names).contains("UPDATE_STAGE", "PLACE_ON_HOLD");
        assertThat(names).doesNotContain("SUBMIT");
        assertForbidden(() -> service.actions(10L, otherSales));

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(sampleItem()), null, null,
            DealStage.PRESENTATION, null, DealLifecycle.ON_HOLD, DepositPolicy.REQUIRED);
        List<String> paused = service.actions(11L, salesManagerActor)
            .availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
        assertThat(paused).contains("RESUME", "MARK_DORMANT");
        assertThat(paused).doesNotContain("SUBMIT", "UPDATE_STAGE");

        stubDealWithQuotations(12L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.QUOTE_BUYER, DealLifecycle.ACTIVE, List.of(quotationOf(12L, 12L, "QT-2026-0012",
                QuotationRecipient.BUYER, 1, QuotationStatus.ISSUED)));
        List<String> quotationOwner = service.actions(12L, salesActor)
            .availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
        assertThat(quotationOwner).contains("MARK_QUOTATION_SENT", "MARK_QUOTATION_ACCEPTED",
            "MARK_QUOTATION_REJECTED");
        List<String> quotationManager = service.actions(12L, salesManagerActor)
            .availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
        assertThat(quotationManager).doesNotContain("MARK_QUOTATION_SENT", "MARK_QUOTATION_ACCEPTED",
            "MARK_QUOTATION_REJECTED");
    }

    @Test
    void actions_neverOffersSubmit() {
        // Ticket-level submit() is retired (superseded by the PricingRequest
        // aggregate) — the API must never advertise a "SUBMIT" action that would
        // 409 on click, for a draft ticket owned by its creator with items or not.
        stubDeal(20L, 1L, TicketStatus.DRAFT, List.of(sampleItem()), null, null,
            DealStage.PRESENTATION, null);
        List<String> withItems = service.actions(20L, salesActor)
            .availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
        assertThat(withItems).doesNotContain("SUBMIT");

        stubDeal(21L, 1L, TicketStatus.DRAFT, List.of(), null, null,
            DealStage.PRESENTATION, null);
        List<String> withoutItems = service.actions(21L, salesActor)
            .availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
        assertThat(withoutItems).doesNotContain("SUBMIT");
    }

    // ── deal pipeline: auto-advance from operational transitions (V50) ────

    @Test
    void confirmCustomer_autoAdvancesDealToOrderReceived() {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.confirmCustomer(10L, salesActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.ORDER_RECEIVED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.STAGE_CHANGED), eq(DealStage.NEGOTIATION), eq(DealStage.ORDER_RECEIVED), anyString());
    }

    @Test
    void confirmDepositPaid_autoAdvancesDealToDepositReceived() {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "DEPOSIT_NOTICE_ISSUED", null, DealStage.ORDER_RECEIVED, null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(BigDecimal.ZERO, new BigDecimal("500.00"));
        service.confirmDepositPaid(10L, accountActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.DEPOSIT_RECEIVED);
    }

    @Test
    void issueImportRequest_autoAdvancesDealToProcurement() {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "DEPOSIT_PAID", null, DealStage.DEPOSIT_RECEIVED, null);
        service.issueImportRequest(10L, importActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.PROCUREMENT);
    }

    @Test
    void confirmFinalPayment_afterFullDelivery_advancesDealToClosedPaid() {
        // Delivered in full, then the balance is paid — both gates satisfied, so
        // CLOSED_PAID advances.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "AWAITING_FINAL_PAYMENT", FulfilmentStatus.FULLY_DELIVERED, DealStage.DELIVERED, null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));
        service.confirmFinalPayment(10L, accountActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.CLOSED_PAID);
    }

    @Test
    void confirmFinalPayment_goodsInWarehouseNotDelivered_doesNotAdvanceClosedPaid() {
        // Case 9 / warehouse path: goods reached GLR's warehouse (GOODS_RECEIVED)
        // and the deal sits at DELIVERY_SCHEDULING, but nothing has been delivered
        // to the customer. Paying the balance in full must NOT close the deal —
        // CLOSED_PAID requires FULLY_DELIVERED, not merely goods-in-warehouse.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "AWAITING_FINAL_PAYMENT", FulfilmentStatus.GOODS_RECEIVED, DealStage.DELIVERY_SCHEDULING, null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));
        service.confirmFinalPayment(10L, accountActor);
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
        verify(ticketRepo, never()).updateSalesStage(10L, DealStage.CLOSED_PAID);
    }

    @Test
    void deliveryCompletion_whenAlreadyFullyPaid_advancesClosedPaid() {
        // Second CLOSED_PAID gate: a deal paid in full before delivery closes
        // exactly when the final delivery completes both tracks.
        TicketItemDto initialItem = deliveryItem(1L, "100.00", "0.00", "0.00");
        TicketDto initial = stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(initialItem),
            "FULLY_PAID", FulfilmentStatus.GOODS_RECEIVED, DealStage.DELIVERY_SCHEDULING, null);
        TicketDto delivered = ticketLike(initial, List.of(deliveryItem(1L, "100.00", "100.00", "0.00")),
            FulfilmentStatus.FULLY_DELIVERED);
        when(ticketRepo.findById(10L)).thenReturn(Optional.of(initial), Optional.of(delivered),
            Optional.of(delivered), Optional.of(delivered));
        when(ticketRepo.hasReceivedGoods(10L)).thenReturn(true);

        service.completeDelivery(10L, new CompleteDeliveryRequest("ส่งครบ"), importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, FulfilmentStatus.FULLY_DELIVERED);
        verify(ticketRepo).updateSalesStage(10L, DealStage.DELIVERED);
        verify(ticketRepo).updateSalesStage(10L, DealStage.CLOSED_PAID);
    }

    @Test
    void autoAdvance_neverRegressesAndSkipsLostDeals() {
        // Deal already past ORDER_RECEIVED (manual correction) — re-confirming must not regress.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null, DealStage.PROCUREMENT, null);
        service.confirmCustomer(10L, salesActor);
        verify(ticketRepo, never()).updateSalesStage(org.mockito.ArgumentMatchers.anyLong(), anyString());
        // Closed-lost lifecycle gates operational transitions before auto-advance.
        stubDeal(11L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.NEGOTIATION, DealLostReason.PROJECT_ON_HOLD);
        assertConflict(() -> service.confirmCustomer(11L, salesActor));
        verify(ticketRepo, never()).updateSalesStage(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void midFulfillmentTransitions_stayInsideProcurementUntilGoodsReceived() {
        // IR_SENT / SHIPPING render from fulfillment_status — no stage writes; they
        // stay inside PROCUREMENT.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "DEPOSIT_PAID", "IR_ISSUED",
            DealStage.PROCUREMENT, null);
        service.markIrSent(10L, importActor);
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "DEPOSIT_PAID", "IR_SENT",
            DealStage.PROCUREMENT, null);
        service.markShipping(10L, importActor);
        verify(ticketRepo, never()).updateSalesStage(org.mockito.ArgumentMatchers.anyLong(), anyString());
        // Goods reaching the warehouse (S17) is the "ready to deliver" signal —
        // markGoodsReceived advances the stage to DELIVERY_SCHEDULING (S18).
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "DEPOSIT_PAID", "SHIPPING",
            DealStage.PROCUREMENT, null);
        service.markGoodsReceived(10L, importActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.DELIVERY_SCHEDULING);
    }

    @Test
    void salesManager_stageWriteIsTheOnlyTicketWritePower() {
        // The pipeline stage/lost/reopen trio is the deliberate exception to
        // handoff 58's read-only invariant — operational actions must stay denied.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertForbidden(() -> service.confirmCustomer(10L, salesManagerActor));
        // submit() is now deprecated and 409s unconditionally for every role, sales_manager
        // included — it no longer demonstrates a role-specific denial, just that the route
        // is dead. See submit_isDeprecatedAndAlwaysConflicts for the dedicated coverage.
        assertConflict(() -> service.submit(10L, salesManagerActor));
        service.markLost(10L, DealLostReason.RELATIONSHIP, null, salesManagerActor);
        verify(ticketRepo).markDealLost(10L, DealLostReason.RELATIONSHIP);
    }

    @Test
    void close_dualTrackAtGoodsReceived_isRefused() {
        // GOODS_RECEIVED means the goods reached GLR's own warehouse (S17) — the
        // customer has received nothing. A fully-paid deal in that state used to
        // close to COMPLETED with zero delivered units, because the manual gate
        // accepted GOODS_RECEIVED-with-no-deliveries while the auto-advance gate
        // (maybeAdvanceClosedPaid) refused it. Both now require FULLY_DELIVERED.
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID", "GOODS_RECEIVED", null, true);

        assertConflict(() -> service.confirmCloseReady(10L, accountActor));

        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void close_dualTrackRejectsWhenPaymentIncomplete() {
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT",
            "GOODS_RECEIVED", null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    @Test
    void close_dualTrackRejectsWhenFulfillmentIncomplete() {
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID", "SHIPPING", null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    // ── cancel ────────────────────────────────────────────────────────────

    @Test
    void cancel_ownerCanCancelFromDraft() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.DRAFT), eq(TicketStatus.CANCELLED), anyString());
    }

    @Test
    void cancel_ownerCanCancelFromInReview() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.IN_REVIEW), eq(TicketStatus.CANCELLED), anyString());
    }

    @Test
    void cancel_ownerCanCancelFromPriceProposed() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.CANCELLED), anyString());
    }

    @Test
    void cancel_rejectsAlreadyClosed() {
        stubTicket(10L, 1L, TicketStatus.CLOSED);
        assertConflict(() -> service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor));
    }

    @Test
    void cancel_rejectsAlreadyCancelled() {
        stubTicket(10L, 1L, TicketStatus.CANCELLED);
        assertConflict(() -> service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesActor));
    }

    @Test
    void cancel_rejectsNonOwner() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, otherSales));
    }

    @Test
    void cancel_requiresAValidReason() {
        // Mandatory, matching markLost: an optional reason gets skipped in practice
        // and the gap it was added to close stays open.
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertBadRequest(() -> service.cancel(10L, null, null, salesActor));
        assertBadRequest(() -> service.cancel(10L, "", null, salesActor));
        assertBadRequest(() -> service.cancel(10L, "MADE_UP_CODE", null, salesActor));
        // A lost reason is not a cancel reason — the two vocabularies are distinct.
        assertBadRequest(() -> service.cancel(10L, DealLostReason.PRICE, null, salesActor));
        verify(ticketRepo, never()).cancelDeal(anyLong(), anyString());
    }

    @Test
    void cancel_persistsReasonAndCarriesTheNoteIntoTheEvent() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.cancel(10L, DealCancelReason.BUDGET_CANCELLED, "ลูกค้าตัดงบปี 2569", salesActor);

        verify(ticketRepo).cancelDeal(10L, DealCancelReason.BUDGET_CANCELLED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(), eq(TicketEventKind.CANCELLED),
            eq(TicketStatus.DRAFT), eq(TicketStatus.CANCELLED),
            argThat(m -> m != null && m.contains(DealCancelReason.BUDGET_CANCELLED)
                && m.contains("ลูกค้าตัดงบปี 2569")));
        verify(ticketRepo).updateLifecycle(10L, DealLifecycle.CANCELLED);
    }

    // ── editItems ─────────────────────────────────────────────────────────
    // 2026-07-16 pricing-integrity audit, finding #4: sales editing descriptive fields
    // must never silently discard import's proposed price or CEO's approved/manual price.

    @Test
    void editItems_preservesExistingPricingAndIgnoresRequestSuppliedPrices() {
        TicketItemDto existing = new TicketItemDto(
            101L, 10L, "OldBrand", "OldModel", "White", "Matte", "60x60", "Cotto",
            new BigDecimal("5"), new BigDecimal("10"),
            new BigDecimal("50"), "USD", "piece",
            new BigDecimal("777.00"), new BigDecimal("888.00"), "THB", 0,
            new BigDecimal("600.0000"), new BigDecimal("777.00"), 3, "PIECE",
            new BigDecimal("999.00"), "CEO special discount");
        stubTicketWithItems(10L, 1L, TicketStatus.PRICE_PROPOSED, List.of(existing));

        TicketItemRequest edited = new TicketItemRequest(
            "NewBrand", "NewModel", "Grey", "Glossy", "80x80", "Cotto",
            new BigDecimal("7"), new BigDecimal("14"), "PIECE",
            new BigDecimal("50"), "USD", "piece",
            new BigDecimal("1"),   // attacker/sales-supplied proposedPrice — must be ignored
            "THB");

        service.editItems(10L, new EditItemsRequest(List.of(edited), "แก้ไข spec"), salesActor);

        ArgumentCaptor<List<TicketItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).replaceItemsPreservingPricing(eq(10L), captor.capture());
        TicketItemDto merged = captor.getValue().get(0);

        assertThat(merged.brand()).isEqualTo("NewBrand");
        assertThat(merged.model()).isEqualTo("NewModel");
        assertThat(merged.qty()).isEqualByComparingTo("7");
        // Pricing fields carried over from the existing item, NOT the request.
        assertThat(merged.proposedPrice()).isEqualByComparingTo("777.00");
        assertThat(merged.approvedPrice()).isEqualByComparingTo("888.00");
        assertThat(merged.calcedCost()).isEqualByComparingTo("600.0000");
        assertThat(merged.calcedPrice()).isEqualByComparingTo("777.00");
        assertThat(merged.calcConfigVersion()).isEqualTo(3);
        assertThat(merged.manualPrice()).isEqualByComparingTo("999.00");
        assertThat(merged.manualOverrideReason()).isEqualTo("CEO special discount");
    }

    @Test
    void editItems_requestOmittingUnitBasisInheritsPriorBasis() {
        // An API edit that omits unitBasis must not silently flip an SQM item to PIECE.
        TicketItemDto existing = new TicketItemDto(
            101L, 10L, "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("5"), new BigDecimal("10"), null, null, null,
            null, null, "THB", 0, null, null, null, "SQM", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.IN_REVIEW, List.of(existing));

        TicketItemRequest edited = new TicketItemRequest(
            "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("6"), new BigDecimal("12"), null,   // unitBasis omitted
            null, null, null, null, null);

        service.editItems(10L, new EditItemsRequest(List.of(edited), null), salesActor);

        ArgumentCaptor<List<TicketItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).replaceItemsPreservingPricing(eq(10L), captor.capture());
        assertThat(captor.getValue().get(0).unitBasis()).isEqualTo("SQM");
    }

    @Test
    void editItems_newItemBeyondCurrentCountGetsNullPricingFields() {
        TicketItemDto existing = new TicketItemDto(
            101L, 10L, "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("5"), new BigDecimal("10"),
            new BigDecimal("50"), "THB", "piece",
            new BigDecimal("100"), new BigDecimal("100"), "THB", 0,
            null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.SUBMITTED, List.of(existing));

        TicketItemRequest first = new TicketItemRequest(
            "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("5"), new BigDecimal("10"), "PIECE",
            new BigDecimal("50"), "THB", "piece", null, "THB");
        TicketItemRequest brandNew = new TicketItemRequest(
            "AnotherBrand", "AnotherModel", null, null, null, "Cotto",
            new BigDecimal("2"), null, "PIECE",
            new BigDecimal("20"), "THB", "piece", new BigDecimal("999"), "THB");

        service.editItems(10L, new EditItemsRequest(List.of(first, brandNew), null), salesActor);

        ArgumentCaptor<List<TicketItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).replaceItemsPreservingPricing(eq(10L), captor.capture());
        TicketItemDto newItem = captor.getValue().get(1);

        assertThat(newItem.brand()).isEqualTo("AnotherBrand");
        assertThat(newItem.proposedPrice()).isNull();
        assertThat(newItem.approvedPrice()).isNull();
        assertThat(newItem.manualPrice()).isNull();
    }

    @Test
    void editItems_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        TicketItemRequest req = new TicketItemRequest(
            "Brand", "Model", "Color", "Texture", "Size", "Factory",
            BigDecimal.ONE, null, "PIECE", null, null, null, null, "THB");

        assertForbidden(() -> service.editItems(10L, new EditItemsRequest(List.of(req), null), otherSales));
    }

    // ── calculatePrices ──────────────────────────────────────────────────

    @Test
    void calculatePrices_priceProposedByCeoDelegatesToPricingEngine() {
        TicketDto calculated = stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        when(priceCalcService.calculateForTicket(10L)).thenReturn(calculated);
        when(priceCalcService.calculateBreakdown(10L)).thenReturn(List.of());

        TicketService.CalculatePricesResult result = service.calculatePrices(10L, ceoActor);

        assertThat(result.ticket()).isEqualTo(calculated);
        verify(priceCalcService).calculateForTicket(10L);
    }

    @Test
    void calculatePrices_rejectsSalesRole() {
        assertForbidden(() -> service.calculatePrices(10L, salesActor));
    }

    @Test
    void calculatePrices_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        assertConflict(() -> service.calculatePrices(10L, ceoActor));
    }

    // ── overrideItemPrice ────────────────────────────────────────────────
    // 2026-07-16 pricing-integrity audit, finding #3: overriding a price previously left
    // no audit trail at all.

    @Test
    void overrideItemPrice_logsPriceOverriddenEventWithItemIdManualPriceAndReason() {
        TicketItemDto item = new TicketItemDto(
            101L, 10L, "Brand", "Model", null, null, null, "Cotto",
            BigDecimal.ONE, null, new BigDecimal("50"), "THB", "piece",
            new BigDecimal("100"), null, "THB", 0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.PRICE_PROPOSED, List.of(item));

        service.overrideItemPrice(10L, 101L,
            new OverridePriceRequest(new BigDecimal("777.00"), "ราคาพิเศษลูกค้า VIP"), ceoActor);

        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.PRICE_OVERRIDDEN), eq(TicketStatus.PRICE_PROPOSED),
            eq(TicketStatus.PRICE_PROPOSED), noteCaptor.capture());
        assertThat(noteCaptor.getValue()).contains("101").contains("777.00").contains("ราคาพิเศษลูกค้า VIP");
        verify(ticketRepo).updateItemManualPrice(101L, new BigDecimal("777.00"), "ราคาพิเศษลูกค้า VIP");
    }

    @Test
    void overrideItemPrice_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        assertConflict(() -> service.overrideItemPrice(10L, 101L,
            new OverridePriceRequest(new BigDecimal("777.00"), null), ceoActor));
    }

    // ── comment ───────────────────────────────────────────────────────────

    @Test
    void comment_addsCommentEventWithoutStatusChange() {
        when(ticketRepo.existsById(10L)).thenReturn(true);
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        service.comment(10L, new CommentRequest("ช่วยตรวจราคาใหม่ด้วย"), importActor);

        verify(ticketRepo).addEvent(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.COMMENTED), isNull(), isNull(), eq("ช่วยตรวจราคาใหม่ด้วย"));
    }

    @Test
    void comment_rejectsNonExistentTicket() {
        when(ticketRepo.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.comment(99L, new CommentRequest("hi"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── sales_manager oversight (read + comment only, zero write actions) ──
    // Product decision (2026-07-16): sales_manager acts like a project manager for the
    // sales team — follow up / check up, but never perform the actual work. It was
    // added to VIEWER_ROLES only; SALES_ROLES/IMPORT_ROLES/CEO_ROLES/ACCOUNT_ROLES are
    // untouched. Every write test below asserts 403.

    @Test
    void list_salesManagerSeesAllTicketsUnfiltered() {
        // Same as any non-sales viewer: no createdBy scoping.
        service.list(null, salesManagerActor);
        verify(ticketRepo).findSummaries(null, null);
    }

    @Test
    void get_salesManagerCanViewAnyonesTicket() {
        TicketDto ticket = stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        assertThat(service.get(10L, salesManagerActor)).isEqualTo(ticket);
    }

    @Test
    void comment_salesManagerCanCommentOnAnyonesTicket() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        service.comment(10L, new CommentRequest("ติดตามความคืบหน้าให้ลูกค้าหน่อย"), salesManagerActor);

        verify(ticketRepo).addEvent(eq(10L), eq(8L), anyString(),
            eq(TicketEventKind.COMMENTED), isNull(), isNull(), eq("ติดตามความคืบหน้าให้ลูกค้าหน่อย"));
    }

    @Test
    void submit_deprecatedConflictAppliesToSalesManagerToo() {
        // Previously sales_manager got a distinct FORBIDDEN (read-only role, never owns a
        // ticket). Now that submit() is deprecated for everyone, it 409s here the same as
        // for any other role — there is no more role-specific denial to assert.
        assertConflict(() -> service.submit(10L, salesManagerActor));
    }

    @Test
    void pickup_rejectsSalesManagerRole() {
        assertForbidden(() -> service.pickup(10L, salesManagerActor));
    }

    @Test
    void proposePrice_rejectsSalesManagerRole() {
        assertForbidden(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), salesManagerActor));
    }

    @Test
    void approve_rejectsSalesManagerRole() {
        assertForbidden(() -> service.approve(10L, salesManagerActor));
    }

    @Test
    void calculatePrices_rejectsSalesManagerRole() {
        assertForbidden(() -> service.calculatePrices(10L, salesManagerActor));
    }

    @Test
    void overrideItemPrice_rejectsSalesManagerRole() {
        assertForbidden(() -> service.overrideItemPrice(10L, 101L,
            new OverridePriceRequest(new BigDecimal("777.00"), null), salesManagerActor));
    }

    @Test
    void generateQuotation_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) before the ownership check even runs.
        assertForbidden(() -> service.generateQuotation(10L, designerQuotation(), salesManagerActor));
    }

    @Test
    void editItems_rejectsSalesManagerRole() {
        // Not role-gated via requireRole, but salesCanEdit requires SALES_ROLES
        // membership regardless of ownership — sales_manager fails that check.
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        TicketItemRequest req = new TicketItemRequest(
            "Brand", "Model", "Color", "Texture", "Size", "Factory",
            BigDecimal.ONE, null, "PIECE", null, null, null, null, "THB");

        assertForbidden(() -> service.editItems(10L, new EditItemsRequest(List.of(req), null), salesManagerActor));
    }

    @Test
    void close_rejectsSalesManagerRole() {
        // The close sequence is account → ceo; sales_manager is read+comment
        // oversight and appears in neither half.
        stubReadyToConfirm(10L);
        assertForbidden(() -> service.confirmCloseReady(10L, salesManagerActor));
        stubAwaitingCeo(11L);
        assertForbidden(() -> service.verifyClose(11L, salesManagerActor));
    }

    @Test
    void cancel_rejectsSalesManagerRole() {
        // Same reasoning as close(): cancel() is owner-gated only, and sales_manager
        // can never own a ticket, so it always 403s here.
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.cancel(10L, DealCancelReason.OWNER_CANCELLED, null, salesManagerActor));
    }

    @Test
    void confirmCustomer_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) before the ownership check.
        assertForbidden(() -> service.confirmCustomer(10L, salesManagerActor));
    }

    @Test
    void confirmDepositPaid_rejectsSalesManagerRole() {
        assertForbidden(() -> service.confirmDepositPaid(10L, salesManagerActor));
    }

    @Test
    void issueImportRequest_rejectsSalesManagerRole() {
        assertForbidden(() -> service.issueImportRequest(10L, salesManagerActor));
    }

    @Test
    void markGoodsReceived_rejectsSalesManagerRole() {
        assertForbidden(() -> service.markGoodsReceived(10L, salesManagerActor));
    }

    @Test
    void confirmFinalPayment_rejectsSalesManagerRole() {
        assertForbidden(() -> service.confirmFinalPayment(10L, salesManagerActor));
    }

    @Test
    void factoryEmail_rejectsSalesManagerRole() {
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, salesManagerActor));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String status) {
        return stubTicketWithItems(ticketId, createdById, status, List.of());
    }

    /**
     * Close-flow stub (V55): the compat constructor can't express close_confirmed_at
     * or invoice_on_file, and both drive the three-party gate.
     */
    private TicketDto stubCloseDeal(long ticketId, String status, String paymentStatus,
                                    String fulfillmentStatus, Instant closeConfirmedAt,
                                    boolean invoiceOnFile) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", status, "NORMAL",
            1L, "Sales User", null, null, "Test Customer", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 0, false, paymentStatus, fulfillmentStatus,
            DealStage.DELIVERED, null, null, Instant.now(),
            DealLifecycle.ACTIVE, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null,
            EntryChannel.DESIGNER_LED, null, null, null, null, null,
            PaymentStage.FULLY_PAID, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
            closeConfirmedAt, closeConfirmedAt == null ? null : "Account User", invoiceOnFile,
            null, null);
        TicketDto ticket = new TicketDto(summary, List.of(), List.of(), null, List.of());
        when(ticketRepo.findById(ticketId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    /** A dual-track deal that satisfies every prerequisite, not yet confirmed. */
    private TicketDto stubReadyToConfirm(long ticketId) {
        return stubCloseDeal(ticketId, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, null, true);
    }

    /** The same deal after ฝ่ายบัญชี has signed off — awaiting CEO verification. */
    private TicketDto stubAwaitingCeo(long ticketId) {
        return stubCloseDeal(ticketId, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, Instant.now(), true);
    }

    private TicketDto stubTicketWithItems(long ticketId, long createdById, String status, List<TicketItemDto> items) {
        return stubTicket(ticketId, createdById, status, items, null, null);
    }

    private TicketDto stubTicketWithTracks(long ticketId, long createdById, String status,
                                           String paymentStatus, String fulfillmentStatus) {
        return stubTicket(ticketId, createdById, status, List.of(), paymentStatus, fulfillmentStatus);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String status, List<TicketItemDto> items,
                                 String paymentStatus, String fulfillmentStatus) {
        return stubDeal(ticketId, createdById, status, items, paymentStatus, fulfillmentStatus,
            DealStage.QUOTE_DESIGN_SIDE, null);
    }

    /** Full deal stub: operational lifecycle + pipeline stage/lost state (V50). */
    private TicketDto stubDeal(long ticketId, long createdById, String status, List<TicketItemDto> items,
                               String paymentStatus, String fulfillmentStatus,
                               String salesStage, String lostReason) {
        String lifecycle = lostReason == null ? DealLifecycle.ACTIVE : DealLifecycle.CLOSED_LOST;
        return stubDeal(ticketId, createdById, status, items, paymentStatus, fulfillmentStatus,
            salesStage, lostReason, lifecycle, DepositPolicy.REQUIRED);
    }

    private TicketDto stubDeal(long ticketId, long createdById, String status, List<TicketItemDto> items,
                               String paymentStatus, String fulfillmentStatus,
                               String salesStage, String lostReason,
                               String lifecycle, String depositPolicy) {
        return stubDealWithQuotations(ticketId, createdById, status, items, paymentStatus, fulfillmentStatus,
            salesStage, lifecycle, List.of(), depositPolicy, lostReason);
    }

    private TicketDto stubDealWithQuotations(long ticketId, long createdById, String status, List<TicketItemDto> items,
                                             String paymentStatus, String fulfillmentStatus,
                                             String salesStage, String lifecycle,
                                             List<QuotationDto> quotations) {
        return stubDealWithQuotations(ticketId, createdById, status, items, paymentStatus, fulfillmentStatus,
            salesStage, lifecycle, quotations, DepositPolicy.REQUIRED, null);
    }

    private TicketDto stubDealWithQuotations(long ticketId, long createdById, String status, List<TicketItemDto> items,
                                             String paymentStatus, String fulfillmentStatus,
                                             String salesStage, String lifecycle,
                                             List<QuotationDto> quotations, String depositPolicy, String lostReason) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", status, "NORMAL",
            createdById, "Sales User", null, null, "Test Customer", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, items.size(), false, paymentStatus, fulfillmentStatus,
            salesStage, lostReason, lostReason == null ? null : Instant.now(), Instant.now(),
            lifecycle, TenderRequirement.UNKNOWN, depositPolicy, null, EntryChannel.DESIGNER_LED);
        TicketDto ticket = new TicketDto(summary, items, List.of(),
            quotations.isEmpty() ? null : quotations.get(0), quotations);
        when(ticketRepo.findById(ticketId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    // generateQuotation captures createQuotation's return value to snapshot items/header
    // against the new quotation_id — tests must stub a non-null return or that call NPEs.
    private static QuotationDto quotationOf(long quotationId, long ticketId, String number) {
        return new QuotationDto(quotationId, ticketId, number, 1L, "Sales User",
            Instant.now(), null, null, "THB", 1, "ISSUED");
    }

    private static QuotationDto quotationOf(long quotationId, long ticketId, String number,
                                            String recipientType, int version, String docStatus) {
        return new QuotationDto(quotationId, ticketId, number, 1L, "Sales User",
            Instant.now(), null, null, "THB", version, docStatus, recipientType,
            null, null, null, null, null, null, null, null, null);
    }

    private static GenerateQuotationRequest designerQuotation() {
        return new GenerateQuotationRequest(QuotationRecipient.DESIGNER, null, null, null, null, null, null);
    }

    private static CreateTicketRequest createRequest(Long projectId, List<TicketItemRequest> items) {
        return new CreateTicketRequest("Test deal", "NORMAL", "ลูกค้า", null, projectId, null, null, null, items);
    }

    private static TicketItemRequest sampleItemRequest() {
        return new TicketItemRequest("Brand", "Model", "White", "Matte", "60x60", null,
            BigDecimal.ONE, null, null, null, null, null, null, null);
    }

    private static TicketItemDto sampleItem() {
        return new TicketItemDto(1L, 10L, "Brand", "Model", null, null,
            "pcs", null, BigDecimal.ONE, null, null, null, null, null,
            null, "THB", 0, null, null, null, "PIECE", null, null);
    }

    private static TicketItemDto deliveryItem(long itemId, String qty, String delivered, String fromStock) {
        return new TicketItemDto(itemId, 10L, "Brand", "Model", null, null,
            "pcs", null, new BigDecimal(qty), null, null, null, null, null,
            null, "THB", 0, null, null, null, "PIECE", null, null,
            new BigDecimal(delivered), new BigDecimal(fromStock), null);
    }

    private static TicketDto ticketLike(TicketDto source, List<TicketItemDto> items, String fulfillmentStatus) {
        TicketSummaryDto s = source.summary();
        TicketSummaryDto summary = new TicketSummaryDto(
            s.id(), s.code(), s.type(), s.title(), s.status(), s.priority(),
            s.createdById(), s.createdByName(), s.assignedToId(), s.assignedToName(),
            s.customerName(), s.customerId(), s.projectId(), s.projectName(),
            s.contactId(), s.contactName(), s.note(), s.createdAt(), s.updatedAt(), s.closedAt(),
            items.size(), s.hasEdits(), s.paymentStatus(), fulfillmentStatus,
            s.salesStage(), s.lostReason(), s.lostAt(), s.stageUpdatedAt(),
            s.lifecycle(), s.tenderRequirement(), s.depositPolicy(), s.depositPolicyReason(),
            s.entryChannel(), s.billingDate(), s.dueDate(), s.creditTermDays(), s.lastFollowUpAt(),
            s.nextFollowUpAt(), s.paymentStage(), s.amountPayable(), s.amountPaid(),
            s.amountOutstanding(), s.overdue(),
            s.closeConfirmedAt(), s.closeConfirmedByName(), s.invoiceOnFile(),
            s.cancelReason(), s.cancelledAt());
        return new TicketDto(summary, items, source.events(), source.quotation(), source.quotations());
    }

    private static void assertForbidden(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertBadRequest(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void assertConflict(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
