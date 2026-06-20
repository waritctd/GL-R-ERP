package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmployeeStatusTest {
    @Test
    void mapsKnownStatusesToBusinessLabelsAndTones() {
        assertThat(EmployeeStatus.name("ACT")).isEqualTo("ทำงานปกติ");
        assertThat(EmployeeStatus.englishName("PRB")).isEqualTo("Probation");
        assertThat(EmployeeStatus.tone("RSG")).isEqualTo("danger");
    }

    @Test
    void treatsBlankStatusAsActiveAndResignedAsInactive() {
        assertThat(EmployeeStatus.active(null)).isTrue();
        assertThat(EmployeeStatus.active("")).isTrue();
        assertThat(EmployeeStatus.active("rsg")).isFalse();
    }
}
