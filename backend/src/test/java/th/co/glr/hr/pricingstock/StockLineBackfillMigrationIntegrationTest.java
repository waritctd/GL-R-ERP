package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository;
import th.co.glr.hr.importrequest.ImportRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/**
 * V201 AS SHIPPED, re-executed verbatim from the classpath over hand-shaped rows: deals whose order was
 * confirmed BEFORE the walk 4 writer shipped have stock lines with qty_from_stock = 0 and sit on the import
 * track. The migration covers those lines and routes the deals that are now all-stock and deposit-settled.
 * Lines on an ISSUED import request are never touched (already ordered from a factory).
 */
class StockLineBackfillMigrationIntegrationTest extends AbstractStockLineIntegrationTest {

    private static final String V201 = "db/migration/V201__stock_line_backfill.sql";
    private int seq;

    @Test
    void confirmedStockLines_getFullCover_unconfirmedAndImportLinesDoNot() {
        long settledAllStock = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        long stockItem = confirmedStockLine(settledAllStock, "S1", 10, true);
        long mixed = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        long mixedStock = confirmedStockLine(mixed, "S2", 4, true);
        long mixedImport = item(mixed, "I2", 8, 0);
        long unconfirmed = deal("CUSTOMER_CONFIRMED", "REQUIRED", "ORDER_RECEIVED", null);
        long unconfirmedStock = confirmedStockLine(unconfirmed, "S3", 6, false);

        runV201();

        assertThat(qcfs(stockItem)).isEqualByComparingTo("10");
        assertThat(qcfs(mixedStock)).isEqualByComparingTo("4");
        assertThat(qcfs(mixedImport)).as("an import line is never covered").isEqualByComparingTo("0");
        assertThat(qcfs(unconfirmedStock)).as("its order was never confirmed").isEqualByComparingTo("0");
    }

    @Test
    void aStockLineOnAnIssuedImportRequest_isNotTouched() {
        long deal = deal("DEPOSIT_PAID", "REQUIRED", "PROCUREMENT", "IR_ISSUED");
        long ordered = confirmedStockLine(deal, "S4", 10, true);
        issuedImportRequestFor(deal, ordered);

        runV201();

        assertThat(qcfs(ordered)).isEqualByComparingTo("0");
        assertThat(fulfilment(deal)).isEqualTo("IR_ISSUED");
        assertThat(stage(deal)).isEqualTo("PROCUREMENT");
    }

    @Test
    void anAllStockDealWhoseDepositIsSettled_isRouted_otherDealsAreNot() {
        long paid = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        confirmedStockLine(paid, "S5", 10, true);
        long noDeposit = deal("CUSTOMER_CONFIRMED", "NOT_REQUIRED", "ORDER_RECEIVED", null);
        confirmedStockLine(noDeposit, "S6", 10, true);
        long unpaid = deal("CUSTOMER_CONFIRMED", "REQUIRED", "ORDER_RECEIVED", null);
        confirmedStockLine(unpaid, "S7", 10, true);
        long mixed = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        confirmedStockLine(mixed, "S8", 4, true);
        item(mixed, "I8", 8, 0);
        long eventsBefore = stageEvents(paid);

        runV201();

        for (long routed : new long[] {paid, noDeposit}) {
            assertThat(fulfilment(routed)).isEqualTo("FROM_STOCK");
            assertThat(stage(routed)).isEqualTo("DELIVERY_SCHEDULING");
        }
        assertThat(stageEvents(paid)).as("the stage move leaves a STAGE_CHANGED row").isEqualTo(eventsBefore + 1);
        assertThat(fulfilment(unpaid)).isNull();
        assertThat(stage(unpaid)).isEqualTo("ORDER_RECEIVED");
        assertThat(fulfilment(mixed)).isNull();
        assertThat(stage(mixed)).isEqualTo("DEPOSIT_RECEIVED");
    }

