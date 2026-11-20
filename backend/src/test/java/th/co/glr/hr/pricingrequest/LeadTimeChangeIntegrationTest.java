package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.CreateLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.LineInput;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.RejectLeadTimeChangeRequest;
import th.co.glr.hr.notification.DealNoticeText;
import th.co.glr.hr.support.RecordingSalesMailer;

/**
 * CR-1 (GLA-167), part C — lead-time change request (option C: one request per factory quote,
 * lines pre-ticked, per-line override, ONE decision for the whole request). Written BEFORE the
 * implementation, against real Postgres through the real services and repositories.
 *
 * <p>Ruling: import raises and edits; only the OWNING sales rep or a sales manager decides. The
 * CEO, import, account and any OTHER sales rep cannot — those cases are written wrong-way-round.
 *
 * <p>Fixture: one request with THREE Factory A lines (lead times 30-45, 60-90, 10-20) and one
 * Factory B line (5-8), every quote already contacted (status forced to REQUESTED).
 *
 * <p>Notices (bell AND email, not bell-only): the service is re-wired with a
 * {@link RecordingSalesMailer}. The owning rep and the fixture sales manager are both given a
 * ผู้จัดการ position so they sit in the {@code sales_manager} fan-out — that is what makes "excluding
 * the rep" and "never the actor" observable. {@code otherSm} is a second sales manager.
 */
class LeadTimeChangeIntegrationTest extends Cr1FixtureSupport {
    private long prId;
    private FactoryQuoteDto quoteA;
    private long a1;
    private long a2;
    private long a3;
    private long b1;
    private RecordingSalesMailer mailer;
    private long otherSm;

