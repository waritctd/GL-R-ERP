package th.co.glr.hr.catalog.importer;

import java.math.BigDecimal;
import java.util.Map;

public record PriceRow(
    long factoryId,
    String productCode,
    String grade,
    String collection,
    String productName,
    String color,
    String surface,
    String sizeRaw,
    BigDecimal widthMm,
    BigDecimal heightMm,
    BigDecimal thicknessMm,
    BigDecimal price,
    String currency,
    String priceUnit,
    BigDecimal sqmPerPiece,
    BigDecimal pcsPerBox,
    BigDecimal sqmPerBox,
    BigDecimal kgPerBox,
    Map<String, String> priceVariants,
    Map<String, String> attributes,
    String sourceSheet,
    int sourceRow,
    // ── provenance / reconciliation (appended so no earlier call site needs touching) ──────────
    // The declared size unit ("mm"/"cm") the profile stated for THIS row — never guessed, see
    // ImportProfile#sizeUnit.
    String sizeUnitDeclared,
    // How sqmPerPiece (above) was obtained, or why it is null. One of:
    //   box_reconciled          — pcs/sqm-per-box agreed with width×height within tolerance
    //   box_only_no_dims        — box figures used; no parsed dimensions to check them against
    //   computed_from_dimensions— no usable box figures; derived from width×height
    //   linear_metre_not_area   — per_linear_m: the box "sqm" column is LINEAR METRES, not area
    //                             (see sqmPerLinearM instead); sqmPerPiece is deliberately null
    //   mismatch_quarantined    — box figure and width×height disagree beyond tolerance; the row
    //                             is quarantined (see quarantineReason) and sqmPerPiece is null
    //   unavailable             — neither box figures nor parsed dimensions exist
    String sqmProvenance,
    // Square metres covered by ONE LINEAR METRE of a per_linear_m product (profile height =
    // min(widthMm, heightMm) / 1000), mirroring V153's sqm_per_linear_m column. Null for anything
    // not priced per_linear_m. Exists so nothing downstream can mistake this figure for an area —
    // it is never written into sqmPerPiece.
    BigDecimal sqmPerLinearM,
    // Non-null ⇒ this row is QUARANTINED: staged with import_error set to this text (both the
    // box-derived and dimension-derived m²/piece figures, plus the tolerance), excluded from
    // commit, but visible to the operator — never dropped silently.
    String quarantineReason,
    // The declared thickness unit ("mm"/"cm"/"none") the profile stated for THIS row — never
    // guessed, see ImportProfile#thicknessUnit. "none" records a genuine, owner-confirmed absence
    // of thickness data in the source (Bode/Vives/Equipe), not a missing declaration.
    String thicknessUnitDeclared,
    // How thicknessMm (above) was obtained, or why it is null. One of:
    //   stated            — a real value existed in the source for this row: a dedicated column, a
    //                       self-describing "9MM" token, a size-embedded bare 3rd value, or an
    //                       Equipe range collapsed to its minimum (see thicknessNote for the
    //                       original range text)
    //   sidecar_resolved  — resolved by joining a separate thickness workbook on a declared
    //                       composite key (Vives: CODIGO+MODELO) — see ImportProfile#thicknessSidecar
    //   profile_default   — no per-row thickness existed anywhere; ImportProfile#defaultThicknessMm
    //                       was applied (Bode: 9mm). MUST stay distinguishable from "stated" so a
    //                       sales rep can find and correct it on the quotation — never silently
    //                       masks a real value: only applied when every other source came back null.
    //   absent            — no thickness value exists for this row from any source; thicknessMm is
    //                       null, and that is a recorded, deliberate absence, not a missing case
    String thicknessProvenance,
    // Free-text detail accompanying thicknessProvenance — Equipe's per-row verification status
    // text, an Equipe range's original wording ("9.5–19.5"), a Vives sidecar's stated reason for a
    // blank thickness ("Not published — special/complementary piece" / "Not found on current
    // catalogue"), or the Bode profile-default's own explanation. Never structured — read
    // thicknessProvenance for that. Null when there is nothing to say.
    String thicknessNote
) {}
