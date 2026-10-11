package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@link SessionContext} may put a principal into a session.
 *
 * <p>{@link SessionSecurityFilter} re-checks a session against the employee row on every request
 * — but only a session carrying the sign-in record {@link SessionContext#signIn} writes (see
 * {@link SessionContext#SESSION_SIGNED_IN_AT_KEY} for why). So a production code path that started
 * a session with a bare {@code session.setAttribute(SESSION_USER_KEY, …)} would compile, pass every
 * existing test, and produce sessions that are never re-checked: a deactivated employee who signed
 * in that way would stay signed in, with the role they started with, for as long as they liked —
 * the exact hole the re-check exists to close. Nothing at runtime would say so.
 *
 * <p>This reads the production sources rather than running anything, because the failure it guards
 * against is a line that does not exist yet (a second sign-in route — SSO, an impersonation tool,
 * a "remember me"). Whoever writes it gets a red build pointing here instead of a quiet gap.
 */
class SessionSignInEntryPointTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final String ONLY_WRITER = "SessionContext.java";
    /** {@code setAttribute(SESSION_USER_KEY} / {@code setAttribute(SessionContext.SESSION_USER_KEY}. */
    private static final Pattern PRINCIPAL_WRITE =
        Pattern.compile("setAttribute\\(\\s*(?:SessionContext\\.)?SESSION_USER_KEY\\b");

    @Test
    void onlySessionContextWritesThePrincipalIntoASession() {
        assertThat(MAIN).as("run from the backend module directory").isDirectory();

        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                List<String> lines = read(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (PRINCIPAL_WRITE.matcher(lines.get(i)).find()) {
                        writers.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // The scan must be able to see a write at all, or "no other writer" is vacuous: both of
        // SessionContext's own (signIn, replacePrincipal) have to be found.
        assertThat(writers).filteredOn(writer -> writer.startsWith(ONLY_WRITER + ":")).hasSize(2);
        assertThat(writers)
            .as("a session must be started through SessionContext.signIn, and its principal replaced through"
                + " SessionContext.replacePrincipal — a bare setAttribute(SESSION_USER_KEY, …) makes a session"
                + " SessionSecurityFilter never re-checks")
            .allMatch(writer -> writer.startsWith(ONLY_WRITER + ":"));
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
