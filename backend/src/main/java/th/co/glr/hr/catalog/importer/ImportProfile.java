package th.co.glr.hr.catalog.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
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

    /** REQUIRED — "mm" or "cm", declaring the unit the source price list writes its size column in.
     * ImportEngine#parse refuses to import a profile missing this field rather than guess. */
    @JsonProperty("size_unit")
    public String sizeUnit;

    /** REQUIRED — "mm", "cm", or "none", declaring how to interpret an AMBIGUOUS (bare, no explicit unit letter
     * attached) numeric thickness value for this profile's source — whether that number comes from a dedicated
     * thickness column or a bare third value in the size string. */
    @JsonProperty("thickness_unit")
    public String thicknessUnit;

    /** OPTIONAL — a profile-level fallback thickness (mm), applied ONLY to a row that resolved NO thickness from
     * any other source (its own column/size-string, and any #thicknessSidecar join). */
    @JsonProperty("default_thickness_mm")
    public BigDecimal defaultThicknessMm;

    /** OPTIONAL — joins thickness onto this profile's rows from a SEPARATE workbook, on a declared composite key.
     * Kept general rather than Vives-specific: any profile can declare one. */
    @JsonProperty("thickness_sidecar")
    public ThicknessSidecar thicknessSidecar;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ThicknessSidecar {
        /** Sheet name inside the SIDECAR workbook (not the main price-list workbook). */
        public String sheet;

        @JsonProperty("header_row")
        public int headerRow = 1;

        /** Header names on the MAIN price-list sheet forming the join key, in order — e.g. ["CODIGO", "MODELO"].
         * Matched case/whitespace-insensitively, same as every other header lookup in this engine. A row missing any
         * key column value never matches the sidecar (key columns are never null-joined). */
        @JsonProperty("key_columns")
        public List<String> keyColumns = List.of();

        /** Header names on the SIDECAR sheet, same order and length as #keyColumns, forming the matching half of the
         * composite key. */
        @JsonProperty("sidecar_key_columns")
        public List<String> sidecarKeyColumns = List.of();

        /** Sidecar header holding the thickness value, already in millimetres. */
        @JsonProperty("value_column")
        public String valueColumn;

        /** OPTIONAL sidecar header holding a free-text status/reason ("Not published — special/ complementary piece",
         * "Not found on current catalogue", "Verified / matched", …). Carried into thicknessNote whether or not the
         * row resolved a value, so a blank thickness is explained rather than merely absent. */
        @JsonProperty("status_column")
        public String statusColumn;
    }

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
