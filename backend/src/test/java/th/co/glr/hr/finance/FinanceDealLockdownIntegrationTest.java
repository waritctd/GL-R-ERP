package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketController;

/**
 * H1 part 2 -- the account lockdown, against the real service, real controllers (MockMvc) and real Postgres.
 *
 * <p>Wrong-way-round first: what account can NO LONGER reach through /api/tickets, which /tickets routes were
 * removed outright, which roles the finance routes refuse, and that a deal outside account's list scope is
 * refused on every finance route -- then the happy path, where every route must both return the finance shape
 * AND actually move the database.
 */
class FinanceDealLockdownIntegrationTest extends FinanceDealTestBase {

    // ═══ B3: account is refused on the /api/tickets read + money routes ═════════════════════════

    @Test
    void account_isRefusedTheTicketReadRoutes_evenOnAnInScopeS15Deal() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");

        for (String path : List.of("/api/tickets/{id}", "/api/tickets/{id}/actions", "/api/tickets/{id}/payments",
                "/api/tickets/{id}/deliveries")) {
            ticketsMvc.perform(get(path, id).session(session(accountUser)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        // and the comment write
        ticketsMvc.perform(post("/api/tickets/{id}/comments", id).session(session(accountUser))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"x\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(commentCount(id)).isZero();
    }

    @Test
    void account_isRefusedTheRemainingTicketsMoneyRoutes_andNothingChanges() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");
        insertPayment(id, "DEPOSIT", new BigDecimal("50000.00"));
        sql("UPDATE sales.ticket SET close_confirmed_at = now(), close_confirmed_by = " + salesRepId
            + " WHERE ticket_id = :id", id);

        expectStatus(ticketsMvc.perform(json(post("/api/tickets/{id}/billing", id).session(session(accountUser)),
            "{\"dueDate\":\"2030-01-01\"}")), 403);
        expectStatus(ticketsMvc.perform(json(post("/api/tickets/{id}/close/revoke", id).session(session(accountUser)),
            "{}")), 403);
        expectStatus(ticketsMvc.perform(json(post("/api/tickets/{id}/stage", id).session(session(accountUser)),
            "{\"stage\":\"CLOSED_PAID\",\"note\":\"x\"}")), 403);

        assertThat(closeConfirmed(id)).as("revoke must not have run").isTrue();
        assertThat(salesStageOf(id)).isEqualTo(DealStage.PROCUREMENT);
        assertThat(jdbc.queryForObject("SELECT due_date IS NULL FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", id), Boolean.class)).isTrue();
    }

    /** The four account-only /tickets routes were REMOVED (their allowed roles became empty): observed as absent. */
    @Test
    void theAccountOnlyTicketsMoneyRoutes_areRemoved_notMerelyRefused_evenForCeo() throws Exception {
        long id = createS15Deal("DEPOSIT_NOTICE_ISSUED");
        // No ApiExceptionHandler here: it turns the framework's "no handler" into a generic 500, which would hide
        // exactly the distinction under test (a route that is GONE -> 404/405, versus one that answers 403).
        MockMvc raw = MockMvcBuilders.standaloneSetup(new TicketController(ticketService, new SessionContext())).build();

        for (UserPrincipal actor : List.of(accountUser, ceoUser)) {
            expectStatus(raw.perform(post("/api/tickets/{id}/deposit-paid", id).session(session(actor))), 404);
            expectStatus(raw.perform(post("/api/tickets/{id}/final-payment", id).session(session(actor))), 404);
            expectStatus(raw.perform(post("/api/tickets/{id}/close/confirm", id).session(session(actor))), 404);
            // /payments exists for GET, so a POST is "method not allowed", never a handler.
            expectStatus(raw.perform(json(post("/api/tickets/{id}/payments", id).session(session(actor)),
                "{\"kind\":\"DEPOSIT\",\"amount\":1}")), 405);
        }
        assertThat(paymentStatusOf(id)).isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(receiptCount(id)).isZero();
    }

    @Test
    void ceoKeepsBillingRevokeAndStageOnTheTicketsPath() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");

        expectStatus(ticketsMvc.perform(json(post("/api/tickets/{id}/billing", id).session(session(ceoUser)),
            "{\"dueDate\":\"2030-01-01\"}")), 200);
        assertThat(jdbc.queryForObject("SELECT due_date IS NOT NULL FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", id), Boolean.class)).isTrue();
    }

    // ═══ B1: finance routes -- role gates and scope (wrong way round) ═══════════════════════════

    @Test
    void everyFinanceRoute_refusesSalesEvenTheDealsOwnRep_andEveryOtherNonFinanceRole() throws Exception {
        long id = createS15Deal("DEPOSIT_NOTICE_ISSUED");

        for (UserPrincipal actor : List.of(salesRep, salesManager, importUser, hrUser, employeeUser)) {
            for (Route route : routes()) {
                expectStatus(financeMvc.perform(route.request(id, actor)), 403, "role " + actor.role() + " on " + route.name());
            }
        }
        assertThat(paymentStatusOf(id)).isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(receiptCount(id)).isZero();
        assertThat(commentCount(id)).isZero();
    }

    @Test
    void ceoIsRefusedTheAccountOnlyFinanceActions_asTodayButMayCommentRevokeAndStage() throws Exception {
        long id = createS15Deal("DEPOSIT_NOTICE_ISSUED");

        for (String name : List.of("deposit-paid", "final-payment", "payments", "close/confirm")) {
            Route route = routes().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
            expectStatus(financeMvc.perform(route.request(id, ceoUser)), 403, "ceo on " + name);
        }
        assertThat(paymentStatusOf(id)).isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(receiptCount(id)).isZero();

        expectStatus(financeMvc.perform(routeNamed("comments").request(id, ceoUser)), 200);
    }

    @Test
    void everyFinanceRoute_refusesAccountOnADealBelowS10_andNothingIsWritten() throws Exception {
        long id = createTicket(DealStage.NEGOTIATION);
        insertAcceptedQuotation(id, "QT-BELOW-" + id, new BigDecimal("1000.00"));

        for (Route route : routes()) {
            expectStatus(financeMvc.perform(route.request(id, accountUser)), 403, "account below S10 on " + route.name());
        }
        assertThat(paymentStatusOf(id)).isNull();
        assertThat(receiptCount(id)).isZero();
        assertThat(commentCount(id)).isZero();
        assertThat(salesStageOf(id)).isEqualTo(DealStage.NEGOTIATION);
    }

    @Test
    void financeRoutes_missingDealIs404_notAScopeRefusal() throws Exception {
        expectStatus(financeMvc.perform(routeNamed("comments").request(999_999_999L, accountUser)), 404);
    }

    // ═══ B1: finance routes -- happy paths: 200 + finance shape + the database actually moved ═══

    @Test
    void depositPaid_accountOnS15_recordsTheReceiptAndAdvancesPayment() throws Exception {
        long id = createS15Deal("DEPOSIT_NOTICE_ISSUED");

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("deposit-paid").request(id, accountUser)));

        assertThat(paymentStatusOf(id)).isEqualTo("DEPOSIT_PAID");
        assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(deal.get("money").get("paymentStatus").asText()).isEqualTo("DEPOSIT_PAID");
        assertFinanceShape(deal, id);
    }

    @Test
    void payments_accountOnS15_writesTheReceipt() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("payments").request(id, accountUser)));

        assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(deal.get("money").get("payments")).hasSize(1);
        assertFinanceShape(deal, id);
    }

    @Test
    void finalPayment_accountOnS15_recordsTheBalanceAndReachesFullyPaid() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");
        insertPayment(id, "DEPOSIT", new BigDecimal("50000.00"));

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("final-payment").request(id, accountUser)));

        assertThat(paymentStatusOf(id)).isEqualTo("FULLY_PAID");
        assertThat(receiptCount(id)).isEqualTo(2);
        assertFinanceShape(deal, id);
    }

    @Test
    void billingRoute_isGoneFromTheFinanceController() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");

        int status = financeMvc.perform(json(post("/api/finance/deals/{id}/billing", id).session(session(accountUser)),
            "{\"dueDate\":\"2030-01-01\"}")).andReturn().getResponse().getStatus();

        assertThat(status).isGreaterThanOrEqualTo(400); // no handler: never a 2xx
        assertThat(jdbc.queryForObject("SELECT due_date IS NULL FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", id), Boolean.class)).isTrue();
    }

    @Test
    void closeConfirm_thenRevoke_accountOnAClosableDeal_movesCloseConfirmedAt() throws Exception {
        long id = createClosableDeal();

        JsonNode confirmed = okDeal(financeMvc.perform(routeNamed("close/confirm").request(id, accountUser)));
        assertThat(closeConfirmed(id)).isTrue();
        assertThat(confirmed.get("money").get("closeConfirmedAt").isNull()).isFalse();
        assertFinanceShape(confirmed, id);

        JsonNode revoked = okDeal(financeMvc.perform(routeNamed("close/revoke").request(id, accountUser)));
        assertThat(closeConfirmed(id)).isFalse();
        assertFinanceShape(revoked, id);
    }

    @Test
    void closeRevoke_ceoMay_asToday() throws Exception {
        long id = createClosableDeal();
        okDeal(financeMvc.perform(routeNamed("close/confirm").request(id, accountUser)));

        okDeal(financeMvc.perform(routeNamed("close/revoke").request(id, ceoUser)));

        assertThat(closeConfirmed(id)).isFalse();
    }

    @Test
    void stage_accountMovesAMoneyStage_andCannotMoveANonMoneyStage() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', next_follow_up_at = CURRENT_DATE + 3 WHERE ticket_id = :id",
            new MapSqlParameterSource("id", id));
        ticketService.addActivity(id,
            new th.co.glr.hr.ticket.DealActivityRequest(LocalDate.now(), th.co.glr.hr.ticket.DealActivityKind.CALL, null), salesRep);

        // a non-money stage is refused before anything moves
        expectStatus(financeMvc.perform(json(post("/api/finance/deals/{id}/stage", id).session(session(accountUser)),
            "{\"stage\":\"NEGOTIATION\",\"note\":\"x\"}")), 403);
        assertThat(salesStageOf(id)).isEqualTo(DealStage.ORDER_RECEIVED);

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("stage").request(id, accountUser)));

        assertThat(salesStageOf(id)).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(deal.get("salesStage").asText()).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertFinanceShape(deal, id);
    }

    @Test
    void comments_accountAndCeoCanPost_andItAppearsInTheFinanceDeal() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");

        okDeal(financeMvc.perform(routeNamed("comments").request(id, accountUser)));
        JsonNode deal = okDeal(financeMvc.perform(routeNamed("comments").request(id, ceoUser)));

        assertThat(commentCount(id)).isEqualTo(2);
        assertThat(deal.get("comments")).hasSize(2);
        assertThat(deal.get("comments").get(0).get("message").asText()).isEqualTo("ความเห็นทดสอบ");
        assertFinanceShape(deal, id);
    }

    // ═══ B3 design: the shared TicketService money methods enforce scope THEMSELVES ═════════════

    /**
     * A deal whose ONLY reason to refuse account is its scope: an otherwise valid DEPOSIT_PAID deal (live, an
     * accepted quotation, the payment status the balance actions need) moved below S10, so neither the stage
     * floor nor the pending-payment rule admits it. Without requireAccountScope these calls would SUCCEED and
     * write a receipt, so a 403 here can only come from the scope check (a 409 from an unrelated
     * precondition would fail the status assertion).
     *
     * <p>Merge note (develop's deal-route staging): the quotation_only container this test used to rely on is
     * no longer excluded from the list scope. confirmDepositPaid needs DEPOSIT_NOTICE_ISSUED, which the
     * pending-payment rule always admits, so its scope check has no reachable out-of-scope real deal; it
     * stays pinned by TicketServiceTest.
     */
    @Test
    void theTicketServiceMoneyMethods_refuseAccountOutsideItsScope_with403_andWriteNothing() {
        long balance = createS15Deal("DEPOSIT_PAID");
        sql("UPDATE sales.ticket SET sales_stage = 'NEGOTIATION' WHERE ticket_id = :id", balance);
        assertThat(tickets.isInAccountScope(balance)).isFalse();

        assertThatThrownBy(() -> ticketService.confirmFinalPayment(balance, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> ticketService.recordPayment(balance,
            new th.co.glr.hr.ticket.RecordPaymentRequest("DEPOSIT", new BigDecimal("10.00"), null, null, null, null, false),
            accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        assertThat(receiptCount(balance)).isZero();
        assertThat(paymentStatusOf(balance)).isEqualTo("DEPOSIT_PAID");
    }

    /**
     * Write-commits-but-response-403 fix, end to end: a deal that is in scope only via the pending-payment rule,
     * acted on by account, returns 200 with the updated view and the write persists.
     */
    @Test
    void anAllowedAccountAction_thatMovesTheDealOutOfScope_stillReturnsTheUpdatedView() throws Exception {
        long id = createTicket(DealStage.NEGOTIATION);
        insertAcceptedQuotation(id, "QT-OUT-" + id, new BigDecimal("100000.00"));
        tickets.updatePaymentStatusUnchecked(id, "DEPOSIT_NOTICE_ISSUED"); // in scope ONLY via the pending rule
        assertThat(tickets.isInAccountScope(id)).isTrue();

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("deposit-paid").request(id, accountUser)));

        assertThat(paymentStatusOf(id)).isEqualTo("DEPOSIT_PAID");          // the write persisted
        assertThat(receiptCount(id)).isEqualTo(1);
        // (deposit-paid also auto-advances the stage to S11, so this real deal stays in scope; the scope-EXIT
        // case itself is pinned deterministically by FinanceDealServiceTest.)
        assertThat(deal.get("money").get("paymentStatus").asText()).isEqualTo("DEPOSIT_PAID");
        assertThat(deal.has("availableActions")).isTrue();
    }

    // ═══ B4: the legacy ticket quotation file gets a SCOPED path for account ═══════════════════

    @Test
    void legacyQuotationFile_account_scopedPath_inScopeOk_belowS10Refused() {
        long inScope = createS15Deal("DEPOSIT_PAID");
        long qIn = quotationIdOf(inScope);
        assertThat(ticketService.getQuotationXlsx(inScope, qIn, accountUser)).isNotEmpty();

        long below = createTicket(DealStage.NEGOTIATION);
        long qBelow = insertAcceptedQuotation(below, "QT-LEG-" + below, new BigDecimal("10.00"));
        assertThatThrownBy(() -> ticketService.getQuotationXlsx(below, qBelow, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        // ceo unchanged
        assertThat(ticketService.getQuotationXlsx(below, qBelow, ceoUser)).isNotEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private record Route(String name, java.util.function.BiFunction<Long, UserPrincipal, MockHttpServletRequestBuilder> build) {
        MockHttpServletRequestBuilder request(long id, UserPrincipal actor) {
            return build.apply(id, actor);
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<Route> routes() {
        return List.of(
            new Route("comments", (id, a) -> json(post("/api/finance/deals/{id}/comments", id).session(session(a)),
                "{\"message\":\"ความเห็นทดสอบ\"}")),
            new Route("deposit-paid", (id, a) -> post("/api/finance/deals/{id}/deposit-paid", id).session(session(a))),
            new Route("final-payment", (id, a) -> post("/api/finance/deals/{id}/final-payment", id).session(session(a))),
            new Route("payments", (id, a) -> json(post("/api/finance/deals/{id}/payments", id).session(session(a)),
                "{\"kind\":\"DEPOSIT\",\"amount\":1000.00,\"note\":\"ทดสอบ\",\"receiptRef\":\"RC-LK\"}")),
            new Route("close/confirm", (id, a) -> post("/api/finance/deals/{id}/close/confirm", id).session(session(a))),
            new Route("close/revoke", (id, a) -> json(post("/api/finance/deals/{id}/close/revoke", id).session(session(a)),
                "{\"note\":\"ทดสอบ\"}")),
            new Route("stage", (id, a) -> json(post("/api/finance/deals/{id}/stage", id).session(session(a)),
                "{\"stage\":\"DEPOSIT_RECEIVED\",\"note\":\"ทดสอบ\"}")));
    }

    private Route routeNamed(String name) {
        return routes().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private void expectStatus(org.springframework.test.web.servlet.ResultActions actions, int expected) throws Exception {
        expectStatus(actions, expected, "");
    }

    private void expectStatus(org.springframework.test.web.servlet.ResultActions actions, int expected, String why)
            throws Exception {
        assertThat(actions.andReturn().getResponse().getStatus()).as(why).isEqualTo(expected);
    }

    private JsonNode okDeal(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("deal");
    }

    private void assertFinanceShape(JsonNode deal, long id) {
        assertThat(deal.get("id").asLong()).isEqualTo(id);
        assertThat(deal.has("money")).isTrue();
        assertThat(deal.has("documents")).isTrue();
        assertThat(deal.has("availableActions")).isTrue();
        assertThat(deal.has("comments")).isTrue();
        assertThat(forbiddenKeysIn(deal)).as("forbidden keys in a finance route response").isEmpty();
    }

    private int commentCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'COMMENTED'",
            new MapSqlParameterSource("id", ticketId), Integer.class);
    }

    private long quotationIdOf(long ticketId) {
        return jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id ORDER BY quotation_id LIMIT 1",
            new MapSqlParameterSource("id", ticketId), Long.class);
    }

    /** A deal the close gate will accept: fully paid, fully delivered, invoice on file, status quotation_issued. */
    private long createClosableDeal() {
        long id = createTicket(DealStage.DELIVERED);
        insertAcceptedQuotation(id, "QT-CL-" + id, new BigDecimal("1000.00"));
        insertPayment(id, "DEPOSIT", new BigDecimal("500.00"));
        insertPayment(id, "BALANCE", new BigDecimal("570.00")); // 1,000 + 7% VAT = 1,070 payable
        jdbc.update("""
            UPDATE sales.ticket SET status = 'quotation_issued', payment_status = 'FULLY_PAID',
                   fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id
            """, new MapSqlParameterSource("id", id));
        attach(id, "tax-invoice.pdf", AttachType.INVOICE);
        return id;
    }
}
