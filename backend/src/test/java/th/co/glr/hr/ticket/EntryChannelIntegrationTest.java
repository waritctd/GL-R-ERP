package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** V144: sales.ticket.entry_channel defaults to UNSPECIFIED ("not stated") instead of asserting DESIGNER_LED,
 * and UNSPECIFIED is rejected as an INPUT so a stated channel can never be un-stated. Mockito cannot reach any
 * of this. */
class EntryChannelIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private UserPrincipal salesRep;
    private long projectId;
    private String customerName;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PricingRequestService is never reached by create() or setEntryChannel(); it exists only to satisfy the
        // constructor.
        ticketService = new TicketService(tickets, new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new ObjectMapper(), customers, new QuotationRenderer(), mock(PricingRequestService.class), new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย ทดสอบ", null, null, null, null, null, null, null,
            "sales-entry-channel@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        salesRep = new UserPrincipal(salesRepId, "sales@glr.co.th", "sales", "sales", salesRepId,
            true, LocalDate.of(2020, 1, 1), false, null, false);

        // TicketService.create() refuses a project-less deal (V50), so a real project is required.
        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        customerName = customer.name();
        ProjectDto project = new ProjectRepository(jdbc).create(customer.id(), "โครงการทดสอบ");
        projectId = project.id();
    }

    /** Evidence 3, wrong-way-round: the caller must NOT be able to un-state a channel, and the stored value must
     * genuinely not move. Mirrors generateQuotation's QuotationRecipient.UNSPECIFIED rejection. */
    @Test
    void setEntryChannel_rejectsUnspecified_andLeavesTheStatedChannelUntouched() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();
        ticketService.setEntryChannel(ticketId, EntryChannel.OWNER_DIRECT, null, salesRep);
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);

        assertThatThrownBy(() ->
            ticketService.setEntryChannel(ticketId, EntryChannel.UNSPECIFIED, "เปลี่ยนกลับ", salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);
    }

    /** But overwriting an already-STATED channel still requires a reason, as before. */
    @Test
    void setEntryChannel_changingAnAlreadyStatedChannelStillRequiresANote() {
        long ticketId = ticketService.create(request(EntryChannel.OWNER_DIRECT), salesRep).summary().id();

        assertThatThrownBy(() -> ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);

        ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT, "ลูกค้าติดต่อเองภายหลัง", salesRep);
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.BUYER_DIRECT);
    }

    private CreateTicketRequest request(String entryChannel) {
        return new CreateTicketRequest("ดีลทดสอบ entry channel", "NORMAL", customerName,
            null, projectId, null, null, entryChannel, List.of());
    }

    private String storedChannel(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().entryChannel();
    }

}
