package th.co.glr.hr.location;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Validated administrative codes plus cached names; address() remains the document contract. */
public record ThaiAddress(String addressLine, String provinceCode, String districtCode, String subdistrictCode,
                         String postalCode, String provinceNameTh, String districtNameTh, String subdistrictNameTh) {
    private static String bare(String name) {
        return name == null ? "" : name.trim().replaceFirst("^(จังหวัด|อำเภอ|เขต|ตำบล|แขวง)\\s*", "").trim();
    }
    public String printable() {
        boolean bangkok = "10".equals(provinceCode);
        return Stream.of(addressLine, (bangkok ? "แขวง" : "ตำบล") + bare(subdistrictNameTh),
            (bangkok ? "เขต" : "อำเภอ") + bare(districtNameTh),
            (bangkok ? "" : "จังหวัด") + bare(provinceNameTh), postalCode)
            .filter(x -> x != null && !x.isBlank()).map(String::trim).collect(Collectors.joining(" "));
    }
}
