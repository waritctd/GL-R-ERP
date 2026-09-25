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
            + "วันที่ลา: " + ThaiText.dateRange(request.startDate(), request.endDate()) + "\n"
            + "ระยะเวลา: " + durationLabel(request) + "\n\n"
            + "ได้แนบใบลามาพร้อมอีเมลนี้แล้ว\n\n"
            + "จึงเรียนมาเพื่อให้ทราบ\n\n"
            + fullName + "\n"
            + nn(formData.nickName());
    }

    private String durationLabel(LeaveRequestDto request) {
        if (request.startTime() != null && request.endTime() != null) {
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
