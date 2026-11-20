package th.co.glr.hr.feedback;

import java.time.Instant;
import java.time.LocalDate;

/** Wire shapes for "แจ้งปัญหา / ขอฟีเจอร์" (FeedbackController). */
public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    /** kind BUG|FEATURE; area one of the fixed area codes; impact optional, one of three per kind. The reporter's
     * identity (name, department, role) is deliberately NOT a field here: the server snapshots it. */
    public record SubmitRequest(String kind, String area, String details, String extra, String impact,
                                String pagePath, String userAgent, String viewport) {
    }

    /** decision ACCEPT (needs eta) | DENY (needs reason). silent=true: no bell/email to the reporter. */
    public record DecisionRequest(String decision, LocalDate eta, String reason, Boolean silent) {
    }

    /** Admin edit of an accepted request. status ACCEPTED|IN_PROGRESS|TESTING|RELEASED; null field = unchanged. */
    public record UpdateRequest(String status, LocalDate eta, Boolean silent) {
    }

    public record FeedbackDto(long id, String kind, String area, String details, String extra, String impact,
                              String pagePath, String userAgent, String viewport,
                              long reporterEmployeeId, String reporterName, String reporterDepartment,
                              String reporterRole, String status, LocalDate eta, String denyReason,
                              Instant createdAt, Instant updatedAt) {
    }
}
