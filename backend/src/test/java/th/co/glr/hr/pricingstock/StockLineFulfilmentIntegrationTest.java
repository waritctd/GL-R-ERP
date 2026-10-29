package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.PendingCommissionItemDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository;
import th.co.glr.hr.importrequest.ImportRequestRenderer;
import th.co.glr.hr.importrequest.ImportRequestRepository;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.RecordDeliveryRequest;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Walk 4 (owner rulings, Ploy 2026-10-29): there is NO reserve-from-stock feature. A stock line
 * (pricing_request_item.stock_source = IN_THAILAND) ends when the CEO confirms the price and then
 * waits for delivery; it is never ordered from a factory. Real services, real repositories, real
 * Postgres, the real {@code OrderConfirmationService#confirmOrder} - no {@code reserveStock} call
 * anywhere in this class.
 *
 * <p>The ONE writer under test: {@code confirmOrder} must write {@code ticket_item.qty_from_stock}
 * from the confirmed pricing request's {@code stock_source}. Every reader below (IR candidates,
 * the "all covered from stock" 409, STOCK delivery, the commission x2/x3 share) already reads that
 * column and is expected to work unchanged once it is written.
 *
 * <p>Fixture note: the abstract fixture's seed ticket line is deleted so "every line of the deal is
 * stock" is decided by the lines this class creates (a stray uncovered seed line would make an
 * all-stock deal look mixed). A stock line's ticket_item carries FACTORY_B on purpose: if the writer
 * is missing, that line shows up as an IR candidate for factory B and the IR assertions go red.
 */
class StockLineFulfilmentIntegrationTest extends AbstractStockLineIntegrationTest {

    private OrderConfirmationService orderConfirmation;
    private ImportRequestService importRequests;
    private CommissionRepository commissionRepository;
    private int quotationSeq;

