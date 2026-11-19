package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.ticket.DealStage;

/** H1 part 2 -- the account lockdown, against the real service, real controllers (MockMvc) and real Postgres. */
class FinanceDealLockdownIntegrationTest extends FinanceDealTestBase {

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
    void finalPayment_accountAtDeliveryScheduling_recordsTheBalanceAndReachesFullyPaid() throws Exception {
        long id = createS15Deal("DEPOSIT_PAID");
        insertPayment(id, "DEPOSIT", new BigDecimal("50000.00"));
        // 2026-11-07: final payment opens at DELIVERY_SCHEDULING (S15 PROCUREMENT is refused).
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERY_SCHEDULING' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", id));

        JsonNode deal = okDeal(financeMvc.perform(routeNamed("final-payment").request(id, accountUser)));

        assertThat(paymentStatusOf(id)).isEqualTo("FULLY_PAID");
        assertThat(receiptCount(id)).isEqualTo(2);
        assertFinanceShape(deal, id);
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
}
