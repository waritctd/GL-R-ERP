package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void maxUploadSizeExceededReturns413NotUnexpected500() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(10 * 1024 * 1024);

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(413);
        assertThat(response.getBody().message()).isEqualTo("Uploaded file is too large");
    }

    @Test
    void noResourceFoundReturns404NotUnexpected500() {
        NoResourceFoundException exception =
            new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/", "/");

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response = handler.handleNoResourceFound(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void unexpectedExceptionStillReturns500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whatever");

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
    }

    @Test
    void unexpectedExceptionLogsAnonymousWhenNoSession() {
        // No session on the request → currentUserId falls back to "anonymous"; response body
        // (asserted above) stays generic either way. This just exercises the no-session branch
        // without throwing, since the log line itself isn't asserted here.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/whatever");

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleUnexpected(new IllegalStateException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