    @BeforeEach
    void setUp() {
        mailer = new RecordingSalesMailer(jdbc);
        fixtureMailer = mailer;
        wireServicesAndCreateDeal();
        long managerPosition = jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active) VALUES ('LTC-SM', 'ผู้จัดการฝ่ายขาย', TRUE)
            RETURNING position_id
            """, Map.of(), Long.class);
        for (long id : new long[] {salesRepId, salesManagerUserId}) {
            jdbc.update("UPDATE hr.employee SET position_id = :p WHERE employee_id = :id", Map.of("p", managerPosition, "id", id));
        }
        otherSm = mailer.seedRoleEmployee("sales_manager");
        prId = requestInImportReview(
            line("A-1", "Factory A", 10, 30, 45, null, null),
            line("A-2", "Factory A", 10, 60, 90, null, null),
            line("A-3", "Factory A", 10, 10, 20, null, null),
            line("B-1", "Factory B", 10, 5, 8, null, null));
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(prId, importActor);
        // Contacted by forcing the status directly (not via markContacted): these tests are about the lead-time flow,
        // so they must stay red for THAT reason and not for the A-part stub.
        for (FactoryQuoteDto d : drafts) {
            jdbc.update("UPDATE sales.factory_quote SET status = 'REQUESTED', requested_at = now() WHERE factory_quote_id = :id",
                java.util.Map.of("id", d.id()));
        }
        quoteA = quoteFor(factoryQuoteService.list(prId, importActor), "Factory A");
        a1 = itemId("A-1");
        a2 = itemId("A-2");
        a3 = itemId("A-3");
        b1 = itemId("B-1");
    }

    // ── C1 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void importRaisesAPendingChangeForTwoOfThreeLines_andCurrentLeadTimesStayUntouched() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(),
            body("ผู้ผลิตแจ้งเลื่อนการผลิต", line(a1, 100, 120), line(a2, 100, 120)), importActor);

        // bell AND email: the owning rep (employee) + the sales managers EXCLUDING the rep (who would
        // otherwise be a second bell, being in that role here); never the import actor
        assertNotices(PricingRequestEventKind.LEAD_TIME_CHANGE_REQUESTED, "ขอเปลี่ยนระยะเวลานำเข้า รอฝ่ายขายอนุมัติ",
            Map.of(salesRepId, requestedMessage(), salesManagerUserId, requestedMessage(), otherSm, requestedMessage()), 2);

        assertThat(change.status()).isEqualTo(LeadTimeChangeStatus.PENDING);
        assertThat(change.factoryQuoteId()).isEqualTo(quoteA.id());
        assertThat(change.requestedBy()).isEqualTo(importUserId);
        assertThat(change.reason()).isEqualTo("ผู้ผลิตแจ้งเลื่อนการผลิต");
        assertThat(change.lines()).extracting(LeadTimeChangeDtos.LeadTimeChangeLineDto::pricingRequestItemId)
            .containsExactlyInAnyOrder(a1, a2);
        assertThat(change.lines()).allSatisfy(l -> {
            assertThat(l.newMinDays()).isEqualTo(100);
            assertThat(l.newMaxDays()).isEqualTo(120);
        });
        // nothing is applied until sales approves
        assertLeadTime(a1, 30, 45);
        assertLeadTime(a2, 60, 90);
        assertLeadTime(a3, 10, 20);
        assertLeadTime(b1, 5, 8);
    }

    // ── C4 — who may create / edit / withdraw ────────────────────────────────────────────────

    @Test
    void onlyImportMayCreate_salesSalesManagerCeoAndAccountAreForbidden() {
        for (UserPrincipal forbidden : List.of(salesActor, otherSalesActor, salesManagerActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.create(quoteA.id(), body("x", line(a1, 100, 120)), forbidden))
                .as("create as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    // ── C5 / C6 — approval ───────────────────────────────────────────────────────────────────

    @Test
    void theOwningRepApproves_listedLinesTakeTheNewValues_unlistedLinesAreUntouched() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(),
            body("r1", line(a1, 100, 120), line(a2, 150, 180)), importActor);

        LeadTimeChangeDto approved = leadTimeChangeService.approve(change.id(), change.version(), salesActor);

        // the REP decided: import is told (action-free FYI) and the sales managers are told so their
        // "รออนุมัติ" bell is not left hanging — but the rep is NOT told about their own decision, and
        // is not reached a second time through the sales_manager role they sit in here
        assertNotices(PricingRequestEventKind.LEAD_TIME_CHANGE_APPROVED, "อนุมัติการเปลี่ยนระยะเวลานำเข้าแล้ว",
            Map.of(importUserId, approvedMessage(salesActor), salesManagerUserId, approvedMessage(salesActor),
                otherSm, approvedMessage(salesActor)), 2);

        assertThat(approved.status()).isEqualTo(LeadTimeChangeStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo(salesRepId);
        assertThat(approved.decidedAt()).isNotNull();
        assertLeadTime(a1, 100, 120);
        assertLeadTime(a2, 150, 180);   // per-line override honoured
        assertLeadTime(a3, 10, 20);     // unlisted line on the SAME factory
        assertLeadTime(b1, 5, 8);       // other factory
    }

    // ── C7 — wrong-way-round ─────────────────────────────────────────────────────────────────

    @Test
    void nobodyElseMayApprove_aDifferentSalesRepImportCeoAndAccountAreForbidden() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (UserPrincipal forbidden : List.of(otherSalesActor, importActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.approve(change.id(), change.version(), forbidden))
                .as("approve as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
        assertLeadTime(a1, 30, 45);
    }

    // ── C8 — reject ──────────────────────────────────────────────────────────────────────────

    @Test
    void rejectNeedsAReason_andLeavesLeadTimesUnchanged() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> leadTimeChangeService.reject(change.id(), new RejectLeadTimeChangeRequest(blank, change.version()), salesActor))
                .as("reason=%s", blank)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        // a refused reject tells nobody
        mailer.assertNoNotice(PricingRequestEventKind.LEAD_TIME_CHANGE_REJECTED, "ไม่อนุมัติการเปลี่ยนระยะเวลานำเข้า");
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);

        LeadTimeChangeDto rejected = leadTimeChangeService.reject(change.id(),
            new RejectLeadTimeChangeRequest("ลูกค้าไม่รับระยะเวลานี้", change.version()), salesActor);
        assertThat(rejected.status()).isEqualTo(LeadTimeChangeStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("ลูกค้าไม่รับระยะเวลานี้");
        assertThat(rejected.decidedBy()).isEqualTo(salesRepId);
        assertLeadTime(a1, 30, 45);
        // the REP rejected: import gets the reason + next step, the sales managers get an FYI, the rep nothing
        assertNotices(PricingRequestEventKind.LEAD_TIME_CHANGE_REJECTED, "ไม่อนุมัติการเปลี่ยนระยะเวลานำเข้า",
            Map.of(importUserId, rejectedMessage(salesActor, "ลูกค้าไม่รับระยะเวลานี้", false),
                salesManagerUserId, rejectedMessage(salesActor, "ลูกค้าไม่รับระยะเวลานี้", true),
                otherSm, rejectedMessage(salesActor, "ลูกค้าไม่รับระยะเวลานี้", true)), 2);
    }

    // ── round 2: optimistic concurrency between import's edit and the approver's decision ────

    @Test
    void approveOrRejectWithAStaleVersionIs409_afterImportEdited_andNothingIsApplied() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        assertThat(change.version()).isEqualTo(1);
        LeadTimeChangeDto edited = leadTimeChangeService.update(change.id(), body("r1 edited", line(a2, 150, 180)), importActor);
        assertThat(edited.version()).as("an import edit bumps the version").isEqualTo(2);

        // the approver still holds version 1
        assertConflict(() -> leadTimeChangeService.approve(change.id(), 1, salesActor));
        assertConflict(() -> leadTimeChangeService.reject(change.id(), new RejectLeadTimeChangeRequest("no", 1), salesActor));
        assertLeadTime(a1, 30, 45);
        assertLeadTime(a2, 60, 90);
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);

        // a missing expectedVersion is a caller bug (400), and the current version still works
        assertThatThrownBy(() -> leadTimeChangeService.approve(change.id(), null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(leadTimeChangeService.approve(change.id(), edited.version(), salesActor).status())
            .isEqualTo(LeadTimeChangeStatus.APPROVED);
        assertLeadTime(a2, 150, 180);
    }

    // ── round 3 ──────────────────────────────────────────────────────────────────────────────

    /** Cancelling the request withdraws its pending change even when NO quote row is cancelled by that call (here
     * the only quotes are already NOT_AVAILABLE, so the cancel cascade touches none). */
    @Test
    void cancellingTheRequestAutoWithdrawsThePendingChange_evenWhenNoQuoteRowWasCancelled() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        jdbc.update("UPDATE sales.factory_quote SET status = 'NOT_AVAILABLE' WHERE pricing_request_id = :id",
            java.util.Map.of("id", prId));

        pricingRequestService.cancel(prId, new PricingRequestRequests.CancelPricingRequestRequest("ลูกค้ายกเลิก"), salesActor);

        assertAutoWithdrawn(change.id());
        assertLeadTime(a1, 30, 45);
    }

    private void assertAutoWithdrawn(long changeId) {
        LeadTimeChangeDto after = leadTimeChangeService.listForPricingRequest(prId, importActor).stream()
            .filter(c -> c.id() == changeId).findFirst().orElseThrow();
        assertThat(after.status()).isEqualTo(LeadTimeChangeStatus.WITHDRAWN);
        assertThat(after.decisionReason()).isEqualTo("ใบราคาถูกแก้ไข — คำขอเดิมถูกยกเลิกอัตโนมัติ");
        assertThat(eventCount(prId, PricingRequestEventKind.LEAD_TIME_CHANGE_WITHDRAWN)).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private String link() {
        return "/pricing-requests/" + prId;
    }

    /** Line 2 of every notice: {@code คำขอราคา <code> · ดีล <ticket> · ลูกค้า <customer>}. */
    private String requestLine() {
        PricingRequestDtos.PricingRequestSummaryDto s = pricingRequests.findSummary(prId).orElseThrow();
        return "คำขอราคา " + s.requestCode() + " · " + DealNoticeText.dealLabel(s.ticketCode(), s.customerName());
    }

    private String requestedMessage() {
        return "ฝ่ายนำเข้า (" + importActor.name() + ") ขอเปลี่ยนระยะเวลานำเข้าของโรงงาน Factory A (2 รายการ) — เหตุผล: ผู้ผลิตแจ้งเลื่อนการผลิต\n"
            + requestLine() + "\nสิ่งที่ต้องทำต่อ: ตรวจสอบระยะเวลานำเข้าใหม่ แล้วอนุมัติหรือไม่อนุมัติ";
    }

    private String approvedMessage(UserPrincipal decider) {
        return decider.name() + " อนุมัติการเปลี่ยนระยะเวลานำเข้าของโรงงาน Factory A แล้ว\n" + requestLine() + "\n" + DealNoticeText.FYI;
    }

    /** {@code forFyi}: the other-decider copy (FYI) rather than import's copy (next step). */
    private String rejectedMessage(UserPrincipal decider, String reason, boolean forFyi) {
        return decider.name() + " ไม่อนุมัติการเปลี่ยนระยะเวลานำเข้าของโรงงาน Factory A — เหตุผล: " + reason + "\n" + requestLine() + "\n"
            + (forFyi ? DealNoticeText.FYI : "สิ่งที่ต้องทำต่อ: ตรวจสอบเหตุผล แล้วส่งคำขอใหม่หากยังต้องเปลี่ยน");
    }

    /**
     * Exactly these recipients (no more — so never the actor, never a double) each got ONE bell row of
     * {@code type} with this exact title/message/link AND one email, and {@code expectedMails} emails in
     * all carry the title (one per employee notice / role fan-out).
     */
    private void assertNotices(String type, String title, Map<Long, String> messageByRecipient, int expectedMails) {
        mailer.assertBellRecipients(type, messageByRecipient.keySet().stream().mapToLong(Long::longValue).toArray());
        messageByRecipient.forEach((id, message) -> mailer.assertNoticeFor(id, type, title, message, link()));
        assertThat(mailer.mails().stream().filter(m -> title.equals(m.title())).count())
            .as("emails titled '%s'", title).isEqualTo(expectedMails);
    }

    private long itemId(String model) {
        return pricingRequestService.get(prId, importActor).items().stream()
            .filter(i -> model.equals(i.model())).findFirst().orElseThrow().id();
    }

    private void assertLeadTime(long itemId, int min, int max) {
        PricingRequestDtos.PricingRequestItemDto item = pricingRequestService.get(prId, importActor).items().stream()
            .filter(i -> i.id() == itemId).findFirst().orElseThrow();
        assertThat(item.leadTimeMinDays()).as("item %d min", itemId).isEqualTo(min);
        assertThat(item.leadTimeMaxDays()).as("item %d max", itemId).isEqualTo(max);
    }

    private static LineInput line(long itemId, Integer min, Integer max) {
        return new LineInput(itemId, min, max);
    }

    private static CreateLeadTimeChangeRequest body(String reason, LineInput... lines) {
        return new CreateLeadTimeChangeRequest(reason, List.of(lines));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
