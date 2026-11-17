package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuotationRecipient {
    private QuotationRecipient() {}

    public static final String DESIGNER = "DESIGNER";
    public static final String OWNER = "OWNER";
    public static final String BUYER = "BUYER";
    public static final String UNSPECIFIED = "UNSPECIFIED";

    public static final Set<String> VALID = Set.of(DESIGNER, OWNER, BUYER, UNSPECIFIED);

    /** Quotation ↔ deal linking slice 2 (S2-B1): the three recipients a client may name on a direct quotation.
     * #UNSPECIFIED is the legacy bucket (V52) for pre-slice-2 rows and is never accepted from a client. */
    public static final Set<String> ADDRESSABLE = Set.of(DESIGNER, OWNER, BUYER);

    /** The quoting route, earliest party first. */
    public static final List<String> ROUTE = List.of(DESIGNER, OWNER, BUYER);

    /** True when {@code earlier} and {@code later} are both on the route and {@code earlier} comes strictly first. */
    public static boolean isStrictlyEarlier(String earlier, String later) {
        int a = ROUTE.indexOf(earlier);
        int b = ROUTE.indexOf(later);
        return a >= 0 && b >= 0 && a < b;
    }

    /** The Thai label written to sales.quotation.recipient_label for a direct quotation — verbatim the frontend's
     * quotationMeta.QUOTATION_RECIPIENT_OPTIONS labels (the only existing Thai convention for these three codes;
     * mockApi.dealQuotations.create/update already writes exactly these). */
    private static final Map<String, String> THAI_LABELS = Map.of(
        DESIGNER, "ผู้ออกแบบ",
        OWNER, "เจ้าของโครงการ",
        BUYER, "ผู้ซื้อ / ผู้รับเหมา");

    public static boolean isValid(String value) {
        return VALID.contains(value);
    }

    public static boolean isAddressable(String value) {
        return value != null && ADDRESSABLE.contains(value);
    }

    /** The Thai label for an {@link #ADDRESSABLE} code, else {@code null}. */
    public static String thaiLabel(String code) {
        return code == null ? null : THAI_LABELS.get(code);
    }
}
