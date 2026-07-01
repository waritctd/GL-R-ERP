package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises TicketRepository's batched item insert, summary/pagination SQL, and the multi-query
 * detail fetch against a real PostgreSQL database (issue #28).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
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

    private CreateTicketRequest sampleTicket(TicketItemRequest... items) {
        return new CreateTicketRequest("ใบเสนอราคา", "NORMAL", "ลูกค้าทดสอบ", null, List.of(items));
    }

    private TicketItemRequest item(String brand, String model, String color, String texture, String size) {
        return new TicketItemRequest(brand, model, color, texture, size, new BigDecimal("1"), null, "THB");
    }
}
