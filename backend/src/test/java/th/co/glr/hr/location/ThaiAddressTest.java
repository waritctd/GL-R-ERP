package th.co.glr.hr.location;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class ThaiAddressTest {
    @Test void formatsBangkokWithoutDuplicatingPrefixes() {
        assertThat(new ThaiAddress("88/8", "10", "1039", "103901", "10110", "กรุงเทพมหานคร", "เขต วัฒนา", "แขวง คลองเตยเหนือ").printable())
            .isEqualTo("88/8 แขวงคลองเตยเหนือ เขตวัฒนา กรุงเทพมหานคร 10110");
    }
    @Test void formatsProvinceOutsideBangkok() {
        assertThat(new ThaiAddress("123/4 ถนนนิมมานเหมินท์", "50", "5001", "500108", "50200", "จังหวัดเชียงใหม่", "อำเภอเมืองเชียงใหม่", "ตำบลสุเทพ").printable())
            .isEqualTo("123/4 ถนนนิมมานเหมินท์ ตำบลสุเทพ อำเภอเมืองเชียงใหม่ จังหวัดเชียงใหม่ 50200");
    }
}
