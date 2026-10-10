package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * {@code GET /api/meta/deal-stages}'s {@code routes} entry: the per-{@link EntryChannel} route,
 * served so a LIST row (which has {@code deal.entryChannel} but no per-deal payload) can print its
 * position on the deal's own route instead of the catalog-wide "of 15".
 *
 * <p>The values must be {@link DealRoute#path} verbatim — the point of serving them is that no
 * client re-declares the table — so what is pinned here is the SHAPE the client relies on (the four
 * keys, stage codes only, {@code DealStage.ORDER} order) plus the one structural invariant the
 * design rests on: a route may only ever vary the party stages, never the operational tail.
 */
class DealStageMetaControllerTest {

    private static final SessionContext SESSIONS = new SessionContext();
    private final DealStageMetaController controller = new DealStageMetaController(SESSIONS);

    private static MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, new UserPrincipal(
            1L, "sales@example.test", "Sales", "sales", 1L, true, null, false, null, false));
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> routes() {
        Object routes = controller.dealStages(signedIn()).get("routes");
        assertThat(routes).as("dealStages() must serve a `routes` entry").isNotNull();
        return (Map<String, List<String>>) routes;
    }

    @Test
    void routesIsKeyedByEveryEntryChannelIncludingUnspecified() {
        assertThat(routes().keySet()).containsExactlyInAnyOrder(
            EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT, EntryChannel.BUYER_DIRECT,
            EntryChannel.UNSPECIFIED);
    }

    @Test
    void unspecifiedAndDesignerLedServeAllFifteenStages() {
        // UNSPECIFIED gates nothing (design 13.1c): a deal with no stated channel must read as the
        // full spine, never as a shortened route it was never put on.
        assertThat(routes().get(EntryChannel.UNSPECIFIED)).containsExactlyElementsOf(DealStage.ORDER);
        assertThat(routes().get(EntryChannel.UNSPECIFIED)).hasSize(15);
        assertThat(routes().get(EntryChannel.DESIGNER_LED)).containsExactlyElementsOf(DealStage.ORDER);
    }

    @Test
    void ownerDirectServesFourteenWithoutQuoteDesignSide() {
        assertThat(routes().get(EntryChannel.OWNER_DIRECT))
            .hasSize(14)
            .doesNotContain(DealStage.QUOTE_DESIGN_SIDE)
            .contains(DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER);
    }

    @Test
    void buyerDirectServesEleven() {
        assertThat(routes().get(EntryChannel.BUYER_DIRECT))
            .hasSize(11)
            .doesNotContain(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)
            .contains(DealStage.SPEC_APPROVED, DealStage.QUOTE_BUYER);
    }

    @Test
    void everyRouteIsServedFromDealRoutePathInDealStageOrder() {
        routes().forEach((channel, stages) -> {
            assertThat(stages).as("route for %s", channel).isEqualTo(DealRoute.path(channel));
            // DealStage.ORDER order: the served list is a subsequence of ORDER, never re-sorted.
            int previous = -1;
            for (String stage : stages) {
                int at = DealStage.ORDER.indexOf(stage);
                assertThat(at).as("%s in DealStage.ORDER", stage).isGreaterThan(previous);
                previous = at;
            }
        });
    }

    /**
     * The operational tail is never route-variable. Mirrors {@code DealRoute.ROUTE_VARIABLE}: were
     * any stage from NEGOTIATION onward off a route, {@code autoAdvanceStage} would skip it silently
     * and the deal would be left behind with no error anywhere.
     */
    @Test
    void everyRouteContainsEveryStageFromNegotiationOnward() {
        List<String> tail = DealStage.ORDER.subList(
            DealStage.ORDER.indexOf(DealStage.NEGOTIATION), DealStage.ORDER.size());
        assertThat(tail).isNotEmpty().contains(DealStage.CLOSED_PAID);
        routes().forEach((channel, stages) ->
            assertThat(stages).as("route for %s", channel).containsAll(tail));
        // ...and no route ever drops a stage outside the five party stages.
        routes().forEach((channel, stages) -> {
            List<String> dropped = DealStage.ORDER.stream().filter(s -> !stages.contains(s)).toList();
            assertThat(DealRoute.ROUTE_VARIABLE).as("dropped from %s", channel).containsAll(dropped);
        });
    }

    @Test
    void routesAreStageCodesOnly_noThaiAndNoLabels() {
        // This endpoint is deliberately Thai-free (see the controller's class note): codes only.
        Pattern thai = Pattern.compile("[\\u0E00-\\u0E7F]");
        routes().forEach((channel, stages) -> {
            assertThat(thai.matcher(channel).find()).as("key %s", channel).isFalse();
            stages.forEach(stage -> {
                assertThat(DealStage.ORDER).as("served value is a stage code").contains(stage);
                assertThat(thai.matcher(stage).find()).isFalse();
            });
        });
    }

    @Test
    void existingEntriesAreUntouched() {
        Map<String, Object> body = controller.dealStages(signedIn());
        assertThat(body.keySet()).containsExactlyInAnyOrder(
            "stages", "phases", "lostReasons", "cancelReasons", "routes");
        assertThat((List<?>) body.get("stages")).hasSize(15);
    }

    @Test
    void stillRequiresASession() {
        assertThatThrownBy(() -> controller.dealStages(new MockHttpSession()))
            .isInstanceOf(ApiException.class);
    }
}
