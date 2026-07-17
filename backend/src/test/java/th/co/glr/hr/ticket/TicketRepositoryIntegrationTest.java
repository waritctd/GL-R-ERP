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
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises TicketRepository's batched item insert, summary/pagination SQL, and the multi-query
 * detail fetch against a real PostgreSQL database (issue #28).
 */
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    @Test
    void createsTicketWithBatchedItemsAndReadsThemBack() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L"),
            item("Honda", "Civic", "Black", "Gloss", "M")), tickets.nextTicketCode(), actorId, "พนักงานขาย");

        TicketDto ticket = tickets.findById(ticketId).orElseThrow();

        assertThat(ticket.summary().code()).startsWith("PR-");
        assertThat(ticket.summary().createdById()).isEqualTo(actorId);
        assertThat(ticket.items()).hasSize(2);
        assertThat(ticket.items()).extracting(TicketItemDto::brand)
            .containsExactlyInAnyOrder("Toyota", "Honda");
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
            QuotationRecipient.DESIGNER, "Designer Co.", "30 วัน", "45 วัน", "ส่งถึงไซต์", null);
        QuotationDto ownerV1 = tickets.createQuotation(ticketId, "QT-2026-0002", actorId, new BigDecimal("1200"),
            QuotationRecipient.OWNER, "Owner", null, null, null, null);
        QuotationDto designerV2 = tickets.createQuotation(ticketId, "QT-2026-0003", actorId, new BigDecimal("1400"),
            QuotationRecipient.DESIGNER, null, null, null, null, null);

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

    // ── quotation snapshot (V49): item-data freeze at issue time ─────────────

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

        // amount = unit_price * qty = 200.00; TicketItemDto has no amount field, so read
        // the stored column directly to confirm it was computed and frozen at insert time.
        BigDecimal amount = jdbc.queryForObject(
            "SELECT amount FROM sales.quotation_item WHERE quotation_id = :id",
            Map.of("id", quotation.id()), BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("200.00");
    }

    @Test
    void insertQuotationItems_noOpWhenAllItemsUnpriced() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        QuotationDto quotation = tickets.createQuotation(ticketId, "QT-2026-0013", actorId, BigDecimal.ZERO);

        TicketItemDto unpriced = new TicketItemDto(0L, ticketId, "Honda", "Civic", null, null, null,
            null, new BigDecimal("5"), null, null, null, null, null,
            null, "THB", 0, null, null, null, "PIECE", null, null);

        tickets.insertQuotationItems(quotation.id(), List.of(unpriced));

        assertThat(tickets.findQuotationItemsByQuotationId(quotation.id(), ticketId)).isEmpty();
    }

    @Test
    void updateQuotationHeader_persistsCustomerAndProjectSnapshot() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        QuotationDto quotation = tickets.createQuotation(ticketId, "QT-2026-0011", actorId, new BigDecimal("100.00"));

        tickets.updateQuotationHeader(quotation.id(), "Frozen Co., Ltd.", "Frozen Address",
            "0100000000001", "02-111-1111", "Frozen Project");

        TicketRepository.QuotationHeaderSnapshot header =
            tickets.findQuotationHeaderSnapshot(quotation.id()).orElseThrow();
        assertThat(header.customerName()).isEqualTo("Frozen Co., Ltd.");
        assertThat(header.customerAddress()).isEqualTo("Frozen Address");
        assertThat(header.customerTaxId()).isEqualTo("0100000000001");
        assertThat(header.customerPhone()).isEqualTo("02-111-1111");
        assertThat(header.projectName()).isEqualTo("Frozen Project");
    }

    @Test
    void uniqueIndex_rejectsDuplicateTicketRecipientAndVersionPair() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");
        tickets.createQuotation(ticketId, "QT-2026-0020", actorId, new BigDecimal("100.00"));

        // Same ticket + same recipient_type + same version is rejected.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status)
            VALUES (:ticketId, :number, :issuedBy, :total, 'THB', 1, 'ISSUED')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QT-2026-0021")
                .addValue("issuedBy", actorId)
                .addValue("total", new BigDecimal("100.00"))))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Same ticket + same version is allowed for a different recipient chain.
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status, recipient_type)
            VALUES (:ticketId, :number, :issuedBy, :total, 'THB', 1, 'ISSUED', 'DESIGNER')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", "QT-2026-0022")
                .addValue("issuedBy", actorId)
                .addValue("total", new BigDecimal("100.00")));
    }

    @Test
    void createTicket_withCustomerProjectContact_surfacesInSummary() {
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);

        CustomerDto customer = customers.create("บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบ");
        ContactDto contact = contacts.create(customer.id(), "สมชาย", "ใจดี", "ผู้จัดการ", "somchai@test.co.th", "080-000-0000");

        long ticketId = tickets.create(
            new CreateTicketRequest("ใบเสนอราคา", "NORMAL", customer.name(),
                customer.id(), project.id(), contact.id(), null, null, List.of(item("Toyota", "Hilux", "White", "Matte", "L"))),
            tickets.nextTicketCode(), actorId, "พนักงานขาย");

        TicketSummaryDto summary = tickets.findById(ticketId).orElseThrow().summary();

        assertThat(summary.customerId()).isEqualTo(customer.id());
        assertThat(summary.projectId()).isEqualTo(project.id());
        assertThat(summary.projectName()).isEqualTo("โครงการทดสอบ");
        assertThat(summary.contactId()).isEqualTo(contact.id());
        assertThat(summary.contactName()).isEqualTo("สมชาย ใจดี");
    }

    @Test
    void addEventWithSnapshot_persistsAndReadsBackAsJsonText() {
        long ticketId = tickets.create(sampleTicket(
            item("Toyota", "Hilux", "White", "Matte", "L")), tickets.nextTicketCode(), actorId, "พนักงานขาย");

        tickets.addEventWithSnapshot(ticketId, actorId, "พนักงานขาย",
            TicketEventKind.PRICE_PROPOSED, TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED,
            "note", "[{\"brand\":\"Toyota\",\"qty\":1}]");

        List<TicketEventDto> events = tickets.findById(ticketId).orElseThrow().events();
        TicketEventDto priceProposed = events.stream()
            .filter(e -> TicketEventKind.PRICE_PROPOSED.equals(e.kind()))
            .findFirst().orElseThrow();

        assertThat(priceProposed.itemSnapshot()).contains("brand").contains("Toyota");

        // plain addEvent (no snapshot) must still work and leave item_snapshot null
        tickets.addEvent(ticketId, actorId, "พนักงานขาย", TicketEventKind.COMMENTED, null, null, "no snapshot here");
        TicketEventDto commented = tickets.findById(ticketId).orElseThrow().events().stream()
            .filter(e -> TicketEventKind.COMMENTED.equals(e.kind()))
            .findFirst().orElseThrow();
        assertThat(commented.itemSnapshot()).isNull();
    }

    private CreateTicketRequest sampleTicket(TicketItemRequest... items) {
        return new CreateTicketRequest("ใบเสนอราคา", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of(items));
    }

    private TicketItemRequest item(String brand, String model, String color, String texture, String size) {
        return new TicketItemRequest(brand, model, color, texture, size, null,
            new BigDecimal("1"), null, null, null, null, null, null, "THB");
    }
}
