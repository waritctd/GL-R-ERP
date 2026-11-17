package th.co.glr.hr.designer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Access to sales.designer. ⚠️ REVERSAL: this class used to say there was no write method here and there must
 * never be one -- the owner's original ruling was "อ่านอย่างเดียว อัปเดตจาก Excel". See V173's own header for
 * the original seeding story. */
@Repository
public class DesignerRepository {
    /** Same shape as CatalogRepository.search's own LIMIT -- a typeahead never needs more than a screenful of rows,
     * and an unbounded ILIKE over ~1,100 rows is already cheap either way. */
    private static final int SEARCH_LIMIT = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public DesignerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Search by code OR name, ACTIVE ONLY -- a rep picking a designer for a NEW quotation must never be offered
     * one of the 13 rows the owner marked ยกเลิก ("เก็บไว้ แต่เลือกใหม่ไม่ได้"). */
    public List<DesignerDto> search(String q) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        return jdbc.query(
            """
            SELECT code, name, active
              FROM sales.designer
             WHERE active = TRUE
               AND (code ILIKE :q OR name ILIKE :q)
             ORDER BY code
             LIMIT :limit
            """,
            new MapSqlParameterSource()
                .addValue("q", pattern)
                .addValue("limit", SEARCH_LIMIT),
            (rs, i) -> new DesignerDto(rs.getString("code"), rs.getString("name"), rs.getBoolean("active"))
        );
    }

    /** Resolve one designer by its exact code, REGARDLESS of active. */
    public Optional<DesignerDto> findByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return jdbc.query(
            """
            SELECT code, name, active
              FROM sales.designer
             WHERE code = :code
            """,
            Map.of("code", code.trim()),
            (rs, i) -> new DesignerDto(rs.getString("code"), rs.getString("name"), rs.getBoolean("active"))
        ).stream().findFirst();
    }

    /** Insert a brand-new designer row (REVERSAL of the original read-only ruling -- see this class's own Javadoc
     * and DesignerController's). */
    public DesignerDto create(String code, String name) {
        jdbc.update(
            """
            INSERT INTO sales.designer (code, name, active, source_sheet, imported_at)
            VALUES (:code, :name, TRUE, 'UI', now())
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", name)
        );
        return new DesignerDto(code, name, true);
    }
}
