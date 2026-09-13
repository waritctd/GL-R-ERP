package th.co.glr.hr.location;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

@Repository
public class ThaiLocationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ThaiLocationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Province(String code, String nameTh, String nameEn) {}
    public record District(String code, String provinceCode, String nameTh, String nameEn) {}
    public record Subdistrict(String code, String districtCode, String provinceCode, String nameTh,
                              String nameEn, List<String> postalCodes) {}
    public List<Province> provinces() {
        return jdbc.query("SELECT * FROM customers.thai_provinces ORDER BY name_th", Map.of(),
            (r,i) -> new Province(r.getString("code"), r.getString("name_th"), r.getString("name_en")));
    }
    public List<District> districts(String provinceCode) {
        return jdbc.query("SELECT * FROM customers.thai_districts WHERE province_code=:code ORDER BY name_th",
            Map.of("code", provinceCode), (r,i) -> new District(r.getString("code"), r.getString("province_code"),
                r.getString("name_th"), r.getString("name_en")));
    }
    public List<Subdistrict> subdistricts(String districtCode) {
        return jdbc.query("""
            SELECT s.*, ARRAY(SELECT p.postal_code FROM customers.thai_subdistrict_postal_codes p
                WHERE p.subdistrict_code=s.code ORDER BY p.postal_code) AS postal_codes
            FROM customers.thai_subdistricts s WHERE district_code=:code ORDER BY name_th
            """, Map.of("code", districtCode), (r,i) -> new Subdistrict(r.getString("code"),
                r.getString("district_code"), r.getString("province_code"), r.getString("name_th"),
                r.getString("name_en"), List.of((String[]) r.getArray("postal_codes").getArray())));
    }
    public ThaiAddress resolve(String line, String province, String district, String subdistrict, String postal) {
        if (province == null || district == null || subdistrict == null) throw invalid();
        var p = provinces().stream().filter(x -> x.code().equals(province)).findFirst().orElseThrow(ThaiLocationRepository::invalid);
        var d = districts(province).stream().filter(x -> x.code().equals(district)).findFirst().orElseThrow(ThaiLocationRepository::invalid);
        var s = subdistricts(district).stream().filter(x -> x.code().equals(subdistrict)).findFirst().orElseThrow(ThaiLocationRepository::invalid);
        String resolvedPostal = postal == null || postal.isBlank() ? null : postal.trim();
        if (resolvedPostal == null && s.postalCodes().size() == 1) resolvedPostal = s.postalCodes().getFirst();
        if ((!s.postalCodes().isEmpty() && (resolvedPostal == null || !s.postalCodes().contains(resolvedPostal)))
            || (s.postalCodes().isEmpty() && resolvedPostal != null)) throw invalid();
        return new ThaiAddress(line == null ? "" : line.trim(), province, district, subdistrict,
            resolvedPostal, p.nameTh(), d.nameTh(), s.nameTh());
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกจังหวัด เขต/อำเภอ แขวง/ตำบล และรหัสไปรษณีย์ให้สัมพันธ์กัน");
    }
}
