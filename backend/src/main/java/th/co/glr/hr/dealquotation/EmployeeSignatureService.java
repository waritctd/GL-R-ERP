package th.co.glr.hr.dealquotation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.EmployeeSignatureRepository.SignatureImage;

/**
 * Quotation v2 (direct deal quotation, V165) — upload/read/delete of an employee's signature
 * image, used to stamp the ผู้อนุมัติ box on an APPROVED quotation.
 *
 * <p>Authz (docs/sales/quotation-v2-plan.md): self, {@code ceo}, or the {@code admin} CAPABILITY (not a
 * role — see {@link ActivityLogRepository#isAdmin}, reused here verbatim rather than
 * re-implementing the same live {@code hr.employee.is_admin} check a second way). Every other
 * caller — including {@code sales_manager}, who approves quotations but does not manage other
 * employees' signatures — is 403.
 */
@Service
public class EmployeeSignatureService {
    private static final long MAX_BYTES = 1024 * 1024; // 1 MB

    private final EmployeeSignatureRepository signatures;
    private final ActivityLogRepository activityLog;

    public EmployeeSignatureService(EmployeeSignatureRepository signatures, ActivityLogRepository activityLog) {
        this.signatures = signatures;
        this.activityLog = activityLog;
    }

    public void upload(long employeeId, MultipartFile file, UserPrincipal actor) {
        requireManage(employeeId, actor);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกไฟล์ลายเซ็น");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ลายเซ็นมีขนาดใหญ่เกินไป (สูงสุด 1 MB)");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่สามารถอ่านไฟล์ได้");
        }
        String mimeType = sniffImageMimeType(bytes);
        if (mimeType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รองรับเฉพาะไฟล์รูปภาพ PNG หรือ JPEG เท่านั้น (ตรวจสอบจากเนื้อไฟล์จริง ไม่ใช่แค่ชื่อไฟล์)");
        }
        signatures.upsert(employeeId, mimeType, bytes, actor.id());
    }

    public SignatureImage get(long employeeId, UserPrincipal actor) {
        requireManage(employeeId, actor);
        return signatures.find(employeeId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ยังไม่มีลายเซ็นสำหรับพนักงานนี้"));
    }

    public void delete(long employeeId, UserPrincipal actor) {
        requireManage(employeeId, actor);
        signatures.delete(employeeId);
    }

    private void requireManage(long employeeId, UserPrincipal actor) {
        boolean self = actor.id() == employeeId;
        boolean ceo = "ceo".equals(actor.role());
        // Live check against hr.employee.is_admin — never trusted off the session, exactly like
        // ActivityLogService#requireAdmin's own Javadoc: revoking the capability takes effect on
        // the next request rather than at the holder's next login.
        boolean admin = activityLog.isAdmin(actor.id());
        if (!self && !ceo && !admin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงลายเซ็นนี้");
        }
    }

    /**
     * Validates the MAGIC BYTES, not the declared {@code Content-Type} — {@code
     * th.co.glr.hr.attachment.FileStorageService#validate} is declarative-only (trusts the
     * client's header), which is exactly what docs/sales/quotation-v2-plan.md calls out as insufficient for
     * this endpoint. PNG: {@code 89 50 4E 47 0D 0A 1A 0A}. JPEG: starts {@code FF D8 FF}.
     *
     * @return the sniffed MIME type, or {@code null} when neither signature matches.
     */
    private static String sniffImageMimeType(byte[] bytes) {
        if (bytes.length >= 8
            && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
            && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }
}
