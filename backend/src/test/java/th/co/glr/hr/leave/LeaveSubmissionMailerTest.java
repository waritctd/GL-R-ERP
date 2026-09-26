package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.EmailRecipient;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;

/**
 * Routing tests for the auto-generated leave-submission email (owner decisions, 2026-09-25): TO the
 * shared HR inbox, CC the requester and their manager (requester only when there is no manager),
 * attaching the ใบลา PDF plus any supporting document, with the subject/body following the requested
 * Thai template. Written wrong-way-round where it matters: the shared inbox is the TO and the people
 * are the CC, never the reverse.
 */
class LeaveSubmissionMailerTest {
    private static final String INBOX = "jobs.glr.co.th@gmail.com";
    private static final byte[] FORM_PDF = "%PDF-form".getBytes();

    private final LeaveFormPdf formPdf = mock(LeaveFormPdf.class);
    private final NotificationEmailService emailService = mock(NotificationEmailService.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final FileAttachmentBlobRepository attachmentBlobs = mock(FileAttachmentBlobRepository.class);
    private final LeaveSubmissionMailer mailer =
        new LeaveSubmissionMailer(formPdf, emailService, notifications, attachmentBlobs, INBOX);

    @Test
    void mailsTheInboxAndCcsRequesterAndManagerWithTheFormAttached() {
        when(formPdf.toPdf(any())).thenReturn(FORM_PDF);
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.of(new EmailRecipient("emp@glr.co.th", "พลอย")));
        when(notifications.findEmployeeRecipient(99L)).thenReturn(Optional.of(new EmailRecipient("mgr@glr.co.th", "ยุทธนา")));

        mailer.send(dto(99L, null), formData("ก้อย"));

        ArgumentCaptor<List<String>> cc = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Mailer.Attachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendLeaveSubmission(eq(INBOX), cc.capture(), subject.capture(), body.capture(),
            eq("/leave"), attachments.capture());

        assertThat(cc.getValue()).containsExactly("emp@glr.co.th", "mgr@glr.co.th");
        // Owner decision: the shared inbox is the TO, the people are CC'd - never the other way round.
        assertThat(cc.getValue()).doesNotContain(INBOX);
        assertThat(attachments.getValue()).hasSize(1);
        assertThat(attachments.getValue().get(0).filename()).startsWith("ใบลา-EMP001-");
        assertThat(attachments.getValue().get(0).bytes()).isEqualTo(FORM_PDF);
        assertThat(subject.getValue()).contains("EMP001").contains("ก้อย").contains("ลาป่วย");
        assertThat(body.getValue()).contains("เรียนฝ่ายบุคคลและหัวหน้างาน,").contains("รหัสพนักงาน EMP001").contains("ก้อย");
    }

    @Test
    void ccsRequesterOnlyWhenThereIsNoManager() {
        when(formPdf.toPdf(any())).thenReturn(FORM_PDF);
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.of(new EmailRecipient("emp@glr.co.th", "พลอย")));

        mailer.send(dto(null, null), formData("ก้อย"));

