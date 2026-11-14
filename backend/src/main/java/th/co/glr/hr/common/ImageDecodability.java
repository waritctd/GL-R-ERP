package th.co.glr.hr.common;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Whether ImageIO can actually DECODE a byte array claiming to be an image — not just whether it carries the
 * right container magic bytes. Both go through the same probe so behaviour can never quietly diverge between
 * them. */
public final class ImageDecodability {

    private ImageDecodability() {
    }

    /** Decodes imageBytes, or returns null on anything ImageIO rejects — a missing reader (unsupported/corrupt
     * format, ImageIO.read returns null) or a reader that throws partway through (e.g. a CMYK JPEG, which some
     * JDKs recognise by header but cannot actually decode). */
    public static BufferedImage decode(byte[] imageBytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The decoded natural size, or null when #decode would return null. */
    public static Dimension size(byte[] imageBytes) {
        BufferedImage decoded = decode(imageBytes);
        return decoded != null ? new Dimension(decoded.getWidth(), decoded.getHeight()) : null;
    }
}
