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
