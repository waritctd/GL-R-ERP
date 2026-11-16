package th.co.glr.hr.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;

/**
 * Pure decisions for the "มีอะไรใหม่" release notice: who may publish or email it, which roles it may target,
 * what a valid request is, and the Thai copy of the bell row and the email. No I/O, so every branch is unit-tested
 * (ReleaseNoticePolicyTest); ReleaseNoticeIntegrationTest proves the decision survives into what is written.
 */
public final class ReleaseNoticePolicy {
    /** hr.notification.type of the bell row. The frontend keys the header bubble's red dot on it. */
    public static final String NOTIFICATION_TYPE = "RELEASE_NOTE";

    /** Every role DivisionAccessPolicy#roleFor can return. A change may target any subset of these. */
    public static final Set<String> ROLES = Set.of(
        "ceo", "hr", "import", "account", "warehouse", "qc", "sales_manager", "sales", "employee");

    /** Publishing a notice and emailing every targeted employee are company-wide broadcasts: CEO and HR only. */
    public static final Set<String> MANAGE_ROLES = Set.of("ceo", "hr");

    /** The id becomes a URL path segment (/whats-new/{id}) and the idempotency key, so it is a plain slug. */
    private static final Pattern RELEASE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    /** hr.notification.title is VARCHAR(200); "มีอะไรใหม่: " + title must fit. */
    private static final int MAX_RELEASE_TITLE = 150;
    private static final int MAX_CHANGE_TITLE = 200;
    private static final int MAX_CHANGES = 50;

    private ReleaseNoticePolicy() {
    }

    public static boolean canManage(String role) {
        return role != null && MANAGE_ROLES.contains(role);
    }

    public static String link(String releaseId) {
        return "/whats-new/" + releaseId;
    }

    /** Trims, lower-cases the roles, and refuses anything that would address the wrong people: an unknown role
     * is a 400, never "nobody" and never "everybody". */
    public static Request normalize(Request request) {
        if (request == null) {
            throw badRequest("ต้องระบุประกาศ");
        }
        String releaseId = trim(request.releaseId());
        if (releaseId == null || !RELEASE_ID.matcher(releaseId).matches()) {
            throw badRequest("รหัสประกาศไม่ถูกต้อง");
        }
        String title = trim(request.title());
        if (title == null || title.isEmpty() || title.length() > MAX_RELEASE_TITLE) {
            throw badRequest("ต้องระบุหัวข้อประกาศ (ไม่เกิน " + MAX_RELEASE_TITLE + " ตัวอักษร)");
        }
        if (request.changes() == null || request.changes().isEmpty() || request.changes().size() > MAX_CHANGES) {
            throw badRequest("ต้องมีรายการเปลี่ยนแปลงอย่างน้อย 1 รายการ");
        }
        List<Change> changes = new ArrayList<>();
        for (Change change : request.changes()) {
            changes.add(normalize(change));
        }
        return new Request(releaseId, title, List.copyOf(changes));
    }

    private static Change normalize(Change change) {
        String title = change == null ? null : trim(change.title());
        if (title == null || title.isEmpty() || title.length() > MAX_CHANGE_TITLE) {
            throw badRequest("ต้องระบุหัวข้อของแต่ละรายการ");
        }
        if (change.roles() == null || change.roles().isEmpty()) {
            throw badRequest("ต้องระบุแผนกที่เกี่ยวข้องของแต่ละรายการ");
        }
        List<String> roles = new ArrayList<>();
        for (String raw : change.roles()) {
            String role = raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
            if (role == null || !ROLES.contains(role)) {
                throw badRequest("ไม่รู้จักแผนก: " + raw);
            }
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        return new Change(title, List.copyOf(roles));
    }

    /** The titles of the changes whose audience includes {@code role}, in release order. */
    public static List<String> titlesFor(String role, List<Change> changes) {
        if (role == null || changes == null) {
            return List.of();
        }
        return changes.stream()
            .filter(change -> change.roles().contains(role))
            .map(Change::title)
            .toList();
    }

    public static String bellTitle(String releaseTitle) {
        return "มีอะไรใหม่: " + releaseTitle;
    }

    /** NotificationBell renders `message` (not `title`), so the row is self-contained. */
    public static String bellMessage(String releaseTitle, List<String> titles) {
        return "มีอะไรใหม่: " + releaseTitle + "\n"
            + titles.size() + " เรื่องที่เกี่ยวกับงานของคุณ กดเพื่อดูวิธีใช้";
    }

    public static String emailSubject(String releaseTitle) {
        return "มีอะไรใหม่: " + releaseTitle;
    }

    /** The email body below NotificationEmailService's own greeting and above its single portal button. */
    public static String emailBody(List<String> titles) {
        StringBuilder body = new StringBuilder("ระบบ GL&R Portal มีการอัปเดต ")
            .append(titles.size())
            .append(" เรื่องที่เกี่ยวกับงานของคุณ\n\n");
        for (String title : titles) {
            body.append("• ").append(title).append('\n');
        }
        body.append("\nวิธีใช้ทีละขั้นตอนพร้อมภาพหน้าจออยู่ในพอร์ทัล กดปุ่มด้านล่างเพื่อเปิดดู")
            .append(" (ต้องเข้าสู่ระบบก่อน) มีคำถาม ติดต่อฝ่าย HR");
        return body.toString();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
