package th.co.glr.hr.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Pure token generation/hashing for the self-service forgot-password flow — deliberately
 * dependency-free (no Spring, no DB, no {@link PasswordEncoder}) so it is unit-testable without a
 * database. {@code PasswordResetTokenCodecTest} pins the two properties that matter: hashing is
 * deterministic (the SAME raw token must always look up the SAME row) and generated tokens do not
 * collide across a large sample.
 *
 * <p>Same SecureRandom + Base64 URL-safe-without-padding shape as {@code CsrfCookieFilter}'s own
 * token issuance (32 random bytes) — see that class for the established pattern this mirrors.
 *
 * <p>SHA-256, deliberately NOT BCrypt: this hashes a high-entropy 32-byte {@link SecureRandom}
 * value for an indexed exact-match database lookup ({@code WHERE token_hash = :hash}), not a
 * low-entropy human password. BCrypt would be both the wrong tool — it exists specifically to
 * slow down guessing a LOW-entropy secret, which a 256-bit random token already defeats by
 * entropy alone — and non-lookupable, since BCrypt salts every call so the same input never
 * reproduces the same output.
 */
final class PasswordResetTokenCodec {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PasswordResetTokenCodec() {
    }

    /** A fresh 32-byte {@link SecureRandom} token, URL-safe-Base64-encoded (no padding). */
    static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }

    /** The lower-case hex SHA-256 digest of {@code rawToken}, for storage/lookup as token_hash. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm under the Java Cryptography Architecture
            // standard names - every conforming JVM must provide it, so this can never actually
            // throw. The checked exception still has to be handled somewhere.
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
    }
}
