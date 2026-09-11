package th.co.glr.hr.dealquotation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.common.ApiException;

/**
 * GLA-75 — validation for a quotation item's picture (V170), kept out of
 * {@link DealQuotationService} so the rules are unit-testable without a database.
 *
 * <p><b>Type is decided by the MAGIC BYTES, never the client's {@code Content-Type} or filename.</b>
 * {@code th.co.glr.hr.attachment.FileStorageService#validate} trusts the declared header, and this
 * repo has been bitten by that (a text file declared {@code image/png} sails through it). This
 * mirrors {@link EmployeeSignatureService}'s sniff — PNG {@code 89 50 4E 47 0D 0A 1A 0A}, JPEG
 * {@code FF D8 FF} — and then goes one step further: the image HEADER must actually parse (an
 * {@link ImageReader} reports a width and height), because a file that merely starts with the
 * right eight bytes and is garbage after them would otherwise be stored and then fail to render
 * on a customer's document. Only the header is read, never the pixels, so a hostile file cannot
 * make the server decode a 50,000 × 50,000 image; its declared size is rejected first.
 */
public final class QuotationItemPictures {
    private QuotationItemPictures() {}

    /** 2 MB. Every picture is embedded in the XLS AND the PDF, and the approval email attaches the
     * PDF — a document with a dozen phone-camera photos would otherwise not survive the mail
     * provider's attachment limit. The reference images (thumbnails, a pattern, cut drawings) are
     * all well under this. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Longest side, in pixels. The widest a picture ever prints is column B (~9 cm), which is
     * ~1,100 px even at 300 dpi, so 4,000 leaves a phone photo untouched. The cap matters beyond
     * the header read here: LibreOffice and Chromium both DECODE the full bitmap at render time,
     * and a flat-colour PNG compresses to well under {@link #MAX_BYTES} at any size — at 8,000²
     * that is 256 MB of RGBA per picture in the PDF converter; at 4,000² it is 64 MB. */
    public static final int MAX_DIMENSION_PX = 4000;

    public static final String PLACEMENT_BELOW = "BELOW";
    public static final String PLACEMENT_BESIDE = "BESIDE";

    public record ValidatedPicture(String mimeType, byte[] bytes, int widthPx, int heightPx) {}

    public static ValidatedPicture validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกไฟล์รูปภาพ");
        }
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่สามารถอ่านไฟล์รูปภาพได้");
        }
        return validate(bytes);
    }

    public static ValidatedPicture validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกไฟล์รูปภาพ");
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        String mimeType = sniffMimeType(bytes);
        if (mimeType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รองรับเฉพาะไฟล์รูปภาพ PNG หรือ JPEG เท่านั้น (ตรวจสอบจากเนื้อไฟล์จริง ไม่ใช่แค่ชื่อไฟล์)");
        }
        int[] size = headerDimensions(bytes);
        if (size == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่สามารถอ่านไฟล์รูปภาพได้ ไฟล์อาจเสียหาย");
        }
        if (size[0] > MAX_DIMENSION_PX || size[1] > MAX_DIMENSION_PX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รูปภาพมีขนาดใหญ่เกินไป (ด้านยาวสุดไม่เกิน " + MAX_DIMENSION_PX + " พิกเซล)");
        }
        return new ValidatedPicture(mimeType, bytes, size[0], size[1]);
    }

    /** {@code null}/blank → {@link #PLACEMENT_BELOW}, the owner's default; otherwise exactly
     * BELOW or BESIDE (case-insensitive), anything else 400. */
    public static String parsePlacement(String raw) {
        if (raw == null || raw.isBlank()) {
            return PLACEMENT_BELOW;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        if (PLACEMENT_BELOW.equals(normalised) || PLACEMENT_BESIDE.equals(normalised)) {
            return normalised;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ตำแหน่งรูปภาพต้องเป็น BELOW หรือ BESIDE");
    }

    /** The sniffed MIME type, or {@code null} when neither signature matches. */
    public static String sniffMimeType(byte[] bytes) {
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

    /** {@code [width, height]} from the image HEADER only (no pixel decode), or {@code null} when
     * no reader can parse it or it declares a non-positive size. */
    public static int[] headerDimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.BAD_REQUEST,
            "ไฟล์รูปภาพมีขนาดใหญ่เกินไป (สูงสุด 2 MB) กรุณาย่อขนาดรูปก่อนแนบ");
    }
}
