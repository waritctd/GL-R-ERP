package th.co.glr.hr.designer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * READ-ONLY access to {@code sales.designer} (V173). There is no write method on this class and
 * there must never be one -- the owner's ruling was "อ่านอย่างเดียว อัปเดตจาก Excel" (read-only,
 * refreshed by re-importing the Excel), so the table's contents change only via a future
 * migration, never through this application. See V173's own header for the full seeding story.
 */
@Repository
public class DesignerRepository {
    /** Same shape as CatalogRepository.search's own LIMIT -- a typeahead never needs more than a
     * screenful of rows, and an unbounded ILIKE over ~1,100 rows is already cheap either way. */
    private static final int SEARCH_LIMIT = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public DesignerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Search by code OR name, ACTIVE ONLY -- a rep picking a designer for a NEW quotation must
     * never be offered one of the 13 rows the owner marked ยกเลิก ("เก็บไว้ แต่เลือกใหม่ไม่ได้").
     * An inactive designer that an OLD quotation already references is still readable through
     * {@link #findByCode}, which deliberately does not filter on {@code active}.
     */
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

    /**
     * Resolve one designer by its exact code, REGARDLESS of {@code active} -- an existing
     * quotation's {@code unit_code} may already hold the code of a designer the owner has since
     * marked ยกเลิก, and the editor still needs to show who that was when the quotation is
     * reopened, even though {@link #search} would no longer surface them for a new pick.
     */
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
}
