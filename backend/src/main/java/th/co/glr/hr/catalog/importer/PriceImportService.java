package th.co.glr.hr.catalog.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.catalog.ProductPriceInput;
import th.co.glr.hr.common.ApiException;
import org.springframework.http.HttpStatus;

@Service
public class PriceImportService {

    private final ImportEngine engine;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PriceImportService(
        ImportEngine engine,
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper objectMapper
    ) {
        this.engine       = engine;
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── parse + stage ─────────────────────────────────────────────────────────

    @Transactional
    public UploadReport uploadAndStage(
        long factoryId,
        String originalFilename,
        InputStream fileStream,
        String label,
        long uploadedBy
    ) {
        ImportProfile prof = loadProfile(factoryId);
        ImportResult result;
        try {
            result = engine.parse(fileStream, prof, factoryId);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "parse failed: " + e.getMessage());
        }

        long versionId = createDraftVersion(factoryId, label, originalFilename,
            uploadedBy, result.rows().size(), result.errors().size());

        UUID sessionId = UUID.randomUUID();
        bulkInsertStaging(versionId, factoryId, result.rows(), sessionId);

        return new UploadReport(
            versionId, sessionId,
            result.rows().size(), result.errors().size(),
            result.errors()
        );
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    public ImportProfile loadProfile(long factoryId) {
        String json;
        try {
            json = jdbc.queryForObject(
                "SELECT config FROM price_catalog.import_profiles WHERE factory_id = :fid",
                Map.of("fid", factoryId),
                String.class
            );
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบ import profile สำหรับ factory id=" + factoryId);
        }
        if (json == null)
            throw new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบ import profile สำหรับ factory id=" + factoryId);
        try {
            return objectMapper.readValue(json, ImportProfile.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "parse profile JSON failed: " + e.getMessage());
        }
    }

    private long createDraftVersion(
        long factoryId, String label, String sourceFile,
        long uploadedBy, int rowCount, int errorCount
    ) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(
            """
            INSERT INTO price_catalog.price_list_versions
                (factory_id, label, source_file, status, uploaded_by, uploaded_at, row_count, error_count)
            VALUES
                (:fid, :label, :sf, 'DRAFT', :by, :now, :rc, :ec)
            """,
            new MapSqlParameterSource()
                .addValue("fid",   factoryId)
                .addValue("label", label)
                .addValue("sf",    sourceFile)
                .addValue("by",    uploadedBy)
                .addValue("now",   Timestamp.from(Instant.now()))
                .addValue("rc",    rowCount)
                .addValue("ec",    errorCount),
            holder,
            new String[]{"version_id"}
        );
        return ((Number) holder.getKeys().get("version_id")).longValue();
    }

    private void bulkInsertStaging(
        long versionId, long factoryId, List<PriceRow> rows, UUID sessionId
    ) {
        String sql = """
            INSERT INTO price_catalog.product_price_staging (
                factory_id, version_id, product_code, grade, collection, product_name,
                color, surface, size_raw, width_mm, height_mm, thickness_mm,
                price, currency, price_unit, sqm_per_piece,
                pcs_per_box, sqm_per_box, kg_per_box,
                price_variants, attributes, source_sheet, source_row, import_session_id
            ) VALUES (
                :fid, :vid, :code, :grade, :col, :name,
                :color, :surf, :sizeRaw, :w, :h, :t,
                :price, :cur, :unit, :sqmPc,
                :pcs, :sqmBox, :kg,
                CAST(:variants AS jsonb), CAST(:attrs AS jsonb), :sheet, :row, :sid
            )
            """;

        MapSqlParameterSource[] batch = rows.stream().map(r -> {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("fid",     factoryId);
            p.addValue("vid",     versionId);
            p.addValue("code",    r.productCode());
            p.addValue("grade",   r.grade());
            p.addValue("col",     r.collection());
            p.addValue("name",    r.productName());
            p.addValue("color",   r.color());
            p.addValue("surf",    r.surface());
            p.addValue("sizeRaw", r.sizeRaw());
            p.addValue("w",       r.widthMm());
            p.addValue("h",       r.heightMm());
            p.addValue("t",       r.thicknessMm());
            p.addValue("price",   r.price());
            p.addValue("cur",     r.currency());
            p.addValue("unit",    r.priceUnit());
            p.addValue("sqmPc",   r.sqmPerPiece());
            p.addValue("pcs",     r.pcsPerBox());
            p.addValue("sqmBox",  r.sqmPerBox());
            p.addValue("kg",      r.kgPerBox());
            p.addValue("variants", toJson(r.priceVariants()));
            p.addValue("attrs",   toJson(r.attributes()));
            p.addValue("sheet",   r.sourceSheet());
            p.addValue("row",     r.sourceRow());
            p.addValue("sid",     sessionId);
            return p;
        }).toArray(MapSqlParameterSource[]::new);

        if (batch.length > 0) jdbc.batchUpdate(sql, batch);
    }

