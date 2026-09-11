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
        // (0,0) fully transparent (the area outside the ink, over the printed rule); (5,5) fully
        // opaque red (the ink itself). If the re-encode ever flattens alpha, (0,0) comes back
        // opaque and the printed rule underneath it would be hidden by a solid box.
        BufferedImage source = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new java.awt.Color(0, 0, 0, 0).getRGB());
        source.setRGB(5, 5, new java.awt.Color(200, 30, 30, 255).getRGB());

        upload(1L, "sig.png", "image/png", encode(source, "png"), selfActor(1L));

        byte[] stored = capturedImage();
        assertThat(capturedMimeType()).isEqualTo("image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(decoded).isNotNull();
        assertThat(new java.awt.Color(decoded.getRGB(0, 0), true).getAlpha())
            .as("transparent source pixel must round-trip transparent")
            .isEqualTo(0);
        java.awt.Color ink = new java.awt.Color(decoded.getRGB(5, 5), true);
        assertThat(ink.getAlpha()).as("opaque ink pixel must round-trip opaque").isEqualTo(255);
        assertThat(ink.getRed()).isEqualTo(200);
        assertThat(ink.getGreen()).isEqualTo(30);
        assertThat(ink.getBlue()).isEqualTo(30);
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
