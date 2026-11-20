package th.co.glr.hr.feedback;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.feedback.FeedbackDtos.DecisionRequest;
import th.co.glr.hr.feedback.FeedbackDtos.FeedbackDto;
import th.co.glr.hr.feedback.FeedbackDtos.Person;
import th.co.glr.hr.feedback.FeedbackDtos.Requester;
import th.co.glr.hr.feedback.FeedbackDtos.RequestersRequest;
import th.co.glr.hr.feedback.FeedbackDtos.SubmitRequest;
import th.co.glr.hr.feedback.FeedbackDtos.UpdateRequest;
import th.co.glr.hr.notification.NotificationService;

/** Staff submit and read their own (as any requester); the admin (live hr.employee.is_admin, as the activity log) decides and tracks. */
@Service
public class FeedbackService {
    static final String LINK = "/whats-new?tab=feedback";
    static final Set<String> AREAS = Set.of("DEALS", "QUOTATIONS", "VISITS", "CATALOG", "COMMISSION", "ATTENDANCE",
        "SPECIAL_MONEY", "LEAVE", "TAX_ALLOWANCE", "PAYROLL", "MENU", "LOGIN", "OTHER");
    private static final Set<String> BUG_IMPACTS = Set.of("BLOCKED", "WORKAROUND", "LOW");
    private static final Set<String> FEATURE_IMPACTS = Set.of("DAILY", "SOMETIMES", "NICE");
    private static final int MAX_TEXT = 4000;
    private static final int MAX_PER_HOUR = 10;
    private static final int MAX_REQUESTERS = 200;
    private static final Map<String, String> STEP_LABEL = Map.of("ACCEPTED", "รับเรื่อง", "IN_PROGRESS", "กำลังทำ",
        "TESTING", "ทดสอบ", "RELEASED", "ใช้งานได้แล้ว");

    /** Mirrors frontend feedbackMeta.js FEEDBACK_AREAS. */
    private static final Map<String, String> AREA_LABEL = Map.ofEntries(
        Map.entry("DEALS", "งานทั้งหมด (ฝ่ายขาย)"), Map.entry("QUOTATIONS", "ใบเสนอราคา"),
        Map.entry("VISITS", "นัดเข้าพบ"), Map.entry("CATALOG", "แคตตาล็อกสินค้า"),
        Map.entry("COMMISSION", "ค่าคอมมิชชัน"), Map.entry("ATTENDANCE", "เวลาทำงาน"),
        Map.entry("SPECIAL_MONEY", "คำขอเงินพิเศษ"), Map.entry("LEAVE", "วันลา"),
        Map.entry("TAX_ALLOWANCE", "ค่าลดหย่อนภาษี"), Map.entry("PAYROLL", "เงินเดือน"),
        Map.entry("MENU", "เมนู / หน้าหลัก"), Map.entry("LOGIN", "เข้าสู่ระบบ"), Map.entry("OTHER", "อื่น ๆ / ไม่แน่ใจ"));

    private final FeedbackRepository repository;
    private final EmployeeAuthRepository employees;
    private final NotificationService notifications;

    public FeedbackService(FeedbackRepository repository, EmployeeAuthRepository employees,
                           NotificationService notifications) {
        this.repository = repository;
        this.employees = employees;
        this.notifications = notifications;
    }

