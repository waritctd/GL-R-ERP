package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApplicationRolesTest {
    @Test
    void normalizesAndAllowsOnlyKnownApplicationRoles() {
        assertThat(ApplicationRoles.requireAllowed(" HR ")).isEqualTo("hr");
        assertThat(ApplicationRoles.isAllowed("director")).isTrue();
        assertThat(ApplicationRoles.isAllowed("owner")).isFalse();
    }

    @Test
    void rejectsUnknownRoles() {
        assertThatThrownBy(() -> ApplicationRoles.requireAllowed("owner"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
