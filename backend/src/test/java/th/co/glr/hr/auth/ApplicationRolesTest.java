package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationRolesTest {
    @Test
    void normalizesAndAllowsOnlyKnownApplicationRoles() {
        assertThat(ApplicationRoles.requireAllowed(" HR ")).isEqualTo("hr");
        assertThat(ApplicationRoles.isAllowed("employee")).isTrue();
        assertThat(ApplicationRoles.isAllowed("sales_manager")).isTrue();
        assertThat(ApplicationRoles.isAllowed("account")).isTrue();
        assertThat(ApplicationRoles.isAllowed("warehouse")).isTrue();
        assertThat(ApplicationRoles.isAllowed("qc")).isTrue();
        assertThat(ApplicationRoles.isAllowed("ceo")).isTrue();
        assertThat(ApplicationRoles.isAllowed("director")).isFalse();
        assertThat(ApplicationRoles.isAllowed("owner")).isFalse();
    }

}
