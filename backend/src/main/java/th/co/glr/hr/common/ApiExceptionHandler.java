package th.co.glr.hr.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            .body(new ErrorResponse(message.isBlank() ? "Invalid request" : message, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("Invalid request", HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ResponseEntity<ErrorResponse> handleMissingAuthentication(AuthenticationCredentialsNotFoundException exception) {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse("Not authenticated", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("Forbidden", HttpStatus.FORBIDDEN.value()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(new ErrorResponse("Method not allowed", HttpStatus.METHOD_NOT_ALLOWED.value()));
    }

    // Thrown for any request path with no matching controller or static resource (e.g. GET / on
    // this API-only backend). Without this handler it falls into handleUnexpected below and gets
    // misreported as a 500.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("Not found", HttpStatus.NOT_FOUND.value()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API exception method={} path={} userId={}",
            request.getMethod(), request.getRequestURI(), currentUserId(request), exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR.value()));
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
        return error.getField() + " " + (error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    public record ErrorResponse(String message, int status) {
    }
}