    @BeforeEach
    void wireOrderConfirmationAndImportRequests() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        CustomerQuotationRepository legacyQuotations = new CustomerQuotationRepository(jdbc);
        DepositNoticeService depositNotices = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticePdfRenderer(), customers, legacyQuotations);
        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, legacyQuotations, depositNotices, notifications);
        // Same wiring the app has (setter-injected): without it the accepted quotation's deposit
        // percent is never read and the 0% path could not be exercised.
        orderConfirmation.wireDealQuotationRepository(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));
        importRequests = new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            new ImportRequestRepository(jdbc), new FactoryConfigRepository(jdbc), tickets, ticketService);
        commissionRepository = new CommissionRepository(jdbc);
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketId));
    }

    // ── B1: the one writer ───────────────────────────────────────────────────────────────

    @Test
    void b1_confirmOrder_writesQtyFromStock_forTheStockLineOnly_andTheCommissionReaderSeesIt() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);

        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        assertThat(qtyFromStock("Stock A")).isEqualByComparingTo("10");
        assertThat(qtyFromStock("Imp A")).isEqualByComparingTo("0");
        List<PendingCommissionItemDto> seenByCommission = commissionRepository.findPendingItems(ticketId);
        assertThat(seenByCommission).filteredOn(i -> i.description().contains("Stock A"))
            .singleElement().satisfies(i -> assertThat(i.qtyFromStock()).isEqualByComparingTo("10"));
        assertThat(seenByCommission).filteredOn(i -> i.description().contains("Imp A"))
            .singleElement().satisfies(i -> assertThat(i.qtyFromStock()).isEqualByComparingTo("0"));
    }

    @Test
    void b1_aStockLineThatArrivesAsANewTicketLine_isInsertedFullyStockCovered() {
        // "Stock New" has no source ticket item (a line a revision added): reconcile INSERTS its
        // ticket_item, and that insert must carry the stock cover too.
        long pcr = acceptedPcr(Set.of("Stock New"),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock New", IN_THAILAND, 6));
        acceptQuotation(pcr, 30);

        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        assertThat(qtyFromStock("Stock New")).isEqualByComparingTo("6");
        assertThat(qtyFromStock("Imp A")).isEqualByComparingTo("0");
    }

    // ── B2 / B3 / B5: the import side and the auto-advance ───────────────────────────────

    @Test
    void b2_mixedDeal_afterTheDeposit_createDraftsOrdersOnlyTheImportLinesFactory() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        recordDeposit();

        List<ImportRequestDto> irs = importRequests.createDrafts(ticketId, null, salesActor);

        assertThat(irs).extracting(ImportRequestDto::factoryName)
            .as("the stock line's factory (B) must never get an import request")
            .containsExactly(FACTORY_A);
        assertThat(irs.get(0).items()).hasSize(1);
    }

    @Test
    void b3_stockOnlyDeal_depositRequired_staysAtOrderReceivedUntilTheDeposit_thenGoesToDeliveryScheduling() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);

        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        assertThat(summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(summary().fulfillmentStatus()).isNull();

        recordDeposit();

        assertThat(summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
    }

    @Test
    void b3_stockOnlyDeal_afterTheDeposit_createDraftsIsRefused409_coveredFromStock() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        recordDeposit();

        assertThatThrownBy(() -> importRequests.createDrafts(ticketId, null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ครอบคลุมจากสต็อกทั้งหมด");
            });
        assertThat(importRequests.list(ticketId, salesActor)).isEmpty();
    }

    @Test
    void b4_stockOnlyDeal_zeroPercentDeposit_goesToDeliverySchedulingAtOrderConfirmation() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 0);

        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        TicketSummaryDto s = summary();
        assertThat(s.depositPolicy()).as("fixture: the 0% quotation put the deal on the no-deposit path")
            .isEqualTo("NOT_REQUIRED");
        assertThat(s.salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(s.fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
    }

    @Test
    void b4_mixedDeal_zeroPercentDeposit_doesNotJumpToDeliveryScheduling() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 0);

        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        assertThat(summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(summary().fulfillmentStatus()).isNull();
    }

    @Test
    void b5_mixedDeal_afterTheDeposit_isDepositReceivedOnly_noJump() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        recordDeposit();

        assertThat(summary().salesStage()).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(summary().fulfillmentStatus()).isNull();
    }

    // ── B6: STOCK delivery without any reserve ───────────────────────────────────────────

    @Test
    void b6_stockOnlyDeal_atStep13_aStockDeliveryOfTheFullQty_succeedsWithoutReserveStock() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        recordDeposit();
        long itemId = ticketItemId("Stock A");

        var after = ticketService.recordPartialDelivery(ticketId, new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("10"))), null), salesActor);

        assertThat(after.items()).singleElement()
            .satisfies(i -> assertThat(i.qtyDelivered()).isEqualByComparingTo("10"));
        assertThat(after.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
    }

    // Review fix 1: the writer sets qty_from_stock at confirmation, so STOCK delivery must wait for the deposit
    // (paid, or a no-deposit policy) - otherwise a REQUIRED stock-only deal could deliver and close with nothing paid.
    @Test
    void b6_requiredStockOnlyDeal_beforeTheDeposit_stockDeliveryIsNotAdvertised_andRefused409() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        long itemId = ticketItemId("Stock A");
        RecordDeliveryRequest full = new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("10"))), null);

        assertThat(actionCodes()).doesNotContain("RECORD_PARTIAL_DELIVERY", "COMPLETE_DELIVERY");
        assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, full, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> ticketService.completeDelivery(ticketId,
            new th.co.glr.hr.ticket.CompleteDeliveryRequest(null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(summary().fulfillmentStatus()).isNull();

        recordDeposit();

        assertThat(actionCodes()).contains("RECORD_PARTIAL_DELIVERY");
    }

    @Test
    void b6_mixedDeal_beforeTheDeposit_aPartialStockDeliveryIsRefused409_andNothingIsRecorded() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(ticketItemId("Stock A"), new BigDecimal("4"))), null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(summary().fulfillmentStatus()).isNull();
    }

    // Review fix 2: waiving the deposit on an all-stock deal at CUSTOMER_CONFIRMED leaves nothing to wait for.
    @Test
    void b4_stockOnlyDeal_atCustomerConfirmed_waivingTheDeposit_goesToDeliveryScheduling() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        assertThat(summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.waiveDeposit(ticketId, "WAIVED", "ลูกค้าเครดิต", salesActor);

        assertThat(summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
    }

    @Test
    void b4_mixedDeal_waivingTheDeposit_doesNotJumpToDeliveryScheduling() {
        long pcr = acceptedPcr(Set.of(),
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 20),
            stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);

        ticketService.waiveDeposit(ticketId, "WAIVED", "ลูกค้าเครดิต", salesActor);

        assertThat(summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(summary().fulfillmentStatus()).isNull();
    }

    // Review fix 3: no new request on a confirmed deal either (create, like submit and revise).
    @Test
    void b12_onceTheOrderIsConfirmed_creatingANewPricingRequestIsRefused409() {
        long accepted = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(accepted, 30);
        // Control: before the order is confirmed the same create is legal.
        pricingRequestService.createDraft(ticketId, requestOf(importLine("New 1", catalogProductFactoryA, FACTORY_A, 5)), salesActor);

        orderConfirmation.confirmOrder(accepted, confirmRequest(), salesActor);

        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(importLine("New 2", catalogProductFactoryA, FACTORY_A, 5)), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── B12 (W4-04): no new pricing round once the order is confirmed ────────────────────

    @Test
    void b12_onceTheOrderIsConfirmed_aCustomerChangeRevisionOfAnotherRequestIsRefused409() {
        long accepted = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(accepted, 30);
        long siblingBefore = persistDraft(importLine("Sib 1", catalogProductFactoryA, FACTORY_A, 5));
        forceStatus(siblingBefore, PricingRequestStatus.QUOTATION_ISSUED);
        long siblingAfter = persistDraft(importLine("Sib 2", catalogProductFactoryA, FACTORY_A, 5));
        forceStatus(siblingAfter, PricingRequestStatus.QUOTATION_ISSUED);

        // Control: before the order is confirmed the same call is legal (so the 409 below is the
        // confirmation, not a malformed request).
        assertThat(pricingRequestService.createCustomerChangeRevision(siblingBefore, revisionOf("Sib 1 rev"),
            salesActor).summary().status()).isEqualTo(PricingRequestStatus.DRAFT);

        orderConfirmation.confirmOrder(accepted, confirmRequest(), salesActor);

        assertThatThrownBy(() -> pricingRequestService.createCustomerChangeRevision(siblingAfter,
            revisionOf("Sib 2 rev"), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(status(siblingAfter)).as("refused: the request is untouched")
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
    }

    @Test
    void b12_onceTheOrderIsConfirmed_aDraftRequestCannotBeSubmitted_409() {
        long accepted = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(accepted, 30);
        long draftBefore = persistDraft(importLine("Late 1", catalogProductFactoryA, FACTORY_A, 5));
        long draftAfter = persistDraft(importLine("Late 2", catalogProductFactoryA, FACTORY_A, 5));

        // Control: before the order is confirmed a DRAFT submits fine.
        pricingRequestService.submit(draftBefore, salesActor);
        assertThat(status(draftBefore)).isEqualTo(PricingRequestStatus.SUBMITTED);

        orderConfirmation.confirmOrder(accepted, confirmRequest(), salesActor);

        assertThatThrownBy(() -> pricingRequestService.submit(draftAfter, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(status(draftAfter)).isEqualTo(PricingRequestStatus.DRAFT);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private ConfirmOrderRequest confirmRequest() {
        return new ConfirmOrderRequest(UUID.randomUUID().toString());
    }

    private CustomerChangeRevisionRequest revisionOf(String model) {
        return new CustomerChangeRevisionRequest("ลูกค้าเปลี่ยน", UUID.randomUUID().toString(),
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", null, List.of(importLine(model, catalogProductFactoryA, FACTORY_A, 5)));
    }

    private List<String> actionCodes() {
        return ticketService.actions(ticketId, salesActor).availableActions().stream()
            .map(a -> a.action()).toList();
    }

    private TicketSummaryDto summary() {
        return tickets.findById(ticketId).orElseThrow().summary();
    }

    /**
     * A pricing request at QUOTATION_ACCEPTED whose lines are linked to freshly created ticket
     * lines (qty stub 1, like a deal's first save) - except the models in {@code unlinked}, which
     * have no source ticket line (so reconcile must INSERT them). A stock line's ticket line names
     * FACTORY_B; an import line's names its own factory.
     */
    private long acceptedPcr(Set<String> unlinked, PricingRequestItemRequest... items) {
        long id = persistDraft(items);
        int sort = 0;
        for (PricingRequestItemRequest item : items) {
            if (unlinked.contains(item.model())) {
                continue;
            }
            long ticketItem = jdbc.queryForObject("""
                INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
                VALUES (:t, 'SCG', :model, '60x60', 1, 'pcs', :sort, :factory)
                RETURNING item_id
                """, new MapSqlParameterSource().addValue("t", ticketId).addValue("model", item.model())
                    .addValue("sort", sort++)
                    .addValue("factory", item.factory() != null ? item.factory() : FACTORY_B), Long.class);
            jdbc.update("""
                UPDATE sales.pricing_request_item SET source_ticket_item_id = :ti
                 WHERE pricing_request_id = :id AND model = :model
                """, Map.of("ti", ticketItem, "id", id, "model", item.model()));
        }
        forceStatus(id, PricingRequestStatus.QUOTATION_ACCEPTED);
        return id;
    }

    /** The customer-accepted new-engine quotation: only its number, total and deposit percent matter here. */
    private void acceptQuotation(long pricingRequestId, int depositPercent) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, pricing_request_id,
                                          deposit_percent, total_amount)
            VALUES (:t, :number, :by, 'PRICING_REQUEST', 'ACCEPTED', :pr, :pct, 1000.00)
            """, new MapSqlParameterSource().addValue("t", ticketId)
                .addValue("number", "QT-WALK4-" + (++quotationSeq)).addValue("by", salesRepId)
                .addValue("pr", pricingRequestId).addValue("pct", depositPercent));
    }

    /** Account confirms the deposit money (the real {@code recordPayment}); payable is 1,070 with VAT. */
    private void recordDeposit() {
        ticketService.recordPayment(ticketId, new RecordPaymentRequest(
            "DEPOSIT", new BigDecimal("321.00"), null, "มัดจำ", null, null, false), accountActor);
    }

    private BigDecimal qtyFromStock(String model) {
        return jdbc.queryForObject("""
            SELECT qty_from_stock FROM sales.ticket_item WHERE ticket_id = :t AND model = :m
            """, Map.of("t", ticketId, "m", model), BigDecimal.class);
    }

    private long ticketItemId(String model) {
        return jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t AND model = :m",
            Map.of("t", ticketId, "m", model), Long.class);
    }
}
