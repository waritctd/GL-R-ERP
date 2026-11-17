package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The shared customer-name and document-number folds (pipeline import here; ReceiptMatcher on the cash branch). */
class SalesNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "บริษัท สมมุติ จำกัด|สมมุติ", "บจก. สมมุติ|สมมุติ", "บริษัท สมมุติ จำกัด (มหาชน)|สมมุติ",
        "Sample Co.,Ltd.|sample", "  SAMPLE   Company Limited |sample", "ห้างหุ้นส่วนจำกัด ทดลอง|ทดลอง",
    })
    void customerFoldsCompanyFormWordsCaseAndSpaces(String raw, String folded) {
        assertThat(SalesNames.normaliseCustomer(raw)).isEqualTo(folded);
    }

    @Test
    void blankCustomerIsEmpty() {
        assertThat(SalesNames.normaliseCustomer(null)).isEmpty();
        assertThat(SalesNames.normaliseCustomer("  ")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "QT-2026-0201-7|QT-2026-0201", "qt-2026-0201-8|QT-2026-0201", " QT - 2026-0201 |QT-2026-0201",
        "QT-2026-0201|QT-2026-0201",
    })
    void documentNumberDropsTheRevisionSuffixButNeverTheSequence(String raw, String base) {
        assertThat(SalesNames.normaliseDoc(raw)).isEqualTo(base);
    }
}
