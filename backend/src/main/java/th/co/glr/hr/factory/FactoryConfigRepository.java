package th.co.glr.hr.factory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Factory master data — name/country/currency plus the RFQ email/unit columns. Mirrors price_catalog.factories
 * — the REAL 9 factories (Bode, CDE, CITY, Equipe, LEA, Padana, Panaria, REFIN, Vives; see V40/V42/V151). */
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

    /** REVIEW ROUND 1, S6: lets PriceImportService#updateFactory read the CURRENT stored row (in particular
     * country/countryOther) before deciding what to keep. */
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

    /** Inserts a new factory master-data row. */
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

    /** @return rows affected — 0 means factoryId does not exist. See #create for countryOther's pairing rule. */
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

    /** Serializes concurrent auto-create attempts for the SAME normalized name so two requests racing to create
     * "the same" factory cannot produce two rows. Caller MUST hold this lock for the remainder of the transaction
     * that does the find-or-create, i.e. call it first. */
    private static final int LOCK_CLASS_ID = 0x46414354; // "FACT", arbitrary but stable

    public void lockFactoryName(String normalizedName) {
        jdbc.query("SELECT pg_advisory_xact_lock(:classId, hashtext(:key))",
            new MapSqlParameterSource().addValue("classId", LOCK_CLASS_ID).addValue("key", normalizedName),
            (rs, rowNum) -> 0);
    }

    /** Auto-creates a factory master row from an unresolved ใบขอซื้อ line (owner decision 2, revised 2026-09-18).
     * created_source = 'IMPORT_REQUEST' + created_by_id/created_at record the provenance so this row is findable
     * later — see V184's comment on created_source. */
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

    /** Lists factory country options with the unknown-country code ZZ last. */
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
