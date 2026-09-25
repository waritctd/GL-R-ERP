package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.EmailRecipient;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;

/**
 * Assembles and sends the auto-generated leave-submission email (2026-09 HR request). Pressing
 * "ยื่นคำขอลา" in the portal now mails the ใบลา F-HR-020 form (rendered by {@link LeaveFormRenderer})
 * to the shared HR inbox, CC'ing the requester and their manager, so staff no longer fill and email a
 * leave form by hand.
 *
 * <p><b>Routing (owner decisions, 2026-09-25):</b>
 * <ul>
 *   <li>TO: the shared HR inbox {@code app.leave.submission-inbox}.</li>
 *   <li>CC: the requester's own address, plus their reports-to (manager) address when one exists.
 *       When the employee has no manager on file, only the requester is CC'd (no HR-wide CC) - the
 *       shared inbox is already the primary recipient.</li>
 *   <li>Attachments: the generated ใบลา PDF always; the request's supporting document (e.g. a medical
 *       certificate) when one was uploaded.</li>
 * </ul>
 *
 * <p>This is a self-contained collaborator so {@link LeaveService} gains exactly one new dependency
 * for the whole email concern (its unit-test constructor wires none, leaving submit email-free under
 * mocks). Delivery itself is deferred to after-commit and run @Async by
 * {@link NotificationEmailService}; a mail failure is logged there, never thrown, so it cannot fail a
 * committed leave submission.
 */
@Component
public class LeaveSubmissionMailer {
    private static final Logger log = LoggerFactory.getLogger(LeaveSubmissionMailer.class);
    private static final String PORTAL_LINK = "/leave";

    private final LeaveFormPdf formPdf;
    private final NotificationEmailService emailService;
    private final NotificationRepository notifications;
    private final FileAttachmentBlobRepository attachmentBlobs;
    private final String inboxAddress;

    public LeaveSubmissionMailer(LeaveFormPdf formPdf,
                                 NotificationEmailService emailService,
                                 NotificationRepository notifications,
                                 FileAttachmentBlobRepository attachmentBlobs,
                                 @Value("${app.leave.submission-inbox:jobs.glr.co.th@gmail.com}") String inboxAddress) {
        this.formPdf = formPdf;
        this.emailService = emailService;
        this.notifications = notifications;
        this.attachmentBlobs = attachmentBlobs;
        this.inboxAddress = inboxAddress == null ? "" : inboxAddress.trim();
    }

    /**
     * Sends the submission email for a just-created SUBMITTED request. {@code formData} carries the
     * form fields {@link LeaveService} assembled (it owns the balance math the year-to-date history
     * needs); this method renders the PDF, pulls any supporting document, resolves the CC recipients,
     * and hands the finished message to {@link NotificationEmailService#sendLeaveSubmission}.
     *
     * <p>{@code @Async}: the Chromium PDF render is the slow step, so it runs off the request thread
     * (the caller schedules this after the submit transaction commits). Best-effort — a failure here
     * never reaches the committed submission.
     */
    @Async
    public void send(LeaveRequestDto request, LeaveFormData formData) {
        List<Mailer.Attachment> attachments = new ArrayList<>();
        attachments.add(new Mailer.Attachment(formPdfName(request), formPdf.toPdf(formData), "application/pdf"));
        addSupportingDocument(request, attachments);

        List<String> cc = new ArrayList<>();
        addEmail(cc, request.employeeId());
        if (request.managerEmployeeId() != null) {
            addEmail(cc, request.managerEmployeeId());
        }

        emailService.sendLeaveSubmission(inboxAddress, cc, subject(request, formData),
            body(request, formData), PORTAL_LINK, attachments);
    }

    /**
     * Emails the employee their completed ใบลา after a manual decision -- the same F-HR-020, now
     * carrying the ticked ความเห็นผู้บังคับบัญชา / HR-receipt boxes ({@code formData} already reflects
     * the decision, assembled post-commit by {@link LeaveService#buildLeaveForm}). TO the employee
     * ONLY (owner decision 2026-09-26); a no-op when they have no address on file. Reuses the
     * attachment-carrying {@code sendLeaveSubmission} transport with the employee as the sole TO.
     */
    @Async
    public void sendDecision(LeaveRequestDto request, LeaveFormData formData) {
        String to = notifications.findEmployeeRecipient(request.employeeId())
            .map(EmailRecipient::email)
            .filter(email -> email != null && !email.isBlank())
            .orElse(null);
        if (to == null) {
            log.info("Leave decision email skipped: employee={} has no address", request.employeeId());
            return;
        }
        List<Mailer.Attachment> attachments = new ArrayList<>();
        attachments.add(new Mailer.Attachment(formPdfName(request), formPdf.toPdf(formData), "application/pdf"));
        emailService.sendLeaveSubmission(to, List.of(), decisionSubject(request),
            decisionBody(request), PORTAL_LINK, attachments);
    }

    private String decisionSubject(LeaveRequestDto request) {
        boolean approved = "APPROVED".equals(request.status());
        return "[" + nn(request.employeeCode()) + "] " + (approved ? "อนุมัติ" : "ไม่อนุมัติ")
            + "ใบลา" + leaveWord(request.leaveTypeNameTh()) + " "
            + ThaiText.dateRange(request.startDate(), request.endDate());
    }

