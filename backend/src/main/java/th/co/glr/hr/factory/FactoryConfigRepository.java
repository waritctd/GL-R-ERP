package th.co.glr.hr.factory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Factory master data — name/country/currency plus the RFQ email/unit columns.
 *
 * <p>Mirrors {@code price_catalog.factories} — the REAL 9 factories (Bode, CDE, CITY, Equipe, LEA,
 * Padana, Panaria, REFIN, Vives; see V40/V42/V151). Until V163 this class read a completely
 * different table, {@code sales.factory_config} (V25) — a separate "RFQ email directory" joined to
 * the real factories only by matching free-text names, a join that matched ZERO rows in production
 * (see V163's own header). V163 folded factory_config's email/unit/notes columns onto
 * price_catalog.factories and dropped factory_config outright, so this repository now reads and
 * writes the single, real master-data table directly. The class/DTO name ({@code
 * FactoryConfigRepository}/{@code FactoryConfigDto}) is kept because {@code GET
 * /api/factory-configs} and its consumers ({@link th.co.glr.hr.pricingcosting.LandedCostCalculator},
 * {@link th.co.glr.hr.factoryquote.FactoryQuoteService}) still expect this shape; only the table
 * underneath changed.
 */
@Repository
public class FactoryConfigRepository {
    private static final String SELECT_COLUMNS =
        "factory_id, name, email, default_currency, unit, country, country_other";

    private final NamedParameterJdbcTemplate jdbc;

    public FactoryConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FactoryConfigDto> findAll() {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM price_catalog.factories ORDER BY name",
            Map.of(),
            (rs, i) -> map(rs));
    }

    /** REVIEW ROUND 1, S6: lets {@code PriceImportService#updateFactory} read the CURRENT stored
     * row (in particular {@code country}/{@code countryOther}) before deciding what to keep. */
    public Optional<FactoryConfigDto> findById(long factoryId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT " + SELECT_COLUMNS + " FROM price_catalog.factories WHERE factory_id = :id",
                Map.of("id", factoryId),
                (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FactoryConfigDto> findByName(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT " + SELECT_COLUMNS + " FROM price_catalog.factories WHERE name = :name",
                Map.of("name", name),
                (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** True if {@code countryCode} (ISO 3166-1 alpha-2, upper-case) exists in price_catalog.country. */
    public boolean countryExists(String countryCode) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM price_catalog.country WHERE country_code = :code)",
            Map.of("code", countryCode), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** True if some OTHER factory (not {@code excludeFactoryId}) already has this exact name. */
    public boolean existsNameExcluding(String name, long excludeFactoryId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM price_catalog.factories WHERE name = :name AND factory_id <> :id)",
            new MapSqlParameterSource().addValue("name", name).addValue("id", excludeFactoryId),
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Inserts a new factory master-data row. {@code countryCode} must already be validated against
     * {@link #countryExists} by the caller ({@code PriceImportService.createFactory}) — {@code
     * country} is NOT NULL + FK (V151), so an unvalidated blank/unknown code would otherwise
     * surface as a 500 via {@code ApiExceptionHandler.handleDataAccess} instead of a clean 400.
     *
     * @param countryOther the free-text "which country" typed alongside {@code 'ZZ'} (อื่นๆ) — the
     *                     caller ({@code PriceImportService#requireValidCountryOther}) must have
     *                     already enforced the pairing (present only for {@code 'ZZ'}, otherwise
     *                     null) that {@code chk_factories_country_other_required_for_zz}/{@code
     *                     _only_for_zz} (V184) also backstop at the DB layer.
     */
    public long create(String name, String countryCode, String countryOther, String defaultCurrency,
                       String email, String unit) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO price_catalog.factories (name, country, country_other, default_currency, email, unit)
            VALUES (:name, :country, :countryOther, :currency, :email, :unit)
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("country", countryCode)
                .addValue("countryOther", countryOther)
                .addValue("currency", defaultCurrency)
                .addValue("email", email)
                .addValue("unit", unit),
            holder, new String[]{"factory_id"});
        return holder.getKey().longValue();
    }

    /** @return rows affected — 0 means {@code factoryId} does not exist. See {@link #create} for
     * {@code countryOther}'s pairing rule. */
    public int update(long factoryId, String name, String countryCode, String countryOther,
                      String defaultCurrency, String email, String unit) {
        return jdbc.update("""
            UPDATE price_catalog.factories
               SET name = :name,
                   country = :country,
                   country_other = :countryOther,
                   default_currency = :currency,
                   email = :email,
                   unit = :unit
             WHERE factory_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("country", countryCode)
                .addValue("countryOther", countryOther)
                .addValue("currency", defaultCurrency)
                .addValue("email", email)
                .addValue("unit", unit)
                .addValue("id", factoryId));
    }

    /**
     * Serializes concurrent auto-create attempts for the SAME normalized name (owner decision 2d,
     * import-request-per-factory-PLAN.md) so two requests racing to create "the same" factory (e.g.
     * two lines on two different tickets both naming a brand-new supplier, in two concurrent
     * transactions) cannot produce two rows.
     *
     * <p>A lock was chosen over a unique index on the normalized name: a unique index would need to
     * be proven safe against whatever factory rows already exist in every environment this migrates
     * into (a failing index CREATE fails the whole prod boot — see V151's own DO-block precedent for
     * how seriously this codebase treats that risk), and that cannot be proven without querying real
     * prod data, which this session has no access to. {@code pg_advisory_xact_lock} needs no such
     * proof: it is transaction-scoped (released automatically at commit/rollback, so it cannot leak
     * across pooled connections — see {@code AbstractPostgresIntegrationTest}'s own note on exactly
     * this primitive) and is the established pattern in this codebase ({@code
     * PricingRequestRepository#lockPricingRequest}). Caller MUST hold this lock for the remainder of
     * the transaction that does the find-or-create, i.e. call it first.
     *
     * <p>REVIEW ROUND 1 nit: uses the two-int-key {@code pg_advisory_xact_lock} overload — a
     * separate keyspace in Postgres from the single-bigint overload {@code
     * PricingDecisionRepository#lockPricingRequest} and its siblings already use — matching {@code
     * PayrollRepository#lockInputDraftMonth}'s own precedent, so a factory-name lock can never
     * collide with an unrelated domain's single-key advisory lock even in the (extremely unlikely)
     * event of a hash collision. {@code LOCK_CLASS_ID} is an arbitrary constant fixed to this table;
     * {@code hashtext(normalizedName)} folds the name into the second int.
     */
    private static final int LOCK_CLASS_ID = 0x46414354; // "FACT", arbitrary but stable

    public void lockFactoryName(String normalizedName) {
        jdbc.query("SELECT pg_advisory_xact_lock(:classId, hashtext(:key))",
            new MapSqlParameterSource().addValue("classId", LOCK_CLASS_ID).addValue("key", normalizedName),
            (rs, rowNum) -> 0);
    }

    /**
     * Auto-creates a factory master row from an unresolved ใบขอซื้อ line (owner decision 2, revised
     * 2026-09-18). Callers MUST have already: (a) held {@link #lockFactoryName} for this
     * transaction, (b) confirmed no existing row matches {@code name} after normalization ({@code
     * ImportRequestService#normalizeFactoryName}) — this method itself does not re-check, by
     * design, so the caller's find-then-create stays one visible operation to a test — and (c)
     * validated {@code countryCode}/{@code countryOther} ({@code
     * ImportRequestService#requireValidNewFactoryCountry}, the same pairing rule {@link #create}
     * documents).
     *
     * <p>Country is no longer defaulted to a silent sentinel (the 09-18 owner decision superseding
     * the earlier 'XX' draft): the caller supplies it, because a REAL country the caller typed is
     * always better than one this method invents. {@code created_source = 'IMPORT_REQUEST'} +
     * {@code created_by_id}/{@code created_at} record the provenance so this row is findable later —
     * see V184's comment on {@code created_source}.
     */
    public long createFromImportRequest(String name, String countryCode, String countryOther, long createdById) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO price_catalog.factories
                (name, country, country_other, created_source, created_by_id, created_at)
            VALUES (:name, :country, :countryOther, 'IMPORT_REQUEST', :createdBy, now())
            """,
            new MapSqlParameterSource().addValue("name", name).addValue("country", countryCode)
                .addValue("countryOther", countryOther).addValue("createdBy", createdById),
            holder, new String[]{"factory_id"});
        return holder.getKey().longValue();
    }

    /** {@code price_catalog.country} options for the factory-editor country picker, so the UI can
     * offer a select instead of the free-text field that used to raise a 500 on a typo.
     *
     * <p>{@code 'ZZ'} (อื่นๆ / "other", V184's catch-all — see that migration's own comment) sorts
     * LAST rather than alphabetically by its Thai name, so a real, known country is never pushed
     * down the list by the one option that means "I don't know". */
    public List<CountryOptionDto> listCountries() {
        return jdbc.query(
            "SELECT country_code, name_en, name_th FROM price_catalog.country"
                + " ORDER BY (country_code = 'ZZ'), name_th",
            Map.of(),
            (rs, i) -> new CountryOptionDto(
                rs.getString("country_code"), rs.getString("name_en"), rs.getString("name_th")));
    }

    private FactoryConfigDto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FactoryConfigDto(
            rs.getLong("factory_id"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("default_currency"),
            rs.getString("unit"),
            rs.getString("country"),
            rs.getString("country_other"));
    }

    public record CountryOptionDto(String countryCode, String nameEn, String nameTh) {}
}
