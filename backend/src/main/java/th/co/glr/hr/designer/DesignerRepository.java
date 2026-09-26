package th.co.glr.hr.designer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code sales.designer} (V173).
 *
 * <p>⚠️ REVERSAL (owner ask relayed 2026-09-26, task "designer-add-from-ui"): this class used to
 * say there was no write method here and there must never be one -- the owner's original ruling
 * was "อ่านอย่างเดียว อัปเดตจาก Excel" (read-only, refreshed by re-importing the Excel). The owner
 * has now explicitly asked for an inline "add a new designer" flow on the quotation editor's
 * DesignerPicker, mirroring DealCustomerCard's "+ เพิ่มลูกค้าใหม่". {@link #create} below is that
 * fresh write path -- see {@link DesignerController}'s class Javadoc for the authz gate. The bulk
 * of this table (~1,100 rows) still only ever changes via a future Excel re-import migration; only
 * ROWS ADDED THROUGH THIS METHOD are UI-authored, tagged {@code source_sheet = 'UI'} to keep that
 * distinction visible in the data itself. See V173's own header for the original seeding story.
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

    /**
     * Insert a brand-new designer row (REVERSAL of the original read-only ruling -- see this
     * class's own Javadoc and {@link DesignerController}'s). {@code code} is the table's PK
     * (VARCHAR(20), no format CHECK -- V173's own header notes real codes are not one consistent
     * shape: 'A001', '1001', 'FL1', 'ก001' are all real), so a duplicate is left to the DB's own PK
     * constraint to reject as a {@link org.springframework.dao.DuplicateKeyException} rather than a
     * racy SELECT-then-INSERT check here -- the controller maps that to 409. {@code source_sheet}
     * is the literal {@code 'UI'} for anything created this way, so a UI-authored row stays
     * distinguishable at a glance from an Excel-imported one; {@code active} is always {@code TRUE}
     * for a brand-new row (there is no way to create one pre-cancelled).
     */
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
