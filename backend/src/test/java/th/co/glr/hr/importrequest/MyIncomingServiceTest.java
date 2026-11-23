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
}
