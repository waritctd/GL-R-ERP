package th.co.glr.hr.importrequest;

import java.util.Locale;
import java.util.Map;

/**
 * Fixed-in-code default lead time (estimated days to arrival) by factory ISO 3166-1 alpha-2
 * country code, autofilled onto a NEW ใบขอซื้อ DRAFT (V184, owner decision 09-18 #2: "they also
 * need to automatically fill estimations of how many days it'll take for the item to arrive like
 * the direct quote form").
 *
 * <p><strong>Mirrors, but does NOT share code with,</strong> {@code
 * frontend/src/features/quotations/quotationMeta.js}'s {@code ORIGIN_COUNTRY_OPTIONS} + {@code
 * ORIGIN_COUNTRY_BY_ISO_CODE} — two independent copies of the same four numbers, one per runtime.
 * A Java constant and a JS constant cannot share a single source of truth without a build-time
 * codegen step this codebase does not have, so <strong>there is no guard against the two
 * drifting</strong>: if the owner asks for these numbers to change, BOTH sides must be edited by
 * hand, and nothing here will fail loudly if only one is. IT/ES 75–90 days, CN 30–45, TH 3–7 (the
 * frontend's "ไทย-สต็อก" bucket) — every other code, INCLUDING {@code 'ZZ'} (อื่นๆ, V184's country
 * catch-all) and anything unmapped, gets no default at all: the rep types the real number in
 * rather than being handed a guess for a country nobody actually characterised.
 */
public final class LeadTimeDefaults {

    public record Range(int minDays, int maxDays) {}

    private static final Map<String, Range> BY_ISO_COUNTRY_CODE = Map.of(
        "IT", new Range(75, 90),
        "ES", new Range(75, 90),
        "CN", new Range(30, 45),
        "TH", new Range(3, 7)
    );

    /**
     * The default range for {@code isoCountryCode}, or {@code null} when this country has no fixed
     * default — including {@code 'ZZ'} and any other unmapped code, by design (see class Javadoc).
     */
    public static Range forCountry(String isoCountryCode) {
        if (isoCountryCode == null) {
            return null;
        }
        return BY_ISO_COUNTRY_CODE.get(isoCountryCode.strip().toUpperCase(Locale.ROOT));
    }

    private LeadTimeDefaults() {}
}
