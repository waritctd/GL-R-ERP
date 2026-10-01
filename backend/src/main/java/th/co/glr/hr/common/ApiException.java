package th.co.glr.hr.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    /** The two keys every error body already carries ({@link ApiExceptionHandler.ErrorResponse});
     * a detail may never shadow them. */
    private static final Set<String> RESERVED_DETAIL_KEYS = Set.of("message", "status");

    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    /**
     * Opt-in structured refusal (quotation ↔ deal linking slice 2, N6): {@code details} are rendered
     * by {@link ApiExceptionHandler} as extra TOP-LEVEL fields of the JSON error body, next to
     * {@code message} and {@code status} — e.g. {@code {"message":…,"status":409,"liveQuotationId":7,
     * "number":"QT-…","docStatus":"DRAFT"}} — so a client can act on the refusal (the frontend reads
     * them as {@code ApiError.details.<key>}, where {@code details} is the whole response JSON).
     *
     * <p>Every other exception uses the two-argument constructor, whose details are empty, and whose
     * body is therefore byte-identical to what it was before this constructor existed. Insertion
     * order is preserved. {@code message}/{@code status} may not be used as keys.
     */
    public ApiException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        if (details == null || details.isEmpty()) {
            this.details = Map.of();
        } else {
            for (String key : details.keySet()) {
                if (key == null || RESERVED_DETAIL_KEYS.contains(key)) {
                    throw new IllegalArgumentException("ApiException detail key may not be '" + key + "'");
                }
            }
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Never null; empty for every exception built with the two-argument constructor. */
    public Map<String, Object> getDetails() {
        return details;
    }
}
