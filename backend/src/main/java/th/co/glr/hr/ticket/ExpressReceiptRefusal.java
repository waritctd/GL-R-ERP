package th.co.glr.hr.ticket;

import java.util.Map;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Why {@link TicketService#recordExpressReceipt} would not place an Express row (review M2). A typed 409 with a
 * machine-readable {@code code} detail, so callers branch on {@link #reason()} — never on the Thai message text.
 */
public class ExpressReceiptRefusal extends ApiException {
    public enum Reason { OVERPAYMENT, DEAL_NOT_ACTIVE, DUPLICATE }

    private final Reason reason;

    public ExpressReceiptRefusal(Reason reason, String message) {
        super(HttpStatus.CONFLICT, message, Map.of("code", reason.name()));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
