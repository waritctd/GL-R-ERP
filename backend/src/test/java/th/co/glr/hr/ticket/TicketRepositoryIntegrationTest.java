package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Exercises TicketRepository's batched item insert, summary/pagination SQL, and the multi-query detail fetch
 * against a real PostgreSQL database (issue #28). */
class TicketRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private long actorId;

    @BeforeEach
    void wireRepositories() {
        tickets = new TicketRepository(jdbc);
        // ticket.created_by is a FK to hr.employee, so a real employee must exist first.
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        actorId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย ทดสอบ", null, null, null, null, null, null, null,
            "sales@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    @Test
    void createsTicketWithBatchedItemsAndReadsThemBack() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L"),
            item("Honda", "Civic", "Black", "Gloss", "M")), tickets.nextTicketCode(), actorId, "พนักงานขาย");

        TicketDto ticket = tickets.findById(ticketId).orElseThrow();

        // New deals are numbered DL-<year>-<4 digits>; existing PR- codes keep theirs.
        assertThat(ticket.summary().code()).matches("DL-\\d{4}-\\d{4}");
        assertThat(ticket.summary().createdById()).isEqualTo(actorId);
        assertThat(ticket.items()).hasSize(2);
        assertThat(ticket.items()).extracting(TicketItemDto::brand)
            .containsExactlyInAnyOrder("Toyota", "Honda");
    }

    @Test
    void createTicket_stockSourcedLineWithNoPrice_persistsTheFlagAndANullPrice() {
        // chk_ticket_item_stock_sale_price is relaxed to
        // `sourced_from_stock OR stock_sale_price IS NULL`, so a flagged line needs no price.
        long ticketId = tickets.create(sampleTicket(
            itemFromStock("Cotto", "Stone Series", "White", "Matte", "60x60", null)),
            tickets.nextTicketCode(), actorId, "พนักงานขาย");

        TicketItemDto persisted = tickets.findById(ticketId).orElseThrow().items().get(0);
        assertThat(persisted.sourcedFromStock()).isTrue();
        assertThat(persisted.stockSalePrice()).isNull();
    }

    @Test
    void stockSalePrice_rejectsMismatchWithSourcedFromStockFlag() {
        // chk_ticket_item_stock_sale_price (V183): sourcedFromStock=false with a non-null price must be refused at the
        // DB layer, not merely by TicketService's own guard — this proves the CHECK constraint itself, independent of
        // the service-level validation.
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        TicketItemDto existing = tickets.findById(ticketId).orElseThrow().items().get(0);

        TicketItemDto edited = new TicketItemDto(
            existing.id(), ticketId, existing.brand(), existing.model(), existing.color(),
            existing.texture(), existing.size(), existing.factory(),
            existing.qty(), existing.qtySqm(), null, null, null,
            existing.proposedPrice(), existing.approvedPrice(), existing.currency(), 0,
            existing.calcedCost(), existing.calcedPrice(), existing.calcConfigVersion(), "PIECE",
            existing.manualPrice(), existing.manualOverrideReason(),
            existing.qtyDelivered(), existing.qtyFromStock(), existing.stockNote(),
            existing.catalogPriceId(), existing.catalogProductCode(),
            null, null, null, null, null, 1,
            false, new BigDecimal("620.00")); // sourcedFromStock=false but a price is set

        assertThatThrownBy(() -> tickets.replaceItemsPreservingPricing(ticketId, List.of(edited)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertCatalogProductWithSqmPerPiece(String factoryName, String countryCode2, String productCode,
                                                      BigDecimal price, String currency, String priceUnit,
                                                      BigDecimal sqmPerPiece) {
        return insertCatalogProductWithSqmPerPiece(factoryName, countryCode2, productCode, price, currency, priceUnit,
            sqmPerPiece, null, null);
    }

    private long insertCatalogProductWithSqmPerPiece(String factoryName, String countryCode2, String productCode,
                                                      BigDecimal price, String currency, String priceUnit,
                                                      BigDecimal sqmPerPiece, BigDecimal thicknessMm,
                                                      BigDecimal pcsPerBox) {
        Long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, :currency)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING factory_id
            """,
            new MapSqlParameterSource()
                .addValue("name", factoryName)
                .addValue("country", countryCode2)
                .addValue("currency", currency),
            Long.class);
        Long versionId = jdbc.queryForObject("""
            INSERT INTO price_catalog.price_list_versions (factory_id, label, status, effective_from)
            VALUES (:factoryId, 'Test catalog', 'ACTIVE', CURRENT_DATE)
            RETURNING version_id
            """,
            new MapSqlParameterSource().addValue("factoryId", factoryId),
            Long.class);
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.product_prices
                (factory_id, version_id, product_code, price, currency, price_unit, sqm_per_piece,
                 thickness_mm, pcs_per_box)
            VALUES (:factoryId, :versionId, :productCode, :price, :currency, :priceUnit, :sqmPerPiece,
                    :thicknessMm, :pcsPerBox)
            RETURNING price_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("versionId", versionId)
                .addValue("productCode", productCode)
                .addValue("price", price)
                .addValue("currency", currency)
                .addValue("priceUnit", priceUnit)
                .addValue("sqmPerPiece", sqmPerPiece)
                .addValue("thicknessMm", thicknessMm)
                .addValue("pcsPerBox", pcsPerBox),
            Long.class);
    }

    @Test
    void summariesPaginateWhileCountReflectsTheWholeMatch() {
        for (int i = 0; i < 3; i++) {
            tickets.create(sampleTicket(item("Brand" + i, "Model", "Red", "Matte", "S")),
                tickets.nextTicketCode(), actorId, "พนักงานขาย");
        }

        List<TicketSummaryDto> firstPage = tickets.findSummaries(null, null, PageRequest.resolve(0, 2));

        assertThat(firstPage).hasSize(2);
        assertThat(tickets.countSummaries(null, null)).isEqualTo(3);
        assertThat(tickets.findSummaries(null, actorId)).hasSize(3);
        assertThat(firstPage.get(0).itemCount()).isEqualTo(1);
    }

    @Test
    void createQuotation_versionsAndSupersedesPerRecipientChain() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");

        QuotationDto designerV1 = tickets.createQuotation(ticketId, "QT-2026-0001", actorId, new BigDecimal("1000"),
            QuotationRecipient.DESIGNER, "Designer Co.", "30 วัน", "45 วัน", "ส่งถึงไซต์", null, null, null, null);
        QuotationDto ownerV1 = tickets.createQuotation(ticketId, "QT-2026-0002", actorId, new BigDecimal("1200"),
            QuotationRecipient.OWNER, "Owner", null, null, null, null, null, null, null);
        QuotationDto designerV2 = tickets.createQuotation(ticketId, "QT-2026-0003", actorId, new BigDecimal("1400"),
            QuotationRecipient.DESIGNER, null, null, null, null, null, null, null, null);

        assertThat(designerV1.quotationVersion()).isEqualTo(1);
        assertThat(ownerV1.quotationVersion()).isEqualTo(1);
        assertThat(designerV2.quotationVersion()).isEqualTo(2);
        assertThat(designerV2.parentQuotationId()).isEqualTo(designerV1.id());
        assertThat(designerV2.docStatus()).isEqualTo("ISSUED");
        assertThat(designerV1.recipientLabel()).isEqualTo("Designer Co.");
        assertThat(designerV1.paymentTerms()).isEqualTo("30 วัน");

        List<QuotationDto> all = tickets.findById(ticketId).orElseThrow().quotations();
        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(q -> q.id() == designerV1.id())
            .singleElement().extracting(QuotationDto::docStatus).isEqualTo("SUPERSEDED");
        assertThat(all).filteredOn(q -> q.id() == ownerV1.id())
            .singleElement().extracting(QuotationDto::docStatus).isEqualTo("ISSUED");
        assertThat(all).filteredOn(q -> q.id() == designerV2.id())
            .singleElement().extracting(QuotationDto::docStatus).isEqualTo("ISSUED");

        // the DTO's single `quotation` field mirrors the newest version (backward compat)
        assertThat(tickets.findById(ticketId).orElseThrow().quotation().quotationVersion()).isEqualTo(2);
    }

    @Test
    void insertQuotationItems_snapshotsOnlyPricedItemsWithFrozenUnitPriceAndAmount() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        QuotationDto quotation = tickets.createQuotation(ticketId, "QT-2026-0010", actorId, new BigDecimal("200.00"));

        TicketItemDto priced = new TicketItemDto(0L, ticketId, "Toyota", "Hilux", "White", "Matte", "L",
            null, new BigDecimal("2"), null, null, null, "pcs", null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto unpriced = new TicketItemDto(0L, ticketId, "Honda", "Civic", null, null, null,
            null, new BigDecimal("5"), null, null, null, null, null,
            null, "THB", 1, null, null, null, "PIECE", null, null);

        tickets.insertQuotationItems(quotation.id(), List.of(priced, unpriced));

        List<TicketItemDto> snapshot = tickets.findQuotationItemsByQuotationId(quotation.id(), ticketId);
        assertThat(snapshot).hasSize(1); // the unpriced item is never snapshotted
        TicketItemDto row = snapshot.get(0);
        assertThat(row.brand()).isEqualTo("Toyota");
        assertThat(row.model()).isEqualTo("Hilux");
        assertThat(row.approvedPrice()).isEqualByComparingTo("100.00");
        assertThat(row.qty()).isEqualByComparingTo("2");

        // amount = unit_price * qty = 200.00; TicketItemDto has no amount field, so read the stored column directly to
        // confirm it was computed and frozen at insert time.
        BigDecimal amount = jdbc.queryForObject(
            "SELECT amount FROM sales.quotation_item WHERE quotation_id = :id",
            Map.of("id", quotation.id()), BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("200.00");
    }

    @Test
    void syncDepositPolicyFromQuotation_movesASystemOwnedPolicyBothWays_andRecordsNoPerson() {
        long ticketId = confirmedTicket();

        assertThat(tickets.syncDepositPolicyFromQuotation(ticketId, DepositPolicy.REQUIRED,
            DepositPolicy.NOT_REQUIRED, "ใบเสนอราคา QT-1 ระบุไม่รับมัดจำ (ชำระเต็มจำนวน)")).isEqualTo(1);
        assertThat(depositPolicyRow(ticketId))
            .containsEntry("deposit_policy", DepositPolicy.NOT_REQUIRED)
            .containsEntry("deposit_policy_reason", "ใบเสนอราคา QT-1 ระบุไม่รับมัดจำ (ชำระเต็มจำนวน)")
            .containsEntry("deposit_policy_set_by", null);

        assertThat(tickets.syncDepositPolicyFromQuotation(ticketId, DepositPolicy.NOT_REQUIRED,
            DepositPolicy.REQUIRED, "ใบเสนอราคา QT-1-1 ระบุมัดจำ 50%")).isEqualTo(1);
        assertThat(depositPolicyRow(ticketId))
            .containsEntry("deposit_policy", DepositPolicy.REQUIRED)
            .containsEntry("deposit_policy_reason", "ใบเสนอราคา QT-1-1 ระบุมัดจำ 50%")
            .containsEntry("deposit_policy_set_by", null);
    }

    /** A NOT_REQUIRED a person chose (waiveDeposit) is never flipped back by a quotation. */
    @Test
    void syncDepositPolicyFromQuotation_writesNothing_onAPolicyAPersonSet() {
        long ticketId = confirmedTicket();
        tickets.updateDepositPolicy(ticketId, DepositPolicy.NOT_REQUIRED, "ลูกค้าโอนเต็มจำนวนล่วงหน้า", actorId);

        assertThat(tickets.syncDepositPolicyFromQuotation(ticketId, DepositPolicy.NOT_REQUIRED,
            DepositPolicy.REQUIRED, "ใบเสนอราคา QT-3 ระบุมัดจำ 50%")).isZero();
        Map<String, Object> row = depositPolicyRow(ticketId);
        assertThat(row).containsEntry("deposit_policy", DepositPolicy.NOT_REQUIRED)
            .containsEntry("deposit_policy_reason", "ลูกค้าโอนเต็มจำนวนล่วงหน้า");
        assertThat(((Number) row.get("deposit_policy_set_by")).longValue()).isEqualTo(actorId);
    }

    @Test
    void paymentStage_noDeposit_forEveryBypassPolicy_onceTheCustomerConfirmedAndNothingIsPaid() {
        for (String policy : List.of(DepositPolicy.NOT_REQUIRED, DepositPolicy.WAIVED, DepositPolicy.CREDIT_CUSTOMER)) {
            assertThat(paymentStage(payableTicket("CUSTOMER_CONFIRMED", policy))).as(policy).isEqualTo("NO_DEPOSIT");
        }
    }

    @Test
    void paymentStage_balancePending_whenTheTrackIsAwaitingFinalPayment_evenWithADepositPaid() {
        long ticket = payableTicket("AWAITING_FINAL_PAYMENT", DepositPolicy.REQUIRED);
        pay(ticket, "DEPOSIT", "321.00");
        assertThat(paymentStage(ticket)).isEqualTo(PaymentStage.BALANCE_PENDING);
    }

    /** A priced item makes payable 1,000 + 7% VAT = 1,070.00 (ticket-item fallback of payableAmount). */
    private long payableTicket(String paymentStatus, String depositPolicy) {
        long ticketId = tickets.create(sampleTicket(item("Toyota", "Hilux", "White", "Matte", "L")),
            tickets.nextTicketCode(), actorId, "พนักงานขาย");
        jdbc.update("UPDATE sales.ticket_item SET approved_price = 1000 WHERE ticket_id = :id", Map.of("id", ticketId));
        jdbc.update("UPDATE sales.ticket SET payment_status = :ps, deposit_policy = :dp WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("ps", paymentStatus).addValue("dp", depositPolicy)
                .addValue("id", ticketId));
        return ticketId;
    }

    private void pay(long ticketId, String kind, String amount) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
            VALUES (:t, :k, :a, 'THB', now(), :by, :ref)""", new MapSqlParameterSource()
            .addValue("t", ticketId).addValue("k", kind).addValue("a", new BigDecimal(amount))
            .addValue("by", actorId).addValue("ref", "PS-" + ticketId + "-" + kind + "-" + amount));
    }

    private String paymentStage(long ticketId) {
        return tickets.findSummaryById(ticketId).orElseThrow().paymentStage();
    }

    private long confirmedTicket() {
        long ticketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        jdbc.update("UPDATE sales.ticket SET payment_status = 'CUSTOMER_CONFIRMED' WHERE ticket_id = :id",
            Map.of("id", ticketId));
        return ticketId;
    }

    private Map<String, Object> depositPolicyRow(long ticketId) {
        return jdbc.queryForMap("""
            SELECT deposit_policy, deposit_policy_reason, deposit_policy_set_by
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", ticketId));
    }

    private CreateTicketRequest sampleTicket(TicketItemRequest... items) {
        return new CreateTicketRequest("ใบเสนอราคา", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of(items));
    }

    private TicketItemRequest item(String brand, String model, String color, String texture, String size) {
        return new TicketItemRequest(brand, model, color, texture, size, null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB");
    }

    private TicketItemRequest itemFromStock(String brand, String model, String color, String texture,
                                            String size, BigDecimal stockSalePrice) {
        return new TicketItemRequest(brand, model, color, texture, size, null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB",
            null, null, true, stockSalePrice);
    }
}
