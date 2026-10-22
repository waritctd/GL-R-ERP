package th.co.glr.hr.importdeal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealCommentDto;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealDto;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealItemDto;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealResponse;

/**
 * Plain unit test (no DB): the import per-deal view must be structurally incapable of carrying a
 * price, cost, margin, discount, commission or weighting field. Inspects the record components by
 * reflection, so a future field addition cannot violate the rule without going red — the same
 * technique {@code ImportRequestDtosNoPricingFieldsTest} uses for the IR DTOs.
 */
class ImportDealDtoNoPricingFieldsTest {

    private static final List<String> FORBIDDEN = List.of(
        "price", "cost", "discount", "commission", "margin", "multiplier", "wastage", "raw", "mode");

    @Test
    void dealDtoCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportDealDto.class);
    }

    @Test
    void itemDtoCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportDealItemDto.class);
    }

    @Test
    void commentDtoCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportDealCommentDto.class);
    }

    @Test
    void responseEnvelopeCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportDealResponse.class);
    }

    private static void assertNoForbiddenComponents(Class<?> record) {
        assertThat(record.isRecord()).as(record.getName() + " must be a record").isTrue();
        for (RecordComponent c : record.getRecordComponents()) {
            // "model" is the product-model text, not a pricing mode — same strip the IR test does.
            String checked = c.getName().toLowerCase(Locale.ROOT).replace("model", "");
            assertThat(FORBIDDEN.stream().noneMatch(checked::contains))
                .as("%s.%s looks like a pricing field — the import deal view must never carry one",
                    record.getSimpleName(), c.getName())
                .isTrue();
        }
    }
}
