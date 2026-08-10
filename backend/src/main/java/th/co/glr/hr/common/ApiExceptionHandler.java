package th.co.glr.hr.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity
            .status(exception.getStatus())
            .body(new ErrorResponse(exception.getMessage(), exception.getStatus().value()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(ApiExceptionHandler::fieldMessage)
            .collect(Collectors.joining(", "));
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(message.isBlank() ? "คำขอไม่ถูกต้อง" : message, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("คำขอไม่ถูกต้อง", HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ResponseEntity<ErrorResponse> handleMissingAuthentication(AuthenticationCredentialsNotFoundException exception) {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse("กรุณาเข้าสู่ระบบก่อนใช้งาน", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("ไม่มีสิทธิ์เข้าถึงรายการนี้", HttpStatus.FORBIDDEN.value()));
    }

    // Thrown for any request path with no matching controller or static resource (e.g. GET / on
    // this API-only backend). Without this handler it falls into handleUnexpected below and gets
    // misreported as a 500.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("ไม่พบรายการนี้", HttpStatus.NOT_FOUND.value()));
    }

    // A known verb on a known path, but the wrong one — GET on a POST-only endpoint, say.
    //
    // Without this handler the exception falls through to handleUnexpected and the client is told
    // 500 "เกิดข้อผิดพลาดภายในระบบ", which says the server broke when in fact the request was
    // malformed. That mattered beyond politeness: the real-stack sweep in
    // frontend/e2e-real/api-surface.spec.js exists to find endpoints that answer a server error,
    // and this one defect made ~130 endpoints report one. Its `surface.js` had to read each
    // endpoint's real verb out of hrApi.js purely to avoid asking the wrong question and burying
    // the single genuine finding underneath the noise.
    //
    // RFC 9110 §15.5.6 requires an Allow header on a 405; Spring populates
    // getSupportedHttpMethods() from the handler mapping, but it is nullable when no candidate
    // handler was resolved, so it is only set when actually present.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new HttpMethod[0]));
        }
        return response.body(
            new ErrorResponse("เมธอดนี้ไม่รองรับสำหรับรายการนี้", HttpStatus.METHOD_NOT_ALLOWED.value()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException exception, HttpServletRequest request) {
        log.error("Database error method={} path={} userId={}",
            request.getMethod(), request.getRequestURI(), currentUserId(request), exception);
        String msg = exception.getMostSpecificCause().getMessage();
        // The prefix is translated; `msg` itself is the raw JDBC driver message (untranslatable,
        // and arguably shouldn't be echoed to the client at all — that's a pre-existing behavior
        // this i18n sweep does not change, see PR notes).
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("เกิดข้อผิดพลาดกับฐานข้อมูล: " + msg, HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API exception method={} path={} userId={}",
            request.getMethod(), request.getRequestURI(), currentUserId(request), exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("เกิดข้อผิดพลาดภายในระบบ", HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    // Best-effort acting-user lookup for the log line above. Defensive: the session may not exist,
    // may not carry the expected attribute, or (in unit tests calling the handler directly) the
    // request may not be a real servlet request at all — any of these fall back to "anonymous".
    // Never throws, and never logs anything beyond the numeric user id (no PII).
    private static String currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "anonymous";
        }
        Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (value instanceof UserPrincipal user) {
            return String.valueOf(user.id());
        }
        return "anonymous";
    }

    private static String fieldMessage(FieldError error) {
        // NOTE (#393 follow-up): error.getDefaultMessage() is Jakarta Bean Validation's own message
        // — for the ~230 @NotBlank/@NotNull/@Size/etc. fields in this codebase that carry no custom
        // `message=`, that is Jakarta's built-in English text ("must not be blank", "must not be
        // null", ...), not a literal in this file. Translating those needs a
        // ValidationMessages.properties bundle (or per-annotation message= overrides), a different
        // and much larger mechanism than the literal-string sweep done here — tracked as a known
        // gap, not fixed in this change.
        return error.getField() + " " + (error.getDefaultMessage() == null ? "ไม่ถูกต้อง" : error.getDefaultMessage());
    }

    public record ErrorResponse(String message, int status) {
    }
}
