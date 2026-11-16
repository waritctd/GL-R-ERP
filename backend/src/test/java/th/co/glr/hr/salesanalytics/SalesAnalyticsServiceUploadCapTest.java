package th.co.glr.hr.salesanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/** The upload cap is on BYTES and is enforced while reading: the server never buffers more than the limit. */
class SalesAnalyticsServiceUploadCapTest {
    private final SalesAnalyticsRepository repository = mock(SalesAnalyticsRepository.class);
    private final SalesAnalyticsService service = new SalesAnalyticsService(repository, new ObjectMapper());
    private final UserPrincipal ceo = user("ceo");

    private static UserPrincipal user(String role) {
        return new UserPrincipal(1L, role + "@glr.co.th", "T", role, 1L, true, LocalDate.of(2020, 1, 1), false, 1L, false);
    }

    /** An endless stream of 'x' that counts what was pulled from it. */
    private static InputStream endless(AtomicLong read) {
        return new InputStream() {
            @Override public int read() { read.incrementAndGet(); return 'x'; }
            @Override public int read(byte[] b, int off, int len) { read.addAndGet(len); java.util.Arrays.fill(b, off, off + len, (byte) 'x'); return len; }
        };
    }

    @Test
    void readingStopsAtTheCapInsteadOfConsumingTheWholeStream() {
        AtomicLong read = new AtomicLong();
        assertThatThrownBy(() -> service.upload(ceo, endless(read), -1))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(read.get()).isEqualTo(SalesAnalyticsService.MAX_BYTES + 1L);
        verifyNoInteractions(repository);
    }

    @Test
    void aDeclaredContentLengthOverTheLimitIsRefusedWithoutReadingAnything() {
        AtomicLong read = new AtomicLong();
        assertThatThrownBy(() -> service.upload(ceo, endless(read), SalesAnalyticsService.MAX_BYTES + 1L))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(read.get()).isZero();
    }

    @Test
    void theControllerChecksTheRoleBeforeItTouchesTheBodyStream() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user("sales_manager"));
        SalesAnalyticsController controller = new SalesAnalyticsController(service, new SessionContext());

        assertThatThrownBy(() -> controller.upload(request, session))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(request, never()).getInputStream();
        verifyNoInteractions(repository);
    }

    @Test
    void aBodyThatIsTooLargeOnlyInBytesNotCharactersIsStillRefused() {
        // 700,000 Thai characters = 2.1 MB of UTF-8 but under 2 MB of "characters".
        byte[] body = ("{\"months\":\"" + "ก".repeat(700_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.upload(ceo, new ByteArrayInputStream(body), body.length))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
