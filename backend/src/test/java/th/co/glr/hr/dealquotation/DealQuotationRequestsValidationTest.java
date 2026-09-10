package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;

/**
 * M4 — bean-validation bounds on {@link DealQuotationRequests}. Runs the SAME {@code
 * jakarta.validation} validator {@code @Valid @RequestBody} applies at the controller, without a
 * Spring context — same pattern as {@code auth/LoginRequestNormalizationTest}.
 */
class DealQuotationRequestsValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @Test
    void validItem_hasNoViolations() {
        assertThat(VALIDATOR.validate(validItem().build())).isEmpty();
    }

    @Test
    void discountPctOutOfRange_isRejected() {
        assertViolated(validItem().discountPct(new BigDecimal("150")).build());
        assertViolated(validItem().discountPct(new BigDecimal("-1")).build());
    }

    @Test
    void unitPriceZeroOrNegative_isRejected() {
        assertViolated(validItem().unitPrice(BigDecimal.ZERO).build());
        assertViolated(validItem().unitPrice(new BigDecimal("-5")).build());
    }

    @Test
    void unitPriceNull_isRejected() {
        assertViolated(validItem().unitPrice(null).build());
    }

    @Test
    void areaSqmOverMax_isRejected() {
        assertViolated(validItem().areaSqm(new BigDecimal("1000000")).build());
    }

    @Test
    void piecesInputOverMax_isRejected() {
        assertViolated(validItem().piecesInput(1_000_001).build());
    }

    @Test
    void piecesPerBoxOutOfRange_isRejected() {
        assertViolated(validItem().piecesPerBox(0).build());
        assertViolated(validItem().piecesPerBox(1001).build());
    }

    @Test
    void modelColorTextureSizeBrand_allowUpTo255Chars_matchingTheLiveColumnWidth() {
        // V162 widened sales.quotation_item.model/color/texture/size to VARCHAR(255) (brand was
        // always 255) -- a stale max=80 bound here would 400 a value the column happily stores.
        String value255 = "x".repeat(255);
        ItemInput item = validItem().model(value255).color(value255).texture(value255)
            .sizeText(value255).brand(value255).build();
        assertThat(VALIDATOR.validate(item)).isEmpty();

        assertViolated(validItem().model("x".repeat(256)).build());
        assertViolated(validItem().color("x".repeat(256)).build());
        assertViolated(validItem().texture("x".repeat(256)).build());
        assertViolated(validItem().sizeText("x".repeat(256)).build());
        assertViolated(validItem().brand("x".repeat(256)).build());
    }

    @Test
    void validUpsertRequest_hasNoViolations() {
        assertThat(VALIDATOR.validate(validUpsert().build())).isEmpty();
    }

    @Test
    void depositPercentOutOfRange_isRejected() {
        assertViolated(validUpsert().depositPercent(101).build());
        assertViolated(validUpsert().depositPercent(-1).build());
    }

    @Test
    void remainderModeMustBeCreditOrOnDelivery() {
        assertViolated(validUpsert().remainderMode("SOMETHING_ELSE").build());
        // null is allowed -- @Pattern only validates a non-null value (same discipline as
        // CLAUDE.md's ${VAR:default} note on absent-vs-empty).
        assertThat(VALIDATOR.validate(validUpsert().remainderMode(null).build())).isEmpty();
        assertThat(VALIDATOR.validate(validUpsert().remainderMode("CREDIT").build())).isEmpty();
        assertThat(VALIDATOR.validate(validUpsert().remainderMode("ON_DELIVERY").build())).isEmpty();
    }

    @Test
    void creditDaysOutOfRange_isRejected() {
        assertViolated(validUpsert().creditDays(366).build());
        assertViolated(validUpsert().creditDays(-1).build());
    }

    @Test
    void validityDaysOutOfRange_isRejected() {
        assertViolated(validUpsert().validityDays(0).build());
        assertViolated(validUpsert().validityDays(366).build());
    }

    private void assertViolated(Object candidate) {
        assertThat(VALIDATOR.validate(candidate)).as(candidate.toString()).isNotEmpty();
    }

    private ItemBuilder validItem() {
        return new ItemBuilder();
    }

    private UpsertBuilder validUpsert() {
        return new UpsertBuilder();
    }

    /** Minimal wither around {@link ItemInput} so each test can mutate one field without
     * re-listing all 21 positional arguments. */
    private static final class ItemBuilder {
        private String locationLabel = "ชั้น 1";
        private Long catalogPriceId = null;
        private String productCode = "MP-001";
        private String brand = "Brand A";
        private String model = "Model A";
        private String color = "White";
        private String texture = "Matte";
        private String sizeText = "60x60";
        private BigDecimal thicknessMm = new BigDecimal("2");
        private BigDecimal sqmPerPiece = new BigDecimal("0.36");
        private String quantityMode = WastageCalculator.QUANTITY_MODE_PIECES;
        private BigDecimal areaSqm = null;
        private Integer piecesInput = 10;
        private String wastageMode = WastageCalculator.WASTAGE_MODE_NONE;
        private BigDecimal wastageValue = null;
        private Integer piecesPerBox = 4;
        private BigDecimal unitPrice = new BigDecimal("100.00");
        private BigDecimal discountPct = BigDecimal.ZERO;
        private String originCountry = "ไทย-สต็อก";
        private Integer leadTimeMinDays = 30;
        private Integer leadTimeMaxDays = 45;
        private String itemNotes = null;

        ItemBuilder model(String v) { model = v; return this; }
        ItemBuilder color(String v) { color = v; return this; }
        ItemBuilder texture(String v) { texture = v; return this; }
        ItemBuilder sizeText(String v) { sizeText = v; return this; }
        ItemBuilder brand(String v) { brand = v; return this; }
        ItemBuilder discountPct(BigDecimal v) { discountPct = v; return this; }
        ItemBuilder unitPrice(BigDecimal v) { unitPrice = v; return this; }
        ItemBuilder areaSqm(BigDecimal v) { areaSqm = v; return this; }
        ItemBuilder piecesInput(Integer v) { piecesInput = v; return this; }
        ItemBuilder piecesPerBox(Integer v) { piecesPerBox = v; return this; }

        ItemInput build() {
            return new ItemInput(locationLabel, catalogPriceId, productCode, brand, model, color, texture,
                sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode,
                wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes);
        }
    }

    /** Same wither shim for {@link UpsertDealQuotationRequest}. */
    private static final class UpsertBuilder {
        private String deptCode = "P003";
        private String unitCode = "D002";
        private LocalDate offerDate = LocalDate.now();
        private Integer depositPercent = 30;
        private String remainderMode = "CREDIT";
        private Integer creditDays = 30;
        private Integer validityDays = 30;
        private String customerNotes = null;
        private List<ItemInput> items = List.of(new ItemBuilder().build());

        UpsertBuilder depositPercent(Integer v) { depositPercent = v; return this; }
        UpsertBuilder remainderMode(String v) { remainderMode = v; return this; }
        UpsertBuilder creditDays(Integer v) { creditDays = v; return this; }
        UpsertBuilder validityDays(Integer v) { validityDays = v; return this; }

        UpsertDealQuotationRequest build() {
            return new UpsertDealQuotationRequest(null, deptCode, unitCode, offerDate, depositPercent, remainderMode,
                creditDays, validityDays, customerNotes, items);
        }
    }
}
