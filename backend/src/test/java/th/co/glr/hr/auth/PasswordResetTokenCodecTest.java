package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for PasswordResetTokenCodec — no DB, no Spring context. */
class PasswordResetTokenCodecTest {

    @Test
    void generatedTokenDecodesToExactlyThirtyTwoBytes() {
        // Proven by mutation, not assumed: shrinking generateRawToken's source to `new byte[4]` still passes every
        // other test in this class (uniqueness and URL-safe-format checks don't care how many bytes back the string),
        // so none of them actually pins the entropy budget the design depends on.
        String rawToken = PasswordResetTokenCodec.generateRawToken();

        byte[] decoded = Base64.getUrlDecoder().decode(rawToken);

        assertThat(decoded).hasSize(32);
    }

}