    private String decisionBody(LeaveRequestDto request) {
        boolean approved = "APPROVED".equals(request.status());
        String verdict = approved ? "ได้รับการอนุมัติแล้ว" : "ไม่ได้รับการอนุมัติ";
        StringBuilder b = new StringBuilder();
        b.append("เรียน ").append(nn(request.employeeName())).append(",\n\n")
            .append("ใบลา").append(leaveWord(request.leaveTypeNameTh())).append(" ")
            .append(dateSpan(request)).append(" ").append(verdict).append("\n\n");
        if (!approved && request.reviewerNote() != null && !request.reviewerNote().isBlank()) {
            b.append("เหตุผล: ").append(request.reviewerNote().trim()).append("\n\n");
        }
        b.append("ได้แนบใบลาที่ระบุผลการพิจารณามาพร้อมอีเมลนี้แล้ว\n\n")
            .append("จึงเรียนมาเพื่อทราบ\n\n")
            .append("ฝ่ายบุคคล");
        return b.toString();
    }

    private void addSupportingDocument(LeaveRequestDto request, List<Mailer.Attachment> attachments) {
        if (request.attachmentId() == null) {
            return;
        }
        attachmentBlobs.findContent(request.attachmentId()).ifPresent(bytes -> {
            String name = request.attachmentFileName() == null ? "leave-attachment" : request.attachmentFileName();
            attachments.add(new Mailer.Attachment(name, bytes, mimeFromName(name)));
        });
    }

    private void addEmail(List<String> cc, long employeeId) {
        notifications.findEmployeeRecipient(employeeId)
            .map(EmailRecipient::email)
            .filter(email -> email != null && !email.isBlank())
            .ifPresent(cc::add);
    }

    // Subject: [รหัสพนักงาน][ชื่อเล่น] ส่งใบลา[ประเภท] เหตุ: [เหตุผล] [วันที่]. The type name is used
    // WITHOUT its leading "ลา" after "ใบลา"/"แจ้งลา" so it reads "ส่งใบลาป่วย", not "ส่งใบลาลาป่วย".
    private String subject(LeaveRequestDto request, LeaveFormData formData) {
        return "[" + nn(request.employeeCode()) + "][" + nn(formData.nickName()) + "] ส่งใบลา"
            + leaveWord(request.leaveTypeNameTh()) + " เหตุ: " + nn(request.reason()) + " "
            + ThaiText.dateRange(request.startDate(), request.endDate());
    }

    private String body(LeaveRequestDto request, LeaveFormData formData) {
        String fullName = nn(request.employeeName());
        return "เรียนฝ่ายบุคคล,\n\n"
            + fullName + " รหัสพนักงาน " + nn(request.employeeCode()) + " ขอแจ้งลา"
            + leaveWord(request.leaveTypeNameTh()) + " เนื่องจาก " + nn(request.reason()) + "\n\n"
            + "วันที่ลา: " + dateSpan(request) + "\n"
            + "ระยะเวลา: " + durationLabel(request) + "\n\n"
            + "ได้แนบใบลามาพร้อมอีเมลนี้แล้ว\n\n"
            + "จึงเรียนมาเพื่อโปรดพิจารณา และขอความกรุณาหัวหน้างานพิจารณาอนุมัติการลาดังกล่าวด้วย "
            + "จะขอบพระคุณยิ่ง\n\n"
            + fullName + "\n"
            + nn(formData.nickName());
    }

    /**
     * The leave span for the email. A multi-day TIMED request binds each clock time to its own date --
     * "ตั้งแต่ X เวลา t1 น. ถึง Y เวลา t2 น." -- so it cannot be misread as "t1-t2 on each day" (the
     * leave runs continuously from t1 on the first day to t2 on the last). Whole-day and
     * single-day-timed requests keep the plain date range; durationLabel carries any single-day times.
     */
    private String dateSpan(LeaveRequestDto request) {
        boolean timed = request.startTime() != null && request.endTime() != null;
        boolean multiDay = request.endDate() != null && !request.endDate().equals(request.startDate());
        if (timed && multiDay) {
            return "ตั้งแต่ " + ThaiText.date(request.startDate()) + " เวลา " + hhmm(request.startTime()) + " น."
                + " ถึง " + ThaiText.date(request.endDate()) + " เวลา " + hhmm(request.endTime()) + " น.";
        }
        return ThaiText.dateRange(request.startDate(), request.endDate());
    }

    private String durationLabel(LeaveRequestDto request) {
        boolean multiDay = request.endDate() != null && !request.endDate().equals(request.startDate());
        if (request.startTime() != null && request.endTime() != null) {
            // A multi-day timed span's "duration" is the total leave, not the single day's clock window
            // (that window is now shown per-date on the วันที่ลา line above).
            if (multiDay) {
                return LeaveDayMath.formatDuration(request.totalDays());
            }
            return "เวลา " + hhmm(request.startTime()) + "–" + hhmm(request.endTime()) + " น.";
        }
        BigDecimal days = request.totalDays() == null ? BigDecimal.ZERO : request.totalDays();
        if (days.compareTo(new BigDecimal("0.5")) == 0) {
            return "ครึ่งวัน";
        }
        if (days.compareTo(BigDecimal.ONE) == 0) {
            return "เต็มวัน";
        }
        return days.stripTrailingZeros().toPlainString() + " วัน";
    }

    private String formPdfName(LeaveRequestDto request) {
        return "ใบลา-" + nn(request.employeeCode()) + "-" + request.startDate() + ".pdf";
    }

    private String mimeFromName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private String hhmm(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private String nn(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    /** The leave-type name for use right after "ใบลา"/"แจ้งลา": drops a leading "ลา" so the type does
     * not double it (ลาป่วย -> "ใบลาป่วย", not "ใบลาลาป่วย"). Types that do not start with "ลา" are
     * returned unchanged. */
    private String leaveWord(String typeNameTh) {
        String name = nn(typeNameTh);
        return name.startsWith("ลา") && name.length() > 2 ? name.substring(2) : name;
    }
}
