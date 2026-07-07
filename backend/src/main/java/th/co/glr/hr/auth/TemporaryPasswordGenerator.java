package th.co.glr.hr.auth;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Mints high-entropy temporary passwords for the HR reset-password flow. Uses {@link SecureRandom}
 * over an unambiguous alphabet (no {@code O/0/I/l/1}) so the value is safe to read aloud or type,
 * yet not guessable like the old {@code employee_code} temp password.
 */
@Component
public class TemporaryPasswordGenerator {
    // Uppercase + lowercase + digits, with visually ambiguous characters (O, 0, I, l, 1) removed.
    private static final char[] ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int LENGTH = 14;

    private final SecureRandom random = new SecureRandom();

    /** Returns a fresh plaintext temporary password. Never log the return value. */
    public String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
