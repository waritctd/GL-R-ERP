package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.common.ApiException;

/**
 * GLA-75: the upload gate for a quotation item's picture. Type is decided by the file's MAGIC
 * BYTES — never the declared Content-Type or the filename — and a file must also parse as an
 * image. Every negative case here asserts a REFUSAL (wrong-way-round).
 */
class QuotationItemPicturesTest {

    @Test
    void aPngNamedFileWithATextBody_isRejected_evenThoughItsContentTypeSaysPng() {
        MockMultipartFile fake = new MockMultipartFile("file", "tile.png", "image/png",
            "this is not a picture, it is text".getBytes(StandardCharsets.UTF_8));
        assertBadRequest(() -> QuotationItemPictures.validate(fake), "PNG หรือ JPEG");
    }

    @Test
    void theRightMagicBytesFollowedByGarbage_isRejected_becauseTheHeaderMustParse() {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        Arrays.fill(bytes, magic.length, bytes.length, (byte) 'x');
        assertBadRequest(() -> QuotationItemPictures.validate(
            new MockMultipartFile("file", "tile.png", "image/png", bytes)), "ไฟล์อาจเสียหาย");
    }

    @Test
    void aGifOrPdf_isRejected_whateverItClaimsToBe() {
        assertBadRequest(() -> QuotationItemPictures.validate(new MockMultipartFile("file", "a.png", "image/png",
            "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1))), "PNG หรือ JPEG");
        assertBadRequest(() -> QuotationItemPictures.validate(new MockMultipartFile("file", "a.jpg", "image/jpeg",
            "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))), "PNG หรือ JPEG");
    }

    @Test
    void anEmptyOrMissingFile_isRejected() {
        assertBadRequest(() -> QuotationItemPictures.validate((MockMultipartFile) null), "กรุณาเลือกไฟล์รูปภาพ");
        assertBadRequest(() -> QuotationItemPictures.validate(
            new MockMultipartFile("file", "a.png", "image/png", new byte[0])), "กรุณาเลือกไฟล์รูปภาพ");
    }

    @Test
    void aFileOverTheSizeCap_isRejected_withAThaiMessage() {
        byte[] big = new byte[(int) QuotationItemPictures.MAX_BYTES + 1];
        byte[] png = png(4, 4);
        System.arraycopy(png, 0, big, 0, png.length); // real PNG magic, so only the SIZE can refuse it
        assertBadRequest(() -> QuotationItemPictures.validate(
            new MockMultipartFile("file", "big.png", "image/png", big)), "สูงสุด 2 MB");
    }

    @Test
    void aDeclaredSizeOverTheDimensionCap_isRejected_beforeAnythingDecodesIt() {
        // A flat colour at the cap + 1 compresses to a few KB — exactly the decompression-bomb shape
        // the dimension cap exists for.
        byte[] huge = png(QuotationItemPictures.MAX_DIMENSION_PX + 1, 8);
        assertThat(huge.length).isLessThan((int) QuotationItemPictures.MAX_BYTES);
        assertBadRequest(() -> QuotationItemPictures.validate(huge), "พิกเซล");
    }

    @Test
    void aRealPngAndARealJpeg_areAccepted_andTheMimeTypeIsSniffed_notTakenFromTheHeader() {
        // Declared WRONG on purpose: a real JPEG uploaded as image/png is stored as image/jpeg.
        var jpeg = QuotationItemPictures.validate(new MockMultipartFile("file", "x.png", "image/png", jpeg(30, 20)));
        assertThat(jpeg.mimeType()).isEqualTo("image/jpeg");
        assertThat(jpeg.widthPx()).isEqualTo(30);
        assertThat(jpeg.heightPx()).isEqualTo(20);

        var png = QuotationItemPictures.validate(new MockMultipartFile("file", "x.jpg", "image/jpeg", png(40, 10)));
        assertThat(png.mimeType()).isEqualTo("image/png");
        assertThat(png.widthPx()).isEqualTo(40);
    }

    @Test
    void placement_defaultsToBelow_acceptsBothCaseInsensitively_andRefusesAnythingElse() {
        assertThat(QuotationItemPictures.parsePlacement(null)).isEqualTo("BELOW");
        assertThat(QuotationItemPictures.parsePlacement("  ")).isEqualTo("BELOW");
        assertThat(QuotationItemPictures.parsePlacement("beside")).isEqualTo("BESIDE");
        assertThat(QuotationItemPictures.parsePlacement("BELOW")).isEqualTo("BELOW");
        assertBadRequest(() -> QuotationItemPictures.parsePlacement("ABOVE"), "BELOW หรือ BESIDE");
    }

    private static void assertBadRequest(Runnable action, String messageFragment) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
            .hasMessageContaining(messageFragment);
    }

    static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    private static byte[] encode(int width, int height, String format) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 110, 160));
        g.fillRect(0, 0, width, height);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
