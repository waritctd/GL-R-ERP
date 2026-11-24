package th.co.glr.hr.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerPrintRuleTest {
    private static final String HQ = "สำนักงานใหญ่";
    private static final String BANK_BUILDING = "อาคารธนาคารตัวอย่าง " + HQ;

    @Test
    void anAddressThatOnlyEndsWithTheWordIsNeverCut() {
        assertThat(CustomerPrintRule.addressOnly(BANK_BUILDING, HQ, null)).isEqualTo(BANK_BUILDING);
        assertThat(CustomerPrintRule.addressOnly(HQ, HQ, null)).isNull();
        assertThat(CustomerPrintRule.joinedAddress(BANK_BUILDING, null, BANK_BUILDING, HQ)).isEqualTo(BANK_BUILDING);
        String noSpace = "อาคารธนาคารตัวอย่าง" + HQ;
        assertThat(CustomerPrintRule.joinedAddress(noSpace, null, "ที่อื่น", HQ)).isEqualTo(noSpace);
        assertThat(CustomerPrintRule.joinedAddress(BANK_BUILDING, null, null, HQ)).isEqualTo(BANK_BUILDING); // typed, no master address
    }

    @Test
    void anOldNoticeLosesOnlyTheCustomersOwnJoinedDefaultBranch() {
        assertThat(CustomerPrintRule.joinedAddress("99 ถนนตัวอย่าง " + HQ, null, "99 ถนนตัวอย่าง", HQ)).isEqualTo("99 ถนนตัวอย่าง");
        assertThat(CustomerPrintRule.joinedAddress(BANK_BUILDING + " " + HQ, null, BANK_BUILDING, HQ)).isEqualTo(BANK_BUILDING);
        assertThat(CustomerPrintRule.joinedAddress("99 ถนนตัวอย่าง " + HQ, null, "99 ถนนตัวอย่าง", "สาขา 00001"))
            .isEqualTo("99 ถนนตัวอย่าง " + HQ);
        assertThat(CustomerPrintRule.joinedAddress("99 ถนนตัวอย่าง " + HQ, "0000000000001", "99 ถนนตัวอย่าง", HQ))
            .isEqualTo("99 ถนนตัวอย่าง " + HQ);
    }

    @Test
    void aPersonsIdPrintsNoBranch() {
        assertThat(CustomerPrintRule.addressWithBranch("12 ซอยตัวอย่าง", HQ, "3100000000074")).isEqualTo("12 ซอยตัวอย่าง");
        assertThat(CustomerPrintRule.addressWithBranch("12 ซอยตัวอย่าง", HQ, "0105500000074")).isEqualTo("12 ซอยตัวอย่าง " + HQ);
    }
}
