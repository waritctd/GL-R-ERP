package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SPEC-PREFILL.md ladder A: two ways to SUGGEST a thickness for a catalog row that has none —
 * "sibling products" ({@link #fromSiblings}) and "box weight" ({@link #fromBoxWeight}) — used only
 * once the catalog chain ({@code product_prices.thickness_mm}, then {@code
 * collection_thickness_default}, then {@code pricing_request_item.thickness_mm_override}) has
 * already failed to resolve one for a line. Every method here is a pure function over plain
 * values: no database access, no Spring wiring beyond the {@code @Component} annotation that lets
 * {@code PricingRequestThicknessSuggestionService} inject it, and no side effects — rendering a
 * suggestion this class returns writes NOTHING; only a human accepting it through {@code
 * PricingRequestItemThicknessService#setItemThickness} does (owner's own framing, SPEC-PREFILL.md:
 * "An ESTIMATED value must never become stored data without the user having seen and accepted
 * it").
 *
 * <h2>Per-factory density and confidence — MEASURED, 2026-09-06, do not invent or re-derive</h2>
 *
 * Measured on prod ({@code tdyzcqzxmhtxpbouewud}) over all 12,936 ACTIVE {@code
 * price_catalog.product_prices} rows carrying BOTH a box weight ({@code kg_per_box}/{@code
 * sqm_per_box}) and a known {@code thickness_mm} — i.e. exactly the rows that let this formula's
 * output be checked against the truth. "Band-correct" is the round-UP snap landing in the SAME one
 * of the four freight bands ({@code [3,8) [8,12) [12,17) [17,21)}, V109) as the row's real
 * thickness; "UNDER-quotes" is the failure mode that actually costs money — a suggested thickness
 * whose freight band is LOWER than the true one, which under-charges freight that is never
 * recovered. Round-to-nearest was measured too and rejected: ~96% of ITS errors are in that same
 * under-quoting direction, because nominal tile thickness is systematically ≥ the weight-implied
 * value (tiles are made a little thicker than their nominal spec, essentially never thinner).
 *
 * <pre>
 * factory   density kg/m³   validation rows   band-correct   UNDER-quotes   confidence
 * Padana         2333             6,641           97.73%        0.80%       HIGH
 * LEA            2328             1,551           85.43%        0.45%       MEDIUM
 * Panaria        2348             1,419           81.18%        5.92%       MEDIUM
 * CDE            2282             1,640           86.77%        2.20%       MEDIUM
 * REFIN          2207             1,611           82.12%       17.38%       LOW
 * CITY           2250                74           79.73%       20.27%       LOW
 * Vives    2315 (pooled)               0               —            —       UNVALIDATED
 * Equipe             —                 2               —            —       SUPPRESS
 * </pre>
 *
 * <p><b>Equipe is excluded outright, not merely low-confidence.</b> Its measured density is
 * {@code 8 kg/m³} (median over the only 2 rows carrying both figures) — roughly a 290× error, so
 * Equipe's {@code kg_per_box}/{@code sqm_per_box} are not expressed in the units this formula
 * assumes. Suppressed unconditionally in {@link #fromBoxWeight}, never merely labelled LOW: an
 * "estimate" built from a 290× error is not a low-confidence estimate, it is a wrong one. This
 * matters at scale — Equipe is 2,122 of the 7,797 prod rows still needing a thickness, the single
 * biggest block after Vives.
 *
 * <p><b>Vives is unvalidated, not suppressed.</b> It has ZERO rows carrying both a box weight and a
 * thickness, so {@code 2315} is a density TRANSFERRED from the other six factories' pooled
 * average, not a figure measured on Vives's own products — and Vives is the LARGEST block still
 * needing a thickness (4,617 rows). {@link #fromBoxWeight} still suggests a value for it (an
 * estimate is better than nothing when it is labelled honestly), but the confidence it returns is
 * {@code UNVALIDATED}, and the Thai basis text says so too — a reviewer skimming only the mm figure
 * must still see the caveat, not just the machine-readable field.
 *
 * <p><b>An unknown factory — one absent from the table entirely — is suppressed</b>, not treated
 * as Vives-style unvalidated-with-a-transferred-density. Silently extending the pooled density to
 * a factory nobody has measured would let a ninth factory (a new import profile, say) immediately
 * start producing confident-looking suggestions this class's own author never saw a single
 * validation row for. Suppress is the safer default the owner's steer prefers here — see
 * SPEC-PREFILL.md's own "prefer suppress; do not silently extend the table."
 *
 * <p>These constants belong in CEO settings eventually (alongside the freight/duty tables {@code
 * PricingFormulaConfigController} already exposes) — this pass does not build that UI. They are
 * hardcoded here, in ONE place, with the measurement they came from recorded alongside them so the
 * next reader can tell what the numbers are worth without re-deriving them.
 */
@Component
public class ThicknessEstimator {

    /**
     * Round-UP-only ladder of nominal tile thicknesses actually seen in the catalogue (mm). A
     * box-weight estimate snaps to the SMALLEST rung ≥ the raw computed value, never to the
     * nearest one — see this class's own Javadoc for why nearest-snap is the wrong direction ~96%
     * of the time.
     */
    private static final List<BigDecimal> STANDARD_LADDER_MM = List.of(
        bd("3"), bd("3.5"), bd("4"), bd("4.5"), bd("5"), bd("5.5"), bd("6"), bd("6.5"),
        bd("7"), bd("7.5"), bd("8"), bd("8.5"), bd("9"), bd("9.5"), bd("10"), bd("10.5"),
        bd("11"), bd("12"), bd("14"), bd("15"), bd("16"), bd("18"), bd("20"));

    /**
     * Excluded outright from {@link #fromBoxWeight} regardless of how plausible a particular row's
     * inputs look — see this class's own Javadoc for the measured 290× density error. Kept as its
     * own named set (rather than simply never adding Equipe to {@link #FACTORY_DENSITY}) so the
     * exclusion reads as DELIBERATE to the next maintainer, not as a factory nobody got around to
     * adding yet.
     */
    private static final Set<String> SUPPRESSED_FACTORIES = Set.of("Equipe");

    /**
     * One factory's measured (or, for Vives, transferred) density and how much to trust it —
     * {@code bandCorrectPct} is {@code null} exactly for Vives (unvalidated: no rows to measure
     * one). See this class's own Javadoc for the full measured table and how each column was
     * derived.
     */
    private record FactoryDensity(int densityKgPerM3, String confidence, Double bandCorrectPct) {}

    private static final Map<String, FactoryDensity> FACTORY_DENSITY = Map.of(
        "Padana",  new FactoryDensity(2333, "HIGH",   97.73),
        "LEA",     new FactoryDensity(2328, "MEDIUM", 85.43),
        "Panaria", new FactoryDensity(2348, "MEDIUM", 81.18),
        "CDE",     new FactoryDensity(2282, "MEDIUM", 86.77),
        "REFIN",   new FactoryDensity(2207, "LOW",    82.12),
        "CITY",    new FactoryDensity(2250, "LOW",    79.73),
        // A TRANSFERRED figure, not a measurement — Vives has ZERO rows carrying both a box weight
        // and a known thickness, so no density can be measured for it, which is why the confidence
        // is UNVALIDATED rather than one of the measured tiers and why bandCorrectPct is null.
        //
        // 2315 is the pooled figure from the earlier three-factory sample (Padana / LEA / REFIN)
        // that SPEC-PREFILL.md carried. Note it is deliberately NOT the mean of the six measured
        // densities listed above — that mean is 2291.3. The two differ by ~1%, which cannot move a
        // result: every estimate snaps to STANDARD_LADDER_MM, whose tightest gap is 0.5mm, roughly
        // an 8% step at these thicknesses. Stated explicitly because an earlier revision of this
        // comment claimed 2315 WAS the six-factory average, which does not reproduce.
        "Vives",   new FactoryDensity(2315, "UNVALIDATED", null));

    /**
     * One suggested thickness: the value a human would see, the Thai text explaining where it came
     * from, and a machine-readable confidence tier ({@code HIGH}/{@code MEDIUM}/{@code LOW}/
     * {@code UNVALIDATED}) the caller may use to style it — e.g. a warning tone for the bottom two.
     */
    public record Suggestion(BigDecimal thicknessMm, String basis, String confidence) {}

    /**
     * Ladder A, rung 3: suggest a thickness from sibling products sharing this row's (factory,
     * collection) — but ONLY when every sibling that HAS a thickness agrees on the same one value.
     * 129 of 220 collections span more than one freight band (measured), so "the collection's
     * thickness" is not a safe inference in general; agreement across every sibling that states one
     * is the narrow case where it IS safe. Compares by NUMERIC VALUE ({@link
     * BigDecimal#compareTo}), not {@link BigDecimal#equals}, because {@code
     * product_prices.thickness_mm} has no fixed scale and two factories' import formats can write
     * the same thickness as {@code 8} and {@code 8.0} — {@code equals} would read those as a
     * disagreement they are not.
     *
     * @param siblingThicknesses one entry per OTHER row in the same (factory, collection) that has
     *                           its own non-null {@code thickness_mm} — see {@link
     *                           CatalogRepository#findSiblingThicknessesMm}. Empty (or null) when
     *                           there are no such siblings: a lone product in its collection, or
     *                           every sibling also lacks a thickness.
     */
    public Optional<Suggestion> fromSiblings(List<BigDecimal> siblingThicknesses) {
        if (siblingThicknesses == null || siblingThicknesses.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal first = siblingThicknesses.get(0);
        boolean allAgree = siblingThicknesses.stream().allMatch(t -> t != null && t.compareTo(first) == 0);
        if (!allAgree) {
            return Optional.empty();
        }
        String basis = "จากสินค้ารุ่นเดียวกัน " + siblingThicknesses.size() + " รายการ";
        return Optional.of(new Suggestion(first, basis, "HIGH"));
    }

    /**
     * Ladder A, rung 4: imply a thickness from a box's weight and footprint area —
     * {@code thickness_mm = (kg_per_box / sqm_per_box) / density_kg_per_m3 * 1000}, then snapped UP
     * to {@link #STANDARD_LADDER_MM} (never to-nearest — see this class's own Javadoc). Suppressed
     * (returns empty) for a factory in {@link #SUPPRESSED_FACTORIES}, a factory absent from {@link
     * #FACTORY_DENSITY} entirely (an unknown factory — see this class's Javadoc for why that is
     * suppressed rather than defaulted to Vives's pooled figure), a non-positive or missing input,
     * or a raw computed thickness ABOVE the ladder's own top rung (20mm) — there is nothing to snap
     * UP to in that case, and returning the raw, un-snapped number would violate the "always one of
     * the ladder's real values" contract every other rung already honours.
     *
     * @param factoryName the catalog row's factory (must match a {@code price_catalog.factories}
     *                     name exactly — see {@link CatalogRepository#findThicknessEstimationInputs})
     * @param kgPerBox     the row's {@code kg_per_box}
     * @param sqmPerBox    the row's REAL footprint area per box — {@code true_sqm_per_box} (V153/
     *                     V164), already corrected for the per-linear-metre mislabelling; see
     *                     {@link CatalogRepository#findThicknessEstimationInputs}'s own Javadoc
     */
    public Optional<Suggestion> fromBoxWeight(String factoryName, BigDecimal kgPerBox, BigDecimal sqmPerBox) {
        if (factoryName == null || SUPPRESSED_FACTORIES.contains(factoryName)) {
            return Optional.empty();
        }
        if (kgPerBox == null || sqmPerBox == null || kgPerBox.signum() <= 0 || sqmPerBox.signum() <= 0) {
            return Optional.empty();
        }
        FactoryDensity density = FACTORY_DENSITY.get(factoryName);
        if (density == null) {
            // Unknown factory — prefer suppress over silently extending the table (see this
            // class's own Javadoc, "An unknown factory").
            return Optional.empty();
        }
        BigDecimal rawMm = kgPerBox
            .divide(sqmPerBox, 10, RoundingMode.HALF_UP)
            .divide(BigDecimal.valueOf(density.densityKgPerM3()), 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(1000));
        BigDecimal snapped = snapUp(rawMm);
        if (snapped == null) {
            return Optional.empty();
        }
        String basis = "ประมาณจากน้ำหนักกล่อง " + plain(kgPerBox) + " กก. ÷ " + plain(sqmPerBox) + " ตร.ม."
            + accuracySuffix(density, factoryName);
        return Optional.of(new Suggestion(snapped, basis, density.confidence()));
    }

    private String accuracySuffix(FactoryDensity density, String factoryName) {
        if (density.bandCorrectPct() != null) {
            return " — ความแม่นยำ ~" + Math.round(density.bandCorrectPct()) + "% สำหรับ " + factoryName;
        }
        return " — ยังไม่มีข้อมูลตรวจสอบความแม่นยำสำหรับ " + factoryName
            + " (ใช้ค่าความหนาแน่นเฉลี่ยของโรงงานอื่นแทน)";
    }

    /**
     * The smallest {@link #STANDARD_LADDER_MM} rung ≥ {@code raw}, or {@code null} when {@code raw}
     * exceeds every rung (nothing to snap UP to).
     */
    private BigDecimal snapUp(BigDecimal raw) {
        for (BigDecimal rung : STANDARD_LADDER_MM) {
            if (raw.compareTo(rung) <= 0) {
                return rung;
            }
        }
        return null;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
