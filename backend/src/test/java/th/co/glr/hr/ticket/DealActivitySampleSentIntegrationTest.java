package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.salesboard.SalesBoardDtos.BoardDeal;
import th.co.glr.hr.salesboard.SalesBoardRepository;

/**
 * Slice 6d (prototype item 6.7, owner answer 1): ส่งตัวอย่าง is a deal activity of its own kind, {@code SAMPLE_SENT}
 * (V223 widens chk_deal_activity_kind). It goes through the SAME POST /api/tickets/{id}/activities path as a call —
 * TicketService#addActivity, real repository, real Postgres — so it carries exactly a call's rule: requireDealOwnership
 * (the deal's owner/keyer with the sales role, or sales_manager / ceo), the optional nextFollowUpAt of slice 6.4, and it
 * counts as a touch for the quiet rule and as ควรตามต่อ's "ครั้งล่าสุด". Unlike a call, what was sent is required.
 *
 * <p>Written wrong-way-round: every refused caller gets 403 and NOTHING is written — no activity, no follow-up date.
 * The kind is spelled as a literal here, not DealActivityKind's constant, so the class compiles before the kind exists
 * and its first run fails on the missing behaviour.
 *
 * <p>Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealActivitySampleSentIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String SAMPLE_SENT = "SAMPLE_SENT";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Bangkok"));

    // ── the write itself ─────────────────────────────────────────────────────

    @Test
    void theOwnerRecordsASampleSent_withWhatWasSent_andAFollowUpDate() {
        long deal = deal(salesActor);

        DealActivityDto logged = ticketService.addActivity(deal,
            new DealActivityRequest(TODAY, SAMPLE_SENT, "ตัวอย่างกระเบื้อง Raku 3 สี", TODAY.plusDays(7)), salesActor);

        assertThat(logged.kind()).isEqualTo(SAMPLE_SENT);
        assertThat(logged.note()).isEqualTo("ตัวอย่างกระเบื้อง Raku 3 สี");
        assertThat(logged.createdById()).isEqualTo(salesActor.id());
        assertThat(sampleRows(deal)).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(TODAY.plusDays(7));
    }

    @Test
    void withoutAFollowUpDate_theDealsDateStaysAsItWas() {
        long deal = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET next_follow_up_at = :d WHERE ticket_id = :id",
            Map.of("d", TODAY.plusDays(20), "id", deal));

        ticketService.addActivity(deal, new DealActivityRequest(TODAY, SAMPLE_SENT, "แคตตาล็อก"), salesActor);

        assertThat(nextFollowUp(deal)).isEqualTo(TODAY.plusDays(20));
        assertThat(sampleRows(deal)).isEqualTo(1);
    }

    @Test
    void whatWasSentIsRequired_blankOrMissingIs400_andNothingIsWritten() {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);

        for (String blank : Arrays.asList(null, "", "   ")) {
            ApiExceptionAssert.of(refusal(() -> ticketService.addActivity(deal,
                    new DealActivityRequest(TODAY, SAMPLE_SENT, blank, TODAY.plusDays(3)), salesActor),
                HttpStatus.BAD_REQUEST)).messageContains("ส่งอะไร");
        }

        assertThat(sampleRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void aCallStillNeedsNoNote_theRequirementIsTheNewKindsAlone() {
        long deal = deal(salesActor);

        ticketService.addActivity(deal, new DealActivityRequest(TODAY, DealActivityKind.CALL, null), salesActor);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'CALL'",
            Map.of("id", deal), Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameRuleAsACall_letsTheKeyerTheManagerAndTheCeoRecordIt() {
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ 6d", "ceo-6d@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
        long keyed = deal(otherSalesActor);
        jdbc.update("UPDATE sales.ticket SET keyed_by = :keyer WHERE ticket_id = :id",
            Map.of("keyer", salesActor.id(), "id", keyed));

        ticketService.addActivity(keyed, new DealActivityRequest(TODAY, SAMPLE_SENT, "ตัวอย่างไม้"), salesActor);
        assertThat(sampleRows(keyed)).as("keyer").isEqualTo(1);

        for (UserPrincipal overseer : List.of(salesManagerActor, ceo)) {
            long deal = deal(salesActor);
            ticketService.addActivity(deal,
                new DealActivityRequest(TODAY, SAMPLE_SENT, "ตัวอย่างหิน", TODAY.plusDays(14)), overseer);
            assertThat(sampleRows(deal)).as(overseer.role()).isEqualTo(1);
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
            actor(createEmployee("บัญชี 6d", "acc-6d@glr.co.th", "AC", "AC-ฝ่ายบัญชี", null), "account"),
            actor(createEmployee("นำเข้า 6d", "imp-6d@glr.co.th", "PCIM", "PCIM-จัดซื้อต่างประเทศ", null), "import"),
            actor(createEmployee("บุคคล 6d", "hr-6d@glr.co.th", "HR", "HR-บุคคล", null), "hr"),
            grantHolder());

        for (UserPrincipal outsider : outsiders) {
            refusal(() -> ticketService.addActivity(deal,
                new DealActivityRequest(TODAY, SAMPLE_SENT, "แอบบันทึกส่งตัวอย่าง", TODAY.plusDays(7)), outsider),
                HttpStatus.FORBIDDEN);
        }

        assertThat(sampleRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    // ── it counts as following up the deal ───────────────────────────────────

    @Test
    void aSampleSentTakesAQuietDealOutOfQuiet_andIsWhatTheFollowUpRowShowsAsLast() {
        QuietRule quietRule = new QuietRule(jdbc, QuietRule.DEFAULT_QUIET_DAYS);
        SalesBoardRepository board = new SalesBoardRepository(jdbc, quietRule);
        long deal = deal(salesActor);
        // Everything that touched the deal happened a month ago: it is quiet.
        jdbc.update("UPDATE sales.ticket SET created_at = now() - interval '30 days' WHERE ticket_id = :id",
            Map.of("id", deal));
        jdbc.update("UPDATE sales.deal_activity SET created_at = now() - interval '30 days' WHERE ticket_id = :id",
            Map.of("id", deal));
        jdbc.update("UPDATE sales.ticket_event SET created_at = now() - interval '30 days' WHERE ticket_id = :id",
            Map.of("id", deal));
        assertThat(quietRule.isQuiet(deal)).as("precondition: quiet").isTrue();

        ticketService.addActivity(deal, new DealActivityRequest(TODAY, SAMPLE_SENT, "ตัวอย่าง Raku สีเทา"), salesActor);

        assertThat(quietRule.isQuiet(deal)).as("a sample sent is a touch, as a call is").isFalse();
        BoardDeal row = board.activeDealsOwnedBy(salesActor.id()).stream()
            .filter(d -> d.ticketId() == deal).findFirst().orElseThrow();
        assertThat(row.quiet()).isFalse();
        assertThat(row.lastActivityKind()).isEqualTo(SAMPLE_SENT);
        assertThat(row.lastActivityNote()).isEqualTo("ตัวอย่าง Raku สีเทา");
    }

    // ── one list of kinds: Java, the database, and nothing else ──────────────

    /** DealActivityKind.VALID and chk_deal_activity_kind must name the same kinds, read from the live constraint. */
    @Test
    void theJavaKindsAndTheDatabaseConstraint_nameExactlyTheSameKinds_includingSampleSent() {
        String definition = jdbc.queryForObject("""
            SELECT pg_get_constraintdef(c.oid)
              FROM pg_constraint c
              JOIN pg_class r ON r.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = r.relnamespace
             WHERE n.nspname = 'sales' AND r.relname = 'deal_activity' AND c.conname = 'chk_deal_activity_kind'
            """, Map.of(), String.class);
        Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> inDatabase = new java.util.TreeSet<>();
        while (m.find()) {
            inDatabase.add(m.group(1));
        }

        assertThat(inDatabase).contains(SAMPLE_SENT);
        assertThat(new java.util.TreeSet<>(DealActivityKind.VALID)).isEqualTo(inDatabase);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A QC employee holding hr.employee.can_create_quotation, resolved through the real session resolver: it acts
     * as {@code sales} — and is on no deal. */
    private UserPrincipal grantHolder() {
        long id = createEmployee("QC ผู้ได้สิทธิ์ออกใบเสนอราคา 6d", "qc-grant-6d@glr.co.th", "QC", "QC-QC&ISO", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", id));
        UserPrincipal resolved = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(id).orElseThrow();
        assertThat(resolved.role()).as("the grant makes a QC employee act as sales").isEqualTo("sales");
        return resolved;
    }

    private LocalDate nextFollowUp(long deal) {
        return jdbc.queryForObject("SELECT next_follow_up_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal), LocalDate.class);
    }

    /** Rows of the new kind only; a text compare, so it reads 0 (not an error) before the kind exists. */
    private int sampleRows(long deal) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'SAMPLE_SENT'",
            Map.of("id", deal), Integer.class);
    }

    /** Tiny wrapper so the 400's message is checked with the refusal. */
    private record ApiExceptionAssert(th.co.glr.hr.common.ApiException e) {
        static ApiExceptionAssert of(th.co.glr.hr.common.ApiException e) { return new ApiExceptionAssert(e); }
        void messageContains(String text) { assertThat(e.getMessage()).contains(text); }
    }
}
