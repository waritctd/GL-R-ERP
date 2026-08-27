package th.co.glr.hr.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes {@code price_catalog.collection_thickness_default} (V152).
 *
 * <p>Four of the nine factory workbooks carry no thickness column at all, so 9,411 catalogue rows
 * have none and cannot resolve a freight band. Thickness is not recoverable from the source files;
 * it has to be supplied. This is the surface that supplies it.
 *
 * <p>Shaped for bulk entry rather than row-at-a-time: 244 (factory, collection) pairs cover every
 * gap, and the CEO fills them in one sitting.
 */
@Repository
public class ThicknessDefaultRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ThicknessDefaultRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every (factory, collection) that has at least one ACTIVE-version row lacking its own
     * thickness, with the default currently covering it (if any) and how many rows it governs.
     *
     * <p>Ordered by affected row count descending: the CEO's first entry should be the one that
     * unblocks the most products. Vives ALTEA alone covers hundreds.
     */
    public List<ThicknessDefaultDtos.ThicknessGapDto> listGaps() {
        return jdbc.query("""
            SELECT f.factory_id,
                   f.name                        AS factory_name,
                   p.collection,
                   count(*)                      AS rows_missing_thickness,
                   max(d.thickness_mm)           AS current_default_mm,
                   bool_or(d.size_norm IS NOT NULL) AS has_size_level_override
              FROM price_catalog.product_prices p
              JOIN price_catalog.factories           f ON f.factory_id = p.factory_id
              JOIN price_catalog.price_list_versions v ON v.version_id = p.version_id
              LEFT JOIN price_catalog.collection_thickness_default d
                     ON d.factory_id = p.factory_id
                    AND d.collection IS NOT DISTINCT FROM p.collection
                    AND d.size_norm IS NULL
             WHERE v.status = 'ACTIVE'
               AND p.thickness_mm IS NULL
             GROUP BY f.factory_id, f.name, p.collection
             ORDER BY count(*) DESC, f.name, p.collection
            """, Map.of(), (rs, i) -> new ThicknessDefaultDtos.ThicknessGapDto(
                rs.getLong("factory_id"),
                rs.getString("factory_name"),
                rs.getString("collection"),
                rs.getInt("rows_missing_thickness"),
                rs.getBigDecimal("current_default_mm"),
                rs.getBoolean("has_size_level_override")));
    }

    /**
     * Upserts collection-level defaults in one transaction.
     *
     * <p>A null or blank thickness CLEARS that collection's default rather than storing a zero --
     * the CHECK constraint forbids a non-positive thickness, and a zero would silently pick the
     * lowest freight band. Clearing returns those rows to NO_THICKNESS, which is the honest state.
     *
     * <p>Only ever touches {@code size_norm IS NULL} rows: size-level overrides are the escape
     * hatch for a collection whose trim differs from its field tile, and a bulk collection-level
     * save must not wipe them.
     *
     * @return number of rows inserted, updated or deleted
     */
    @Transactional
    public int saveAll(List<ThicknessDefaultRequests.ThicknessDefaultEntry> entries, Long updatedBy) {
        int touched = 0;
        for (ThicknessDefaultRequests.ThicknessDefaultEntry entry : entries) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("factoryId", entry.factoryId())
                .addValue("collection", entry.collection())
                .addValue("updatedBy", updatedBy);

            BigDecimal thickness = entry.thicknessMm();
            if (thickness == null) {
                touched += jdbc.update("""
                    DELETE FROM price_catalog.collection_thickness_default
                     WHERE factory_id = :factoryId
                       AND collection IS NOT DISTINCT FROM :collection
                       AND size_norm IS NULL
                    """, params);
                continue;
            }

            touched += jdbc.update("""
                INSERT INTO price_catalog.collection_thickness_default
                    (factory_id, collection, size_norm, thickness_mm, updated_by, updated_at)
                VALUES (:factoryId, :collection, NULL, :thicknessMm, :updatedBy, now())
                ON CONFLICT ON CONSTRAINT uq_collection_thickness DO UPDATE
                   SET thickness_mm = EXCLUDED.thickness_mm,
                       updated_by   = EXCLUDED.updated_by,
                       updated_at   = now()
                """, params.addValue("thicknessMm", thickness));
        }
        return touched;
    }

    /** How many ACTIVE-version rows still cannot resolve a thickness at all. */
    public int countRowsStillMissingThickness() {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM price_catalog.v_priceable_product
             WHERE pricing_status = 'NO_THICKNESS'
            """, Map.of(), Integer.class);
        return n != null ? n : 0;
    }
}
