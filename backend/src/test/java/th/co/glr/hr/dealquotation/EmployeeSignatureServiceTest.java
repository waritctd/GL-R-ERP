package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * Covers the upload-time defect fix this class exists for: {@code sniffImageMimeType} proves a
 * PNG/JPEG container and nothing else (not bit depth, colour type, interlacing, or JPEG colour
 * space), so a decodable-in-header-only file used to be stored as-is and only fail, silently,
 * months later at quotation render time ({@code QuotationRenderer#anchorApproverSignature}).
 * {@link EmployeeSignatureService#upload} now probes decodability with the shared
 * {@code th.co.glr.hr.common.ImageDecodability} check and re-encodes every accepted upload to a
 * canonical 8-bit ARGB PNG before it is stored.
 *
 * <p>The signature is drawn over a printed rule in the quotation template; the PNG's own alpha
 * channel is what keeps the rule visible on both sides of the ink
 * ({@code EmployeeSignatureService#encodeCanonicalPng}'s own Javadoc), so
 * {@link #aTransparentArgbPng_roundTripsWithAlphaIntact} is the test that guards against a silent
 * "white box over the signature line" regression.
 */
class EmployeeSignatureServiceTest {

    private final EmployeeSignatureRepository signatures = mock(EmployeeSignatureRepository.class);
    private final ActivityLogRepository activityLog = mock(ActivityLogRepository.class);
    private final EmployeeSignatureService service = new EmployeeSignatureService(signatures, activityLog);

    @Test
    void aTransparentArgbPng_roundTripsWithAlphaIntact() throws IOException {
        // Ink = (5,5) fully opaque red + (6,5) half-transparent red; everything else transparent.
        // After the trim (ink bbox 2x1, margin 2 px) the output is 6x5 with the ink at (2,2)/(3,2).
        // If the re-encode ever flattens alpha, the margin comes back opaque and the printed rule
        // underneath it would be hidden by a solid box.
        BufferedImage source = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(5, 5, new Color(200, 30, 30, 255).getRGB());
        source.setRGB(6, 5, new Color(200, 30, 30, 100).getRGB());

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        assertThat(capturedMimeType()).isEqualTo("image/png");
        BufferedImage decoded = storedImage();
        assertThat(decoded.getWidth()).isEqualTo(6);
        assertThat(decoded.getHeight()).isEqualTo(5);
        assertThat(new Color(decoded.getRGB(0, 0), true).getAlpha())
            .as("the transparent margin must round-trip transparent")
            .isEqualTo(0);
        Color ink = new Color(decoded.getRGB(2, 2), true);
        assertThat(ink.getAlpha()).as("opaque ink pixel must round-trip opaque").isEqualTo(255);
        assertThat(ink.getRed()).isEqualTo(200);
        assertThat(ink.getGreen()).isEqualTo(30);
        assertThat(ink.getBlue()).isEqualTo(30);
        assertThat(new Color(decoded.getRGB(3, 2), true).getAlpha())
            .as("partial alpha inside the ink must survive").isEqualTo(100);
    }

    // ── trim to ink (owner-approved 2026-09-13) — synthetic images only, the repo is public ────

    @Test
    void aPaddedPng_isCroppedToTheInkBoundingBoxPlusMargin() throws IOException {
        // 400x200 canvas, ink 150x30 at (100,60): the owner's real file had exactly this shape of
        // problem (ink ~32% x 20% of the frame). margin = max(2, round(150 * 0.02)) = 3.
        BufferedImage source = patternedInk(400, 200, 100, 60, 150, 30);

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getWidth()).isEqualTo(156);
        assertThat(stored.getHeight()).isEqualTo(36);
        assertInkCopied(source, 100, 60, 150, 30, stored, 3);
        assertMarginTransparent(stored, 3);
    }

    @Test
    void anAlreadyTightPng_isUnchangedApartFromTheMargin() throws IOException {
        BufferedImage source = patternedInk(150, 30, 0, 0, 150, 30);

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getWidth()).isEqualTo(156);
        assertThat(stored.getHeight()).isEqualTo(36);
        assertInkCopied(source, 0, 0, 150, 30, stored, 3);
        assertMarginTransparent(stored, 3);
    }

    @Test
    void faintSinglePixelNoiseInTheCorners_doesNotDefeatTheTrim() throws IOException {
        BufferedImage source = patternedInk(400, 200, 100, 60, 150, 30);
        source.setRGB(0, 0, new Color(0, 0, 0, 10).getRGB());
        source.setRGB(399, 199, new Color(0, 0, 0, EmployeeSignatureService.INK_ALPHA_THRESHOLD).getRGB());
        source.setRGB(399, 0, new Color(40, 40, 40, 20).getRGB());

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getWidth()).as("corner dust must not widen the crop").isEqualTo(156);
        assertThat(stored.getHeight()).isEqualTo(36);
    }

    @Test
    void aFaintButRealHairline_isKeptInsideTheCrop() throws IOException {
        // A 1 px anti-aliased stroke at ~31% alpha, 30 px below the main ink: real ink, must stay.
        BufferedImage source = patternedInk(400, 200, 100, 60, 150, 30);
        for (int x = 100; x < 250; x++) {
            source.setRGB(x, 120, new Color(20, 30, 120, 80).getRGB());
        }

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getHeight()).as("ink rows 60..120 = 61 px + 2x3 margin").isEqualTo(67);
        assertThat(new Color(stored.getRGB(3, 3 + 60), true).getAlpha()).isEqualTo(80);
    }

    @Test
    void anOpaqueWhiteBackgroundPng_isTrimmedOfItsNearWhiteBorder() throws IOException {
        BufferedImage source = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 100);
        g.setColor(new Color(10, 10, 60));
        g.fillRect(50, 20, 100, 20);
        g.dispose();
        source.setRGB(299, 99, new Color(240, 240, 240).getRGB()); // paper-white noise

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getWidth()).isEqualTo(104); // 100 + 2x max(2, round(100*0.02)=2)
        assertThat(stored.getHeight()).isEqualTo(24);
        assertThat(new Color(stored.getRGB(2, 2), true)).isEqualTo(new Color(10, 10, 60, 255));
    }

    @Test
    void aFullyTransparentImage_isRejected_andNothingIsStored() throws IOException {
        BufferedImage source = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(10, 10, new Color(0, 0, 0, 20).getRGB()); // dust only — still "empty"
        byte[] bytes = encode(source, "png");

        assertThatThrownBy(() -> upload(1L, "sig.png", "image/png", bytes, selfActor(1L)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ไม่พบลายเซ็นในรูปภาพนี้");
        verify(signatures, never()).upsert(anyLong(), anyString(), any(byte[].class), anyLong());
    }

    @Test
    void aBlankWhiteOpaqueImage_isRejected_andNothingIsStored() throws IOException {
        BufferedImage source = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        byte[] bytes = encode(source, "png");

        assertThatThrownBy(() -> upload(1L, "sig.png", "image/png", bytes, selfActor(1L)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ไม่พบลายเซ็นในรูปภาพนี้");
        verify(signatures, never()).upsert(anyLong(), anyString(), any(byte[].class), anyLong());
    }

    /**
     * Review finding 1: the pixel cap runs on the HEADER, before any decode. The file is a crafted
     * 45-byte PNG whose IHDR claims 8000x8000 with no image data at all — undecodable. Had the
     * decode run first, the rejection would read "ไม่สามารถอ่านไฟล์รูปภาพนี้ได้"; getting the SIZE
     * message instead proves the cap came first (and a real 8000x8000 decode is never attempted).
     */
    @Test
    void anOverLimitImage_isRejectedFromItsHeader_beforeAnyDecode() {
        byte[] bytes = pngHeaderOnly(8000, 8000);
        assertThat(bytes.length).isLessThan(100);

        assertThatThrownBy(() -> upload(1L, "sig.png", "image/png", bytes, selfActor(1L)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("รูปลายเซ็นมีขนาดใหญ่เกินไป")
            .hasMessageContaining("4000");
        verify(signatures, never()).upsert(anyLong(), anyString(), any(byte[].class), anyLong());
    }

    @Test
    void anImageExactlyAtTheLimit_4000x4000_isStillAccepted() throws IOException {
        BufferedImage source = new BufferedImage(4000, 4000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setColor(new Color(20, 30, 120));
        g.fillRect(1900, 1950, 200, 50);
        g.dispose();

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        BufferedImage stored = storedImage();
        assertThat(stored.getWidth()).isEqualTo(208); // 200 + 2x max(2, round(200*0.02)=4)
        assertThat(stored.getHeight()).isEqualTo(58);
    }

    /** A PNG signature + a valid IHDR chunk (correct CRC) declaring {@code w x h}, 8-bit RGBA, and
     * nothing else — ImageIO can read the size from it but cannot decode a pixel. */
    private static byte[] pngHeaderOnly(int w, int h) {
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(17);
        ihdr.put(new byte[]{'I', 'H', 'D', 'R'}).putInt(w).putInt(h)
            .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ihdr.array());
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        out.put(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        out.putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        out.putInt(0).put(new byte[]{'I', 'E', 'N', 'D'}).putInt(0xAE426082); // IEND + its fixed CRC
        return out.array();
    }

    /** Transparent w x h canvas with an opaque ink rectangle whose colour varies per pixel, so a
     * one-pixel misalignment of the copy is detectable. */
    private static BufferedImage patternedInk(int w, int h, int inkX, int inkY, int inkW, int inkH) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < inkH; y++) {
            for (int x = 0; x < inkW; x++) {
                img.setRGB(inkX + x, inkY + y, new Color(x % 256, (y * 7) % 256, 90, 255).getRGB());
            }
        }
        return img;
    }

    private static void assertInkCopied(BufferedImage source, int inkX, int inkY, int inkW, int inkH,
                                        BufferedImage stored, int margin) {
        for (int y = 0; y < inkH; y++) {
            for (int x = 0; x < inkW; x++) {
                assertThat(stored.getRGB(margin + x, margin + y))
                    .as("ink pixel (%d,%d)", x, y)
                    .isEqualTo(source.getRGB(inkX + x, inkY + y));
            }
        }
    }

    private static void assertMarginTransparent(BufferedImage stored, int margin) {
        for (int y = 0; y < stored.getHeight(); y++) {
            for (int x = 0; x < stored.getWidth(); x++) {
                boolean inMargin = x < margin || y < margin
                    || x >= stored.getWidth() - margin || y >= stored.getHeight() - margin;
                if (inMargin) {
                    assertThat(stored.getRGB(x, y) >>> 24).as("margin pixel (%d,%d) alpha", x, y).isZero();
                }
            }
        }
    }

    private BufferedImage storedImage() throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(capturedImage()));
        assertThat(decoded).isNotNull();
        return decoded;
    }

    @Test
    void anUndecodableFileWithValidPngMagic_isRejected_withTheThaiMessage_andNothingIsWritten() {
        // Real 8-byte PNG signature (passes the magic-byte sniff) followed by garbage that is not
        // a parseable PNG stream at all — ImageIO throws reading the header, which
        // ImageDecodability.decode turns into null. This is the shape a 16-bit-depth PNG, an
        // interlaced/APNG, or a CMYK JPEG take in practice: right container, undecodable body.
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        Arrays.fill(bytes, magic.length, bytes.length, (byte) 'x');

        assertThatThrownBy(() -> upload(1L, "sig.png", "image/png", bytes, selfActor(1L)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("ไม่สามารถอ่านไฟล์รูปภาพนี้ได้");

        verify(signatures, never()).upsert(anyLong(), anyString(), any(byte[].class), anyLong());
    }

    @Test
    void aJpeg_isStoredAsCanonicalPng() throws IOException {
        BufferedImage source = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(new Color(20, 90, 150));
        g.fillRect(0, 0, 30, 20);
        g.dispose();

        upload(1L, "sig.jpg", "image/jpeg", encode(source, "jpg"), selfActor(1L));

        assertThat(capturedMimeType()).isEqualTo("image/png");
        byte[] stored = capturedImage();
        byte[] pngMagic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(Arrays.copyOf(stored, 8)).isEqualTo(pngMagic);
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
    }

    @Test
    void theSizeLimitAppliesToTheStoredCanonicalPng_notJustTheUploadedFile() throws IOException {
        // 600x600 random-noise pixels: as a lossy JPEG this compresses to ~210 KB (comfortably
        // under the 1 MB input gate), but the same noise re-encoded as a lossless ARGB PNG is
        // ~1.03 MB — OVER the cap. The upload must still be refused, even though the file the
        // caller sent was well within the limit.
        BufferedImage noise = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 600; x++) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        byte[] jpegBytes = encode(noise, "jpg");
        assertThat(jpegBytes.length).isLessThan(1024 * 1024);

        assertThatThrownBy(() -> upload(1L, "sig.jpg", "image/jpeg", jpegBytes, selfActor(1L)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining("สูงสุด 1 MB");

        verify(signatures, never()).upsert(anyLong(), anyString(), any(byte[].class), anyLong());
    }

    private void upload(long employeeId, String filename, String contentType, byte[] bytes, UserPrincipal actor) {
        service.upload(employeeId,
            new MockMultipartFile("file", filename, contentType, bytes), actor);
    }

    private byte[] capturedImage() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(signatures).upsert(anyLong(), anyString(), captor.capture(), anyLong());
        return captor.getValue();
    }

    private String capturedMimeType() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(signatures).upsert(anyLong(), captor.capture(), any(byte[].class), anyLong());
        return captor.getValue();
    }

    private UserPrincipal selfActor(long employeeId) {
        when(signatures.employeeExists(employeeId)).thenReturn(true);
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, "sales_manager",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
