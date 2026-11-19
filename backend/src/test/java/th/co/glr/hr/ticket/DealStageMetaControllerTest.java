package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/** GET /api/meta/deal-stages's routes entry: the per-EntryChannel route, served so a LIST row (which has
 * deal.entryChannel but no per-deal payload) can print its position on the deal's own route instead of the
 * catalog-wide "of 15". */
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
    void ownerDirectServesFourteenWithoutQuoteDesignSide() {
        assertThat(routes().get(EntryChannel.OWNER_DIRECT))
            .hasSize(14)
            .doesNotContain(DealStage.QUOTE_DESIGN_SIDE)
            .contains(DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER);
    }

    @Test
    void stillRequiresASession() {
        assertThatThrownBy(() -> controller.dealStages(new MockHttpSession()))
            .isInstanceOf(ApiException.class);
    }

}