    @Test
    void runningItTwice_changesNothingTheSecondTime() {
        long paid = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        confirmedStockLine(paid, "S9", 10, true);
        runV201();
        long events = stageEvents(paid);

        runV201();

        assertThat(stageEvents(paid)).isEqualTo(events);
        assertThat(stage(paid)).isEqualTo("DELIVERY_SCHEDULING");
    }

    // Guards, each pinned on its own: the rows below are shaped so that ONLY the guard under test stops V201.

    @Test
    void aLostDeal_isNotRouted_evenWhenItsLinesAreAlreadyCovered() {
        long lost = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST', lost_reason = 'PRICE', lost_at = now() WHERE ticket_id = :t",
            Map.of("t", lost));
        coveredStockLine(lost, "L1", 10);

        runV201();

        assertThat(fulfilment(lost)).isNull();
        assertThat(stage(lost)).isEqualTo("DEPOSIT_RECEIVED");
        assertThat(stageEvents(lost)).isZero();
    }

    @Test
    void aDealPastStep13WithNoFulfilment_isNotMovedBack_andGetsNoEvent() {
        for (String late : new String[] {"DELIVERED", "CLOSED_PAID"}) {
            long deal = deal("FULLY_PAID", "REQUIRED", late, null);
            coveredStockLine(deal, "P-" + late, 10);
            long eventsBefore = stageEvents(deal);

            runV201();

            assertThat(stage(deal)).as(late + " is not pulled back to DELIVERY_SCHEDULING").isEqualTo(late);
            assertThat(stageEvents(deal)).as("no STAGE_CHANGED for " + late).isEqualTo(eventsBefore);
        }
    }

    @Test
    void aLineAConfirmedRequestAlsoMapsAsAnImportLine_keepsItsQtyFromStock() {
        long deal = deal("DEPOSIT_PAID", "REQUIRED", "DEPOSIT_RECEIVED", null);
        long ambiguous = confirmedStockLine(deal, "A1", 10, true);
        // a second confirmed request maps the SAME ticket line as an IMPORT line (stock_source NULL)
        long pcr = pricingRequests.create(deal, pricingRequests.nextRequestCode(),
            requestOf(importLine("A1", catalogProductFactoryA, FACTORY_A, 10)), salesRepId);
        jdbc.update("UPDATE sales.pricing_request_item SET source_ticket_item_id = :ti WHERE pricing_request_id = :p",
            Map.of("ti", ambiguous, "p", pcr));
        jdbc.update("UPDATE sales.pricing_request SET status = 'QUOTATION_ACCEPTED', order_confirmed_at = now() WHERE pricing_request_id = :p",
            Map.of("p", pcr));

        runV201();

        assertThat(qcfs(ambiguous)).as("ambiguous data is left alone").isEqualByComparingTo("0");
    }

    // Closed deals must not be rewritten: raising qty_from_stock would retroactively hand pending commission the x2/x3 share.
    @Test
    void aClosedOrLostDeal_hasItsStockLinesUntouched() {
        for (String lifecycle : new String[] {"CLOSED_LOST", "CANCELLED", "COMPLETED"}) {
            long deal = deal("FULLY_PAID", "REQUIRED", "CLOSED_PAID", null);
            if ("CLOSED_LOST".equals(lifecycle)) {
                jdbc.update("UPDATE sales.ticket SET lifecycle = :l, lost_reason = 'PRICE', lost_at = now() WHERE ticket_id = :t",
                    Map.of("l", lifecycle, "t", deal));
            } else {
                jdbc.update("UPDATE sales.ticket SET lifecycle = :l WHERE ticket_id = :t", Map.of("l", lifecycle, "t", deal));
            }
            long stockItem = confirmedStockLine(deal, "C-" + lifecycle, 10, true);

            runV201();

            assertThat(qcfs(stockItem)).as(lifecycle + " deal untouched").isEqualByComparingTo("0");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private void runV201() {
        String text;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(V201)) {
            assertThat(in).as(V201 + " on the classpath").isNotNull();
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        jdbc.getJdbcOperations().execute(text);
    }

    private long deal(String paymentStatus, String depositPolicy, String stage, String fulfilment) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       deposit_policy, sales_stage, fulfillment_status)
            VALUES (:code, 'backfill', :by, 'บริษัท ทดสอบ', 'quotation_issued', :ps, :policy, :stage, :fs)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", "BF-" + (++seq)).addValue("by", salesRepId)
                .addValue("ps", paymentStatus).addValue("policy", depositPolicy).addValue("stage", stage)
                .addValue("fs", fulfilment), Long.class);
    }

    private long item(long ticket, String model, int qty, int qtyFromStock) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, qty_from_stock, unit, sort_order, factory)
            VALUES (:t, 'SCG', :m, '60x60', :q, :s, 'pcs', :sort, :f) RETURNING item_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("m", model).addValue("q", qty)
                .addValue("s", qtyFromStock).addValue("sort", ++seq).addValue("f", FACTORY_A), Long.class);
    }

    /** A ticket line with qty_from_stock 0 (pre-writer) fed by a stock line of an accepted PCR, order confirmed or not. */
    private long confirmedStockLine(long ticket, String model, int qty, boolean orderConfirmed) {
        long ticketItem = item(ticket, model, qty, 0);
        PricingRequestItemRequest line = stockLine(model, IN_THAILAND, qty);
        long pcr = pricingRequests.create(ticket, pricingRequests.nextRequestCode(), requestOf(line), salesRepId);
        jdbc.update("UPDATE sales.pricing_request_item SET source_ticket_item_id = :ti WHERE pricing_request_id = :p",
            Map.of("ti", ticketItem, "p", pcr));
        jdbc.update("""
            UPDATE sales.pricing_request SET status = :s,
                   order_confirmed_at = CASE WHEN :c THEN now() ELSE NULL END
             WHERE pricing_request_id = :p
            """, new MapSqlParameterSource().addValue("s", PricingRequestStatus.QUOTATION_ACCEPTED)
                .addValue("c", orderConfirmed).addValue("p", pcr));
        return ticketItem;
    }

    /** A confirmed stock line whose ticket line is ALREADY covered (qty_from_stock = qty): step 1 has nothing to do. */
    private long coveredStockLine(long ticket, String model, int qty) {
        long ticketItem = confirmedStockLine(ticket, model, qty, true);
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = qty WHERE item_id = :i", Map.of("i", ticketItem));
        return ticketItem;
    }

    private void issuedImportRequestFor(long ticket, long ticketItem) {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        var snapshot = new ImportRequestQueryRepository(jdbc).loadTicketSnapshot(ticket).orElseThrow();
        long id = stored.insertDraft(ticket, factoryId(FACTORY_A), FACTORY_A, "SCG", 1, snapshot, 30, 45, null, null,
            salesRepId, "x");
        jdbc.update("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
            SELECT :id, ti.item_id, 1, ti.model, ti.size, ti.qty, ti.unit, ti.brand, ti.model
              FROM sales.ticket_item ti WHERE ti.item_id = :ti
            """, Map.of("id", id, "ti", ticketItem));
        jdbc.update("""
            UPDATE sales.import_request SET status = 'ISSUED', doc_number = 'IRMIG001', issue_date = current_date,
                   import_step = 'CONTACTED', import_step_at = current_date, first_issued_date = current_date
             WHERE import_request_id = :id
            """, Map.of("id", id));
    }

    private BigDecimal qcfs(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :i",
            Map.of("i", itemId), BigDecimal.class);
    }

    private String fulfilment(long ticket) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :t",
            Map.of("t", ticket), String.class);
    }

    private String stage(long ticket) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :t",
            Map.of("t", ticket), String.class);
    }

    private long stageEvents(long ticket) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'STAGE_CHANGED'",
            Map.of("t", ticket), Long.class);
    }
}