    @Transactional
    public FeedbackDto submit(UserPrincipal actor, SubmitRequest request) {
        if (actor == null || request == null) {
            throw bad("คำขอไม่ถูกต้อง");
        }
        String details = request.details() == null ? "" : request.details().trim();
        if (!"BUG".equals(request.kind()) && !"FEATURE".equals(request.kind())) {
            throw bad("ต้องเลือกประเภท");
        }
        if (request.area() == null || !AREAS.contains(request.area())) {
            throw bad("ต้องเลือกหน้าที่เกี่ยวข้อง");
        }
        if (chars(details) < 10 || chars(details) > MAX_TEXT) {
            throw bad("รายละเอียดต้องยาว 10–4000 ตัวอักษร");
        }
        String extra = blankToNull(request.extra());
        if (extra != null && chars(extra) > MAX_TEXT) {
            throw bad("ข้อความเพิ่มเติมยาวเกินไป");
        }
        String impact = blankToNull(request.impact());
        if (impact != null && !("BUG".equals(request.kind()) ? BUG_IMPACTS : FEATURE_IMPACTS).contains(impact)) {
            throw bad("ระดับผลกระทบไม่ถูกต้อง");
        }
        if (repository.countLastHour(actor.id()) >= MAX_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ส่งได้ไม่เกิน 10 เรื่องต่อชั่วโมง ลองใหม่ภายหลัง");
        }
        SubmitRequest clean = new SubmitRequest(request.kind(), request.area(), details, extra, impact,
            cut(request.pagePath(), 300), cut(request.userAgent(), 300), cut(request.viewport(), 30));
        long id = repository.insert(actor.id(), actor.name(), repository.departmentOf(actor.id()), actor.role(), clean);
        repository.addRequester(id, actor.id());
        FeedbackDto saved = repository.findById(id).orElseThrow();
        String subject = ("BUG".equals(clean.kind()) ? "แจ้งปัญหา" : "ขอฟีเจอร์") + " #" + id + " จาก " + actor.name();
        String body = clean.details();
        for (long adminId : repository.activeAdminIds()) {
            notifications.notify(adminId, "FEEDBACK_SUBMITTED", subject, body, LINK, true);
        }
        return saved;
    }

    public List<FeedbackDto> listMine(UserPrincipal actor) {
        return repository.findByRequester(actor.id());
    }

    public List<FeedbackDto> listAll(UserPrincipal actor) {
        requireAdmin(actor);
        return repository.findAll();
    }

    @Transactional
    public FeedbackDto decide(UserPrincipal actor, long id, DecisionRequest request) {
        requireAdmin(actor);
        boolean accept = request != null && "ACCEPT".equals(request.decision());
        boolean deny = request != null && "DENY".equals(request.decision());
        String reason = request == null || request.reason() == null ? null : request.reason().trim();
        if (!accept && !deny) {
            throw bad("ต้องระบุการตัดสินใจ");
        }
        if (accept && request.eta() == null) {
            throw bad("ต้องระบุวันกำหนดเสร็จ");
        }
        if (deny && (reason == null || chars(reason) < 5 || chars(reason) > 2000)) {
            throw bad("เหตุผลต้องยาว 5–2000 ตัวอักษร");
        }
        FeedbackDto before = existing(id);
        if (!repository.decide(id, accept ? "ACCEPTED" : "DENIED", accept ? request.eta() : null,
            accept ? null : reason, actor.id())) {
            throw conflict();
        }
        if (Boolean.TRUE.equals(request.silent())) {
            return existing(id);
        }
        String ref = "#" + id;
        // รับเรื่อง and ไม่รับเรื่อง: a one-line bell, and a detailed email.
        for (Requester requester : before.requesters()) {
            notifications.notifyWithEmailBody(requester.employeeId(), "FEEDBACK_DECIDED",
                accept ? "รับเรื่อง " + ref + " แล้ว" : "ไม่รับเรื่อง " + ref,
                accept ? "กำหนดเสร็จ " + ThaiText.date(request.eta()) : "เหตุผล: " + reason, LINK,
                accept ? detailedBody(before, "รับเรื่องแล้ว", request.eta(), null)
                    : detailedBody(before, "ไม่รับเรื่อง", null, reason));
        }
        return existing(id);
    }

