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
    void hrDivisionDerivesHr() {
        assertThat(DivisionAccessPolicy.roleFor(record("HR", "HR-บุคคล", "พนักงาน"))).isEqualTo("hr");
    }

    @Test
    void salesDivisionManagerDerivesSalesManager() {
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "ผู้จัดการฝ่ายการตลาด"))).isEqualTo("sales_manager");
        // Assistant managers count too (title contains ผู้จัดการ).
        assertThat(DivisionAccessPolicy.roleFor(record("SA", "SA-ฝ่ายขาย", "ผู้ช่วยผู้จัดการฝ่ายขาย"))).isEqualTo("sales_manager");
    }

    // ── can_create_quotation grant: an employee-tier role acts as sales (owner ruling 2026-10-14) ──

    @Test
    void grantHolderInQcWarehouseOrUnmappedDivisionActsAsSales() {
        assertThat(DivisionAccessPolicy.roleFor(record("QC", "QC-QC&ISO", "พนักงาน", true))).isEqualTo("sales");
        assertThat(DivisionAccessPolicy.roleFor(record("WH", "WH-คลังสินค้า", "พนักงาน", true))).isEqualTo("sales");
        assertThat(DivisionAccessPolicy.roleFor(record("OTHER", "ฝ่ายอื่น", "พนักงาน", true))).isEqualTo("sales");
    }

    private EmployeeLoginRecord record(String divisionCode, String divisionName, String position) {
        return record(divisionCode, divisionName, position, false);
    }

    private EmployeeLoginRecord record(String divisionCode, String divisionName, String position, boolean canCreateQuotation) {
        return new EmployeeLoginRecord(
            1L, "E1", "e1@glr.co.th", "E One", true,
            1L, divisionCode, divisionName, position, LocalDate.now(), "hash", false, canCreateQuotation);
    }
}
