package th.co.glr.hr.pricingrequest;

import java.util.Set;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/** Canonical unit-basis codes shared across the pricing-request / factory-quote / costing aggregate: PER_SQM |
 * PER_PIECE | PER_BOX | PER_LINEAR_M. */
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

    /** Normalizes a free-typed unit value (e.g. a factory's own wording in an email reply) to one of the four
     * canonical codes above, accepting a handful of common synonyms. */
    public static String canonicalize(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldLabel + " ต้องไม่เว้นว่าง");
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case PER_SQM, "SQM", "SQ.M", "M2", "M²" -> PER_SQM;
            case PER_PIECE, "PIECE", "PCS", "PC", "EACH" -> PER_PIECE;
            case PER_BOX, "BOX" -> PER_BOX;
            case PER_LINEAR_M, "LINEAR_M", "LINEAR_METER", "METER", "METRE" -> PER_LINEAR_M;
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่รองรับค่า " + fieldLabel + " '" + value + "'");
        };
    }
}
