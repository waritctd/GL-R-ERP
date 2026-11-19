package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;

/**
 * Slice 6d (prototype item 6.7, owner answer 1): ส่งตัวอย่าง is a deal activity of its own kind, {@code SAMPLE_SENT}
 * (V224 widens chk_deal_activity_kind). It goes through the SAME POST /api/tickets/{id}/activities path as a call —
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
