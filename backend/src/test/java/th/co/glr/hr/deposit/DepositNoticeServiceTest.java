package th.co.glr.hr.deposit;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.PaymentReceiptDto;
import th.co.glr.hr.ticket.PaymentTrack;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

class DepositNoticeServiceTest {

    private final DepositNoticeRepository docs = mock(DepositNoticeRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final DepositNoticeRenderer renderer = mock(DepositNoticeRenderer.class);
    private final RemainingInvoiceRenderer remainingRenderer = mock(RemainingInvoiceRenderer.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final CustomerQuotationRepository quotationRepo = mock(CustomerQuotationRepository.class);
    private final DepositNoticeService service = new DepositNoticeService(
        docs, ticketRepo, notifications, renderer, remainingRenderer, customerRepo, quotationRepo);
    {
        // Default stub so every payment-track write in this file (advancePaymentStatus's
        // compare-and-set) reads as "succeeded" unless a specific test overrides it — Mockito's
        // default answer for an unstubbed int-returning method is 0, which the service would
        // read as a lost race and turn into a 409. any() (untyped) matches null too, so a null
        // "expected" would be covered by this same stub (not exercised by this file's fixtures,
        // which always start from a real status, but kept for consistency with TicketServiceTest).
        when(ticketRepo.advancePaymentStatus(anyLong(), anyString(), any(), anyString())).thenReturn(1);
    }

    private final UserPrincipal owner = new UserPrincipal(
        1L, "sales@glr.co.th", "Sales", "sales", 1L, true, LocalDate.of(2026, 1, 1), false, 1L, false);
    // sales_manager: read-only oversight of deposit notices — never owns a ticket
    // (no create access), so every owner-gated write is denied by ownership alone.
    private final UserPrincipal salesManagerActor = new UserPrincipal(
        8L, "sales_manager@glr.co.th", "Sales Manager", "sales_manager", 8L, true,
        LocalDate.of(2026, 1, 1), false, null, false);

    // ── issue(): the document IS the payment-track step ─────────────────────

    @Test
    void issue_advancesPaymentTrackAndKeepsTicketStatus() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.issue(99L, 1L, "Sales")).thenReturn(Optional.of("GLRD69001"));

        service.issue(99L, owner);

        // Payment track advances; the main status is untouched (no document_issued flip
        // — that side effect killed the dual-track UI and let unpaid tickets close).
        verify(ticketRepo).advancePaymentStatus(
            10L, DepositPolicy.REQUIRED, "CUSTOMER_CONFIRMED", PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.DEPOSIT_NOTICE_ISSUED),
            eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), anyString());
        verify(ticketRepo, never()).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.DOCUMENT_ISSUED), anyString(), anyString(), anyString());
    }

    @Test
    void issue_requiresQuotationIssuedStatus() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.APPROVED, null);
        assertConflict(() -> service.issue(99L, owner));
    }

    @Test
    void issue_requiresCustomerConfirmedOrDepositNoticeIssuedPayment() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, null);
        assertConflict(() -> service.issue(99L, owner));
        verify(docs, never()).issue(anyLong(), anyLong(), anyString());

        // A payment status genuinely past the deposit-notice step (already paid) is also
        // refused — only CUSTOMER_CONFIRMED (first issue) or DEPOSIT_NOTICE_ISSUED (a revision,
        // tested separately below — PaymentTrack's one legal self-loop) are legal here.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID");
        assertConflict(() -> service.issue(99L, owner));
        verify(docs, never()).issue(anyLong(), anyLong(), anyString());
    }

    /**
     * Rule from the payment-track redesign (site 2): re-issuing FROM DEPOSIT_NOTICE_ISSUED
     * itself — a revision — is now a legal self-loop, where it used to be refused with the same
     * CONFLICT as any other non-CUSTOMER_CONFIRMED status. See {@code PaymentTrackIntegrationTest}
     * for the real-DB proof (new doc number minted, old one SUPERSEDED, payment_status unchanged).
     */
    @Test
    void issue_fromDepositNoticeIssued_isNowAllowedAsARevision() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED");
        when(docs.issue(99L, 1L, "Sales")).thenReturn(Optional.of("GLRD69002"));

        service.issue(99L, owner); // must not throw

        verify(ticketRepo).advancePaymentStatus(10L, DepositPolicy.REQUIRED,
            "DEPOSIT_NOTICE_ISSUED", PaymentTrack.DEPOSIT_NOTICE_ISSUED);
    }

    // ── sales_manager oversight (read only, zero write actions) ─────────────
    // Product decision (2026-07-16): sales_manager is a read+comment-only follow-up
    // role for the sales team on tickets; the same rule extends to deposit notices
    // (money-adjacent customer documents). Added to VIEWER_ROLES only.

    @Test
    void listByTicket_salesManagerCanViewAnyTicketsNotices() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.findByTicket(10L)).thenReturn(List.of());

        service.listByTicket(10L, salesManagerActor); // must not throw

        verify(docs).findByTicket(10L);
    }

    @Test
    void getById_salesManagerCanViewAnyonesDocument() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");

        DepositNoticeDto doc = service.getById(99L, salesManagerActor); // must not throw
        assertThat(doc.id()).isEqualTo(99L);
    }

    // ── Phase B (role-scoped views): import has no business reading deposit ─
    // notices — a customer financial document, unlike a ticket's other fields
    // which import may still see. Mirrors salesViewScope.js hiding the whole
    // "depositNotice" section from import's TicketDetailPage.

    private final UserPrincipal importActor = new UserPrincipal(
        3L, "import@glr.co.th", "Import", "import", 3L, true, LocalDate.of(2026, 1, 1), false, null, false);

    @Test
    void listByTicket_importDenied() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.listByTicket(10L, importActor));
    }

    @Test
    void getById_importDenied() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.getById(99L, importActor));
    }

    // downloadRemainingInvoice_importDenied removed (O1, GLA-99 step 2 review-round-1): the
    // stateless GET .../remaining-invoice/file endpoint and DepositNoticeService#getRemainingInvoiceXlsx
    // it called are gone — every write/read path now goes through RemainingInvoiceService, whose
    // own viewer gate is proven forbidden-for-import by RemainingInvoiceServiceIntegrationTest
    // #read_import_isRefused_onIssuedDocument (real DB). remainingInvoiceOptions_importDenied below
    // still pins requireTicketViewer's import-denial on the surviving stateless /options preview.

    @Test
    void remainingInvoiceOptions_importDenied() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.getRemainingInvoiceOptions(10L, importActor));
    }

    @Test
    void remainingInvoiceOptions_otherSalesRepDenied() {
        // sales scoped to the deal it OWNS — createdById=1 (owner) in stubTicket; a different
        // sales rep must be refused, mirroring requireTicketViewer's ownership check.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        UserPrincipal otherSalesRep = new UserPrincipal(
            77L, "other-sales@glr.co.th", "Other Sales", "sales", 77L, true,
            LocalDate.of(2026, 1, 1), false, null, false);
        assertForbidden(() -> service.getRemainingInvoiceOptions(10L, otherSalesRep));
    }

    // ── Remaining invoice item sourcing / capacity / defaults (branch fix) ──────────────────
    // The real production shape: every ticket_item.approved_price is NULL for a deal priced
    // through PricingRequest -> PricingDecision -> CustomerQuotation (stubTicket's items are
    // always List.of() — see stubTicket below), so a fixture that also sets approvedPrice would
    // hide the exact bug this branch fixes. None of these tests ever populate ticket items with
    // approvedPrice unless the test is specifically about the legacy back-compat path.

    @Test
    void remainingInvoiceXlsx_sourcesItemsFromAcceptedQuotationUsingStoredLineSubtotal() throws Exception {
        // Bypass policy (WAIVED): an ACCEPTED quotation qualifies with NO deposit notice needed at
        // all (owner ruling A's exception) — deliberately used here so THIS test can isolate
        // "stored lineSubtotal used verbatim" from the separate deposit-matching rules (B/C),
        // which have their own dedicated tests below.
        stubTicketWithDepositPolicy(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", DepositPolicy.WAIVED);
        // lineSubtotal deliberately NOT equal to finalUnitPrice*qty (299.99 vs 300.00) so this
        // test actually proves the stored subtotal is used verbatim, not recomputed.
        CustomerQuotationItemDto item = new CustomerQuotationItemDto(
            1L, 1, 1L, 1L, "กระเบื้อง A", null, "PER_SQM", new BigDecimal("3"),
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
            null, new BigDecimal("299.99"), BigDecimal.ZERO, new BigDecimal("299.99"));
        when(quotationRepo.findByTicket(10L)).thenReturn(
            List.of(quotation(1L, 10L, "ACCEPTED", 1, List.of(item))));

        // O1 (GLA-99 step 2 review-round-1): getRemainingInvoiceXlsx is gone — asserted via the
        // SAME package-private resolveRemainingInvoiceSnapshot RemainingInvoiceService itself
        // freezes off of, so this still pins resolveRemainingInvoice's own item-sourcing logic.
        DepositNoticeService.RemainingInvoiceSnapshot snap =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner);
        DepositNoticeService.ResolvedRemainingInvoice resolved = snap.resolved();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).amount()).isEqualByComparingTo("299.99");
        assertThat(resolved.items().get(0).netUnitPrice()).isEqualByComparingTo("100");
        // Bypass policy -> no deduction row, depositAmount is zero.
        assertThat(resolved.depositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void remainingInvoiceXlsx_nullLineSubtotalFallsBackToFinalUnitPriceTimesQtyHalfUp() throws Exception {
        // Finding 5 (preview/render agreement, the null case): quotationLineAmount's
        // null-lineSubtotal branch — line_subtotal is nullable (V74) — must fall back to
        // HALF_UP(finalUnitPrice * qty), the SAME function both the subtotal-match check (rule B)
        // and this item's own amount field route through, so a null there can never silently
        // diverge between the options preview and the actual render.
        stubTicketWithDepositPolicy(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", DepositPolicy.WAIVED);
        // finalUnitPrice * qty = 33.335 * 3 = 100.005 exactly -> HALF_UP rounds the half-cent UP
        // to 100.01, proving the actual recompute+rounding ran, not merely "some non-null number".
        CustomerQuotationItemDto item = new CustomerQuotationItemDto(
            1L, 1, 1L, 1L, "กระเบื้อง B", null, "PER_SQM", new BigDecimal("3"),
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("33.335"),
            null, null, BigDecimal.ZERO, null); // lineSubtotal = null
        when(quotationRepo.findByTicket(10L)).thenReturn(
            List.of(quotation(1L, 10L, "ACCEPTED", 1, List.of(item))));

        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).amount()).isEqualByComparingTo("100.01");
    }

    @Test
    void remainingInvoiceOptions_fallsBackToIssuedDepositNoticeItemsWhenNoAcceptedQuotation() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        // An ISSUED (not ACCEPTED) quotation must be ignored by the remaining-invoice path —
        // unlike buildItemsFromRequest's pickQuotation (deposit notice creation), which DOES fall
        // back to ISSUED. See resolveRemainingInvoice's own Javadoc for why these differ.
        //
        // Finding 1 (vacuous test): this test used to only assert itemCount/depositAmount, which
        // stayed green even under the mutation "let ISSUED quotations qualify too" — that mutation
        // would source items from the ISSUED quotation's "Ignored"/amount=10 line instead of the
        // notice's own "จากใบแจ้งยอดมัดจำ"/amount=100 line, but itemCount would still read 2 (1 item
        // + 1 deposit row) either way. Asserting the actual description and itemsTotal below makes
        // that mutation observable and kills it.
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ISSUED", 1, List.of(quotationItem("Ignored", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "จากใบแจ้งยอดมัดจำ", new BigDecimal("2"), "แผ่น",
            new BigDecimal("50"), null, new BigDecimal("50"), new BigDecimal("100"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", new BigDecimal("40"), List.of(noticeItem))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNull();
        assertThat(options.itemCount()).isEqualTo(2); // 1 item + 1 deposit row
        assertThat(options.depositAmount()).isEqualByComparingTo("40");
        // itemsTotal must be the NOTICE item's amount (100), never the ignored ISSUED quotation's
        // (10) — this is what actually distinguishes "sourced from the notice" from "sourced from
        // the wrongly-qualifying ISSUED quotation".
        assertThat(options.itemsTotal()).isEqualByComparingTo("100");
        // No DEPOSIT-kind payment_receipt rows stubbed -> falls back to the issued notice's own
        // doc number as the deposit-reference default.
        assertThat(options.defaultDepositReference()).isEqualTo("GLRD69001");
    }

    @Test
    void remainingInvoiceOptions_defaultDepositReferencePrefersReceiptRefOverNoticeDocNumber() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // reference = "QT-2026-1" (the quotation's own number, from the `quotation()` fixture's
        // "QT-2026-" + id convention) — the match rule this branch adds (deposit_notice.reference
        // == quotation.number). Notice's OWN items (ruling D11: items are notice-sourced, not
        // quotation-sourced) total 10, deposit = 4 (<= the 10 item total) so rule C's negative-net
        // refusal does not fire — this test is about deposit-reference precedence, not the amount
        // rules (which have their own dedicated tests).
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", BigDecimal.ONE, "แผ่น",
            BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", new BigDecimal("10"), new BigDecimal("4"),
                List.of(noticeItem))));
        when(ticketRepo.findReceiptsByTicket(10L)).thenReturn(List.of(
            new PaymentReceiptDto(1L, 10L, "DEPOSIT", new BigDecimal("4"), "THB",
                Instant.now(), 1L, "Account", null, 5L, "AI2600145", Instant.now())));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNull();
        assertThat(options.defaultDepositReference()).isEqualTo("AI2600145");
        assertThat(options.depositReferenceOptions())
            .extracting(RemainingInvoiceOptionsDto.ReferenceOption::value)
            .contains("AI2600145", "GLRD69001", "");
    }

    // remainingInvoiceXlsx_explicitParamsPassThroughToRenderer removed (O1): reference/deposit-
    // reference/issueDate/notes pass-through was the deleted stateless /file endpoint's own query-
    // param semantics, which has no successor — RemainingInvoiceService's createDraft/updateDraft
    // own field-override semantics (req.reference()/req.depositReference()/req.docDate()/
    // req.notes(), each falling back to a resolved/existing default when omitted) are exercised by
    // RemainingInvoiceServiceIntegrationTest instead.

    @Test
    void createDraft_overCapacityThrowsConflict_viaRemainingInvoiceServicesOwnGate() {
        // O1 successor for the deleted remainingInvoiceXlsx_overCapacityThrowsConflictWithoutTruncating:
        // capacity is no longer enforced by DepositNoticeService#getRemainingInvoiceXlsx (gone) —
        // it is RemainingInvoiceService#createDraft's own requireCapacity gate now (see that
        // class), exercised here through the SAME resolveRemainingInvoiceSnapshot content this
        // file's other fixtures already drive.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(quotation(1L, 10L, "ACCEPTED", 1,
            List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // A deposit row too -> MAX_ITEM_ROWS notice items + 1 deposit row = one over capacity.
        List<DepositNoticeItemDto> noticeItems = depositNoticeItems(RemainingInvoiceRenderer.MAX_ITEM_ROWS);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, new BigDecimal("40"), noticeItems)));
        RemainingInvoiceRepository storedMock = mock(RemainingInvoiceRepository.class);
        when(storedMock.findByTicket(10L)).thenReturn(List.of());
        RemainingInvoiceService remainingInvoiceService =
            new RemainingInvoiceService(storedMock, service, ticketRepo, remainingRenderer);

        assertConflict(() -> remainingInvoiceService.createDraft(10L, null, owner));
        verify(storedMock, never()).replaceItems(anyLong(), any());
    }

    @Test
    void remainingInvoiceOptions_neverThrowsForOverCapacityOnlyReportsIt() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(quotation(1L, 10L, "ACCEPTED", 1,
            List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        List<DepositNoticeItemDto> noticeItems = depositNoticeItems(RemainingInvoiceRenderer.MAX_ITEM_ROWS);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, new BigDecimal("40"), noticeItems)));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner); // must not throw

        assertThat(options.blockingReason()).isNull();
        assertThat(options.itemCount()).isEqualTo(RemainingInvoiceRenderer.MAX_ITEM_ROWS + 1);
        assertThat(options.maxItems()).isEqualTo(RemainingInvoiceRenderer.MAX_ITEM_ROWS);
        assertThat(options.itemCount()).isGreaterThan(options.maxItems());
    }

    @Test
    void remainingInvoiceXlsx_noAcceptedQuotationNoIssuedNoticeNoLegacyThrowsConflict() throws Exception {
        // stubTicket's items are always List.of() (no legacy approved_price), and both repos are
        // unstubbed (Mockito default: empty list) -> nothing this document could possibly price.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        // O1: getRemainingInvoiceXlsx is gone; this exact "truly nothing to price" terminal case
        // (no accepted quotation at all -> resolveLegacy -> no issued notice AND no legacy
        // approved_price items either) is the ONE branch resolveRemainingInvoice/resolveLegacy
        // itself still throws 409 for, regardless of caller — see resolveLegacy's own Javadoc.
        // Every OTHER blocking case (negative net, empty matched-notice snapshot, deposit>items)
        // reports via blockingReason instead (see the sibling tests converted to
        // resolveRemainingInvoiceSnapshot elsewhere in this file), which only createDraft/
        // getRemainingInvoiceXlsx (now gone) used to turn into a 409.
        assertConflict(() -> service.resolveRemainingInvoiceSnapshot(10L, null, owner));
    }

    @Test
    void remainingInvoiceXlsx_legacyApprovedPriceUsedWhenNoQuotationOrIssuedNotice() throws Exception {
        TicketItemDto approvedItem = new TicketItemDto(1L, 10L, "SCG", "Model", "White", "Matte", "60x60",
            "Factory", new BigDecimal("2"), null, null, null, null, null, new BigDecimal("150"), "THB",
            0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", List.of(approvedItem));

        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).amount()).isEqualByComparingTo("300"); // 150*2
    }

    // ── Owner ruling A-D: quotation selection, subtotal match, negative-net refusal ─────────

    @Test
    void remainingInvoiceXlsx_designerQuotationExcludedBuyerQuotationChosenAutomatically() throws Exception {
        // Designer's early quotation is ACCEPTED too (a real shape: several pricing requests on
        // one deal), but has NO matching issued deposit notice — it must never qualify on its own.
        // The buyer's quotation (higher id = newer) DOES have a matching notice and must win.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        CustomerQuotationItemDto designerItem = quotationItem("Designer pick", BigDecimal.ONE, "PER_PIECE",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        CustomerQuotationItemDto buyerItem = quotationItem("Buyer final", BigDecimal.ONE, "PER_PIECE",
            new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("20"));
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(designerItem)),
            quotation(2L, 10L, "ACCEPTED", 1, List.of(buyerItem))));
        // Ruling D11: items come from the MATCHED NOTICE's own snapshot, never the quotation's —
        // this notice item's description ("From matched notice") is deliberately unlike EITHER
        // quotation's own item text, so a passing assertion below proves sourcing switched to the
        // notice, not merely that "the buyer's quotation" (vs the designer's) was correctly chosen.
        DepositNoticeItemDto matchedNoticeItem = new DepositNoticeItemDto(
            1L, 1, "From matched notice", BigDecimal.ONE, "แผ่น",
            new BigDecimal("20"), null, new BigDecimal("20"), new BigDecimal("20"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69002", "QT-2026-2", new BigDecimal("20"), new BigDecimal("5"),
                List.of(matchedNoticeItem))));

        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).description()).isEqualTo("From matched notice");
        // reference still identifies WHICH quotation was chosen (the buyer's, not the designer's) —
        // that selection logic is unchanged by D11, only the item/deduction SOURCE is.
        assertThat(resolved.defaultReference()).isEqualTo("QT-2026-2");
        assertThat(resolved.depositAmount()).isEqualByComparingTo("5");
    }

    @Test
    void remainingInvoiceOptions_twoQualifyingQuotationsDefaultsToNewest() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("Older", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN))),
            quotation(2L, 10L, "ACCEPTED", 1, List.of(quotationItem("Newer", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, BigDecimal.ZERO, depositNoticeItems(1)),
            issuedNotice(6L, 10L, 2, "GLRD69002", "QT-2026-2", BigDecimal.TEN, BigDecimal.ZERO, depositNoticeItems(1))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNull();
        assertThat(options.quotationOptions()).hasSize(2);
        assertThat(options.defaultQuotationId()).isEqualTo(2L);
        assertThat(options.defaultReference()).isEqualTo("QT-2026-2");
    }

    @Test
    void remainingInvoiceOptions_explicitQuotationIdHonoured() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("Older", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN))),
            quotation(2L, 10L, "ACCEPTED", 1, List.of(quotationItem("Newer", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, BigDecimal.ZERO, depositNoticeItems(1)),
            issuedNotice(6L, 10L, 2, "GLRD69002", "QT-2026-2", BigDecimal.TEN, BigDecimal.ZERO, depositNoticeItems(1))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, 1L, owner);

        assertThat(options.defaultReference()).isEqualTo("QT-2026-1");
    }

    @Test
    void remainingInvoiceXlsx_foreignQuotationIdRefused() throws Exception {
        // quotationId 999 belongs to no quotation of this ticket at all.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, BigDecimal.ZERO, depositNoticeItems(1))));

        // O1: getRemainingInvoiceXlsx is gone; the 400 gate lives in resolveRemainingInvoice
        // itself (shared by every caller, including resolveRemainingInvoiceSnapshot below).
        assertThatThrownBy(() -> service.resolveRemainingInvoiceSnapshot(10L, 999L, owner))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Finding 1 (Opus review, mutation coverage gap): findQualifyingQuotations gates on
     * {@code QuotationStatus.ACCEPTED} only — an ISSUED-but-not-yet-accepted quotation must NOT
     * qualify as the final quotation, even when a matching ISSUED deposit notice exists for it.
     * Mutating that gate to also accept ISSUED left every pre-existing test green, because
     * resolveLegacy (the fallback reached when nothing qualifies) happens to source items from the
     * SAME matching notice independently of any quotation link — so itemCount/blockingReason are
     * identical either way. What actually distinguishes "qualified via the accepted-quotation path"
     * from "fell through to legacy" is {@code defaultReference}/{@code referenceOptions}:
     * resolveFromQuotation sets defaultReference to the quotation's own number and populates
     * referenceOptions from buildQuotationReferenceOptions, while resolveLegacy hard-codes both to
     * null/empty (see resolveRemainingInvoice's own Javadoc) — that is what this test pins.
     * Mutation-checked: widening the ACCEPTED check to `|| QuotationStatus.ISSUED.equals(...)`
     * turns this one test red (defaultReference becomes "QT-2026-1", referenceOptions non-empty)
     * and every other test in this file stays green; reverted after confirming. */
    @Test
    void remainingInvoiceOptions_issuedButNotAcceptedQuotationDoesNotQualifyAsFinalQuotation() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ISSUED", 1, List.of(quotationItem("Not yet accepted", BigDecimal.ONE,
                "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", new BigDecimal("2"), "แผ่น",
            new BigDecimal("50"), null, new BigDecimal("50"), new BigDecimal("100"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, BigDecimal.ZERO,
                List.of(noticeItem))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNull(); // resolveLegacy still prices it, via the notice
        assertThat(options.itemsTotal()).isEqualByComparingTo("100"); // sourced from the notice regardless
        // The discriminator: an ISSUED (not ACCEPTED) quotation must never be treated as a
        // "qualifying final quotation" — defaultReference/referenceOptions stay legacy-empty.
        assertThat(options.defaultReference()).isNull();
        assertThat(options.referenceOptions()).isEmpty();
        assertThat(options.defaultQuotationId()).isNull();
    }

    @Test
    void remainingInvoiceOptions_acceptedQuotationWithoutIssuedNoticeIsBlockedUnderRequiredPolicy() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED"); // depositPolicy REQUIRED
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // No issued deposit notice at all -> the accepted quotation cannot qualify.

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner); // must not throw

        assertThat(options.blockingReason()).contains("QT-2026-1");
        assertThat(options.itemCount()).isZero();
    }

    // remainingInvoiceXlsx_acceptedQuotationWithoutIssuedNoticeThrowsConflict removed (O1):
    // duplicate of remainingInvoiceOptions_acceptedQuotationWithoutIssuedNoticeIsBlockedUnderRequiredPolicy
    // above, which pins the identical fixture's blockingReason via the surviving /options entry
    // point — resolveRemainingInvoice never actually throws for this case (see that method).

    @Test
    void remainingInvoiceXlsx_bypassPolicyQualifiesAcceptedQuotationWithoutNoticeAndNoDeductionRow() throws Exception {
        stubTicketWithDepositPolicy(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", DepositPolicy.NOT_REQUIRED);
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));

        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.depositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Ruling D11 (2026-09-17): items/deduction come from the matched deposit notice's OWN
    // item snapshot, and a subtotal difference between that notice and its quotation no longer
    // blocks anything — supersedes the old "subtotal must match" rule these tests used to pin.

    @Test
    void remainingInvoiceOptions_itemsComeFromMatchedNoticeNotQuotationEvenWhenSubtotalsDiffer() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("Quotation item", BigDecimal.ONE,
                "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN))))); // quotation item total = 10
        // Notice subtotal (99) does NOT match the quotation's own item total (10) — e.g. the
        // notice was edited/re-issued at a different amount after the quotation was accepted.
        // Under D11 this is no longer a mismatch to block on: the notice's own items/amount ARE
        // the agreed figure now.
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "Notice item", new BigDecimal("3"), "แผ่น",
            new BigDecimal("33"), null, new BigDecimal("33"), new BigDecimal("99"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", new BigDecimal("99"), new BigDecimal("5"),
                List.of(noticeItem))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNull(); // never blocks on a subtotal difference any more
        assertThat(options.itemCount()).isEqualTo(2); // 1 notice item + 1 deposit row
        assertThat(options.itemsTotal()).isEqualByComparingTo("99"); // the NOTICE's own total, not the quotation's (10)
    }

    @Test
    void remainingInvoiceXlsx_itemsSourcedFromMatchedNoticeSubtotalDifferenceNeverThrows() throws Exception {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("Quotation item", BigDecimal.ONE,
                "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "Notice item", new BigDecimal("3"), "แผ่น",
            new BigDecimal("33"), null, new BigDecimal("33"), new BigDecimal("99"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", new BigDecimal("99"), new BigDecimal("5"),
                List.of(noticeItem))));

        DepositNoticeService.ResolvedRemainingInvoice resolved = // must not throw
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.blockingReason()).isNull();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).description()).isEqualTo("Notice item");
        assertThat(resolved.items().get(0).amount()).isEqualByComparingTo("99");
    }

    /** Proves the D11 ruling's "no caching" property end to end: the SAME quotation/notice pairing
     * is read twice, but the second read simulates the notice having been edited/re-issued at a
     * different amount in between (a fresh {@code docs.findByTicket} stub, exactly like a new DB
     * read would return) — the remaining invoice on that NEXT read must reflect the edit, not the
     * first read's cached figures, because {@code resolveRemainingInvoiceSnapshot}/{@code
     * getRemainingInvoiceOptions} recompute everything fresh from the repositories on every call
     * (see this section's own "stateless by owner decision" header comment). */
    @Test
    void remainingInvoiceXlsx_editingDepositNoticeChangesTheNextReadNoCaching() throws Exception {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("Quotation item", BigDecimal.ONE,
                "PER_PIECE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));

        DepositNoticeItemDto firstVersionItem = new DepositNoticeItemDto(
            1L, 1, "Before edit", BigDecimal.ONE, "แผ่น",
            new BigDecimal("50"), null, new BigDecimal("50"), new BigDecimal("50"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.ZERO, new BigDecimal("5"),
                List.of(firstVersionItem))));

        DepositNoticeService.ResolvedRemainingInvoice first =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(first.items().get(0).description()).isEqualTo("Before edit");
        assertThat(first.items().get(0).amount()).isEqualByComparingTo("50");
        assertThat(first.depositAmount()).isEqualByComparingTo("5");

        // The notice was edited/re-issued: a fresh version with a different item and deposit
        // amount, still matching the same quotation's reference. No cache to invalidate — the
        // service simply re-reads docs.findByTicket, which now returns the edited version.
        DepositNoticeItemDto secondVersionItem = new DepositNoticeItemDto(
            2L, 1, "After edit", BigDecimal.ONE, "แผ่น",
            new BigDecimal("80"), null, new BigDecimal("80"), new BigDecimal("80"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(6L, 10L, 2, "GLRD69002", "QT-2026-1", BigDecimal.ZERO, new BigDecimal("12"),
                List.of(secondVersionItem))));

        DepositNoticeService.ResolvedRemainingInvoice second =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(second.items().get(0).description()).isEqualTo("After edit");
        assertThat(second.items().get(0).amount()).isEqualByComparingTo("80");
        assertThat(second.depositAmount()).isEqualByComparingTo("12");
    }

    @Test
    void remainingInvoiceOptions_depositGreaterThanItemsIsBlocked() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // Ruling D11: itemsTotal must come from the NOTICE's own items (10 here), not the
        // quotation's — deposit (15) exceeds it, so rule C's negative-net refusal fires.
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", BigDecimal.ONE, "แผ่น", BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, new BigDecimal("15"),
                List.of(noticeItem))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).contains("ติดลบ");
    }

    // remainingInvoiceXlsx_depositGreaterThanItemsThrowsConflict removed (O1): duplicate of
    // remainingInvoiceOptions_depositGreaterThanItemsIsBlocked above (identical fixture, same
    // blockingReason assertion) — resolveRemainingInvoice never throws for this case itself.

    @Test
    void remainingInvoiceXlsx_netEqualsZeroIsAllowed() throws Exception {
        // deposit == item total exactly -> net = 0, which must be ALLOWED (only < 0 is refused).
        // Ruling D11: item total must come from the NOTICE's own items, not the quotation's.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", BigDecimal.ONE, "แผ่น", BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, BigDecimal.TEN,
                List.of(noticeItem))));

        DepositNoticeService.ResolvedRemainingInvoice resolved = // must not block
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.blockingReason()).isNull();
    }

    // ── D11 finding 1 (2026-09-18): the matched notice's own discountLabel is authored in the
    // LONG form ("ส่วนลด X ต่อหน่วย" — see itemsFromQuotation, used by both createDraft and
    // OrderConfirmationService.createDepositNoticeFromQuotation), which clips in the renderer's
    // narrow column G. itemsFromDepositNoticeForRemainingInvoice must normalize it into the SAME
    // short "ลด X" form itemsFromQuotationForRemainingInvoice already uses — derived from the
    // notice item's own unitPrice/netUnitPrice, never by parsing discountLabel itself (the fixture
    // below deliberately sets discountLabel to a WRONG long-form string so a test that accidentally
    // passed it through unchanged, or that string-parsed it, would fail).

    @Test
    void remainingInvoiceXlsx_noticeSourcedDiscountLabelIsNormalizedToShortForm() throws Exception {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.TEN, "PER_PIECE",
                new BigDecimal("100"), new BigDecimal("43.17"), new BigDecimal("56.83"))))));
        // unitPrice=100 (approvedUnitPrice), netUnitPrice=56.83 (finalUnitPrice) -> discount = 43.17
        // per unit, the same figure the REAL long-form label below quotes. discountLabel is set to
        // the actual long form itemsFromQuotation would have authored when this notice was
        // created/issued — normalization must replace it with "ลด 43.17", not pass it through and
        // not re-derive some other number from it.
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", BigDecimal.TEN, "แผ่น",
            new BigDecimal("100"), "ส่วนลด 43.17 ต่อหน่วย", new BigDecimal("56.83"), new BigDecimal("568.30"));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", new BigDecimal("568.30"), new BigDecimal("5"),
                List.of(noticeItem))));

        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.items().get(0).discountLabel()).isEqualTo("ลด 43.17");
    }

    // ── D11 finding 2 (2026-09-18): once a quotation is matched to a notice, that notice's own
    // item snapshot is the SOLE source of items (see resolveFromQuotation's own header comment) —
    // an empty/null snapshot must refuse the document outright, never silently render a
    // zero-total invoice and never fall back to the quotation's own items (that would resurrect
    // exactly the sourcing ambiguity D11 removed).

    @Test
    void remainingInvoiceOptions_matchedNoticeWithEmptyItemsIsBlocked() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.ZERO, new BigDecimal("5"),
                List.of()))); // empty item snapshot

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).isNotNull().contains("ไม่มีรายการสินค้า");
        assertThat(options.itemsTotal()).isEqualByComparingTo(BigDecimal.ZERO); // never a fabricated total
    }

    @Test
    void remainingInvoiceXlsx_matchedNoticeWithEmptyItemsThrowsConflictNotSilentRender() throws Exception {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // Deposit amount ZERO here on purpose (not "5" like the options test above): with an empty
        // item snapshot, items total 0 and deposit 0 gives net == 0, which rule C explicitly
        // ALLOWS (only < 0 is refused — see remainingInvoiceXlsx_netEqualsZeroIsAllowed above). This
        // isolates the emptiness guard from rule C's negative-net refusal — without the guard, this
        // exact fixture would fall through to a SILENT zero-total render (the bug this test pins),
        // not merely coincide with an unrelated block.
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.ZERO, BigDecimal.ZERO,
                List.of())));

        // O1: resolveRemainingInvoice itself never throws (RemainingInvoiceService#createDraft is
        // what turns a non-null blockingReason into the 409 today) — the emptiness guard itself,
        // which this test pins, is proven by the blockingReason being set at all (never silently
        // falling through to a fabricated zero-total render).
        DepositNoticeService.ResolvedRemainingInvoice resolved =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner).resolved();
        assertThat(resolved.blockingReason()).isNotNull().contains("ไม่มีรายการสินค้า");
        assertThat(resolved.items()).isEmpty();
    }

    // Legacy-fallback overload: reference "REF-1" never matches any of this file's "QT-2026-N"
    // quotation numbers, and subtotal is a placeholder (depositAmount*2) — correct ONLY for a
    // fixture where no ACCEPTED quotation is in play at all (the legacy resolveLegacy path, which
    // never checks reference/subtotal against a quotation). Any test with an ACCEPTED quotation
    // that should QUALIFY must use the 7-arg overload below instead.
    private DepositNoticeDto issuedNotice(long docId, long ticketId, int version, String docNumber,
                                          BigDecimal depositAmount, List<DepositNoticeItemDto> items) {
        return issuedNotice(docId, ticketId, version, docNumber, "REF-1",
            depositAmount.multiply(new BigDecimal("2")), depositAmount, items);
    }

    // reference/subtotal explicit — reference must equal the qualifying quotation's own number
    // (rule A's match key) and subtotal must equal that quotation's own item total (rule B) for
    // the notice to actually qualify a quotation as a remaining-invoice source.
    private DepositNoticeDto issuedNotice(long docId, long ticketId, int version, String docNumber,
                                          String reference, BigDecimal subtotal, BigDecimal depositAmount,
                                          List<DepositNoticeItemDto> items) {
        return new DepositNoticeDto(
            docId, ticketId, "DEPOSIT_NOTICE", version, docNumber, LocalDate.of(2026, 8, 1), "ISSUED",
            "ACME", "0100000000000", "Bangkok", "Showroom", reference, "THB",
            new BigDecimal("0.50"), subtotal, depositAmount,
            new BigDecimal("0.07"), BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), true, true, "Sales", "Preparer", null, null, items);
    }

    @Test
    void createDraft_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) as the very first check.
        assertForbidden(() -> service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null),
            salesManagerActor));
    }

    @Test
    void update_rejectsSalesManagerRole() {
        // update() has NO role gate — only requireTicketOwner. sales_manager can
        // never own a ticket, so the ownership check alone denies it.
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.APPROVED, null);

        assertForbidden(() -> service.update(99L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null),
            salesManagerActor));
    }

    @Test
    void issue_rejectsSalesManagerRole() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.issue(99L, salesManagerActor));
    }

    @Test
    void requestRevision_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) as the very first check.
        assertForbidden(() -> service.requestRevision(10L,
            new RevisionRequest(RevisionScope.QTY_OR_NOTE, "reason"), salesManagerActor));
    }

    // ── Header autofill + item fallback (branch fix: deposit-notice autofill) ─
    // See DepositNoticeService.createDraft/buildItemsFromRequest for what changed and why —
    // every deal created through the pricing-request chain reached these methods with
    // approved_price NULL on every ticket_item and no header autofill at all.

    @Test
    void createDraft_autofillsHeaderFromCustomerMasterAndTicketProjectName() {
        stubTicketWithCustomer(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", 42L, "Project Zeta");
        when(customerRepo.findById(42L)).thenReturn(Optional.of(
            new CustomerDto(42L, "ACME", "0100000000000", "123 Road", "Showroom", "02-000-0000")));
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of());
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(201L);
        stubDraft(201L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        ArgumentCaptor<DepositNoticeDraftRequest> captor = ArgumentCaptor.forClass(DepositNoticeDraftRequest.class);
        verify(docs).createDraft(eq(10L), captor.capture(), any());
        DepositNoticeDraftRequest effective = captor.getValue();
        assertThat(effective.customerTaxId()).isEqualTo("0100000000000");
        assertThat(effective.customerAddress()).isEqualTo("123 Road Showroom");
        assertThat(effective.projectName()).isEqualTo("Project Zeta");
    }

    @Test
    void createDraft_nullCustomerIdLeavesHeaderFieldsBlankWithoutThrowing() {
        // Base fixture's ticket carries customerId = null — must not NPE, and the customer
        // repository must never even be consulted.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of());
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(202L);
        stubDraft(202L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        ArgumentCaptor<DepositNoticeDraftRequest> captor = ArgumentCaptor.forClass(DepositNoticeDraftRequest.class);
        verify(docs).createDraft(eq(10L), captor.capture(), any());
        assertThat(captor.getValue().customerTaxId()).isNull();
        assertThat(captor.getValue().customerAddress()).isNull();
        verify(customerRepo, never()).findById(anyLong());
    }

    @Test
    void createDraft_fallsBackToAcceptedQuotationWhenNoTicketItemHasApprovedPrice() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED"); // items: List.of() — no approved_price
        CustomerQuotationItemDto item = quotationItem("กระเบื้อง A", new BigDecimal("10"), "PER_SQM",
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"));
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(quotation(1L, 10L, "ACCEPTED", 1, List.of(item))));
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(203L);
        stubDraft(203L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        List<DepositNoticeItemRequest> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("กระเบื้อง A");
        assertThat(items.get(0).unit()).isEqualTo("ตร.ม.");
        assertThat(items.get(0).unitPrice()).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void createDraft_prefersAcceptedQuotationOverAnIssuedOne() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        CustomerQuotationItemDto issuedItem = quotationItem("Issued Item", BigDecimal.ONE, "PER_PIECE",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        CustomerQuotationItemDto acceptedItem = quotationItem("Accepted Item", BigDecimal.ONE, "PER_PIECE",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        // findByTicket's own contract: ascending by quotation_id ALONE (see its own Javadoc —
        // quotation_revision_no is scoped to a single pricing request, not comparable across
        // the multiple pricing requests one ticket can have) — the accepted revision here has
        // the higher quotation_id, same as a real revise-after-accept flow.
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ISSUED", 1, List.of(issuedItem)),
            quotation(2L, 10L, "ACCEPTED", 2, List.of(acceptedItem))));
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(204L);
        stubDraft(204L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(1);
        assertThat(itemsCaptor.getValue().get(0).description()).isEqualTo("Accepted Item");
    }

    @Test
    void createDraft_legacyApprovedPriceItemsWinOverQuotationChainWhenBothExist() {
        TicketItemDto approvedItem = new TicketItemDto(1L, 10L, "SCG", "Model", "White", "Matte", "60x60",
            "Factory", new BigDecimal("10"), null, null, null, null, null, new BigDecimal("100"), "THB",
            0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", List.of(approvedItem));
        // Even though an ACCEPTED quotation exists too, the legacy approved_price path must win
        // and the quotation chain must never even be queried (buildLegacyItems short-circuits).
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(205L);
        stubDraft(205L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(1);
        assertThat(itemsCaptor.getValue().get(0).unitPrice()).isEqualTo(new BigDecimal("100"));
        verify(quotationRepo, never()).findByTicket(10L);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void stubDraft(long docId, long ticketId) {
        DepositNoticeDto draft = new DepositNoticeDto(
            docId, ticketId, "DEPOSIT_NOTICE", 1, null, null, "DRAFT",
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-1", "THB",
            new BigDecimal("0.50"), new BigDecimal("1000.00"), new BigDecimal("500.00"),
            new BigDecimal("0.07"), new BigDecimal("35.00"), new BigDecimal("535.00"),
            List.of(), false, false, "Sales", "Preparer", null, null, List.of());
        when(docs.findById(docId)).thenReturn(Optional.of(draft));
    }

    @org.junit.jupiter.api.Test
    void createDraft_rejectsPausedDeal() {
        // Phase 1 lifecycle gate: deposit-notice mutations advance the payment track,
        // so an ON_HOLD deal must not create/issue notices.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", "ON_HOLD");
        assertThatThrownBy(() -> service.createDraft(10L,
                new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @org.junit.jupiter.api.Test
    void issue_rejectsPausedDeal() {
        stubDraft(5L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", "ON_HOLD");
        assertThatThrownBy(() -> service.issue(5L, owner))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private void stubTicket(long ticketId, String status, String paymentStatus) {
        stubTicket(ticketId, status, paymentStatus, "ACTIVE");
    }

    private void stubTicket(long ticketId, String status, String paymentStatus, String lifecycle) {
        stubTicketWithDepositPolicy(ticketId, status, paymentStatus, DepositPolicy.REQUIRED, lifecycle);
    }

    // Remaining-invoice quotation-selection fixture (owner ruling A): a deposit policy other than
    // REQUIRED changes whether an ACCEPTED quotation qualifies WITHOUT a matching issued deposit
    // notice — see DepositPolicy.bypassesDepositNotice.
    private void stubTicketWithDepositPolicy(long ticketId, String status, String paymentStatus, String depositPolicy) {
        stubTicketWithDepositPolicy(ticketId, status, paymentStatus, depositPolicy, "ACTIVE");
    }

    private void stubTicketWithDepositPolicy(long ticketId, String status, String paymentStatus,
                                             String depositPolicy, String lifecycle) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            lifecycle, "UNKNOWN", depositPolicy, null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, List.of(), List.of(), null, List.of())));
    }

    // customerId non-null + a projectName — the header-autofill source fields — with no items
    // (so the item-fallback path is exercised at the same time by every caller of this fixture).
    private void stubTicketWithCustomer(long ticketId, String status, String paymentStatus,
                                        Long customerId, String projectName) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", customerId, null, projectName, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, List.of(), List.of(), null, List.of())));
    }

    // Same ticket shape as stubTicket, but with explicit ticket_item rows — for the legacy
    // approved_price fallback path.
    private void stubTicketWithItems(long ticketId, String status, String paymentStatus,
                                     List<TicketItemDto> items) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, items, List.of(), null, List.of())));
    }

    // Minimal CustomerQuotationDto fixture — only the fields buildItemsFromRequest's pickQuotation
    // and itemsFromQuotation actually read (ticketId, docStatus, quotationRevisionNo, items) carry
    // meaningful values; everything else is a harmless placeholder.
    private CustomerQuotationDto quotation(long id, long ticketId, String docStatus, int revisionNo,
                                           List<CustomerQuotationItemDto> items) {
        return new CustomerQuotationDto(
            id, "QT-2026-" + id, ticketId, 900L, 901L, "CUSTOMER", null, docStatus, 1, revisionNo,
            null, 1L, "Sales", Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            null, null, null, null, null, null, null, null, Instant.now(), null, null, items);
    }

    private CustomerQuotationItemDto quotationItem(String description, BigDecimal qty, String unitBasis,
                                                    BigDecimal unitPrice, BigDecimal discount, BigDecimal net) {
        BigDecimal lineSubtotal = net.multiply(qty);
        return new CustomerQuotationItemDto(
            1L, 1, 1L, 1L, description, null, unitBasis, qty, unitPrice, discount, net,
            null, lineSubtotal, BigDecimal.ZERO, lineSubtotal);
    }

    // Ruling D11: a matched deposit notice's OWN item snapshot drives the remaining invoice's rows
    // (and its capacity check) — this builds `count` such rows, each worth 10, for tests that used
    // to size a QUOTATION's item list for capacity/total purposes before that ruling.
    private List<DepositNoticeItemDto> depositNoticeItems(int count) {
        List<DepositNoticeItemDto> items = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(new DepositNoticeItemDto(
                i, i, "Notice item " + i, BigDecimal.ONE, "แผ่น",
                BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN));
        }
        return items;
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
