package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

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
    void wrongVerbReturns405WithAllowHeaderNot500() {
        // GET on a POST-only endpoint. Before this handler existed the exception fell through to
        // handleUnexpected and the caller was told the server had broken, which is why
        // frontend/e2e-real/helpers/surface.js has to read each endpoint's real verb out of
        // hrApi.js — GETting everything would have reported ~130 phantom server errors.
        HttpRequestMethodNotSupportedException exception =
            new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMethodNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(405);
        // RFC 9110 §15.5.6 requires Allow on a 405.
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
    }

    @Test
    void wrongVerbStillReturns405WhenNoSupportedMethodsAreKnown() {
        // getSupportedHttpMethods() is nullable — Spring leaves it null when no candidate handler
        // resolved. The status must not depend on the header being populatable.
        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("TRACE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isEmpty();
    }

    // ── Upload endpoints: a malformed request is the CLIENT's error, never a 500 ──────────
    //
    // All three of these reached handleUnexpected before these handlers existed, so every one of
    // the eleven multipart endpoints answered 500 to an ordinary client mistake. Content
    // negotiation runs before any role gate, so all nine roles could reach it.

    @Test
    void unsupportedContentTypeReturns415WithAcceptHeaderNot500() {
        // application/json posted to an endpoint declaring consumes = multipart/form-data.
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
            MediaType.APPLICATION_JSON, List.of(MediaType.MULTIPART_FORM_DATA));

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMediaTypeNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(415);
        // RFC 9110 §15.5.16 recommends advertising what the resource does accept.
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.MULTIPART_FORM_DATA);
    }

    @Test
    void unsupportedContentTypeStillReturns415WhenNoSupportedTypesAreKnown() {
        // Mirrors wrongVerbStillReturns405…: the status must not depend on the header being
        // populatable, which it is not when no candidate handler resolved.
        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("Content-Type is not supported"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getAccept()).isEmpty();
    }

    @Test
    void nonMultipartRequestToAnUploadEndpointReturns400Not500() {
        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleMultipart(new MultipartException("Current request is not a multipart request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
    }

    @Test
    void oversizedUploadReturns413NotTheGeneric400() {
        // MaxUploadSizeExceededException extends MultipartException, so without its own handler it
        // would collapse into the 400 above — telling the caller their request was malformed when
        // it was merely larger than APP_MAX_FILE_SIZE. Spring resolves the most specific handler,
        // so this stays 413 regardless of declaration order.
        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleUploadTooLarge(new MaxUploadSizeExceededException(10_485_760L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(413);
    }

    @Test
    void unexpectedExceptionStillReturns500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/whatever");

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response =
            handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("เกิดข้อผิดพลาดภายในระบบ");
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
