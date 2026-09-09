package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * No test existed for this controller before Change 3 (owner ruling, 2026-09: remove กล่อง/เมตร
 * from new pickers). Needs no database — the whole controller is a static Thai-label lookup keyed
 * off {@link UnitBasis}'s four constants — so this is a plain {@code MockMvc} unit test, not a
 * {@code AbstractPostgresIntegrationTest} subclass.
 */
class UnitBasisMetaControllerTest {

    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new UnitBasisMetaController(new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, "import@glr.co.th", "Import Tester", "import", 1L,
                true, LocalDate.of(2026, 1, 1), false, null, false));
        return session;
    }

    @Test
    void anonymousCallerIsRejected() throws Exception {
        mvc.perform(get("/api/meta/unit-bases"))
            .andExpect(status().isUnauthorized());
    }

    /**
     * Deliberately NOT role-gated (see the controller's own class Javadoc): the payload is the
     * same four constants for every caller, revealing nothing about any deal/request/quote.
     */
    @Test
    void anyAuthenticatedRoleCanRead() throws Exception {
        mvc.perform(get("/api/meta/unit-bases").session(session()))
            .andExpect(status().isOk());
    }

    /**
     * Change 3's whole point: all FOUR codes are still served (never narrowed to just the
     * selectable two — label lookups for an existing PER_BOX/PER_LINEAR_M row still need this),
     * each carrying a `selectable` flag that is true for exactly PER_PIECE/PER_SQM.
     */
    @Test
    void servesAllFourCodesWithSelectableTrueOnlyForPieceAndSqm() throws Exception {
        String json = mvc.perform(get("/api/meta/unit-bases").session(session()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(json)
            .contains("\"code\":\"PER_PIECE\"", "\"code\":\"PER_SQM\"",
                "\"code\":\"PER_BOX\"", "\"code\":\"PER_LINEAR_M\"")
            // Thai labels stay served for all four, unaffected by selectability.
            .contains("\"label\":\"แผ่น\"", "\"label\":\"ตร.ม.\"", "\"label\":\"กล่อง\"", "\"label\":\"เมตร\"");

        long selectableTrueCount = json.split("\"selectable\":true", -1).length - 1;
        long selectableFalseCount = json.split("\"selectable\":false", -1).length - 1;
        assertThat(selectableTrueCount).as("PER_PIECE and PER_SQM only").isEqualTo(2);
        assertThat(selectableFalseCount).as("PER_BOX and PER_LINEAR_M only").isEqualTo(2);
    }
}
