package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationDto;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Owner ruling 2026-08-13 — the supervision half of "Sales declares, Import can correct." PR #706 let the deal
 * owner declare qty_from_stock; the stated mitigation for an uncorroborated number feeding that same rep's
 * STOCK_BONUS was "Import can correct it", and nothing told anyone to look. */
class StockDeclarationNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The one used by the notification; anything else must not be selected. */
    private static final String SALES_DIVISION = "SA";
    private static final String IMPORT_DIVISION = "PCIM";
    private static final String EXECUTIVE_DIVISION = "MD";

    private TicketRepository tickets;
    private NotificationRepository notifications;
    private TicketService ticketService;

    private long ownerId;
    private long salesManagerId;
    private long assistantSalesManagerId;
    private long inactiveSalesManagerId;
    private long otherRepId;
    private long salesEmployeeWithNoPositionId;
    private long importManagerId;
    private long ceoId;

    private UserPrincipal owner;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        ticketService = newTicketService(tickets, notifications);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        // The declaring rep. A plain ฝ่ายขาย position, so DivisionAccessPolicy would derive "sales" — not
        // "sales_manager" — for them, which is what makes them a non-recipient of their own notification.
        ownerId = employee(employees, "เจ้าของดีล ทดสอบ", "notif-owner", SALES_DIVISION, "พนักงานขาย");
        owner = principal(ownerId, "เจ้าของดีล ทดสอบ", "sales");

        // The intended recipient, and the assistant that DivisionAccessPolicy documents as also being a sales_manager
        // ("ผู้ช่วยผู้จัดการ" contains "ผู้จัดการ").
        salesManagerId = employee(employees, "ผู้จัดการ ฝ่ายขาย", "notif-sm", SALES_DIVISION, "ผู้จัดการฝ่ายขาย");
        assistantSalesManagerId = employee(employees, "ผู้ช่วย ฝ่ายขาย", "notif-asm",
            SALES_DIVISION, "ผู้ช่วยผู้จัดการฝ่ายขาย");

        // Every category that must NOT be selected.
        inactiveSalesManagerId = employee(employees, "อดีตผู้จัดการ ฝ่ายขาย", "notif-exsm",
            SALES_DIVISION, "ผู้จัดการฝ่ายขาย");
        deactivate(inactiveSalesManagerId);
        otherRepId = employee(employees, "พนักงานขาย อื่น", "notif-rep2", SALES_DIVISION, "พนักงานขาย");
        salesEmployeeWithNoPositionId = employee(employees, "ไม่มีตำแหน่ง ฝ่ายขาย", "notif-nopos",
            SALES_DIVISION, "พนักงานขาย");
        clearPosition(salesEmployeeWithNoPositionId);
        importManagerId = employee(employees, "ผู้จัดการ ฝ่ายจัดซื้อ", "notif-im",
            IMPORT_DIVISION, "ผู้จัดการฝ่ายจัดซื้อต่างประเทศ");
        ceoId = employee(employees, "กรรมการ ผู้จัดการ", "notif-ceo", EXECUTIVE_DIVISION, "กรรมการผู้จัดการ");

        ceoUser = principal(ceoId, "กรรมการ ผู้จัดการ", "ceo");
    }

    /** The defect class PR #708 fixed for files: a side effect that outlives the transaction that caused it. Here
     * it would be a notification telling a manager to review a declaration that never happened. Why this cannot
     * pass vacuously. */
    @Test
    void aRolledBackDeclarationTakesItsNotificationWithIt() {
        long ticketId = createLegacyTicket(List.of(item("100.00")));
        long itemId = onlyItemId(ticketId);

        AtomicBoolean notified = new AtomicBoolean(false);
        NotificationRepository notificationSpy = spy(new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();  // the real INSERT really happens
            notified.set(true);
            return result;
        }).when(notificationSpy).notifyByRole(eq("sales_manager"), anyLong(), anyString(), anyString());

        TicketRepository ticketSpy = spy(new TicketRepository(jdbc));
        doAnswer(invocation -> {
            if (notified.get()) {
                throw new IllegalStateException("simulated failure after the notification was written");
            }
            return invocation.callRealMethod();
        }).when(ticketSpy).findById(anyLong());

        TicketService service = transactional(newTicketService(ticketSpy, notificationSpy));

        assertThatThrownBy(() -> service.decideProcurementSource(ticketId, split(ticketId, true), owner))
            .describedAs("the failure is armed by the notification, so reaching it proves one was issued")
            .isInstanceOf(IllegalStateException.class);

        verify(notificationSpy).notifyByRole(eq("sales_manager"), eq(ticketId),
            eq(TicketEventKind.STOCK_RESERVED), anyString());
        assertThat(inbox(salesManagerId))
            .describedAs("the notification was inserted and then rolled back with the declaration")
            .isEmpty();
        assertThat(inbox(assistantSalesManagerId)).isEmpty();
        assertThat(qtyFromStock(itemId))
            .describedAs("the declaration itself rolled back — otherwise the empty inbox above "
                + "would only mean the INSERT matched nobody")
            .isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT procurement_source_decided_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), java.sql.Timestamp.class))
            .describedAs("the once-only latch rolled back too").isNull();
    }

    private TicketService newTicketService(TicketRepository ticketRepository,
                                           NotificationRepository notificationRepository) {
        // PricingRequestService is mocked exactly as in StockDeclarationAuthzIntegrationTest: reserveStock never calls
        // it. The stub exists only so a future edit routing through markLost/cancel cannot NPE on Mockito's default
        // null.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        return new TicketService(ticketRepository, notificationRepository,
            new ObjectMapper(), new CustomerRepository(jdbc), new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
    }

    private static StockReservationRequest declare(long itemId, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemId, new BigDecimal(qtyFromStock), "มีของในสต็อก")));
    }

    /**
     * A deal whose order was confirmed from a DEAL_DIRECT quotation issued before 2026-11-07 — the
     * "legacy" shape where the rep still makes the import/stock split. Hand-seeded (quotation row +
     * DEAL_PROMOTED_FROM_QUOTATION event) because the quotation service wiring is package-private elsewhere.
     */
    private long createLegacyTicket(List<TicketItemRequest> items) {
        long ticketId = createTicket(items);
        long quotationId = jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, issued_at, origin)
            VALUES (:t, :n, :by, '2026-11-06 10:00:00+07', 'DEAL_DIRECT') RETURNING quotation_id
            """, Map.of("t", ticketId, "n", "QT-NOTIF-" + ticketId, "by", ownerId), Long.class);
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, related_document_type, related_document_id)
            VALUES (:t, :by, :kind, 'QUOTATION', :q)
            """, Map.of("t", ticketId, "by", ownerId, "kind", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION, "q", quotationId));
        return ticketId;
    }

    private ProcurementSourceRequest split(long ticketId, boolean... fromStock) {
        List<Long> ids = itemIds(ticketId);
        List<ProcurementSourceRequest.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) lines.add(new ProcurementSourceRequest.Line(ids.get(i), fromStock[i]));
        return new ProcurementSourceRequest(lines);
    }

    private long createTicket(List<TicketItemRequest> items) {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบแจ้งเตือนสต็อก", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, items);
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private static TicketItemRequest item(String qty) {
        return new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
            new BigDecimal(qty), null, "PIECE", null, null, null, null, "THB");
    }

    private long onlyItemId(long ticketId) {
        List<Long> ids = itemIds(ticketId);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    /** Ordered as {@code TicketRepository} returns them, so index 0/1 match the creation order. */
    private List<Long> itemIds(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().items().stream().map(TicketItemDto::id).toList();
    }

    private List<NotificationDto> inbox(long employeeId) {
        return notifications.findByEmployeeId(employeeId);
    }

    private BigDecimal qtyFromStock(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private long employee(EmployeeRepository employees, String name, String emailPrefix,
                          String divisionCode, String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            emailPrefix + "@glr.co.th", null, divisionCode, divisionCode + " Division", "แผนก" + divisionCode,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private void deactivate(long employeeId) {
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id",
            new MapSqlParameterSource().addValue("id", employeeId));
    }

    private void clearPosition(long employeeId) {
        jdbc.update("UPDATE hr.employee SET position_id = NULL WHERE employee_id = :id",
            new MapSqlParameterSource().addValue("id", employeeId));
    }

    /** name is the real employee name, not the role: the notification quotes actor.name(), and a principal whose
     * name were "sales" would let the message assertions pass while saying nothing about which rep declared. */
    private static UserPrincipal principal(long employeeId, String name, String role) {
        return new UserPrincipal(employeeId, role + "-notif@glr.co.th", name, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
