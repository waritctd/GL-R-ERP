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
    void accountingDivisionDerivesAccount() {
        // AC-ฝ่ายบัญชี confirms money receipts (deposit/final payment) on sales tickets.
        assertThat(DivisionAccessPolicy.roleFor(record("AC", "AC-ฝ่ายบัญชี", "พนักงาน"))).isEqualTo("account");
        // Managers in accounting keep the account role; manager power comes from isManager.
        assertThat(DivisionAccessPolicy.roleFor(record("AC", "AC-บัญชี", "ผู้จัดการฝ่ายบัญชี"))).isEqualTo("account");
        // Legacy import rows have no source_code — the AC- name prefix must still map.
        assertThat(DivisionAccessPolicy.roleFor(record(null, "AC-บัญชี", "พนักงาน"))).isEqualTo("account");
    }

    @Test
    void nonSalesManagerKeepsBaseEmployeeRole() {
        // A ฝ่าย manager outside sales/accounting is an employee by role; manager power comes from isManager.
        assertThat(DivisionAccessPolicy.roleFor(record("WH", "WH-คลังสินค้า", "ผู้จัดการฝ่ายคลังสินค้า"))).isEqualTo("employee");
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
    void canonicalManagerTitleDerivesSameRolesAsBakedFormAfterV30() {
        // V30 collapses ฝ่าย-baked titles (ผู้จัดการฝ่ายขาย/ผู้จัดการฝ่ายผลิต) to canonical "ผู้จัดการ".
        // Scope/role must stay division-driven and unchanged for every affected employee.
        // Sales manager: was ผู้จัดการฝ่ายขาย → now ผู้จัดการ, still sales_manager.
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "ผู้จัดการ"))).isEqualTo("sales_manager");
        assertThat(DivisionAccessPolicy.isManager(record("SA", "SA-ฝ่ายขาย", "ผู้จัดการ"))).isTrue();
        // Production manager: was ผู้จัดการฝ่ายผลิต → now ผู้จัดการ, base role employee but still a manager.
        assertThat(DivisionAccessPolicy.roleFor(record("PROD", "PROD-ฝ่ายผลิต", "ผู้จัดการ"))).isEqualTo("employee");
        assertThat(DivisionAccessPolicy.isManager(record("PROD", "PROD-ฝ่ายผลิต", "ผู้จัดการ"))).isTrue();
    }

    @Test
    void canonicalAssistantManagerStaysManagerButDistinctFromExecutive() {
        // V30 keeps assistant managers distinct as canonical "ผู้ช่วยผู้จัดการ": still a manager, never ceo.
        assertThat(DivisionAccessPolicy.isManager(record("PROD", "PROD-ฝ่ายผลิต", "ผู้ช่วยผู้จัดการ"))).isTrue();
        assertThat(DivisionAccessPolicy.roleFor(record("PROD", "PROD-ฝ่ายผลิต", "ผู้ช่วยผู้จัดการ"))).isEqualTo("employee");
    }

    @Test
    void managingDirectorTitleUntouchedByNormalizationStillCeo() {
        // V30 must NOT rewrite กรรมการผู้จัดการ (it starts with กรรมการ, not ผู้จัดการ); it stays an executive.
        assertThat(DivisionAccessPolicy.roleFor(record("MD", "MD-ผู้บริหารระดับสูง", "กรรมการผู้จัดการ"))).isEqualTo("ceo");
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
