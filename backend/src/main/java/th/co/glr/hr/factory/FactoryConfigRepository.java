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
        "factory_id, name, email, default_currency, unit, country";

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
     */
    public long create(String name, String countryCode, String defaultCurrency, String email, String unit) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO price_catalog.factories (name, country, default_currency, email, unit)
            VALUES (:name, :country, :currency, :email, :unit)
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("country", countryCode)
                .addValue("currency", defaultCurrency)
                .addValue("email", email)
                .addValue("unit", unit),
            holder, new String[]{"factory_id"});
        return holder.getKey().longValue();
    }

    /** @return rows affected — 0 means {@code factoryId} does not exist. */
    public int update(long factoryId, String name, String countryCode, String defaultCurrency, String email,
                      String unit) {
        return jdbc.update("""
            UPDATE price_catalog.factories
               SET name = :name,
                   country = :country,
                   default_currency = :currency,
                   email = :email,
                   unit = :unit
             WHERE factory_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("country", countryCode)
                .addValue("currency", defaultCurrency)
                .addValue("email", email)
                .addValue("unit", unit)
                .addValue("id", factoryId));
    }

    /** {@code price_catalog.country} options for the factory-editor country picker, so the UI can
     * offer a select instead of the free-text field that used to raise a 500 on a typo. */
    public List<CountryOptionDto> listCountries() {
        return jdbc.query(
            "SELECT country_code, name_en, name_th FROM price_catalog.country ORDER BY name_th",
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
            rs.getString("country"));
    }

    public record CountryOptionDto(String countryCode, String nameEn, String nameTh) {}
}