    // ── C3: validate ─────────────────────────────────────────────────────────

    @Transactional
    public void validate(long versionId) {
        requireDraft(versionId);

        // reset previous errors (re-validate idempotent)
        jdbc.update(
            "UPDATE price_catalog.product_price_staging SET import_error = NULL WHERE version_id = :vid",
            Map.of("vid", versionId)
        );

        // mark duplicate rows within the same version (keep first by rowid, flag rest)
        jdbc.update("""
            UPDATE price_catalog.product_price_staging s
               SET import_error = 'รหัสซ้ำในไฟล์'
              FROM (
                SELECT price_id FROM (
                    SELECT price_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY version_id,
                                            coalesce(product_code,''),
                                            coalesce(grade,''),
                                            coalesce(size_raw,''),
                                            coalesce(surface,'')
                               ORDER BY price_id
                           ) rn
                      FROM price_catalog.product_price_staging
                     WHERE version_id = :vid
                ) t WHERE rn > 1
              ) dups
             WHERE s.price_id = dups.price_id
            """,
            Map.of("vid", versionId)
        );
    }

    // ── C3: staging report + diff ─────────────────────────────────────────────

    public StagingReport getStagingReport(long versionId) {
        // counts
        Map<String, Object> counts = jdbc.queryForMap(
            """
            SELECT
                count(*)                                    AS total,
                count(*) FILTER (WHERE import_error IS NULL)  AS valid,
                count(*) FILTER (WHERE import_error IS NOT NULL) AS invalid
              FROM price_catalog.product_price_staging
             WHERE version_id = :vid
            """,
            Map.of("vid", versionId)
        );

        // previous ACTIVE version for same factory
        Long prevVersionId = jdbc.query(
            """
            SELECT v2.version_id
              FROM price_catalog.price_list_versions v1
              JOIN price_catalog.price_list_versions v2
                ON v2.factory_id = v1.factory_id
               AND v2.status = 'ACTIVE'
             WHERE v1.version_id = :vid
             LIMIT 1
            """,
            Map.of("vid", versionId),
            (rs, i) -> rs.getLong("version_id")
        ).stream().findFirst().orElse(null);

        int newProducts = 0, removedProducts = 0, priceChanged = 0;

        if (prevVersionId != null) {
            long prev = prevVersionId;
            newProducts = countDiff("""
                SELECT count(DISTINCT coalesce(s.product_code,'__null__'||s.price_id::text))
                  FROM price_catalog.product_price_staging s
                 WHERE s.version_id = :vid
                   AND s.import_error IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM price_catalog.product_prices p
                        WHERE p.version_id = :prev
                          AND p.product_code IS NOT DISTINCT FROM s.product_code
                          AND p.grade        IS NOT DISTINCT FROM s.grade
                   )
                """, versionId, prev);

            removedProducts = countDiff("""
                SELECT count(DISTINCT coalesce(p.product_code,'__null__'||p.price_id::text))
                  FROM price_catalog.product_prices p
                 WHERE p.version_id = :prev
                   AND NOT EXISTS (
                       SELECT 1 FROM price_catalog.product_price_staging s
                        WHERE s.version_id = :vid
                          AND s.import_error IS NULL
                          AND s.product_code IS NOT DISTINCT FROM p.product_code
                          AND s.grade        IS NOT DISTINCT FROM p.grade
                   )
                """, versionId, prev);

            priceChanged = countDiff("""
                SELECT count(*)
                  FROM price_catalog.product_price_staging s
                  JOIN price_catalog.product_prices p
                    ON p.version_id = :prev
                   AND p.product_code IS NOT DISTINCT FROM s.product_code
                   AND p.grade        IS NOT DISTINCT FROM s.grade
                   AND p.size_raw     IS NOT DISTINCT FROM s.size_raw
                 WHERE s.version_id = :vid
                   AND s.import_error IS NULL
                   AND s.price <> p.price
                """, versionId, prev);
        }

        List<String> sampleErrors = jdbc.query(
            """
            SELECT DISTINCT import_error
              FROM price_catalog.product_price_staging
             WHERE version_id = :vid AND import_error IS NOT NULL
             LIMIT 20
            """,
            Map.of("vid", versionId),
            (rs, i) -> rs.getString("import_error")
        );

        return new StagingReport(
            versionId,
            ((Number) counts.get("total")).intValue(),
            ((Number) counts.get("valid")).intValue(),
            ((Number) counts.get("invalid")).intValue(),
            newProducts, removedProducts, priceChanged,
            prevVersionId,
            sampleErrors
        );
    }

