package th.co.glr.hr.pricingrequest;

import java.util.Set;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Canonical unit-basis codes shared across the pricing-request / factory-quote / costing
 * aggregate: {@code PER_SQM | PER_PIECE | PER_BOX | PER_LINEAR_M}.
 *
 * <p>Extracted (financial-integrity review remediation, commit 3) from what used to be a
 * private {@code FactoryQuoteService.canonicalUnit} copy, so {@code FactoryQuoteService},
 * {@code PricingCostingService} and {@code PricingRequestService} all normalize/validate unit
 * values against exactly one definition instead of three independently-maintained copies that
 * could silently drift apart.
 */
public final class UnitBasis {
    public static final String PER_SQM = "PER_SQM";
    public static final String PER_PIECE = "PER_PIECE";
    public static final String PER_BOX = "PER_BOX";
    public static final String PER_LINEAR_M = "PER_LINEAR_M";

    public static final Set<String> VALUES = Set.of(PER_SQM, PER_PIECE, PER_BOX, PER_LINEAR_M);

    private UnitBasis() {}

    /** True if {@code value} is already exactly one of the four canonical codes above. */
    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    /**
     * Normalizes a free-typed unit value (e.g. a factory's own wording in an email reply) to
     * one of the four canonical codes above, accepting a handful of common synonyms.
     * {@code fieldLabel} names the field in the thrown message — callers that pre-date this
     * extraction (e.g. {@code FactoryQuoteService}) pass the same label they always threw, so
     * this refactor changes no externally-visible message text for them.
     *
     * @throws ApiException 400 if {@code value} is blank, 422 if it does not match any known
     *         canonical code or synonym.
     */
    public static String canonicalize(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldLabel + " must not be blank");
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case PER_SQM, "SQM", "SQ.M", "M2", "M²" -> PER_SQM;
            case PER_PIECE, "PIECE", "PCS", "PC", "EACH" -> PER_PIECE;
            case PER_BOX, "BOX" -> PER_BOX;
            case PER_LINEAR_M, "LINEAR_M", "LINEAR_METER", "METER", "METRE" -> PER_LINEAR_M;
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unsupported " + fieldLabel + " '" + value + "'");
        };
    }
}
