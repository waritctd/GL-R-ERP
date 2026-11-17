package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
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

/**
 * Slice 6d (prototype item 6.7, owner answer 2): บันทึกการส่งสินค้า's วันที่ส่ง. The optional {@code deliveredOn} of
 * POST /api/tickets/{id}/deliveries (RecordDeliveryRequest) back-dates the record — at most 7 days back, never a future
 * day, on the Asia/Bangkok calendar, enforced by the server — through the real TicketService and TicketRepository on
 * real Postgres. A past day is stored in the existing {@code delivery_record.delivered_at} at 12:00 Asia/Bangkok; no
 * date, or today, stores the insert time exactly as before.
 *
 * <p>The due date follows the earlier date, which the owner accepted: it is pinned here through the one shared rule
 * (TicketRepository#paymentDueStates and the summary it feeds), with everything else about the money held equal
 * against a twin deal delivered today.
 *
 * <p>Wrong-way-round: everyone who could not record a delivery cannot back-date one, and nothing is written.
 *
 * <p>Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DeliveryBackDateIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private TicketRepository tickets;
    private TicketService ticketService;
    private EmployeeRepository employees;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;
    private UserPrincipal salesManagerUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), new CustomerRepository(jdbc), new QuotationRenderer(), pricingRequests,
            new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc),
            new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee("เจ้าของงาน ย้อนวันส่ง", "bd-owner@glr.co.th", "SALES", "แผนกขาย");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee("พนักงานขายอื่น ย้อนวัน", "bd-other@glr.co.th", "SALES", "แผนกขาย"), "sales");
        importUser = principal(createEmployee("ฝ่ายนำเข้า ย้อนวัน", "bd-import@glr.co.th", "PCIM", "PCIM-จัดซื้อต่างประเทศ"), "import");
        ceoUser = principal(createEmployee("ซีอีโอ ย้อนวัน", "bd-ceo@glr.co.th", "EXE", "ผู้บริหาร"), "ceo");
        accountUser = principal(createEmployee("ฝ่ายบัญชี ย้อนวัน", "bd-account@glr.co.th", "AC", "AC-ฝ่ายบัญชี"), "account");
        hrUser = principal(createEmployee("ฝ่ายบุคคล ย้อนวัน", "bd-hr@glr.co.th", "HR", "HR-บุคคล"), "hr");
        salesManagerUser = principal(createEmployee("ผจก.ขาย ย้อนวัน", "bd-sm@glr.co.th", "SALES", "ฝ่ายขาย"),
            "sales_manager");
    }

    private static LocalDate today() {
        return LocalDate.now(BANGKOK);
    }

    // ── what is stored ───────────────────────────────────────────────────────

    @Test
    void aPastDayIsStoredAtNoonBangkok_onThatDay() {
        long deal = deliverableDeal(null);

        ticketService.recordPartialDelivery(deal, deliver(deal, "4.00", today().minusDays(5)), owner);

        assertThat(deliveredAtsInBangkok(deal)).containsExactly(today().minusDays(5).atTime(12, 0));
        assertThat(qtyDelivered(deal)).isEqualByComparingTo("4.00");
    }

    @Test
    void sevenDaysBackIsTheEarliestDayAccepted() {
        long deal = deliverableDeal(null);

        ticketService.recordPartialDelivery(deal, deliver(deal, "1.00", today().minusDays(7)), owner);

        assertThat(deliveredAtsInBangkok(deal)).containsExactly(today().minusDays(7).atTime(12, 0));
    }

    @Test
    void noDateOrToday_storesTheMomentItWasRecorded_exactlyAsBefore() {
        for (LocalDate deliveredOn : new LocalDate[] {null, today()}) {
            long deal = deliverableDeal(null);

            ticketService.recordPartialDelivery(deal, deliver(deal, "2.00", deliveredOn), owner);

            Map<String, Object> row = jdbc.queryForMap(
                "SELECT delivered_at, created_at, now() AS db_now FROM sales.delivery_record WHERE ticket_id = :id",
                Map.of("id", deal));
            assertThat(row.get("delivered_at")).as("deliveredOn=%s", deliveredOn).isEqualTo(row.get("created_at"));
        }
    }

    @Test
    void theEventInTheHistorySaysWhichDayTheGoodsWent_whenItIsNotToday() {
        long deal = deliverableDeal(null);
        LocalDate day = today().minusDays(3);

        ticketService.recordPartialDelivery(deal, deliver(deal, "4.00", day), owner);

        String message = jdbc.queryForObject("""
            SELECT message FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'DELIVERY_RECORDED'
            """, Map.of("id", deal), String.class);
        assertThat(message).contains("วันที่ส่ง " + th.co.glr.hr.common.ThaiText.date(day));
    }

    // ── the window, enforced by the server ───────────────────────────────────

    @Test
    void eightDaysBack_is400_andNothingIsWritten() {
        long deal = deliverableDeal(null);

        ApiException e = refusal(() -> ticketService.recordPartialDelivery(
            deal, deliver(deal, "4.00", today().minusDays(8)), owner), HttpStatus.BAD_REQUEST);

        assertThat(e.getMessage()).contains("7 วัน");
        assertNothingDelivered(deal);
    }

    @Test
    void tomorrow_is400_andNothingIsWritten() {
        long deal = deliverableDeal(null);

        ApiException e = refusal(() -> ticketService.recordPartialDelivery(
            deal, deliver(deal, "4.00", today().plusDays(1)), owner), HttpStatus.BAD_REQUEST);

        assertThat(e.getMessage()).contains("อนาคต");
        assertNothingDelivered(deal);
    }

    /** 01:00 on Friday 13 Nov 2026 in Bangkok is still Thursday 12 Nov in UTC: the window is the Bangkok one. */
    @Test
    void theWindowIsTheBangkokCalendar_notUtc() {
        ticketService.useClock(Clock.fixed(Instant.parse("2026-11-12T18:00:00Z"), ZoneOffset.UTC));
        LocalDate bangkokToday = LocalDate.of(2026, 11, 13);

        long earliest = deliverableDeal(null);
        ticketService.recordPartialDelivery(earliest, deliver(earliest, "1.00", bangkokToday.minusDays(7)), owner);
        assertThat(deliveredAtsInBangkok(earliest)).containsExactly(LocalDateTime.of(2026, 11, 6, 12, 0));

        // 5 Nov is 7 days back from UTC's 12 Nov, but 8 from Bangkok's 13 Nov.
        long tooEarly = deliverableDeal(null);
        refusal(() -> ticketService.recordPartialDelivery(
            tooEarly, deliver(tooEarly, "1.00", LocalDate.of(2026, 11, 5)), owner), HttpStatus.BAD_REQUEST);
        assertNothingDelivered(tooEarly);

        // 13 Nov is "tomorrow" in UTC, but today in Bangkok.
        long bangkokTodayDeal = deliverableDeal(null);
        ticketService.recordPartialDelivery(bangkokTodayDeal, deliver(bangkokTodayDeal, "1.00", bangkokToday), owner);
        assertThat(qtyDelivered(bangkokTodayDeal)).isEqualByComparingTo("1.00");

        long future = deliverableDeal(null);
        refusal(() -> ticketService.recordPartialDelivery(
            future, deliver(future, "1.00", bangkokToday.plusDays(1)), owner), HttpStatus.BAD_REQUEST);
        assertNothingDelivered(future);
    }

    // ── wrong-way-round: whoever cannot record a delivery cannot back-date one ─

    @Test
    void anotherRepTheSalesManagerAccountImportHrAndAQuotationGrantHolder_are403_andNothingIsWritten() {
        long deal = deliverableDeal(null);
        List<UserPrincipal> outsiders = List.of(
            otherSalesRep, salesManagerUser, accountUser, importUser, hrUser, grantHolder());

        for (UserPrincipal outsider : outsiders) {
            refusal(() -> ticketService.recordPartialDelivery(
                deal, deliver(deal, "4.00", today().minusDays(3)), outsider), HttpStatus.FORBIDDEN);
            // An out-of-window date does not turn the refusal into a validation answer: who comes first.
            refusal(() -> ticketService.recordPartialDelivery(
                deal, deliver(deal, "4.00", today().minusDays(30)), outsider), HttpStatus.FORBIDDEN);
        }

        assertNothingDelivered(deal);
    }

    @Test
    void theOwnerAndTheCeo_mayBackDate() {
        long ownersDeal = deliverableDeal(null);
        ticketService.recordPartialDelivery(ownersDeal, deliver(ownersDeal, "1.00", today().minusDays(2)), owner);
        assertThat(deliveredAtsInBangkok(ownersDeal)).containsExactly(today().minusDays(2).atTime(12, 0));

        long ceosDeal = deliverableDeal(null);
        ticketService.recordPartialDelivery(ceosDeal, deliver(ceosDeal, "1.00", today().minusDays(2)), ceoUser);
        assertThat(deliveredAtsInBangkok(ceosDeal)).containsExactly(today().minusDays(2).atTime(12, 0));
    }

    // ── the due date follows the earlier date; nothing else about the money moves ─

    /**
     * Two identical deals (฿100,000 accepted quotation, CREDIT 3 days from full delivery, deposit paid, balance owed),
     * delivered in full by the same rep: one today, one back-dated 5 days. The due date is the delivery date + 3 as
     * paymentDueStates computes it; the back-dated one is therefore already past due (today − 2) and overdue, the
     * other due today + 3 and not. Everything else the summary says about money, stage and fulfilment is identical.
     */
    @Test
    void aBackDatedFinalDelivery_movesTheDueDateAndTheOverdueFlag_andNothingElse() {
        long deliveredToday = deliverableDeal(3);
        long backDated = deliverableDeal(3);

        ticketService.recordPartialDelivery(deliveredToday, deliver(deliveredToday, "10.00", null), owner);
        ticketService.recordPartialDelivery(backDated, deliver(backDated, "10.00", today().minusDays(5)), owner);

        Map<Long, TicketRepository.PaymentDueState> due = tickets.paymentDueStates(List.of(deliveredToday, backDated));
        assertThat(due.get(deliveredToday)).isEqualTo(new TicketRepository.PaymentDueState(today().plusDays(3), false));
        assertThat(due.get(backDated)).isEqualTo(new TicketRepository.PaymentDueState(today().minusDays(2), true));

        TicketSummaryDto a = tickets.findSummaryById(deliveredToday).orElseThrow();
        TicketSummaryDto b = tickets.findSummaryById(backDated).orElseThrow();
        assertThat(b.paymentDueDate()).isEqualTo(today().minusDays(2));
        assertThat(b.overdue()).isTrue();
        assertThat(a.paymentDueDate()).isEqualTo(today().plusDays(3));
        assertThat(a.overdue()).isFalse();
        assertThat(b.paymentDueBasis()).isEqualTo(a.paymentDueBasis()).isEqualTo("CREDIT_FROM_DELIVERY");
        assertThat(b.paymentDueCreditDays()).isEqualTo(a.paymentDueCreditDays()).isEqualTo(3);

        // Everything else: identical.
        assertThat(b.paymentStatus()).isEqualTo(a.paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(b.paymentStage()).isEqualTo(a.paymentStage());
        assertThat(b.amountPayable()).isEqualByComparingTo(a.amountPayable());
        assertThat(b.amountPaid()).isEqualByComparingTo(a.amountPaid());
        assertThat(b.amountOutstanding()).isEqualByComparingTo(a.amountOutstanding()).isPositive();
        assertThat(b.fulfillmentStatus()).isEqualTo(a.fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(b.salesStage()).isEqualTo(a.salesStage()).isEqualTo(DealStage.DELIVERED);
        assertThat(b.commissionRecorded()).isEqualTo(a.commissionRecorded()).isFalse();
        assertThat(commissionRows(backDated)).isEqualTo(commissionRows(deliveredToday)).isZero();
        assertThat(eventKinds(backDated)).isEqualTo(eventKinds(deliveredToday));
    }

    /**
     * delivered_at is read as the DELIVERY date, not the insert time: the due date takes the LATEST delivery date (the
     * day the last goods arrived), and the history lists records in delivery-date order. A final batch back-dated
     * before a partial recorded today therefore leaves the due date on today's partial.
     */
    @Test
    void aFinalBatchBackDatedBeforeAnEarlierRecordedPartial_dueDateIsTheLatestDeliveryDay_andTheListIsInDeliveryOrder() {
        long deal = deliverableDeal(3);

        ticketService.recordPartialDelivery(deal, deliver(deal, "6.00", null), owner);
        ticketService.recordPartialDelivery(deal, deliver(deal, "4.00", today().minusDays(4)), owner);

        assertThat(tickets.findSummaryById(deal).orElseThrow().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(tickets.paymentDueStates(List.of(deal)).get(deal).dueDate()).isEqualTo(today().plusDays(3));
        List<DeliveryRecordDto> listed = tickets.findDeliveriesByTicket(deal);
        assertThat(listed).extracting(r -> r.items().get(0).qty().stripTrailingZeros().toPlainString())
            .containsExactly("4", "6");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /**
     * 10 units owned by {@link #owner}, all declared from stock and the deposit paid, so a STOCK delivery is possible.
     * With {@code creditDays}, a ฿100,000 accepted quotation whose remainder is CREDIT that many days from full
     * delivery, and a ฿30,000 deposit received — a balance stays owed.
     */
    private long deliverableDeal(Integer creditDays) {
        CreateTicketRequest request = new CreateTicketRequest(
            "งานทดสอบย้อนวันส่ง", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("10.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของงาน ย้อนวันส่ง");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        jdbc.update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_PAID' WHERE ticket_id = :id", Map.of("id", ticketId));
        if (creditDays != null) {
            long q = tickets.createQuotation(ticketId, "QT-BD-" + ticketId, ownerId, new BigDecimal("100000.00")).id();
            tickets.markQuotationStatus(ticketId, q, "ACCEPTED");
            jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = :d WHERE quotation_id = :q",
                Map.of("d", creditDays, "q", q));
            jdbc.update("""
                INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
                VALUES (:t, 'DEPOSIT', 30000.00, 'THB', now(), :by, :ref)
                """, Map.of("t", ticketId, "by", ownerId, "ref", "BD-" + ticketId));
        }
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(onlyItemId(ticketId), new BigDecimal("10.00"), "ทดสอบ"))),
            ceoUser);
        return ticketId;
    }

    private RecordDeliveryRequest deliver(long ticketId, String qty, LocalDate deliveredOn) {
        return new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(onlyItemId(ticketId), new BigDecimal(qty))), null, deliveredOn);
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private BigDecimal qtyDelivered(long ticketId) {
        return jdbc.queryForObject("SELECT qty_delivered FROM sales.ticket_item WHERE ticket_id = :id",
            Map.of("id", ticketId), BigDecimal.class);
    }

    private List<LocalDateTime> deliveredAtsInBangkok(long ticketId) {
        return jdbc.queryForList("""
            SELECT delivered_at AT TIME ZONE 'Asia/Bangkok' FROM sales.delivery_record
             WHERE ticket_id = :id ORDER BY delivery_id
            """, Map.of("id", ticketId), LocalDateTime.class);
    }

    private void assertNothingDelivered(long ticketId) {
        assertThat(qtyDelivered(ticketId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.delivery_record WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM sales.ticket_event
             WHERE ticket_id = :id AND kind IN ('DELIVERY_RECORDED', 'DELIVERY_COMPLETED')
            """, Map.of("id", ticketId), Integer.class)).isZero();
        assertThat(tickets.findSummaryById(ticketId).orElseThrow().fulfillmentStatus())
            .isNotIn(FulfilmentStatus.PARTIALLY_DELIVERED, FulfilmentStatus.FULLY_DELIVERED);
    }

    private int commissionRows(long ticketId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.commission_record WHERE source_ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private List<String> eventKinds(long ticketId) {
        return jdbc.queryForList("SELECT kind FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", ticketId), String.class);
    }

    private UserPrincipal grantHolder() {
        long id = createEmployee("QC ผู้ได้สิทธิ์ออกใบเสนอราคา ย้อนวัน", "bd-qc-grant@glr.co.th", "QC", "QC-QC&ISO");
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", id));
        UserPrincipal resolved = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(id).orElseThrow();
        assertThat(resolved.role()).as("the grant makes a QC employee act as sales").isEqualTo("sales");
        return resolved;
    }

    private static ApiException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("expected a %s refusal", status).isInstanceOf(ApiException.class);
        ApiException e = (ApiException) thrown;
        assertThat(e.getStatus()).as("status of: %s", e.getMessage()).isEqualTo(status);
        return e;
    }

    private long createEmployee(String name, String email, String divisionCode, String divisionName) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionName, divisionName,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-bd@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
