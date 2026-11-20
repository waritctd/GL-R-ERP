package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** WHO may record a delivery — stages 13–14 (ส่งมอบสินค้า). ROLE_PERMISSIONS in frontend/src/api/routes.js
 * records that it "is read+comment oversight ONLY … it must never be added to" the write permissions. */
class DeliveryAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;
    private UserPrincipal salesManagerUser;
    private UserPrincipal warehouseUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ส่งมอบ", "deliv-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น", "deliv-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "deliv-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "deliv-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "deliv-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล", "deliv-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "deliv-salesmgr@glr.co.th"), "sales_manager");
        warehouseUser = principal(createEmployee(employees, "พนักงานคลัง", "deliv-wh@glr.co.th"), "warehouse");
    }

    /** The case the widened gate must NOT open. otherSalesRep holds the same sales role as the owner, so the only
     * thing between them and another rep's deal is the per-row ownership test. */
    @Test
    void salesRepWhoDoesNotOwnTheDeal_isRefused_andNothingIsDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "3.00"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    /** The 2026-09/V184 transfer's own case, written wrong-way-round like every refusal above: import used to KEEP
     * delivery access under the additive 2026-08-17 widening — the later transfer took it away. */
    @Test
    void import_noLongerRecordsDelivery_theChangeIsATransferToSales_notAnAddition() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(
                ticketId, deliver(itemId, "2.00"), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
    }

    @Test
    void dealOwner_completesDelivery_andTheDealReachesFullyDelivered() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);

        ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest(null, null), owner);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("10.00");
        assertThat(tickets.findById(ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
    }

    @Test
    void noInvoice_salesAndWarehouseMayRecordWithANote_storedAndInTheHistory_blankIsRefused() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);
        // An invoiced round with quantity left does not stop the no-invoice path: a note and no round named records with no
        // round (goods the invoice does not cover can still go), and over-long notes are a 400.
        assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, deliverNoInvoice(itemId, "2.00", "x".repeat(501)), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        jdbc.update("INSERT INTO hr.division (source_code, name_th, is_active) VALUES ('AC', 'AC-บัญชี', TRUE)", Map.of());
        jdbc.update("UPDATE hr.employee SET division_id = (SELECT division_id FROM hr.division WHERE source_code = 'AC') WHERE employee_id = :e",
            Map.of("e", accountUser.id()));
        ticketService.recordPartialDelivery(ticketId, deliverNoInvoice(itemId, "2.00", "PO-9"), owner);
        assertThat(jdbc.queryForObject("SELECT delivery_request_id FROM sales.delivery_record", Map.of(), Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT no_invoice_note FROM sales.delivery_record", Map.of(), String.class)).isEqualTo("PO-9");
        // Account (the AC division) hears of it for the audit; the actor is excluded.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE type = 'DELIVERY_RECORDED' AND message LIKE '%ก่อนออกใบกำกับภาษี%'",
            Map.of(), Integer.class)).isPositive();
        jdbc.update("DELETE FROM sales.delivery_record_item", Map.of());
        jdbc.update("DELETE FROM sales.delivery_record", Map.of());
        jdbc.update("UPDATE sales.ticket_item SET qty_delivered = 0", Map.of());
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FROM_STOCK'", Map.of());
        jdbc.update("DELETE FROM sales.delivery_request_line", Map.of());
        jdbc.update("DELETE FROM sales.delivery_request", Map.of());
        long sentRound = DeliveryRoundFixture.addRound(jdbc, ticketId, "SENT");
        // Sales still sees the action (the round no longer gates it).
        assertThat(ticketService.actions(ticketId, owner).availableActions().stream().map(TicketResponses.TicketActionDto::action))
            .contains("RECORD_PARTIAL_DELIVERY", "COMPLETE_DELIVERY");

        // A blank or missing note is refused with its own code; nothing is written.
        for (String blank : new String[] {null, "   "}) {
            assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, deliverNoInvoice(itemId, "3.00", blank), owner))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getDetails()).containsEntry("code", "NO_INVOICE_NOTE_REQUIRED");
                });
        }
        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");
        // Warehouse staff without an invoice: the same note rule, then recorded with the note stored.
        assertThatThrownBy(() -> ticketService.recordRoundDelivery(ticketId, sentRound, new RoundDeliveryRequest(
                List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("1.00"))), null, null, null, null, " "), warehouseUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsEntry("code", "NO_INVOICE_NOTE_REQUIRED"));
        // A round that is not this deal's is a 404 and writes nothing; a sent round (not yet invoiced) is linked.
        assertThatThrownBy(() -> ticketService.recordRoundDelivery(ticketId, 999L, new RoundDeliveryRequest(
                List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("1.00"))), null, null, null, null, "x"), warehouseUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        ticketService.recordRoundDelivery(ticketId, sentRound, new RoundDeliveryRequest(
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("1.00"))), null, null, null, null, "ใบส่งของ 5"), warehouseUser);
        assertThat(jdbc.queryForObject("SELECT delivery_request_id FROM sales.delivery_record", Map.of(), Long.class)).isEqualTo(sentRound);
        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForList("SELECT no_invoice_note FROM sales.delivery_record", Map.of(), String.class)).containsExactly("ใบส่งของ 5");
        jdbc.update("DELETE FROM sales.delivery_record_item", Map.of());
        jdbc.update("DELETE FROM sales.delivery_record", Map.of());
        jdbc.update("UPDATE sales.ticket_item SET qty_delivered = 0", Map.of());

        ticketService.recordPartialDelivery(ticketId, deliverNoInvoice(itemId, "3.00", "  PO-77 ส่งก่อน  "), owner);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("3.00");
        assertThat(jdbc.queryForObject("SELECT no_invoice_note FROM sales.delivery_record", Map.of(), String.class)).isEqualTo("PO-77 ส่งก่อน");
        assertThat(jdbc.queryForObject("SELECT delivery_request_id FROM sales.delivery_record", Map.of(), Long.class)).isNull();
        assertThat(jdbc.queryForList("SELECT message FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'DELIVERY_RECORDED'",
            Map.of("t", ticketId), String.class)).filteredOn(m -> m.contains("ส่งก่อนออกใบกำกับ: PO-77 ส่งก่อน")).hasSize(1);
        assertThat(tickets.findDeliveriesByTicket(ticketId).get(0).noInvoiceNote()).isEqualTo("PO-77 ส่งก่อน");
        // ส่งมอบครบ takes the same note rule.
        assertThatThrownBy(() -> ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest(null, null), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails()).containsEntry("code", "NO_INVOICE_NOTE_REQUIRED"));
        ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest(null, null, null, null, "ส่งส่วนที่เหลือ ใบส่งของ 12"), owner);
        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("10.00");
    }

    @Test
    void overWhatTheInvoiceCovers_isRefused_andARecordWithinItIsLinkedToTheRound() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);
        jdbc.update("UPDATE sales.delivery_request_line SET qty = 6", Map.of());

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, deliver(itemId, "7.00"), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getDetails()).containsEntry("code", "ROUND_QTY_EXCEEDED");
                assertThat(e.getMessage()).contains("(เหลือ 6)");
            });
        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");

        ticketService.recordPartialDelivery(ticketId, deliver(itemId, "6.00"), owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE delivery_request_id IS NOT NULL",
            Map.of(), Integer.class)).isEqualTo(1);
    }

    @Test
    void anyWarehouseStaff_recordsOnTheRoundEndpoint_butNeverOnTheDealRoutes_andTheOwnerIsTold() {
        long ticketId = deliverableDeal();
        long itemId = onlyItemId(ticketId);
        long roundId = jdbc.queryForObject("SELECT delivery_request_id FROM sales.delivery_request", Map.of(), Long.class);
        var one = new RoundDeliveryRequest(List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal("4.00"))),
            null, null, "คุณลูกค้า", null, null);

        assertThatThrownBy(() -> ticketService.recordPartialDelivery(ticketId, deliver(itemId, "1.00"), warehouseUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest(null, null), warehouseUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        for (UserPrincipal outsider : List.of(otherSalesRep, importUser, accountUser)) {
            assertThatThrownBy(() -> ticketService.recordRoundDelivery(ticketId, roundId, one, outsider))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("0.00");

        ticketService.recordRoundDelivery(ticketId, roundId, one, warehouseUser);

        assertThat(qtyDelivered(itemId)).isEqualByComparingTo("4.00");
        assertThat(jdbc.queryForList("SELECT message FROM hr.notification WHERE employee_id = :e AND type = 'DELIVERY_RECORDED'",
            Map.of("e", ownerId), String.class)).singleElement().asString().startsWith("ส่งของแล้ว 4 แผ่น");
    }

    /** A deal with 10 units, all declared from stock, so a delivery is actually possible — canRecordDelivery needs
     * remaining quantity AND a source (FROM_STOCK, stock on the lines, or a received warehouse quantity). */
    private long deliverableDeal() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบส่งมอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("10.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ส่งมอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        // Stock goods leave only after the deposit (walk 4): the fixture records it, since this class is about WHO may deliver.
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :id",
            java.util.Map.of("id", ticketId));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), "ทดสอบ"))),
            ceoUser);
        // The tax-invoice rule: goods leave only against an INVOICED round.
        DeliveryRoundFixture.ensureInvoicedRound(jdbc, ticketId);
        return ticketId;
    }

    private static RecordDeliveryRequest deliverNoInvoice(long itemId, String qty, String note) {
        return new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal(qty))), null, null, null, note);
    }

    private static RecordDeliveryRequest deliver(long itemId, String qty) {
        return new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(itemId, new BigDecimal(qty))), null);
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private BigDecimal qtyDelivered(long itemId) {
        return jdbc.queryForObject("SELECT qty_delivered FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-deliv@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
