package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryPasswordGeneratorTest {
    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    void generatesLongPasswordsFromASafeAlphabet() {
        String password = generator.generate();

        assertThat(password).hasSizeGreaterThanOrEqualTo(12);
        // Alphanumeric only, and none of the visually ambiguous characters O/0/I/l/1.
        assertThat(password).matches("[A-Za-z2-9]+");
        assertThat(password).doesNotContainAnyWhitespaces();
        assertThat(password.chars()).noneMatch(c -> c == 'O' || c == '0' || c == 'I' || c == 'l' || c == '1');
    }

    @Test
    void generatesADistinctValueEachTimeAndNeverTheEmployeeCode() {
        String employeeCode = "GLR-42";
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String password = generator.generate();
            assertThat(password).isNotEqualTo(employeeCode);
            seen.add(password);
        }
        // High entropy: 100 draws should be unique (collisions are astronomically unlikely).
        assertThat(seen).hasSize(100);
    }
}
