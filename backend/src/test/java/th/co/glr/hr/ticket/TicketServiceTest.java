package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestService.CancelOpenForTicketResult;

class TicketServiceTest {

    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifRepo = mock(NotificationRepository.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final QuotationRenderer quotationRenderer = new QuotationRenderer();
    private final PricingRequestService pricingRequestService = mock(PricingRequestService.class);
    private final th.co.glr.hr.auth.EmployeeAuthRepository employeeAuthRepo =
        mock(th.co.glr.hr.auth.EmployeeAuthRepository.class);
    {
        // Default stub so every markLost/cancel call in this file doesn't NPE on cancelOpenForTicket's
        // now-non-primitive return value — Mockito's default answer for an unstubbed reference-typed method is null,
        // and TicketService calls .hasAbandoned on the result unconditionally.
        when(pricingRequestService.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new CancelOpenForTicketResult(0, List.of()));
        // Gate default: the manual forward-updateStage gate needs
        // hasActivitySinceLastStageChange() to be true, or every stubDeal-backed forward
        // updateStage call in this Mockito-only file 400s on the missing-tracking-fields
        // message. The gate's real enforcement (including this exact repository call
        // against real SQL) is covered by DealTrackingAndActivityIntegrationTest — this file
        // stays focused on role/ownership/backward-move logic, per its own existing header.
        when(ticketRepo.hasActivitySinceLastStageChange(anyLong())).thenReturn(true);
        // Default stub so every payment-track write in this file (advancePaymentStatus's compare-and-set) reads as
        // "succeeded" unless a specific test overrides it — Mockito's default answer for an unstubbed int-returning
        // method is 0, which requirePaymentAdvanced would read as a lost race and throw a 409.
        when(ticketRepo.advancePaymentStatus(anyLong(), anyString(), any(), anyString())).thenReturn(1);
        // Same shape, same reason, for the ticket-status machine's compare-and-set: an unstubbed int-returning mock
        // answers 0, which requireStatusAdvanced reads as a lost race and turns into a 409. This stub says nothing
        // about the CAS itself — a mocked repository cannot prove a WHERE clause.
        when(ticketRepo.transitionStatus(anyLong(), any(), any())).thenReturn(1);
        // H1 lockdown: account's money actions now also require the deal to be inside account's list scope
        // (TicketRepository.isInAccountScope -- a SQL predicate this Mockito file cannot prove;
        // FinanceDealLockdownIntegrationTest proves it against real Postgres).
        when(ticketRepo.isInAccountScope(anyLong())).thenReturn(true);
    }
    private final TicketService service = new TicketService(
        ticketRepo, notifRepo, new ObjectMapper(), customerRepo, quotationRenderer,
        pricingRequestService, employeeAuthRepo,
        mock(th.co.glr.hr.customer.ContactRepository.class), mock(th.co.glr.hr.customer.ProjectRepository.class),
        mock(th.co.glr.hr.dealquotation.DealQuotationRepository.class));

    private final UserPrincipal salesActor   = actor(1L, "sales");
    private final UserPrincipal otherSales   = actor(2L, "sales");
    private final UserPrincipal importActor  = actor(3L, "import");
    private final UserPrincipal ceoActor     = actor(4L, "ceo");
    private final UserPrincipal accountActor = actor(5L, "account");
    // sales_manager: read + comment oversight only — a project-manager-style follow-up role for the sales team.
    // Never owns a ticket (cannot create one), so every owner-gated write action denies it via the ownership check
    // alone.
    private final UserPrincipal salesManagerActor = actor(8L, "sales_manager");

    @Test
    void get_salesCannotViewOthersTicket() {
        stubTicket(10L, 99L, TicketStatus.DRAFT);
        assertForbidden(() -> service.get(10L, salesActor));
    }

    /** import has its own per-deal endpoint (GET /api/import/deals/{id}); the whole-deal read is refused. */
    @Test
    void get_importIsRefusedTheWholeDealRead() {
        stubTicket(10L, 99L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.get(10L, importActor));
    }

    @Test
    void confirmDepositPaid_byAccount_outOfAccountScope_isRefusedBeforeAnyWrite() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);
        when(ticketRepo.isInAccountScope(10L)).thenReturn(false);

