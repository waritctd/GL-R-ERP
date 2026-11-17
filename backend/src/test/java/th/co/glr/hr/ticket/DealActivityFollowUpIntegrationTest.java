package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;

/**
 * Slice 6.4: the call dialog's ตามครั้งถัดไป — the optional {@code nextFollowUpAt} on POST /api/tickets/{id}/activities
 * (DealActivityRequest), through the real TicketService and TicketRepository on real Postgres.
 *
 * <p>Who may set a deal's follow-up date is exactly who may already set it today: {@code updateTracking}'s
 * requireDealOwnership (the deal's owner/keyer with the sales role, or sales_manager / ceo) plus its requireActive.
 * Written wrong-way-round: every refused caller gets 403 (409 for a non-ACTIVE deal, 400 for a past date) and NOTHING
 * is written — neither the date nor the activity. And the write touches ONLY next_follow_up_at: the tracking PUT is
 * a full replace that would wipe designer/owner/buyer names, which is why this path never goes through it.
 *
 * <p>Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealActivityFollowUpIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    /** 01:00 on Friday 13 Nov 2026 in Bangkok, still Thursday 12 Nov in UTC: the two calendars disagree. */
    private static final Instant NOW = Instant.parse("2026-11-12T18:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @BeforeEach
    void fixTheClock() {
        ticketService.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── the write itself ─────────────────────────────────────────────────────

    @Test
    void ownersCallWithAFollowUp_writesOnlyTheFollowUpDate_andTheActivity() {
        long deal = deal(salesActor);
        fillTrackingFields(deal);
        Map<String, Object> before = trackingSnapshot(deal);

        DealActivityDto activity = ticketService.addActivity(deal,
            new DealActivityRequest(TODAY, DealActivityKind.CALL, "คุยแล้ว ยังสนใจ", TODAY.plusDays(7)), salesActor);

        assertThat(activity.kind()).isEqualTo(DealActivityKind.CALL);
        assertThat(activityCount(deal)).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(TODAY.plusDays(7));
        assertThat(trackingSnapshot(deal))
            .as("designer/owner/buyer names, win%, quarter, estimate, last follow-up: untouched")
            .isEqualTo(before);
    }

    @Test
    void aCallWithoutAFollowUp_leavesTheDealsDateAsItWas() {
        long deal = deal(salesActor);
        setNextFollowUp(deal, TODAY.plusDays(20));
        fillTrackingFields(deal);
        Map<String, Object> before = trackingSnapshot(deal);

        ticketService.addActivity(deal, new DealActivityRequest(TODAY, DealActivityKind.CALL, "ไม่รับสาย"), salesActor);

        assertThat(nextFollowUp(deal)).isEqualTo(TODAY.plusDays(20));
        assertThat(trackingSnapshot(deal)).isEqualTo(before);
        assertThat(activityCount(deal)).isEqualTo(1);
    }

    @Test
    void todayInBangkokIsAllowed_evenWhileUtcIsStillOnTheDayBefore() {
        long deal = deal(salesActor);

        ticketService.addActivity(deal, new DealActivityRequest(TODAY, DealActivityKind.CALL, null, TODAY), salesActor);

        assertThat(nextFollowUp(deal)).isEqualTo(TODAY);
    }

    @Test
    void theSameRuleTheTrackingPathUses_letsTheKeyerTheManagerAndTheCeoSetIt() {
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ 6.4", "ceo-64@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
        long keyed = deal(otherSalesActor);
        jdbc.update("UPDATE sales.ticket SET keyed_by = :keyer WHERE ticket_id = :id",
            Map.of("keyer", salesActor.id(), "id", keyed));

        ticketService.addActivity(keyed,
            new DealActivityRequest(TODAY, DealActivityKind.CALL, null, TODAY.plusDays(3)), salesActor);
        assertThat(nextFollowUp(keyed)).as("keyer").isEqualTo(TODAY.plusDays(3));

        for (UserPrincipal overseer : List.of(salesManagerActor, ceo)) {
            long deal = deal(salesActor);
            ticketService.addActivity(deal,
                new DealActivityRequest(TODAY, DealActivityKind.CALL, null, TODAY.plusDays(14)), overseer);
            assertThat(nextFollowUp(deal)).as(overseer.role()).isEqualTo(TODAY.plusDays(14));
        }
    }

    // ── wrong-way-round: refused, and nothing at all is written ──────────────

    @Test
    void anotherRepAccountImportHrAndAQuotationGrantHolder_are403_andNothingIsWritten() {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        List<UserPrincipal> outsiders = List.of(
            otherSalesActor,
            actor(createEmployee("บัญชี 6.4", "acc-64@glr.co.th", "AC", "AC-ฝ่ายบัญชี", null), "account"),
            actor(createEmployee("นำเข้า 6.4", "imp-64@glr.co.th", "PCIM", "PCIM-จัดซื้อต่างประเทศ", null), "import"),
            actor(createEmployee("บุคคล 6.4", "hr-64@glr.co.th", "HR", "HR-บุคคล", null), "hr"),
            grantHolder());

        for (UserPrincipal outsider : outsiders) {
            refusal(() -> ticketService.addActivity(deal,
                new DealActivityRequest(TODAY, DealActivityKind.CALL, "แอบตั้งวัน", TODAY.plusDays(7)), outsider),
                HttpStatus.FORBIDDEN);
        }

        assertThat(nextFollowUp(deal)).isEqualTo(original);
        assertThat(activityCount(deal)).isZero();
    }

    @Test
    void aPastDateInBangkokIs400_andNothingIsWritten() {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);

        // 12 Nov is still "today" in UTC at this instant, but it is yesterday in Bangkok.
        refusal(() -> ticketService.addActivity(deal,
            new DealActivityRequest(TODAY, DealActivityKind.CALL, null, TODAY.minusDays(1)), salesActor),
            HttpStatus.BAD_REQUEST);

        assertThat(nextFollowUp(deal)).isEqualTo(original);
        assertThat(activityCount(deal)).isZero();
    }

    @Test
    void aNonActiveDealRefusesTheFollowUp_409_butStillTakesAPlainActivityAsBefore() {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id", Map.of("id", deal));

        refusal(() -> ticketService.addActivity(deal,
            new DealActivityRequest(TODAY, DealActivityKind.CALL, null, TODAY.plusDays(7)), salesActor),
            HttpStatus.CONFLICT);
        assertThat(nextFollowUp(deal)).isEqualTo(original);
        assertThat(activityCount(deal)).isZero();

        // Unchanged behaviour: logging why a deal went quiet still works on a non-ACTIVE deal.
        ticketService.addActivity(deal, new DealActivityRequest(TODAY, DealActivityKind.CALL, "ลูกค้าเลือกเจ้าอื่น"),
            salesActor);
        assertThat(activityCount(deal)).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A QC employee holding hr.employee.can_create_quotation, resolved through the real session resolver: it acts
     * as {@code sales} — and is on no deal. */
    private UserPrincipal grantHolder() {
        long id = createEmployee("QC ผู้ได้สิทธิ์ออกใบเสนอราคา 6.4", "qc-grant-64@glr.co.th", "QC", "QC-QC&ISO", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", id));
        UserPrincipal resolved = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(id).orElseThrow();
        assertThat(resolved.role()).as("the grant makes a QC employee act as sales").isEqualTo("sales");
        return resolved;
    }

    private void fillTrackingFields(long deal) {
        jdbc.update("""
            UPDATE sales.ticket
               SET designer_name = 'สตูดิโอเส้นตรง', owner_name = 'คุณเจ้าของ', buyer_name = 'บริษัท ผู้ซื้อ',
                   win_probability = 60, expected_quarter = '2027-Q1', estimated_value = 250000,
                   last_follow_up_at = DATE '2026-11-01'
             WHERE ticket_id = :id
            """, Map.of("id", deal));
    }

    /** Every tracking column the PUT /tracking full replace would rewrite, except next_follow_up_at itself. */
    private Map<String, Object> trackingSnapshot(long deal) {
        return jdbc.queryForMap("""
            SELECT designer_name, owner_name, buyer_name, win_probability, expected_quarter, estimated_value,
                   last_follow_up_at, lifecycle, sales_stage
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal));
    }

    private void setNextFollowUp(long deal, LocalDate date) {
        jdbc.update("UPDATE sales.ticket SET next_follow_up_at = :d WHERE ticket_id = :id", Map.of("d", date, "id", deal));
    }

    private LocalDate nextFollowUp(long deal) {
        return jdbc.queryForObject("SELECT next_follow_up_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal), LocalDate.class);
    }

    /** Activities other than the "สร้างงาน" row TicketService#create writes. */
    private int activityCount(long deal) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND note IS DISTINCT FROM 'สร้างงาน'",
            Map.of("id", deal), Integer.class);
    }
}
