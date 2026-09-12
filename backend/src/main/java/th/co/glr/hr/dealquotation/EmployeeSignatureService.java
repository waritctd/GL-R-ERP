package th.co.glr.hr.dealquotation;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ImageDecodability;
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
 *
 * <p>Existence: after the authz decision, an {@code employeeId} with no {@code hr.employee} row
 * is 404 on every verb ({@code ไม่พบพนักงาน…}) — the real-backend write sweep
 * ({@code e2e-real/write-authz.spec.js}) found {@code DELETE /api/employees/999999/signature}
 * answering 204, a write "succeeding" against nothing. Authz comes FIRST so a caller without the
 * capability still sees 403 and learns nothing about which ids exist. Deleting an EXISTING
 * employee's absent signature stays 204: DELETE is idempotent.
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
        requireEmployee(employeeId);
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
        // The magic-byte sniff above proves the container (PNG/JPEG) and nothing else — a
        // 16-bit-depth PNG, an interlaced/APNG, or a CMYK JPEG all pass it and then fail to
        // decode, which is exactly the defect this method exists to close: such a file used to
        // be stored as-is and only failed, silently, months later at quotation render time (see
        // QuotationRenderer#anchorApproverSignature's Javadoc). Probe decodability with the same
        // shared check QuotationRenderer uses, HERE, so a bad upload is rejected with an
        // actionable message instead of being accepted and quietly dropped from a customer
        // document later.
        BufferedImage decoded = ImageDecodability.decode(bytes);
        if (decoded == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถอ่านไฟล์รูปภาพนี้ได้ กรุณาบันทึกเป็น PNG หรือ JPEG มาตรฐานแล้วอัปโหลดใหม่");
        }
        // Re-encode to a canonical 8-bit PNG rather than storing the original bytes verbatim:
        // this repairs decodable-but-atypical files (16-bit depth, odd color types, an
        // interlaced/APNG, a CMYK-but-decodable JPEG) so every later reader of
        // hr.employee_signature sees one predictable format instead of having to guess.
        byte[] canonicalPng = encodeCanonicalPng(decoded);
        if (canonicalPng.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ลายเซ็นมีขนาดใหญ่เกินไป (สูงสุด 1 MB)");
        }
        signatures.upsert(employeeId, "image/png", canonicalPng, actor.id());
    }

    /**
     * Re-encodes {@code decoded} to a canonical 8-bit ARGB PNG. The signature is drawn over a
     * printed rule in the quotation template — {@code QuotationRenderer#placeSignaturePicture}'s
     * own comment notes the PNG's alpha is what keeps the rule visible on both sides of the ink —
     * so this MUST NOT flatten or drop transparency.
     *
     * <p>Converting to {@link BufferedImage#TYPE_INT_ARGB} before writing, rather than calling
     * {@code ImageIO.write(decoded, "png", out)} directly, guarantees an alpha channel exists
     * regardless of {@code decoded}'s own color model (indexed, grayscale, opaque RGB, 16-bit,
     * CMYK-derived, etc.) instead of leaving it to ImageIO's PNG writer to infer one — some
     * {@link BufferedImage} types write out opaque even when the source had transparency.
     * {@code AlphaComposite.Src} makes the copy a straight per-pixel copy of source ARGB (as
     * opposed to the default {@code SrcOver}, which would composite onto whatever the destination
     * already held — here always fully-transparent black, so the two are equivalent for a fresh
     * canvas, but {@code Src} says the intent plainly and stays correct if that ever changes).
     */
    private static byte[] encodeCanonicalPng(BufferedImage decoded) {
        BufferedImage argb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(decoded, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(argb, "png", out);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถแปลงไฟล์รูปภาพนี้เป็น PNG ได้ กรุณาบันทึกเป็น PNG หรือ JPEG มาตรฐานแล้วอัปโหลดใหม่");
        }
        return out.toByteArray();
    }

    public SignatureImage get(long employeeId, UserPrincipal actor) {
        requireManage(employeeId, actor);
        requireEmployee(employeeId);
        return signatures.find(employeeId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ยังไม่มีลายเซ็นสำหรับพนักงานนี้"));
    }

    public void delete(long employeeId, UserPrincipal actor) {
        requireManage(employeeId, actor);
        requireEmployee(employeeId);
        signatures.delete(employeeId);
    }

    private void requireEmployee(long employeeId) {
        if (!signatures.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบพนักงานรหัส " + employeeId);
        }
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