        assertForbidden(() -> service.confirmDepositPaid(10L, accountActor));

        verify(ticketRepo, never()).insertPaymentReceipt(anyLong(), anyString(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void moneyWrites_byAccount_outOfAccountScope_areAllRefused() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        when(ticketRepo.isInAccountScope(10L)).thenReturn(false);

        assertForbidden(() -> service.confirmFinalPayment(10L, accountActor));
        assertForbidden(() -> service.confirmCloseReady(10L, accountActor));
        assertForbidden(() -> service.recordPayment(10L,
            new RecordPaymentRequest("DEPOSIT", new BigDecimal("1.00"), null, null, null, null, false), accountActor));
        assertForbidden(() -> service.financeRevokeCloseConfirmation(10L, null, accountActor));
        assertForbidden(() -> service.financeUpdateStage(10L, DealStage.CLOSED_PAID, null, accountActor));
        assertForbidden(() -> service.financeComment(10L, new CommentRequest("hi"), accountActor));

        verify(ticketRepo, never()).insertPaymentReceipt(anyLong(), anyString(), any(), anyLong(), any(), any(), any(), any());
        verify(ticketRepo, never()).confirmClose(anyLong(), anyLong());
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
            // buildDesc() renders the item's model, not its brand.
            assertThat(text).contains("IssueTimeModel");
            assertThat(text).doesNotContain("EditedBrand");
            assertThat(text).doesNotContain("EditedModel");
            // "เรียน" (A5) and the customer name (B5) are separate template cells that don't land adjacent in the PDF's
            // text-extraction order — assert each independently.
            assertThat(text).contains("เรียน");
            assertThat(text).contains("Issue-Time Customer");
            // B8 carries a literal double space before the colon in the real template.
            assertThat(text).contains("Project  : Issue-Time Project");
        }
    }

    @Test
    void confirmCloseReady_refusedWithoutInvoiceOnFile() {
        // The invoice is produced externally and uploaded; without it there is nothing to verify against.
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, null, false);

        assertConflict(() -> service.confirmCloseReady(10L, accountActor));

