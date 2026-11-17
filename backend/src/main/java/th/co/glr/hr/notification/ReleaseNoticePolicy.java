package th.co.glr.hr.notification;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    /** At most this many entries are listed in one reader's email; the rest become "และอีก n เรื่อง". */
    public static final int MAX_EMAIL_ITEMS = 5;
    /** A change's one-sentence "why" (release files hold them to one sentence; this is the server's hard cap). */
    public static final int MAX_WHY = 300;

    /** The id becomes a URL path segment (/whats-new/{id}) and the idempotency key, so it is a plain slug. */
    private static final Pattern RELEASE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    /** hr.notification.title is VARCHAR(200); "มีอะไรใหม่: " + title must fit. */
    private static final int MAX_RELEASE_TITLE = 150;
    private static final int MAX_CHANGE_TITLE = 200;
    private static final int MAX_CHANGES = 50;
    /** Anything a mail client may render as a line break: ASCII whitespace (incl. VT and FF), NEL, and the Unicode
     * line and paragraph separators. Java's {@code \s} alone is ASCII-only. */
    private static final Pattern LINE_BREAKING = Pattern.compile("[\\s\\u0085\\p{Zl}\\p{Zp}]+");
    /** Dropped outright: every control character (C0 and C1) and the zero-width space / BOM. ZWNJ and ZWJ (U+200C,
     * U+200D) are kept: some scripts need them, and they cannot break a line. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\u200B\\uFEFF]");
    private static final Pattern SPACE_RUN = Pattern.compile(" {2,}");
    private static final String[] THAI_MONTHS = {
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};

    private ReleaseNoticePolicy() {
    }

    public static boolean canManage(String role) {
        return role != null && MANAGE_ROLES.contains(role);
    }

    public static String link(String releaseId) {
        return "/whats-new/" + releaseId;
    }

    /** Trims, lower-cases the roles, and refuses anything that would address the wrong people: an unknown role
     * is a 400, never "nobody" and never "everybody". The optional {@code why} is put on one line (whitespace
     * collapsed, control characters dropped) and capped; the optional {@code date} must be ISO yyyy-MM-dd. */
    public static Request normalize(Request request) {
        if (request == null) {
            throw badRequest("ต้องระบุประกาศ");
        }
        String releaseId = trim(request.releaseId());
        if (releaseId == null || !RELEASE_ID.matcher(releaseId).matches()) {
            throw badRequest("รหัสประกาศไม่ถูกต้อง");
        }
        // One line: a title reaches the subject, the bell row and the plain-text mail, where a newline would forge
        // a line of its own (review LOW-1). Same rule as the why.
        String title = oneLine(request.title());
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
        return new Request(releaseId, title, List.copyOf(changes), normalizeDate(request.date()),
            Boolean.TRUE.equals(request.resendUnconfirmed()));
    }

    private static Change normalize(Change change) {
        String title = change == null ? null : oneLine(change.title());
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
        return new Change(title, List.copyOf(roles), normalizeWhy(change.why()));
    }

    /** Anything line-breaking (CR, LF, tab, VT, FF, NEL, U+2028, U+2029) becomes one space first, so a line break
     * becomes a space and not nothing; then control characters, U+200B and U+FEFF are dropped and space runs
     * collapsed. Thai text (tone marks, sara am, single spaces) and ZWNJ/ZWJ pass through unchanged. Null stays
     * null. */
    private static String oneLine(String raw) {
        if (raw == null) {
            return null;
        }
        String spaced = LINE_BREAKING.matcher(raw).replaceAll(" ");
        return SPACE_RUN.matcher(INVISIBLE.matcher(spaced).replaceAll("")).replaceAll(" ").trim();
    }

    private static String normalizeWhy(String raw) {
        String oneLine = oneLine(raw);
        if (oneLine == null || oneLine.isEmpty()) {
            return null;
        }
        if (oneLine.length() > MAX_WHY) {
            throw badRequest("คำอธิบายของแต่ละรายการยาวเกิน " + MAX_WHY + " ตัวอักษร");
        }
        return oneLine;
    }

    private static String normalizeDate(String raw) {
        String date = trim(raw);
        if (date == null || date.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date).toString();
        } catch (DateTimeParseException exception) {
            throw badRequest("วันที่ของประกาศไม่ถูกต้อง");
        }
    }

    /** The titles of the changes whose audience includes {@code role}, in release order. */
    public static List<String> titlesFor(String role, List<Change> changes) {
        return changesFor(role, changes).stream().map(Change::title).toList();
    }

    /** The changes whose audience includes {@code role}, in release order: everything one reader is told about,
     * and nothing else. */
    public static List<Change> changesFor(String role, List<Change> changes) {
        if (role == null || changes == null) {
            return List.of();
        }
        return changes.stream()
            .filter(change -> change.roles().contains(role))
            .toList();
    }

    /** "2026-11-17" → "17 พ.ย. 2569" (Buddhist year). Null for null. Expects an already-validated ISO date. */
    public static String thaiDate(String isoDate) {
        if (isoDate == null) {
            return null;
        }
        LocalDate date = LocalDate.parse(isoDate);
        return date.getDayOfMonth() + " " + THAI_MONTHS[date.getMonthValue() - 1] + " " + (date.getYear() + 543);
    }

    public static String bellTitle(String releaseTitle) {
        return "มีอะไรใหม่: " + releaseTitle;
    }

    /** NotificationBell renders `message` (not `title`), so the row is self-contained. */
    public static String bellMessage(String releaseTitle, List<String> titles) {
        return "มีอะไรใหม่: " + releaseTitle + "\n"
            + titles.size() + " เรื่องที่เกี่ยวกับงานของคุณ กดเพื่อดูวิธีใช้";
    }

    /**
     * One reader's email: the release named with its date, the reader's own entries (title + why) capped at
     * {@link #MAX_EMAIL_ITEMS} with a "และอีก n เรื่อง" line, and the link to the release page. {@code roleChanges}
     * must already be {@link #changesFor} the reader's role: this method lists exactly what it is given.
     */
    public static ReleaseNoticeEmail email(Request release, List<Change> roleChanges) {
        String date = thaiDate(release.date());
        String subject = date == null
            ? "มีอะไรใหม่: " + release.title()
            : "มีอะไรใหม่ " + date + ": " + release.title();
        int total = roleChanges.size();
        String intro = "ระบบ GL&R Portal " + (date == null ? "มีการอัปเดต" : "อัปเดตเมื่อ " + date)
            + " มี " + total + " เรื่องที่เกี่ยวกับงานของคุณ";
        List<ReleaseNoticeEmail.Item> items = roleChanges.stream()
            .limit(MAX_EMAIL_ITEMS)
            .map(change -> new ReleaseNoticeEmail.Item(change.title(), change.why()))
            .toList();
        String moreLine = total > MAX_EMAIL_ITEMS ? "และอีก " + (total - MAX_EMAIL_ITEMS) + " เรื่อง" : null;
        String outro = "วิธีใช้ทีละขั้นตอนพร้อมภาพหน้าจออยู่ในหน้า มีอะไรใหม่ ของพอร์ทัล (ต้องเข้าสู่ระบบก่อน)"
            + " มีคำถาม ติดต่อฝ่าย HR";
        return new ReleaseNoticeEmail(subject, intro, items, moreLine, outro, "เปิดดูมีอะไรใหม่",
            link(release.releaseId()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
