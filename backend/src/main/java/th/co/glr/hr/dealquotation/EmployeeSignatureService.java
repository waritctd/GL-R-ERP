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

/** Quotation v2 (direct deal quotation, V165) — upload/read/delete of an employee's signature image, used to
 * stamp the ผู้อนุมัติ box on an APPROVED quotation. Deleting an EXISTING employee's absent signature stays
 * 204: DELETE is idempotent. */
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
        // The magic-byte sniff above proves the container and nothing else. Pixel-dimension cap FIRST, from the header
        // only: MAX_BYTES bounds the FILE, not the pixels, and a flat 8000x8000 PNG is ~250 KB but decodes (plus the
        // ARGB copy below) to hundreds of MB — enough to OOM a 512 MB instance.
        int[] headerSize = QuotationItemPictures.headerDimensions(bytes);
        if (headerSize != null && (headerSize[0] > QuotationItemPictures.MAX_DIMENSION_PX
                || headerSize[1] > QuotationItemPictures.MAX_DIMENSION_PX)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รูปลายเซ็นมีขนาดใหญ่เกินไป (ด้านยาวสุดไม่เกิน " + QuotationItemPictures.MAX_DIMENSION_PX
                    + " พิกเซล) กรุณาย่อขนาดรูปแล้วอัปโหลดใหม่");
        }
        BufferedImage decoded = ImageDecodability.decode(bytes);
        if (decoded == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถอ่านไฟล์รูปภาพนี้ได้ กรุณาบันทึกเป็น PNG หรือ JPEG มาตรฐานแล้วอัปโหลดใหม่");
        }
        // Re-encode to a canonical 8-bit PNG rather than storing the original bytes verbatim: this repairs
        // decodable-but-atypical files so every later reader of hr.employee_signature sees one predictable format
        // instead of having to guess.
        byte[] canonicalPng = encodeCanonicalPng(decoded);
        if (canonicalPng.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ลายเซ็นมีขนาดใหญ่เกินไป (สูงสุด 1 MB)");
        }
        signatures.upsert(employeeId, "image/png", canonicalPng, actor.id());
    }

    /** Re-encodes decoded to a canonical 8-bit ARGB PNG. */
    private static byte[] encodeCanonicalPng(BufferedImage decoded) {
        BufferedImage argb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(decoded, 0, 0, null);
        } finally {
            g.dispose();
        }
        BufferedImage trimmed = trimToInk(argb);
        if (trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่พบลายเซ็นในรูปภาพนี้ (รูปว่างเปล่าหรือโปร่งใสทั้งหมด) กรุณาอัปโหลดรูปที่มีลายเซ็น");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(trimmed, "png", out);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่สามารถแปลงไฟล์รูปภาพนี้เป็น PNG ได้ กรุณาบันทึกเป็น PNG หรือ JPEG มาตรฐานแล้วอัปโหลดใหม่");
        }
        return out.toByteArray();
    }

    // Crop transparent margins on upload so signature ink fills its printed slot.

    /** A pixel of a transparency-bearing image counts as ink when its alpha is ABOVE this (0-255). Faint pixels
     * INSIDE the ink's bounding box are copied through untouched; only the box is decided by this. */
    static final int INK_ALPHA_THRESHOLD = 32;

    /** The same cut for a fully OPAQUE image (a JPEG, or a PNG on a white background): a pixel is ink when its
     * darkest channel is more than this far below white, i.e. 255 - min(r,g,b) > 32. */
    static final int INK_DARKNESS_THRESHOLD = 32;

    /** Uniform transparent margin around the ink, so anti-aliased stroke ends are never flush with the picture
     * frame: 2% of the ink's longer side, at least 2 px. Small enough not to reintroduce the float the trim
     * removes (the owner's ~648x165 ink gets 13 px). */
    static final double TRIM_MARGIN_FRACTION = 0.02;
    static final int TRIM_MARGIN_MIN_PX = 2;

    /** Crops argb to the bounding box of its ink pixels plus a uniform transparent margin. "Ink" is decided by
     * alpha when ANY pixel is not fully opaque, otherwise by darkness (see the two thresholds above). */
    static BufferedImage trimToInk(BufferedImage argb) {
        int w = argb.getWidth();
        int h = argb.getHeight();
        // Scanned one row at a time rather than materialising a full w*h int[] — keeps the upload's peak memory to the
        // decoded image and its ARGB copy.
        int[] row = new int[w];
        boolean hasTransparency = false;
        for (int y = 0; y < h && !hasTransparency; y++) {
            argb.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) {
                if ((p >>> 24) < 255) {
                    hasTransparency = true;
                    break;
                }
            }
        }
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            argb.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if (isInk(row[x], hasTransparency)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int inkW = maxX - minX + 1;
        int inkH = maxY - minY + 1;
        int margin = Math.max(TRIM_MARGIN_MIN_PX,
            (int) Math.round(Math.max(inkW, inkH) * TRIM_MARGIN_FRACTION));
        BufferedImage out = new BufferedImage(inkW + 2 * margin, inkH + 2 * margin, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(margin, margin, inkW, inkH, argb.getRGB(minX, minY, inkW, inkH, null, 0, inkW), 0, inkW);
        return out;
    }

    private static boolean isInk(int argbPixel, boolean byAlpha) {
        if (byAlpha) {
            return (argbPixel >>> 24) > INK_ALPHA_THRESHOLD;
        }
        int r = (argbPixel >> 16) & 0xFF;
        int g = (argbPixel >> 8) & 0xFF;
        int b = argbPixel & 0xFF;
        return 255 - Math.min(r, Math.min(g, b)) > INK_DARKNESS_THRESHOLD;
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
        // ActivityLogService#requireAdmin's own Javadoc: revoking the capability takes effect on the next request
        // rather than at the holder's next login.
        boolean admin = activityLog.isAdmin(actor.id());
        if (!self && !ceo && !admin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงลายเซ็นนี้");
        }
    }

    /** Validates the MAGIC BYTES, not the declared Content-Type — th.co.glr.hr.attachment.FileStorageService#validate
     * is declarative-only (trusts the client's header), which is exactly what docs/sales/quotation-v2-plan.md calls
     * out as insufficient for this endpoint. PNG: 89 50 4E 47 0D 0A 1A 0A. */
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