    @Transactional
    public FeedbackDto update(UserPrincipal actor, long id, UpdateRequest request) {
        requireAdmin(actor);
        String status = request == null ? null : request.status();
        if (status != null && !FeedbackRepository.ACCEPTED_FAMILY.contains(status)) {
            throw bad("สถานะไม่ถูกต้อง");
        }
        FeedbackDto before = existing(id);
        LocalDate eta = request == null ? null : request.eta();
        if (!FeedbackRepository.ACCEPTED_FAMILY.contains(before.status())) {
            throw conflict();
        }
        boolean statusChanged = status != null && !status.equals(before.status());
        boolean etaChanged = eta != null && !eta.equals(before.eta());
        if (!statusChanged && !etaChanged) {
            return before;
        }
        if (!repository.update(id, status, eta)) {
            throw conflict();
        }
        FeedbackDto after = existing(id);
        // Only the first release is emailed: going back a step and releasing again is bell only.
        boolean firstRelease = statusChanged && "RELEASED".equals(after.status())
            && repository.markFirstRelease(id);
        if (request != null && Boolean.TRUE.equals(request.silent())) {
            return after;
        }
        for (Requester requester : after.requesters()) {
            notifications.notifyWithEmailBody(requester.employeeId(), "FEEDBACK_UPDATED",
                firstRelease ? "เรื่อง #" + id + " ใช้งานได้แล้ว" : "อัปเดตเรื่อง #" + id,
                "ความคืบหน้า: " + STEP_LABEL.get(after.status()) + " · กำหนดเสร็จ " + ThaiText.date(after.eta()), LINK,
                firstRelease ? detailedBody(after, "ใช้งานได้แล้ว", after.eta(), null) : null);
        }
        return after;
    }

    /** Admin replaces who asked for this request. The reporter always stays; adding people notifies nobody. */
    @Transactional
    public FeedbackDto setRequesters(UserPrincipal actor, long id, RequestersRequest request) {
        requireAdmin(actor);
        FeedbackDto item = existing(id);
        if (request == null || request.employeeIds() == null) {
            throw bad("คำขอไม่ถูกต้อง");
        }
        Set<Long> wanted = new LinkedHashSet<>(request.employeeIds());
        wanted.add(item.reporterEmployeeId());
        if (wanted.contains(null)) {
            throw bad("คำขอไม่ถูกต้อง");
        }
        if (wanted.size() > MAX_REQUESTERS) {
            throw bad("เพิ่มผู้ขอได้ไม่เกิน 200 คน");
        }
        List<Long> ids = List.copyOf(wanted);
        // Only NEWLY added people must be active: a reporter / requester who has since left stays on the list.
        Set<Long> current = item.requesters().stream().map(Requester::employeeId).collect(Collectors.toSet());
        List<Long> added = ids.stream().filter(e -> !current.contains(e) && e != item.reporterEmployeeId()).toList();
        if (!added.isEmpty() && repository.countActive(added) != added.size()) {
            throw bad("มีพนักงานที่ไม่พบหรือพ้นสภาพ");
        }
        repository.replaceRequesters(id, ids);
        return existing(id);
    }

    /** Active employees the admin can pick as requesters. */
    public List<Person> people(UserPrincipal actor) {
        requireAdmin(actor);
        return employees.findAllActive().stream()
            .map(e -> new Person(e.employeeId(), e.name(), e.divisionName(), DivisionAccessPolicy.roleFor(e))).toList();
    }

    /** The reporter-facing email/bell text: which request, its status and date, then their own words back. */
    private static String detailedBody(FeedbackDto item, String statusLabel, LocalDate eta, String reason) {
        String kind = "BUG".equals(item.kind()) ? "แจ้งปัญหา" : "ขอฟีเจอร์";
        StringBuilder body = new StringBuilder()
            .append("เรื่อง #").append(item.id()).append(" · ").append(kind).append(" · ")
            .append(AREA_LABEL.getOrDefault(item.area(), item.area())).append('\n')
            .append("สถานะ: ").append(statusLabel).append('\n');
        if (eta != null) {
            body.append("กำหนดเสร็จ: ").append(ThaiText.date(eta)).append('\n');
        }
        if (reason != null) {
            body.append("เหตุผล: ").append(reason).append('\n');
        }
        return body.append("\nรายละเอียดคำขอ:\n").append(item.details()).toString();
    }

    private FeedbackDto existing(long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเรื่องนี้"));
    }

    private void requireAdmin(UserPrincipal actor) {
        if (actor == null || !employees.isAdmin(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ถูกตัดสินแล้วหรือยังไม่ได้รับเรื่อง");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Characters as Postgres char_length counts them: an emoji is one, not two UTF-16 units. */
    private static int chars(String value) {
        return value.codePointCount(0, value.length());
    }

    /** Cuts to {@code max} characters without splitting a surrogate pair. */
    private static String cut(String value, int max) {
        if (value == null || chars(value) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