    private int countDiff(String sql, long versionId, long prev) {
        Integer n = jdbc.queryForObject(sql,
            Map.of("vid", versionId, "prev", prev), Integer.class);
        return n != null ? n : 0;
    }

    // ── C3: commit ────────────────────────────────────────────────────────────

    @Transactional
    public CommitResult commit(long versionId, long userId) {
        requireDraft(versionId);

        Integer valid = jdbc.queryForObject(
            "SELECT count(*) FROM price_catalog.product_price_staging WHERE version_id = :vid AND import_error IS NULL",
            Map.of("vid", versionId), Integer.class
        );
        if (valid == null || valid == 0)
            throw new ApiException(HttpStatus.CONFLICT, "ไม่มีแถวที่ valid — ไม่สามารถ commit ได้");

        // find current ACTIVE version for this factory (for incremental merge)
        Long prevVersionId = jdbc.query("""
            SELECT v2.version_id
              FROM price_catalog.price_list_versions v1
              JOIN price_catalog.price_list_versions v2
                ON v2.factory_id = v1.factory_id AND v2.status = 'ACTIVE'
             WHERE v1.version_id = :vid
             LIMIT 1
            """,
            Map.of("vid", versionId),
            (rs, i) -> rs.getLong("version_id")
        ).stream().findFirst().orElse(null);

        // 1. copy valid staging rows → product_prices
        int inserted = jdbc.update("""
            INSERT INTO price_catalog.product_prices (
                factory_id, version_id, product_code, grade, collection, product_name,
                color, surface, size_raw, width_mm, height_mm, thickness_mm,
                price, currency, price_unit, sqm_per_piece,
                pcs_per_box, sqm_per_box, kg_per_box,
                price_variants, attributes, source_sheet, source_row
            )
            SELECT
                factory_id, version_id, product_code, grade, collection, product_name,
                color, surface, size_raw, width_mm, height_mm, thickness_mm,
                price, currency, price_unit, sqm_per_piece,
                pcs_per_box, sqm_per_box, kg_per_box,
                price_variants, attributes, source_sheet, source_row
              FROM price_catalog.product_price_staging
             WHERE version_id = :vid
               AND import_error IS NULL
            ON CONFLICT ON CONSTRAINT uq_price DO UPDATE
               SET price        = EXCLUDED.price,
                   collection   = EXCLUDED.collection,
                   product_name = EXCLUDED.product_name,
                   color        = EXCLUDED.color,
                   width_mm     = EXCLUDED.width_mm,
                   height_mm    = EXCLUDED.height_mm,
                   thickness_mm = EXCLUDED.thickness_mm,
                   sqm_per_piece= EXCLUDED.sqm_per_piece,
                   pcs_per_box  = EXCLUDED.pcs_per_box,
                   sqm_per_box  = EXCLUDED.sqm_per_box,
                   kg_per_box   = EXCLUDED.kg_per_box,
                   price_variants = EXCLUDED.price_variants,
                   attributes   = EXCLUDED.attributes
            """,
            Map.of("vid", versionId)
        );

        // 2. incremental merge: copy old products not matched by any new-file row
        int retained = 0;
        if (prevVersionId != null) {
            retained = jdbc.update("""
                INSERT INTO price_catalog.product_prices (
                    factory_id, version_id, product_code, grade, collection, product_name,
                    color, surface, size_raw, width_mm, height_mm, thickness_mm,
                    price, currency, price_unit, sqm_per_piece,
                    pcs_per_box, sqm_per_box, kg_per_box,
                    price_variants, attributes, source_sheet, source_row
                )
                SELECT
                    p.factory_id, :vid, p.product_code, p.grade, p.collection, p.product_name,
                    p.color, p.surface, p.size_raw, p.width_mm, p.height_mm, p.thickness_mm,
                    p.price, p.currency, p.price_unit, p.sqm_per_piece,
                    p.pcs_per_box, p.sqm_per_box, p.kg_per_box,
                    p.price_variants, p.attributes, p.source_sheet, p.source_row
                  FROM price_catalog.product_prices p
                 WHERE p.version_id = :prevVid
                   AND NOT EXISTS (
                       SELECT 1 FROM price_catalog.product_price_staging s
                        WHERE s.version_id = :vid
                          AND s.import_error IS NULL
                          AND (
                              (p.product_code IS NOT NULL AND s.product_code = p.product_code)
                              OR (p.product_code IS NULL AND p.product_name IS NOT NULL
                                  AND s.product_name = p.product_name)
                          )
                   )
                ON CONFLICT ON CONSTRAINT uq_price DO NOTHING
                """,
                Map.of("vid", versionId, "prevVid", prevVersionId)
            );
        }

        // 3. activate this version
        jdbc.update(
            "UPDATE price_catalog.price_list_versions SET status = 'ACTIVE' WHERE version_id = :vid",
            Map.of("vid", versionId)
        );

        // 4. archive previous ACTIVE versions of the same factory
        int archived = jdbc.update("""
            UPDATE price_catalog.price_list_versions
               SET status = 'ARCHIVED'
             WHERE status = 'ACTIVE'
               AND version_id <> :vid
               AND factory_id = (
                   SELECT factory_id FROM price_catalog.price_list_versions WHERE version_id = :vid
               )
            """,
            Map.of("vid", versionId)
        );

        // 5. delete staging (keep only error rows for audit)
        jdbc.update(
            "DELETE FROM price_catalog.product_price_staging WHERE version_id = :vid AND import_error IS NULL",
            Map.of("vid", versionId)
        );

        return new CommitResult(versionId, inserted, retained, archived);
    }

