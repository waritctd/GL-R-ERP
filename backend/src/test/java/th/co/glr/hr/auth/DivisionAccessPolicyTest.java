package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DivisionAccessPolicyTest {

    @Test
    void executiveDivisionDerivesCeo() {
        assertThat(DivisionAccessPolicy.roleFor(record("MD", "MD-ผู้บริหารระดับสูง", "กรรมการผู้จัดการ"))).isEqualTo("ceo");
        assertThat(DivisionAccessPolicy.roleFor(record("MD", "MD-ผู้บริหารระดับสูง", "ประธานกรรมการ"))).isEqualTo("ceo");
    }

    @Test
    void executivePositionDerivesCeoRegardlessOfDivision() {
        // A กรรมการ-family title outranks the division mapping.
        assertThat(DivisionAccessPolicy.roleFor(record("AC", "AC-บัญชี", "กรรมการ"))).isEqualTo("ceo");
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "กรรมการผู้จัดการ"))).isEqualTo("ceo");
    }

    @Test
    void adminDivisionDerivesAdmin() {
        assertThat(DivisionAccessPolicy.roleFor(record("ADMIN", "ADMIN-ผู้ดูแลระบบ", "พนักงาน"))).isEqualTo("admin");
    }

    @Test
    void hrDivisionDerivesHr() {
        assertThat(DivisionAccessPolicy.roleFor(record("HR", "HR-บุคคล", "พนักงาน"))).isEqualTo("hr");
    }

    @Test
    void foreignPurchasingDivisionDerivesImport() {
        assertThat(DivisionAccessPolicy.roleFor(record("PCIM", "PCIM-จัดซื้อต่างประเทศ", "พนักงาน"))).isEqualTo("import");
    }

    @Test
    void salesDivisionManagerDerivesSalesManager() {
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "ผู้จัดการฝ่ายการตลาด"))).isEqualTo("sales_manager");
        // Assistant managers count too (title contains ผู้จัดการ).
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "ผู้ช่วยผู้จัดการฝ่ายขาย"))).isEqualTo("sales_manager");
    }

    @Test
    void salesDivisionNonManagerDerivesSales() {
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "พนักงาน"))).isEqualTo("sales");
    }

    @Test
    void nonSalesManagerKeepsBaseEmployeeRole() {
        // A ฝ่าย manager outside sales is an employee by role; manager power comes from isManager.
        assertThat(DivisionAccessPolicy.roleFor(record("AC", "AC-บัญชี", "ผู้จัดการฝ่ายบัญชี"))).isEqualTo("employee");
    }

    @Test
    void nullDivisionAndPositionDerivesEmployeeWithoutNpe() {
        assertThat(DivisionAccessPolicy.roleFor(record(null, null, null))).isEqualTo("employee");
    }

    @Test
    void divisionCodeFallsBackToNamePrefix() {
        // source_code missing → prefix of the ฝ่าย name is used.
        assertThat(DivisionAccessPolicy.roleFor(record(null, "SA-ฝ่ายขาย", "พนักงาน"))).isEqualTo("sales");
    }

    @Test
    void isManagerDetectsManagerTitlesIncludingAssistants() {
        assertThat(DivisionAccessPolicy.isManager(record("AC", "AC-บัญชี", "ผู้จัดการฝ่ายบัญชี"))).isTrue();
        assertThat(DivisionAccessPolicy.isManager(record("WH", "WH-คลังสินค้า", "ผู้ช่วยผู้จัดการส่วนคลังสินค้า"))).isTrue();
        assertThat(DivisionAccessPolicy.isManager(record("MD", "MD-ผู้บริหารระดับสูง", "กรรมการผู้จัดการ"))).isTrue();
    }

    @Test
    void isManagerFalseForNonManagers() {
        assertThat(DivisionAccessPolicy.isManager(record("AC", "AC-บัญชี", "พนักงาน"))).isFalse();
        assertThat(DivisionAccessPolicy.isManager(record(null, null, null))).isFalse();
    }

    private EmployeeLoginRecord record(String divisionCode, String divisionName, String position) {
        return new EmployeeLoginRecord(
            1L, "E1", "e1@glr.co.th", "E One", true,
            1L, divisionCode, divisionName, position, LocalDate.now(), "hash", false);
    }
}