        ArgumentCaptor<List<String>> cc = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendLeaveSubmission(eq(INBOX), cc.capture(), any(), any(), eq("/leave"), any());
        assertThat(cc.getValue()).containsExactly("emp@glr.co.th");
    }

    @Test
    void attachesTheSupportingDocumentWhenPresent() {
        when(formPdf.toPdf(any())).thenReturn(FORM_PDF);
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.of(new EmailRecipient("emp@glr.co.th", "พลอย")));
        byte[] cert = "%PDF-cert".getBytes();
        when(attachmentBlobs.findContent(555L)).thenReturn(Optional.of(cert));

        mailer.send(dto(null, 555L), formData("ก้อย"));

        ArgumentCaptor<List<Mailer.Attachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendLeaveSubmission(eq(INBOX), any(), any(), any(), eq("/leave"), attachments.capture());
        assertThat(attachments.getValue()).hasSize(2);
        assertThat(attachments.getValue().get(1).filename()).isEqualTo("cert.pdf");
        assertThat(attachments.getValue().get(1).bytes()).isEqualTo(cert);
    }

    @Test
    void bodyBindsTimesToDatesForAMultiDaySpanAndAsksForApproval() {
        when(formPdf.toPdf(any())).thenReturn(FORM_PDF);
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.of(new EmailRecipient("emp@glr.co.th", "พลอย")));

        mailer.send(spanDto(), formData("พลอย"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendLeaveSubmission(eq(INBOX), any(), any(), body.capture(), eq("/leave"), any());
        String text = body.getValue();

        // Each clock time is bound to its own date, so it cannot read as "13:30-17:30 on each day".
        assertThat(text).contains("วันที่ลา: ตั้งแต่").contains("13:30").contains("17:30").contains(" ถึง ");
        assertThat(text).doesNotContain("13:30–17:30");
        // The span's duration is the TOTAL leave, not a single day's 4-hour window.
        assertThat(text).contains("ระยะเวลา: 3 วัน 4 ชม.").doesNotContain("รวมเวลา");
        // Approval-request closing replaces the weaker "เพื่อให้ทราบ".
        assertThat(text).contains("ขอความกรุณาหัวหน้างาน").contains("อนุมัติ")
            .doesNotContain("จึงเรียนมาเพื่อให้ทราบ");
    }

    @Test
    void sendDecisionMailsTheEmployeeOnlyWithTheCompletedForm() {
        when(formPdf.toPdf(any())).thenReturn(FORM_PDF);
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.of(new EmailRecipient("emp@glr.co.th", "พลอย")));

        mailer.sendDecision(approvedDto(), formData("พลอย"));

        ArgumentCaptor<List<String>> cc = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Mailer.Attachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendLeaveSubmission(eq("emp@glr.co.th"), cc.capture(), subject.capture(),
            any(), eq("/leave"), attachments.capture());

        // Owner decision: the decision email goes TO the employee only -- nobody is CC'd, and it never
        // goes to the shared HR inbox (which is the submission email's TO).
        assertThat(cc.getValue()).isEmpty();
        // "] อนุมัติ" (not merely "อนุมัติ", which is a substring of "ไม่อนุมัติ" too) so an
        // approve/reject wording swap in the subject would fail this.
        assertThat(subject.getValue()).contains("EMP001").contains("] อนุมัติใบลา")
            .doesNotContain("ไม่อนุมัติ");
        assertThat(attachments.getValue()).hasSize(1);
        assertThat(attachments.getValue().get(0).bytes()).isEqualTo(FORM_PDF);
    }

    @Test
    void sendDecisionSkippedWhenEmployeeHasNoAddress() {
        when(notifications.findEmployeeRecipient(10L)).thenReturn(Optional.empty());
        mailer.sendDecision(approvedDto(), formData("พลอย"));
        verify(emailService, org.mockito.Mockito.never())
            .sendLeaveSubmission(any(), any(), any(), any(), any(), any());
    }

    private LeaveRequestDto approvedDto() {
        OffsetDateTime ts = OffsetDateTime.parse("2026-09-21T09:15:00+07:00");
        return new LeaveRequestDto(
            77L, 10L, "EMP001", "พลอย วริศ",
            "SICK", "ลาป่วย", "Sick leave",
            LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-22"), null, null,
            new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("0.00"),
            2026, "เจ็บขา", null, null, "APPROVED",
            new BigDecimal("6.00"), new BigDecimal("5.00"), null,
            10L, "พลอย วริศ", ts,
            99L, "จินตนา หาญมนตรี", ts, null, null,
            99L, "จินตนา หาญมนตรี",
            ts, ts,
            "12", "พระโขนง", "คลองเตย", "กรุงเทพ", "0917949655",
            null, false, null, Map.of(), false,
            null, null,
            List.of(), BigDecimal.ZERO);
    }

    private LeaveFormData formData(String nickName) {
        return new LeaveFormData(
            "EMP001", "พลอย วริศ", nickName,
            "จัดซื้อ", "จัดซื้อ", "จัดซื้อ",
            "SICK", "ลาป่วย",
            LocalDate.parse("2026-09-08"),
            LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"), null, null,
            new BigDecimal("1.00"), "เจ็บขา",
            null, null, null, null, "0917949655",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, null);
    }

    /** 28 ก.ย. 13:30 -> 1 ต.ค. 17:30 = 3.5 วัน: a multi-day sub-day span. */
    private LeaveRequestDto spanDto() {
        OffsetDateTime ts = OffsetDateTime.parse("2026-09-25T09:00:00+07:00");
        return new LeaveRequestDto(
            77L, 10L, "EMP001", "พลอย วริศ",
            "SICK", "ลาป่วย", "Sick leave",
            LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-01"),
            LocalTime.of(13, 30), LocalTime.of(17, 30),
            new BigDecimal("3.50"), new BigDecimal("3.50"), new BigDecimal("0.00"),
            2026, "เส้นประสาทอักเสบ", null, null, "SUBMITTED",
            new BigDecimal("6.00"), new BigDecimal("2.50"), null,
            10L, "พลอย วริศ", ts,
            null, null, null, null, null,
            null, null,
            ts, ts,
            "12", "พระโขนง", "คลองเตย", "กรุงเทพ", "0917949655",
            null, false, null, Map.of(), false,
            null, null,
            List.of(), BigDecimal.ZERO);
    }

    private LeaveRequestDto dto(Long managerEmployeeId, Long attachmentId) {
        OffsetDateTime ts = OffsetDateTime.parse("2026-09-07T09:00:00+07:00");
        return new LeaveRequestDto(
            77L, 10L, "EMP001", "พลอย วริศ",
            "SICK", "ลาป่วย", "Sick leave",
            LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-07"), null, null,
            new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("0.00"),
            2026, "เจ็บขา", attachmentId, attachmentId == null ? null : "cert.pdf", "SUBMITTED",
            new BigDecimal("6.00"), new BigDecimal("5.00"), null,
            10L, "พลอย วริศ", ts,
            null, null, null, null, null,
            managerEmployeeId, managerEmployeeId == null ? null : "ยุทธนา",
            ts, ts,
            "12", "พระโขนง", "คลองเตย", "กรุงเทพ", "0917949655",
            null, false, null, Map.of(), false,
            null, null,
            List.of(), BigDecimal.ZERO);
    }
}
