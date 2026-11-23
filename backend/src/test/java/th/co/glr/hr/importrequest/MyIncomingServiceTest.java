package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MyIncomingServiceTest {

    @Test
    void aProjectNameThatCarriesTheCustomerFallsBackToTheDealCode() {
        // equal, contained ("บจก. X สาขา Y"), or the same after company words and punctuation are dropped
        assertThat(MyIncomingService.hiddenCustomerSafe("ทดสอบ เอ็กซ์", "บริษัท ทดสอบ เอ็กซ์ จำกัด", "PR-1")).isEqualTo("PR-1");
        assertThat(MyIncomingService.hiddenCustomerSafe("บจก. ทดสอบ เอ็กซ์ สาขา วาย", "ทดสอบ เอ็กซ์", "PR-1")).isEqualTo("PR-1");
        assertThat(MyIncomingService.hiddenCustomerSafe("TEST CUSTOMER CO., LTD. branch 2", "Test Customer Co., Ltd.", "PR-1")).isEqualTo("PR-1");
        // a very short customer name hides more, never less
        assertThat(MyIncomingService.hiddenCustomerSafe("อาคาร เอ", "เอ", "PR-1")).isEqualTo("PR-1");
    }

    @Test
    void anUnrelatedProjectNameIsKept_andNoNameMeansTheCode() {
        assertThat(MyIncomingService.hiddenCustomerSafe("โครงการ คอนโดริมน้ำ", "ทดสอบ เอ็กซ์", "PR-1")).isEqualTo("โครงการ คอนโดริมน้ำ");
        assertThat(MyIncomingService.hiddenCustomerSafe("โครงการ คอนโดริมน้ำ", null, "PR-1")).isEqualTo("โครงการ คอนโดริมน้ำ");
        assertThat(MyIncomingService.hiddenCustomerSafe(" ", "ทดสอบ เอ็กซ์", "PR-1")).isEqualTo("PR-1");
        assertThat(MyIncomingService.hiddenCustomerSafe(null, "ทดสอบ เอ็กซ์", "PR-1")).isEqualTo("PR-1");
    }

    private static th.co.glr.hr.auth.UserPrincipal as(String role) {
        return new th.co.glr.hr.auth.UserPrincipal(1L, "t@test.invalid", "ทดสอบ", role, null, true, null, false, null, false);
    }

    @Test
    void aRoundLabelIsRealOnlyForImportAndTheCeo() {
        assertThat(IncomingService.roundLabelFor(as("sales"), 42, "PI-TEST-1")).isEqualTo("รอบขนส่ง #42");
        assertThat(IncomingService.roundLabelFor(as("sales_manager"), 42, "PI-TEST-1")).isEqualTo("รอบขนส่ง #42");
        assertThat(IncomingService.roundLabelFor(as("import"), 42, "PI-TEST-1")).isEqualTo("PI-TEST-1");
        assertThat(IncomingService.roundLabelFor(as("ceo"), 42, "PI-TEST-1")).isEqualTo("PI-TEST-1");
    }
}
