package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Slice 6b fix round 2: a deadlock or lock-wait victim on the visit writes (POST /api/sales/visits and /{id}/done) is a
 * clean Thai 409 telling the rep to try again — never a 500 carrying the raw driver message. Caught in the controller,
 * outside the service's @Transactional proxy, as ImportRequestController#asConflict does (a serialization failure can
 * surface at commit).
 */
class VisitControllerConcurrencyTest {

    private final VisitService service = mock(VisitService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new VisitController(service, new SessionContext()))
        .setMessageConverters(new JacksonJsonHttpMessageConverter())
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    private final UserPrincipal rep = new UserPrincipal(6L, "rep@glr.co.th", "พนักงานขาย", "sales", 6L, true,
        LocalDate.now(), false, null, false);

    @Test
    void aDeadlockVictimOnALogIsAThai409_notA500WithTheDriverMessage() throws Exception {
        when(service.create(any(), any())).thenThrow(
            new PessimisticLockingFailureException("ERROR: deadlock detected Detail: Process 4242 waits for ShareLock"));

        MvcResult result = perform(post("/api/sales/visits").contentType(MediaType.APPLICATION_JSON)
            .content("{\"ticketId\":7,\"contactName\":\"คุณอรุณ\",\"plannedDate\":\"2026-11-17\",\"done\":true}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("มีการบันทึกพร้อมกัน").doesNotContain("deadlock").doesNotContain("Process");
    }

    @Test
    void aLockTimeoutOnATickIsAThai409() throws Exception {
        when(service.markDone(anyLong(), any(), any(), any())).thenThrow(
            new CannotAcquireLockException("ERROR: canceling statement due to lock timeout"));

        MvcResult result = perform(post("/api/sales/visits/9/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ไปแล้ว\",\"nextFollowUpAt\":\"2026-11-24\"}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .contains("มีการบันทึกพร้อมกัน").doesNotContain("lock timeout");
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, rep);
        return mvc.perform(request.session(session)).andReturn();
    }
}
