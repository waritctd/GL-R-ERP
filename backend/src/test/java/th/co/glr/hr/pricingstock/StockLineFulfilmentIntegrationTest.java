package th.co.glr.hr.pricingstock;

import th.co.glr.hr.ticket.DeliveryRoundFixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
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
    private int quotationSeq;

    @BeforeEach
    void wireOrderConfirmationAndImportRequests() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        CustomerQuotationRepository legacyQuotations = new CustomerQuotationRepository(jdbc);
        DepositNoticeService depositNotices = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticePdfRenderer(), customers, legacyQuotations, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));
        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, legacyQuotations, depositNotices, notifications);
        // Same wiring the app has (setter-injected): without it the accepted quotation's deposit
        // percent is never read and the 0% path could not be exercised.
        orderConfirmation.wireDealQuotationRepository(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketId));
    }

    // ── B2 / B3 / B5: the import side and the auto-advance ───────────────────────────────

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

    // ── B6: STOCK delivery without any reserve ───────────────────────────────────────────

    @Test
    void b6_stockOnlyDeal_atStep13_aStockDeliveryOfTheFullQty_succeedsWithoutReserveStock() {
        long pcr = acceptedPcr(Set.of(), stockLine("Stock A", IN_THAILAND, 10));
        acceptQuotation(pcr, 30);
        orderConfirmation.confirmOrder(pcr, confirmRequest(), salesActor);
        recordDeposit();
        long itemId = ticketItemId("Stock A");

        DeliveryRoundFixture.ensureInvoicedRound(jdbc, ticketId);
        var after = ticketService.recordPartialDelivery(ticketId, new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("10"))), null), salesActor);

        assertThat(after.items()).singleElement()
            .satisfies(i -> assertThat(i.qtyDelivered()).isEqualByComparingTo("10"));
        assertThat(after.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private ConfirmOrderRequest confirmRequest() {
        return new ConfirmOrderRequest(UUID.randomUUID().toString());
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

    private long ticketItemId(String model) {
        return jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t AND model = :m",
            Map.of("t", ticketId, "m", model), Long.class);
    }
}
