package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for {@link PasswordResetTokenCodec} — no DB, no Spring context. Pins exactly
 * the two properties a lookup-by-hash design depends on: hashing is deterministic (the same raw
 * token always produces the same hash, or {@code findValidByTokenHash} could never match a token
 * that was hashed identically at issuance and again at redemption) and generated tokens are
 * high-entropy enough not to collide in practice.
 */
class PasswordResetTokenCodecTest {

    private static final Pattern URL_SAFE_BASE64_NO_PADDING = Pattern.compile("^[A-Za-z0-9_-]+$");

    @Test
    void hashingTheSameRawTokenTwiceProducesTheSameHash() {
        String rawToken = PasswordResetTokenCodec.generateRawToken();

        assertThat(PasswordResetTokenCodec.hash(rawToken)).isEqualTo(PasswordResetTokenCodec.hash(rawToken));
    }

    @Test
    void differentRawTokensHashDifferently() {
        String first = PasswordResetTokenCodec.generateRawToken();
        String second = PasswordResetTokenCodec.generateRawToken();

        assertThat(first).isNotEqualTo(second);
        assertThat(PasswordResetTokenCodec.hash(first)).isNotEqualTo(PasswordResetTokenCodec.hash(second));
    }

    @Test
    void hashIsALowercaseHexSha256Digest() {
        String hash = PasswordResetTokenCodec.hash("some-raw-token-value");

        // SHA-256 is 32 bytes -> 64 hex characters, and HexFormat.formatHex is lower-case by default.
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void generatedTokenIsUrlSafeBase64WithNoPadding() {
        String rawToken = PasswordResetTokenCodec.generateRawToken();

        assertThat(rawToken).matches(URL_SAFE_BASE64_NO_PADDING);
        assertThat(rawToken).doesNotContain("=", "+", "/");
    }

    @Test
    void generatedTokensDoNotCollideAcrossALargeSample() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(PasswordResetTokenCodec.generateRawToken()))
                .as("token %d should be unique", i)
                .isTrue();
        }
    }
}
