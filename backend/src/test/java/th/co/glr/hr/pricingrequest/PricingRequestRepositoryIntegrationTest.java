package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/** Exercises PricingRequestRepository's create/transition/event SQL against a real PostgreSQL database —
 * Mockito cannot cover the CHECK constraints or the compare-and-set transition() semantics this repository
 * relies on. */
class PricingRequestRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository requests;
    private TicketRepository tickets;
    private EmployeeRepository employees;
    private long salesActorId;
    private long ticketId;
    private int clientRequestSeq;

    @BeforeEach
    void wireRepositories() {
        requests = new PricingRequestRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        clientRequestSeq = 1000;

        salesActorId = createEmployee("พนักงานขาย ทดสอบ", "sales@glr.co.th");
        ticketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), salesActorId, "พนักงานขาย ทดสอบ");
    }

    @Test
    void transition_returnsOneFromExpectedStatusAndZeroFromStaleStatus_leavingRowUnchanged() {
        long id = createDraft();

        int firstAttempt = requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        assertThat(firstAttempt).isEqualTo(1);
        assertThat(requests.findSummary(id).orElseThrow().status()).isEqualTo(PricingRequestStatus.SUBMITTED);

        // Stale: the row is no longer DRAFT, so this compare-and-set must no-op.
        int staleAttempt = requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, salesActorId);
        assertThat(staleAttempt).isEqualTo(0);

        PricingRequestSummaryDto unchanged = requests.findSummary(id).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(unchanged.cancelledAt()).isNull();
    }

    // Review remediation (COMMIT 5, P1 finding 1): PricingRequestStatus.ALLOWED used to be purely decorative —
    // transition() persisted whatever (expected, next) pair it was given, regardless of whether the canonical map
    // listed it.
    @Test
    void transition_rejectsATransitionNotInTheCanonicalMap_andLeavesTheRowUnchanged() {
        long id = createDraft();
        // DRAFT -> COSTING_IN_PROGRESS is not, and has never been, a real transition — no code path attempts it; it
        // exists purely to prove the guard fires for a nonsense pair.
        assertThatThrownBy(() -> requests.transition(
            id, PricingRequestStatus.DRAFT, PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DRAFT")
            .hasMessageContaining("AWAITING_FACTORY_RESPONSE");

        PricingRequestSummaryDto unchanged = requests.findSummary(id).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(unchanged.updatedAt()).isNotNull();
    }


    @Test
    void transition_assignedImportId_firstWriterWins_notOverwrittenByLaterPickupAttempt() {
        long importA = createEmployee("นำเข้า เอ", "importa@glr.co.th");
        long importB = createEmployee("นำเข้า บี", "importb@glr.co.th");
        long id = createDraft();
        requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        requests.transition(id, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING, importA, null);

        assertThat(requests.findSummary(id).orElseThrow().assignedImportId()).isEqualTo(importA);

        // A second Import user attempting to claim the request on a later transition; assignedImportId must NOT change
        // to importB.
        requests.transition(id, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
        requests.transition(id, PricingRequestStatus.AWAITING_FACTORY_RESPONSE, PricingRequestStatus.READY_FOR_CEO_REVIEW, importB, null);

        assertThat(requests.findSummary(id).orElseThrow().assignedImportId()).isEqualTo(importA);
    }

    // --- helpers ---

    private long createDraft() {
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, null, null, null, null, null, clientRequestId(String.valueOf(++clientRequestSeq)),
            List.of(item("Toyota", "1")));
        return requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);
    }

    private long createEmployee(String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest("ใบเสนอราคา", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of());
    }

    private PricingRequestItemRequest item(String brand, String model) {
        return new PricingRequestItemRequest(null, null, null, brand, model, brand + " " + model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", UnitBasis.PER_PIECE, QuantityType.REFERENCE, null, null, null);
    }

    private String clientRequestId(String suffix) {
        return "22222222-2222-2222-2222-" + String.format("%012d", Long.parseLong(suffix));
    }
}
