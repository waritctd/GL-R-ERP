package th.co.glr.hr.importrequest;

import java.util.Locale;
import java.util.Map;

/** Fixed-in-code default lead time by factory ISO 3166-1 alpha-2 country code, autofilled onto a NEW ใบขอซื้อ
 * DRAFT. */
public final class LeadTimeDefaults {

    public record Range(int minDays, int maxDays) {}

    private static final Map<String, Range> BY_ISO_COUNTRY_CODE = Map.of(
        "IT", new Range(75, 90),
        "ES", new Range(75, 90),
        "CN", new Range(30, 45),
        "TH", new Range(3, 7)
    );

    /** The default range for isoCountryCode, or null when this country has no fixed default — including 'ZZ' and
     * any other unmapped code, by design (see class Javadoc). */
    public static Range forCountry(String isoCountryCode) {
        if (isoCountryCode == null) {
            return null;
        }
        return BY_ISO_COUNTRY_CODE.get(isoCountryCode.strip().toUpperCase(Locale.ROOT));
    }

    private LeadTimeDefaults() {}
}
