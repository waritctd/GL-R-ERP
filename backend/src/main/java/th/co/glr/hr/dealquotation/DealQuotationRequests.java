package th.co.glr.hr.dealquotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DealQuotationRequests {
    private DealQuotationRequests() {}

    /** One item row, as typed by Sales in the editor. Server recomputes every derived number —
     * see {@link WastageCalculator} — never trusts a client-sent total.
     *
     * <p>M4: {@code model}/{@code color}/{@code texture}/{@code sizeText}/{@code brand} are
     * capped at 255, matching {@code sales.quotation_item}'s LIVE column widths — V49 created them
     * at VARCHAR(80), but V162 widened model/color/texture/size (and brand was always 255); a
     * stale {@code max = 80} here would 400 a value the column happily stores. {@code
     * wastageValue}'s bound is MODE-dependent (percent vs. flat piece count) and so is enforced in
     * {@link WastageCalculator#calculate}, not here — see that class's own M4 note.
     */
    public record ItemInput(
        @Size(max = 255) String locationLabel,
        Long catalogPriceId,
        @Size(max = 255) String productCode,
        @Size(max = 255) String brand,
        @Size(max = 255) String model,
        @Size(max = 255) String color,
        @Size(max = 255) String texture,
        @Size(max = 255) String sizeText,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        // "AREA" | "PIECES" — see QuantityMode.
        @NotBlank String quantityMode,
        @DecimalMax("999999") BigDecimal areaSqm,
        @Max(1_000_000) Integer piecesInput,
        // "PERCENT" | "PIECES" | "NONE" — see WastageMode.
        @NotBlank String wastageMode,
        BigDecimal wastageValue,
        @Min(1) @Max(1000) Integer piecesPerBox,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        @DecimalMin("0") @DecimalMax("100") BigDecimal discountPct,
        @Size(max = 40) String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        @Size(max = 4000) String itemNotes
    ) {}

    public record UpsertDealQuotationRequest(
        @Size(max = 20) String deptCode,
        @Size(max = 20) String unitCode,
        LocalDate offerDate,
        @Min(0) @Max(100) Integer depositPercent,
        // "CREDIT" | "ON_DELIVERY"
        @Pattern(regexp = "CREDIT|ON_DELIVERY", message = "ต้องเป็น CREDIT หรือ ON_DELIVERY")
        String remainderMode,
        @Min(0) @Max(365) Integer creditDays,
        @Min(1) @Max(365) Integer validityDays,
        @Size(max = 4000) String customerNotes,
        @NotEmpty List<@Valid ItemInput> items
    ) {}

    public record ApproveRequest(@Size(max = 2000) String note) {}

    public record RejectRequest(@NotBlank @Size(max = 2000) String reason) {}

    public record CancelRequest(@Size(max = 2000) String reason) {}
}
