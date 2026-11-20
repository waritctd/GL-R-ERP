package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

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

}