    // ── C3: version list ──────────────────────────────────────────────────────

    public List<Map<String, Object>> listVersions(long factoryId) {
        return jdbc.query(
            """
            SELECT v.version_id, v.label, v.source_file, v.status,
                   v.effective_from, v.uploaded_at, v.row_count, v.error_count,
                   f.name AS factory_name
              FROM price_catalog.price_list_versions v
              JOIN price_catalog.factories f USING (factory_id)
             WHERE v.factory_id = :fid
             ORDER BY v.uploaded_at DESC
            """,
            Map.of("fid", factoryId),
            (rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("versionId",    rs.getLong("version_id"));
                m.put("label",        rs.getString("label"));
                m.put("sourceFile",   rs.getString("source_file"));
                m.put("status",       rs.getString("status"));
                m.put("effectiveFrom",rs.getDate("effective_from"));
                m.put("uploadedAt",   rs.getTimestamp("uploaded_at"));
                m.put("rowCount",     rs.getInt("row_count"));
                m.put("errorCount",   rs.getInt("error_count"));
                m.put("factoryName",  rs.getString("factory_name"));
                return m;
            }
        );
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void requireDraft(long versionId) {
        String status = jdbc.queryForObject(
            "SELECT status FROM price_catalog.price_list_versions WHERE version_id = :vid",
            Map.of("vid", versionId), String.class
        );
        if (!"DRAFT".equals(status))
            throw new ApiException(HttpStatus.CONFLICT,
                "version " + versionId + " สถานะ " + status + " (ต้อง DRAFT)");
    }

    // ── response DTOs ─────────────────────────────────────────────────────────

    public record StagingReport(
        long versionId,
        int totalStaged,
        int validCount,
        int invalidCount,
        int newProducts,
        int removedProducts,
        int priceChanged,
        Long prevVersionId,
        List<String> sampleErrors
    ) {}

    public record CommitResult(
        long versionId,
        int committed,
        int retained,
        int versionsArchived
    ) {}

    public List<Map<String, Object>> listFactories() {
        return jdbc.query(
            "SELECT factory_id, name, country, default_currency FROM price_catalog.factories ORDER BY name",
            Map.of(),
            (rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("factoryId",       rs.getLong("factory_id"));
                m.put("name",            rs.getString("name"));
                m.put("country",         rs.getString("country"));
                m.put("defaultCurrency", rs.getString("default_currency"));
                return m;
            }
        );
    }

    // ── C4: profile management ────────────────────────────────────────────────

    public String getRawProfile(long factoryId) {
        return jdbc.queryForObject(
            "SELECT config FROM price_catalog.import_profiles WHERE factory_id = :fid",
            Map.of("fid", factoryId), String.class
        );
    }

    @Transactional
    public void updateProfile(long factoryId, String configJson) {
        // validate it can be parsed before saving
        try { objectMapper.readValue(configJson, ImportProfile.class); }
        catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid profile JSON: " + e.getMessage());
        }
        int updated = jdbc.update(
            "UPDATE price_catalog.import_profiles SET config = :cfg::jsonb, updated_at = now() WHERE factory_id = :fid",
            Map.of("cfg", configJson, "fid", factoryId)
        );
        if (updated == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบ profile สำหรับ factory id=" + factoryId);
    }

    private String toJson(Object v) {
        if (v == null) return null;
        try { return objectMapper.writeValueAsString(v); }
        catch (JsonProcessingException e) { return null; }
    }

    // ── response DTO ──────────────────────────────────────────────────────────

    public record UploadReport(
        long versionId,
        UUID sessionId,
        int parsedRows,
        int errorCount,
        List<String> errors
    ) {}

    // ── C5: create factory ────────────────────────────────────────────────────

    public Map<String, Object> createFactory(String name, String country, String defaultCurrency) {
        String cur = defaultCurrency != null && !defaultCurrency.isBlank()
            ? defaultCurrency.strip().toUpperCase() : "EUR";
        String cty = country != null && !country.isBlank() ? country.strip().toUpperCase() : null;

        var holder = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO price_catalog.factories (name, country, default_currency) VALUES (:name, :country, :cur)",
            new MapSqlParameterSource()
                .addValue("name",    name.strip())
                .addValue("country", cty)
                .addValue("cur",     cur),
            holder, new String[]{"factory_id"}
        );
        long factoryId = ((Number) holder.getKeys().get("factory_id")).longValue();

        String blankCfg = String.format(
            "{\"number_format\":\"eu\",\"sheets\":[],\"columns\":{},\"defaults\":{\"currency\":\"%s\"}}", cur);
        jdbc.update(
            "INSERT INTO price_catalog.import_profiles (factory_id, config) VALUES (:fid, CAST(:cfg AS jsonb))",
            Map.of("fid", factoryId, "cfg", blankCfg)
        );

        Map<String, Object> m = new HashMap<>();
        m.put("factoryId",       factoryId);
        m.put("name",            name.strip());
        m.put("country",         cty);
        m.put("defaultCurrency", cur);
        return m;
    }