        verify(ticketRepo, never()).confirmClose(anyLong(), anyLong());
    }

    @Test
    void verifyClose_refusedBeforeAccountConfirms() {
        stubReadyToConfirm(10L);
        assertConflict(() -> service.verifyClose(10L, ceoActor));
        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void ceoCannotSignBothHalves() {
        // Two signatures means two people. ACCOUNT_ROLES includes ceo as a money fallback; CLOSE_CONFIRM_ROLES
        // deliberately does not, or the CEO could confirm and verify alone.
        stubReadyToConfirm(10L);
        assertForbidden(() -> service.confirmCloseReady(10L, ceoActor));
    }

    @Test
    void verifyClose_reChecksPrerequisitesSoAStaleConfirmationCannotSlipThrough() {
        // Confirmed, then the deal regressed (refund / returned delivery) before the CEO acted. The CEO verifies,
        // never overrides.
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT",
            FulfilmentStatus.FULLY_DELIVERED, Instant.now(), true);

        assertConflict(() -> service.verifyClose(10L, ceoActor));

        verify(ticketRepo, never()).updateLifecycle(eq(10L), eq(DealLifecycle.COMPLETED));
    }

    @Test
    void confirmDepositPaid_rejectsCeoRole() {
        // No CEO fallback grants here — account ONLY.
        assertForbidden(() -> service.confirmDepositPaid(10L, ceoActor));
    }

    @Test
    void reserveStock_dealOwner_isForbiddenAndWritesNothing() {
        TicketItemDto item = deliveryItem(1L, "100.00", "0.00", "0.00");
        // createdById 1L == salesActor's id: this is the owning rep's own deal, yet the rep no longer
        // declares per line (POST /procurement-source replaced it).
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(item),
            "DEPOSIT_PAID", null, DealStage.ORDER_RECEIVED, null);

        assertForbidden(() -> service.reserveStock(10L, new StockReservationRequest(List.of(
            new StockReservationRequest.Line(1L, new BigDecimal("40.00"), null))), salesActor));

        verify(ticketRepo, never()).reserveStock(anyLong(), any());
        verify(ticketRepo, never()).updateFulfillmentStatus(anyLong(), any());
    }

    // Owner ruling 2026-11-07: รับชำระส่วนที่เหลือ is closed until the deal finished money-milestone 3
    // (PROCUREMENT), i.e. reached DELIVERY_SCHEDULING or later. Generic RECORD_PAYMENT stays ungated.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} @ {1}: FINAL_PAYMENT hidden, confirmFinalPayment 409")
    @org.junit.jupiter.params.provider.CsvSource({
        "DEPOSIT_PAID,ORDER_RECEIVED,REQUIRED",
        "DEPOSIT_PAID,DEPOSIT_RECEIVED,REQUIRED",
        "DEPOSIT_PAID,PROCUREMENT,REQUIRED",
        "AWAITING_FINAL_PAYMENT,PROCUREMENT,REQUIRED",
        "CUSTOMER_CONFIRMED,ORDER_RECEIVED,WAIVED",
        "CUSTOMER_CONFIRMED,PROCUREMENT,NOT_REQUIRED"
    })
    void finalPayment_beforeDeliveryScheduling_isHiddenAndRefused(String paymentStatus, String stage, String policy) {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), paymentStatus, "SHIPPING",
            stage, null, DealLifecycle.ACTIVE, policy);

        assertThat(actionCodes(10L, accountActor)).doesNotContain("FINAL_PAYMENT");
        assertConflict(() -> service.confirmFinalPayment(10L, accountActor));
        verify(ticketRepo, never()).insertPaymentReceipt(anyLong(), anyString(), any(), anyLong(), any(), any(), any(), any());
        verify(ticketRepo, never()).advancePaymentStatus(anyLong(), anyString(), anyString(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} @ {1}: FINAL_PAYMENT offered")
    @org.junit.jupiter.params.provider.CsvSource({
        "DEPOSIT_PAID,DELIVERY_SCHEDULING",
        "DEPOSIT_PAID,DELIVERED",
        "AWAITING_FINAL_PAYMENT,DELIVERY_SCHEDULING",
        "AWAITING_FINAL_PAYMENT,DELIVERED"
    })
    void finalPayment_atDeliverySchedulingOrLater_isStillOffered(String paymentStatus, String stage) {
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), paymentStatus, "SHIPPING", stage, null);

        assertThat(actionCodes(10L, accountActor)).contains("FINAL_PAYMENT");
    }

    @Test
    void confirmFinalPayment_rejectsCeoRole() {
        // There is no CEO fallback here — recording ANY payment, including the final one, is
        // account ONLY.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        assertForbidden(() -> service.confirmFinalPayment(10L, ceoActor));
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

    @Test
    void updateStage_ownerCanAdvanceSalesStage() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.PRESENTATION, null);
        service.updateStage(10L, DealStage.SPEC_APPROVED, null, salesActor);
        verify(ticketRepo).updateSalesStage(10L, DealStage.SPEC_APPROVED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.STAGE_CHANGED), eq(DealStage.PRESENTATION), eq(DealStage.SPEC_APPROVED), isNull());
    }

    @Test
    void updateStage_rejectsNonOwnerSalesAndWrongRolesPerTarget() {
        // DEPOSIT_PAID on the payment track so the two grants at the bottom exercise the ROLE routing this test is
        // about, and not the fact gate (updateStage_intoAGatedStage_*). The refusals above it are unaffected either
        // way: requireStageWriteAccess runs first.
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.DEPOSIT_PAID, null,
            DealStage.PRESENTATION, null);
        // another rep's deal
        assertForbidden(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, otherSales));
        // sales cannot set money/import fallback stages
        assertForbidden(() -> service.updateStage(10L, DealStage.DEPOSIT_RECEIVED, null, salesActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.PROCUREMENT, null, salesActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.CLOSED_PAID, null, salesActor));
        // account only money stages; import only PROCUREMENT H1 lockdown: account reaches updateStage only via the
        // finance entrypoint (money stages only).
        assertForbidden(() -> service.financeUpdateStage(10L, DealStage.SPEC_APPROVED, null, accountActor));
        assertForbidden(() -> service.updateStage(10L, DealStage.SPEC_APPROVED, null, importActor));
        service.financeUpdateStage(10L, DealStage.DEPOSIT_RECEIVED, "บัญชีแก้ย้อนหลังจากเอกสารรับเงิน", accountActor);
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

    /** Each of the four gated stages, with its own fact absent. ceo passes every role gate. */
    @Test
    void updateStage_intoAGatedStage_isRefusedWhileItsFactIsMissing_andWritesNothing() {
        // No payment track at all -> no verified customer order, no deposit, not fully paid.
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertConflict(() -> service.updateStage(10L, DealStage.ORDER_RECEIVED, "x", ceoActor));
        assertConflict(() -> service.updateStage(10L, DealStage.DEPOSIT_RECEIVED, "x", ceoActor));
        assertConflict(() -> service.updateStage(10L, DealStage.CLOSED_PAID, "x", ceoActor));
        // A deposit NOTICE is a document, not money: DEPOSIT_RECEIVED must still refuse.
        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.DEPOSIT_NOTICE_ISSUED, null,
            DealStage.ORDER_RECEIVED, null);
        assertConflict(() -> service.updateStage(11L, DealStage.DEPOSIT_RECEIVED, "x", ceoActor));
        // GOODS_RECEIVED is GLR's own warehouse (S17) — the customer has received nothing, so DELIVERED must refuse.
        // This is the same distinction deliveryGateComplete documents.
        stubDeal(12L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.FULLY_PAID,
            FulfilmentStatus.GOODS_RECEIVED, DealStage.DELIVERY_SCHEDULING, null);
        assertConflict(() -> service.updateStage(12L, DealStage.DELIVERED, "x", ceoActor));

        verify(ticketRepo, never()).updateSalesStage(anyLong(), anyString());
    }

    /** CLOSED_PAID needs BOTH halves — the same bar maybeAdvanceClosedPaid clears. Paid in full with the goods
     * still undelivered is the case a FULLY_PAID-only gate let through, i.e. a manual path claiming a stage the
     * automatic path would have refused. */
    @Test
    void updateStage_closedPaid_needsDeliveryAsWellAsPayment() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.FULLY_PAID,
            FulfilmentStatus.GOODS_RECEIVED, DealStage.DELIVERY_SCHEDULING, null);
        assertConflict(() -> service.financeUpdateStage(10L, DealStage.CLOSED_PAID, "รับเงินครบ", accountActor));

        stubDeal(11L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.FULLY_PAID, null,
            DealStage.DELIVERY_SCHEDULING, null);
        assertConflict(() -> service.financeUpdateStage(11L, DealStage.CLOSED_PAID, "รับเงินครบ", accountActor));

        verify(ticketRepo, never()).updateSalesStage(anyLong(), anyString());

        // Both halves in place -> accepted. From DELIVERED, the adjacent move: nothing MANDATORY is stepped over, so
        // no note is needed and the facts are the only thing permitting it.
        stubDeal(12L, 1L, TicketStatus.DRAFT, List.of(), PaymentTrack.FULLY_PAID,
            FulfilmentStatus.FULLY_DELIVERED, DealStage.DELIVERED, null);
        service.financeUpdateStage(12L, DealStage.CLOSED_PAID, null, accountActor);
        verify(ticketRepo).updateSalesStage(12L, DealStage.CLOSED_PAID);
    }

    @Test
    void markLost_cascadesCancelOpenPricingRequestsWithTheDealsOwnReason() {
        stubDeal(10L, 1L, TicketStatus.DRAFT, List.of(), null, null, DealStage.NEGOTIATION, null);
        service.markLost(10L, DealLostReason.PRICE, "แพ้ราคาคู่แข่ง", salesActor);
        verify(pricingRequestService).cancelOpenForTicket(10L, DealLostReason.PRICE, PricingRequestService.DeadDealCause.LOST, salesActor);
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

    @Test
    void waiveDeposit_ownerOrSalesManagerOnlyAndIssueImportRequestCanBypassNotice() {
        // Deposit policy is set by the OWNING sales rep, or sales_manager as a backup.
        // salesActor (id 1L) owns ticket 10L (stubDeal's createdById), so it grants here; the
        // account/ceo roles do not.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null);

        service.waiveDeposit(10L, DepositPolicy.WAIVED, "ลูกค้าเครดิตดี", salesActor);

        verify(ticketRepo).updateDepositPolicy(10L, DepositPolicy.WAIVED, "ลูกค้าเครดิตดี", 1L);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.POLICY_CHANGED), eq(DealStage.ORDER_RECEIVED), eq(DealStage.ORDER_RECEIVED),
            eq("deposit_policy → WAIVED — ลูกค้าเครดิตดี"));
        // account, ceo, and a non-owning sales rep are still refused. sales_manager is NOT in
        // this list — see waiveDeposit_grantsSalesManagerAsBackupOwner below.
        assertForbidden(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, "account ขอเอง", accountActor));
        assertForbidden(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, "ceo ขอเอง", ceoActor));
        assertForbidden(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, "sales อื่นขอเอง", otherSales));
        assertBadRequest(() -> service.waiveDeposit(10L, DepositPolicy.WAIVED, " ", salesActor));

        // Rule 5 (payment-track state machine): null does not qualify as "deposit bypassed" on
        // its own — a bypass-policy deal must have actually reached CUSTOMER_CONFIRMED first (via
        // confirmCustomer). Straight from null it is refused, and the
        // CUSTOMER_CONFIRMED case below is what still proves the issuable gate (shared with the
        // per-factory issue) lets a WAIVED/NOT_REQUIRED/CREDIT_CUSTOMER deal bypass the notice.
        TicketDto nullWaived = stubDeal(11L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null, DealLifecycle.ACTIVE, DepositPolicy.WAIVED);
        assertConflict(() -> service.requireImportRequestIssuable(nullWaived.summary()));

        TicketDto confirmedWaived = stubDeal(13L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "CUSTOMER_CONFIRMED", null,
            DealStage.ORDER_RECEIVED, null, DealLifecycle.ACTIVE, DepositPolicy.WAIVED);
        service.requireImportRequestIssuable(confirmedWaived.summary());

        TicketDto nullRequired = stubDeal(12L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null,
            DealStage.ORDER_RECEIVED, null, DealLifecycle.ACTIVE, DepositPolicy.REQUIRED);
        assertConflict(() -> service.requireImportRequestIssuable(nullRequired.summary()));
    }

    // H1 lockdown: account gets its action list from financeActions (same gates, no ticket view-access check).
    private List<String> actionCodes(long ticketId, UserPrincipal actor) {
        var response = "account".equals(actor.role())
            ? service.financeActions(ticketId, actor)
            : service.actions(ticketId, actor);
        return response.availableActions().stream().map(TicketResponses.TicketActionDto::action).toList();
    }

    /**
     * FINAL_PAYMENT must not be advertised to the CEO — offering a button
     * that 403s on click is exactly what {@link #canIssueImportRequest}'s "never advertise a
     * dead action" discipline exists to prevent. (RECORD_PAYMENT's own advertisement,
     * {@code canRecordPayment}, additionally requires {@code amountPayable > 0}, which the stub
     * infra here always zeroes — that half of the gate is pinned by
     * {@code DepositPolicyAuthzIntegrationTest}'s real-DB companion instead, where amountPayable is
     * computed for real.)
     */
    @Test
    void actions_neverOffersFinalPaymentToCeo_onlyToAccount() {
        stubDeal(52L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED",
            DealStage.DELIVERY_SCHEDULING, null);

        assertThat(actionCodes(52L, ceoActor)).doesNotContain("FINAL_PAYMENT");
        assertThat(actionCodes(52L, accountActor)).contains("FINAL_PAYMENT");
    }

    @Test
    void confirmFinalPayment_afterFullDelivery_advancesDealToClosedPaid() {
        // Delivered in full, then the balance is paid — both gates satisfied, so CLOSED_PAID advances.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(),
            "AWAITING_FINAL_PAYMENT", FulfilmentStatus.FULLY_DELIVERED, DealStage.DELIVERED, null);
        when(ticketRepo.payableAmount(10L)).thenReturn(new BigDecimal("1000.00"));
        when(ticketRepo.sumPaid(10L)).thenReturn(new BigDecimal("500.00"), new BigDecimal("500.00"),
            new BigDecimal("1000.00"));
        service.confirmFinalPayment(10L, accountActor);
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
    void salesManager_stageWriteIsTheOnlyTicketWritePower() {
        // The pipeline stage/lost/reopen trio is the deliberate exception to handoff 58's read-only invariant —
        // operational actions must stay denied.
        stubDeal(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of(), null, null, DealStage.NEGOTIATION, null);
        assertForbidden(() -> service.confirmCustomer(10L, salesManagerActor));
        service.markLost(10L, DealLostReason.RELATIONSHIP, null, salesManagerActor);
        verify(ticketRepo).markDealLost(10L, DealLostReason.RELATIONSHIP);
    }

    @Test
    void close_dualTrackRejectsWhenPaymentIncomplete() {
        stubCloseDeal(10L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT",
            "GOODS_RECEIVED", null, true);
        assertConflict(() -> service.confirmCloseReady(10L, accountActor));
    }

    @Test
    void editItems_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        TicketItemRequest req = new TicketItemRequest(
            "Brand", "Model", "Color", "Texture", "Size", "Factory",
            BigDecimal.ONE, null, "PIECE", null, null, null, null, "THB");

        assertForbidden(() -> service.editItems(10L, new EditItemsRequest(List.of(req), null), otherSales));
    }

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String status) {
        return stubTicketWithItems(ticketId, createdById, status, List.of());
    }

    /** Close-flow stub (V56): the compat constructor can't express close_confirmed_at or invoice_on_file, and both
     * drive the three-party gate. */
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
            null, null, null, null, null, null, false, false, null, 0);
        TicketDto ticket = new TicketDto(summary, List.of(), List.of(), null, List.of());
        when(ticketRepo.findById(ticketId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    /** A dual-track deal that satisfies every prerequisite, not yet confirmed. */
    private TicketDto stubReadyToConfirm(long ticketId) {
        return stubCloseDeal(ticketId, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID",
            FulfilmentStatus.FULLY_DELIVERED, null, true);
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
            lifecycle, TenderRequirement.UNKNOWN, depositPolicy, null, EntryChannel.DESIGNER_LED,
            null, null, null, null,
            // Gate default: a non-null nextFollowUpAt so the manual
            // forward-updateStage gate's other half is satisfied — see the class-level stub
            // of hasActivitySinceLastStageChange() above for the same reasoning.
            LocalDate.now(),
            PaymentStage.NOT_REQUIRED, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
            null, null, false, null, null, null, null, null, null, false, false, null, 0);
        TicketDto ticket = new TicketDto(summary, items, List.of(),
            quotations.isEmpty() ? null : quotations.get(0), quotations);
        when(ticketRepo.findById(ticketId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    // generateQuotation captures createQuotation's return value to snapshot items/header against the new
    // quotation_id — tests must stub a non-null return or that call NPEs.
    private static QuotationDto quotationOf(long quotationId, long ticketId, String number) {
        return new QuotationDto(quotationId, ticketId, number, 1L, "Sales User",
            Instant.now(), null, null, "THB", 1, "ISSUED");
    }

    private static QuotationDto quotationOf(long quotationId, long ticketId, String number,
                                            String recipientType, int version, String docStatus) {
        return new QuotationDto(quotationId, ticketId, number, 1L, "Sales User",
            Instant.now(), null, null, "THB", version, docStatus, recipientType,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TicketItemDto deliveryItem(long itemId, String qty, String delivered, String fromStock) {
        return new TicketItemDto(itemId, 10L, "Brand", "Model", null, null,
            "pcs", null, new BigDecimal(qty), null, null, null, null, null,
            null, "THB", 0, null, null, null, "PIECE", null, null,
            new BigDecimal(delivered), new BigDecimal(fromStock), null);
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
