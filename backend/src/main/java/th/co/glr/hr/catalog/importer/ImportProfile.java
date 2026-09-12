package th.co.glr.hr.catalog.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportProfile {

    @JsonProperty("number_format")
    public String numberFormat = "eu";

    public List<SheetConfig> sheets = List.of();

    public Map<String, String> columns = Map.of();

    @JsonProperty("column_aliases")
    public Map<String, List<String>> columnAliases = Map.of();

    public Map<String, String> defaults = Map.of();

    @JsonProperty("price_column_rule")
    public PriceColumnRule priceColumnRule;

    @JsonProperty("allow_missing_code")
    public boolean allowMissingCode = false;

    @JsonProperty("fill_down")
    public List<String> fillDown = List.of();

    @JsonProperty("fill_down_per_sheet")
    public Map<String, List<String>> fillDownPerSheet = Map.of();

    @JsonProperty("split_column")
    public Map<String, String> splitColumn = Map.of();

    @JsonProperty("size_from")
    public String sizeFrom;

    @JsonProperty("size_format")
    public String sizeFormat;

    /**
     * REQUIRED — "mm" or "cm", declaring the unit the source price list writes its size column
     * in. There is deliberately NO default and no magnitude-based fallback: {@code ImportEngine}
     * used to guess ("values &lt; 300 are cm") and got it wrong the size it most needed to be
     * right — the unit is a property of the DOCUMENT (a factory's price list and its retail
     * catalogue can disagree), never something inferrable from the brand or the numbers
     * themselves. {@code ImportEngine#parse} refuses to import a profile missing this field
     * rather than guess. See CLAUDE.md / the 2026-09 catalogue-accuracy work for the reconciled
     * facts this was set from (Padana=cm, LEA=mm, CDE=mm, Bode=mm — each verified against
     * m²/box ÷ pcs/box, not inferred).
     */
    @JsonProperty("size_unit")
    public String sizeUnit;

    /**
     * REQUIRED — "mm", "cm", or "none", declaring how to interpret an AMBIGUOUS (bare, no
     * explicit unit letter attached) numeric thickness value for this profile's source — whether
     * that number comes from a dedicated thickness column or a bare third value in the size
     * string. Same discipline as {@link #sizeUnit}, and for the identical reason: {@code
     * ImportEngine} used to assume a bare third size value ({@code "598X598X18"}) was ALWAYS
     * already millimetres. That is true for Bode but false for the Chinese "2026 GENERAL EXPORT"
     * list, which writes it in CENTIMETRES ({@code "60X120X1.0"} = a 9 mm tile — its own worksheet
     * tab is named "2CM" for the 20 mm slabs); nothing about the two shapes lets code tell them
     * apart without a declared unit, so there is no default and no magnitude-based fallback here
     * either.
     *
     * <p>A value that already carries its own explicit unit letter — {@code "9MM"}, the truncated
     * {@code "9M"}/{@code "9,4M"} forms, or Padana's own {@code "8MM"}-style {@code Spessore}
     * column text — is self-describing millimetres and is read as-is regardless of this setting;
     * this field only governs the ambiguous bare-number case.
     *
     * <p>{@code "none"} is a legitimate declared value, not a placeholder for "not yet
     * configured": it means this factory's source genuinely carries no reliable thickness data at
     * all. Confirmed by scanning every cell of three real price lists — Bode (one stray token in
     * the whole sheet), Vives (zero across 13,851 description cells), Equipe (zero across 4,248) —
     * for which the owner is sending separate thickness files. A {@code "none"} profile always
     * imports {@code thickness_mm = NULL}; that is the correct, recorded absence, never a
     * quarantine and never a guess. This is a statement about how to INTERPRET a thickness value
     * when one exists, not an assertion that one must exist — {@code ImportEngine} never requires
     * a non-null thickness to import a row.
     */
    @JsonProperty("thickness_unit")
    public String thicknessUnit;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SheetConfig {
        public String name;

        @JsonProperty("header_row")
        public int headerRow = 1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceColumnRule {
        public String type;          // "choose" | "first_non_empty"
        public List<String> options; // for choose
        public String selected;      // for choose
        @JsonProperty("keep_all_as")
        public String keepAllAs;     // for choose
        public Map<String, String> map; // for first_non_empty: col → price_unit
    }
}