    // ── C5: upload + auto commit ──────────────────────────────────────────────

    @Transactional
    public UploadCommitResult uploadAndCommit(
        long factoryId, String originalFilename,
        InputStream fileStream, String label, long uploadedBy
    ) {
        ImportProfile prof = loadProfile(factoryId);
        ImportResult result;
        try {
            result = engine.parse(fileStream, prof, factoryId);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "parse failed: " + e.getMessage());
        }

        long versionId = createDraftVersion(factoryId, label, originalFilename,
            uploadedBy, result.rows().size(), result.errors().size());
        UUID sessionId = UUID.randomUUID();
        bulkInsertStaging(versionId, factoryId, result.rows(), sessionId);
        validate(versionId);

        CommitResult cr = commit(versionId, uploadedBy);

        List<String> errors = new ArrayList<>(result.errors());
        int skipped = result.rows().size() - cr.committed();
        if (skipped > 0) errors.add(skipped + " แถวถูกข้ามเพราะรหัสซ้ำ");

        return new UploadCommitResult(versionId, result.rows().size(), cr.committed(),
            cr.retained(), result.errors().size() + skipped, errors);
    }

    public record UploadCommitResult(
        long versionId, int parsedRows, int committedRows, int retainedRows, int errorCount, List<String> errors
    ) {}

    // ── C5: product CRUD ──────────────────────────────────────────────────────

    private long findOrCreateManualVersion(long factoryId) {
        List<Long> active = jdbc.query(
            """
            SELECT version_id FROM price_catalog.price_list_versions
             WHERE factory_id = :fid AND status = 'ACTIVE'
             ORDER BY uploaded_at DESC LIMIT 1
            """,
            Map.of("fid", factoryId),
            (rs, i) -> rs.getLong("version_id")
        );
        if (!active.isEmpty()) return active.get(0);

        var holder = new GeneratedKeyHolder();
        jdbc.update(
            """
            INSERT INTO price_catalog.price_list_versions
                (factory_id, label, status, uploaded_at, row_count, error_count)
            VALUES (:fid, 'ป้อนด้วยตนเอง', 'ACTIVE', :now, 0, 0)
            """,
            new MapSqlParameterSource()
                .addValue("fid", factoryId)
                .addValue("now", Timestamp.from(Instant.now())),
            holder, new String[]{"version_id"}
        );
        return ((Number) holder.getKeys().get("version_id")).longValue();
    }

    public long addProductManual(long factoryId, ProductPriceInput in) {
        long versionId = findOrCreateManualVersion(factoryId);
        Long priceId = jdbc.queryForObject(
            """
            INSERT INTO price_catalog.product_prices (
                factory_id, version_id, product_code, grade, collection, product_name,
                color, surface, size_raw, price, currency, price_unit
            ) VALUES (
                :fid, :vid, :code, :grade, :col, :name,
                :color, :surf, :sizeRaw, :price, :cur, :unit
            )
            ON CONFLICT ON CONSTRAINT uq_price DO UPDATE
                SET price        = EXCLUDED.price,
                    product_name = COALESCE(EXCLUDED.product_name, product_prices.product_name),
                    collection   = COALESCE(EXCLUDED.collection,   product_prices.collection),
                    color        = COALESCE(EXCLUDED.color,        product_prices.color)
            RETURNING price_id
            """,
            new MapSqlParameterSource()
                .addValue("fid",     factoryId)
                .addValue("vid",     versionId)
                .addValue("code",    in.productCode())
                .addValue("grade",   in.grade())
                .addValue("col",     in.collection())
                .addValue("name",    in.productName())
                .addValue("color",   in.color())
                .addValue("surf",    in.surface())
                .addValue("sizeRaw", in.sizeRaw())
                .addValue("price",   in.price())
                .addValue("cur",     in.currency() != null ? in.currency() : "EUR")
                .addValue("unit",    in.priceUnit() != null ? in.priceUnit() : "per_sqm"),
            Long.class
        );
        return priceId != null ? priceId : -1L;
    }

    public void updateProduct(long priceId, ProductPriceInput in) {
        int updated = jdbc.update(
            """
            UPDATE price_catalog.product_prices SET
                product_code = :code,   grade        = :grade, collection   = :col,
                product_name = :name,   color        = :color, surface      = :surf,
                size_raw     = :sizeRaw, price       = :price, currency     = :cur,
                price_unit   = :unit
            WHERE price_id = :pid
            """,
            new MapSqlParameterSource()
                .addValue("pid",     priceId)
                .addValue("code",    in.productCode())
                .addValue("grade",   in.grade())
                .addValue("col",     in.collection())
                .addValue("name",    in.productName())
                .addValue("color",   in.color())
                .addValue("surf",    in.surface())
                .addValue("sizeRaw", in.sizeRaw())
                .addValue("price",   in.price())
                .addValue("cur",     in.currency())
                .addValue("unit",    in.priceUnit())
        );
        if (updated == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสินค้า price_id=" + priceId);
    }

    public void deleteProduct(long priceId) {
        int deleted = jdbc.update(
            "DELETE FROM price_catalog.product_prices WHERE price_id = :pid",
            Map.of("pid", priceId)
        );
        if (deleted == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสินค้า price_id=" + priceId);
    }
}
