package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestItemDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.ImportRequestItemInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateEmailDraftRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;

/**
 * Plain unit test (no DB) pinning a hard boundary from import-request-per-factory-PLAN.md: nothing
 * IR-facing may carry price, discount, pricing mode, or wastage — those stay with {@code
 * FactoryQuoteService}/{@code PricingDecisionService}, visible only to import (in that OTHER
 * context) and the CEO. {@link ImportRequestDtos}'s own class Javadoc states the rule in prose;
 * this test makes it something a future field addition cannot violate without going red, by
 * inspecting the record components directly via reflection rather than trusting the comment to stay
 * accurate.
 */
class ImportRequestDtosNoPricingFieldsTest {

    /** Case-insensitive substrings that must never appear in a component name on these records. */
    private static final List<String> FORBIDDEN = List.of("price", "discount", "wastage", "mode", "cost");

    @Test
    void importRequestDtoCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportRequestDto.class);
    }

    @Test
    void importRequestItemDtoCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportRequestItemDto.class);
    }

    @Test
    void importRequestItemInputCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportRequestItemInput.class);
    }

    @Test
    void updateImportRequestRequestCarriesNoPricingFields() {
        assertNoForbiddenComponents(UpdateImportRequestRequest.class);
    }

    /** REVIEW ROUND 1, S6/owner decision 09-18 #3 §B: extended to the printed form's own render
     * input and to the order-email draft's edit request — both are as import-facing as the DTOs
     * above, and neither was covered before this branch added them. */
    @Test
    void importRequestFormDataCarriesNoPricingFields() {
        assertNoForbiddenComponents(ImportRequestFormData.class);
        assertNoForbiddenComponents(ImportRequestFormData.Line.class);
    }

    @Test
    void updateEmailDraftRequestCarriesNoPricingFields() {
        assertNoForbiddenComponents(UpdateEmailDraftRequest.class);
    }

    private static void assertNoForbiddenComponents(Class<?> record) {
        assertThat(record.isRecord()).as(record.getName() + " must be a record").isTrue();
        RecordComponent[] components = record.getRecordComponents();
        for (RecordComponent c : components) {
            String lower = c.getName().toLowerCase(Locale.ROOT);
            // REVIEW ROUND 2, S-A nit: "model" (e.g. ImportRequestItemDto#model, the line's raw
            // product-model text — the exact same non-pricing concept PricingRequestDtos#model and
            // PricingDecisionDtos#model already name "model") is a legitimate, pre-existing domain
            // term that happens to START with the same four letters as the forbidden "mode" token
            // (a PRICING mode, e.g. a hypothetical pricingMode/costMode enum) — "model".contains("mode")
            // is true by spelling coincidence, not by meaning. Strip "model" out before checking so
            // this stays a check for an actual pricing-mode field rather than a false positive on
            // every field literally named "model": a genuine "pricingMode"/"costMode" does not itself
            // contain the substring "model" (it has no trailing "l"), so this strip never hides one.
            String checked = lower.replace("model", "");
            assertThat(FORBIDDEN.stream().noneMatch(checked::contains))
                .as("%s.%s looks like a pricing field (checked against %s) — IR-facing DTOs must "
                    + "carry no price/discount/mode/wastage/cost, see ImportRequestDtos' class Javadoc",
                    record.getSimpleName(), c.getName(), FORBIDDEN)
                .isTrue();
        }
        assertThat(Arrays.stream(components)).isNotEmpty();
    }
}
