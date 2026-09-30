package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.ClearanceFeeRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.CreatePricingFormulaConfigRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.DutyRateRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.FreightRateRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * BUSINESS-LOGIC CHANGE, owner-requested 2026-10-01: the default margin (กำไร) on
 * {@code sales.pricing_formula_config} moves from 20% to 30% (migration V198).
 *
 * <p>Real-Postgres proof of three things Mockito cannot reach: the seeded current config really
 * is 30% after a fresh migrate; the column DEFAULT really changed; and -- the guard that
 * matters -- a CEO who already customised the margin (e.g. 24%) is NOT overwritten when the
 * migration's SQL runs against their data. The last is proven by re-running the migration file's
 * own text against rows the test sets up, since the golden-template DB has already applied it.
 */
class DefaultMargin30PctMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String MIGRATION = "/db/migration/V198__pricing_formula_default_margin_30pct.sql";

    private PricingFormulaConfigRepository formulaConfigs;

    @BeforeEach
    void wireRepository() {
        formulaConfigs = new PricingFormulaConfigRepository(jdbc);
    }

    @Test
    void freshMigrate_currentConfigDefaultMarginIs30Percent() {
        PricingFormulaConfigDto current = formulaConfigs.findCurrent().orElseThrow();

        assertThat(current.defaultMarginPct()).isEqualByComparingTo("0.300000");
        assertThat(current.isCurrent()).isTrue();
    }

    @Test
    void freshMigrate_publishesANewVersionAndLeavesTheV109SeedRowUntouchedAsHistory() {
        PricingFormulaConfigDto current = formulaConfigs.findCurrent().orElseThrow();

        assertThat(current.version()).isEqualTo(2);
        Map<String, Object> v1 = jdbc.queryForMap(
            "SELECT default_margin_pct, is_current FROM sales.pricing_formula_config WHERE version = 1", Map.of());
        // Same audit rule the CEO service follows: the old row is never UPDATEd, only flipped.
        assertThat((BigDecimal) v1.get("default_margin_pct")).isEqualByComparingTo("0.200000");
        assertThat(v1.get("is_current")).isEqualTo(false);
    }

    @Test
    void freshMigrate_newVersionCopiesEveryOtherValueAndAllChildRows() {
        PricingFormulaConfigDto v2 = formulaConfigs.findCurrent().orElseThrow();
        Map<String, Object> v1 = jdbc.queryForMap("""
            SELECT formula_config_id, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer,
                   selling_buffer, selling_price_round_up_to
              FROM sales.pricing_formula_config WHERE version = 1
            """, Map.of());

        assertThat(v2.insuranceValueFactor()).isEqualByComparingTo((BigDecimal) v1.get("insurance_value_factor"));
        assertThat(v2.insuranceRate()).isEqualByComparingTo((BigDecimal) v1.get("insurance_rate"));
        assertThat(v2.insuranceBuffer()).isEqualByComparingTo((BigDecimal) v1.get("insurance_buffer"));
        assertThat(v2.costBuffer()).isEqualByComparingTo((BigDecimal) v1.get("cost_buffer"));
        assertThat(v2.sellingBuffer()).isEqualByComparingTo((BigDecimal) v1.get("selling_buffer"));
        assertThat(v2.sellingPriceRoundUpTo()).isEqualByComparingTo((BigDecimal) v1.get("selling_price_round_up_to"));

        long v1Id = ((Number) v1.get("formula_config_id")).longValue();
        // Must be a genuinely NEW row (not v1 itself) -- otherwise the copy assertions below are vacuous.
        assertThat(v2.formulaConfigId()).isNotEqualTo(v1Id);
        assertThat(v2.freightRates()).hasSize(childCount("pricing_freight_rate", v1Id)).hasSize(39);
        assertThat(v2.dutyRates()).hasSize(childCount("pricing_duty_rate", v1Id)).hasSize(2);
        assertThat(v2.clearanceFees()).hasSize(childCount("pricing_clearance_fee", v1Id)).hasSize(4);
        // A spot value, not just a count: the copy must carry the amounts, not blank rows.
        assertThat(v2.freightRates()).anyMatch(r -> "CN".equals(r.originCountryCode())
            && r.thicknessMinMm().compareTo(new BigDecimal("3")) == 0
            && r.qtyMinSqm().compareTo(new BigDecimal("1")) == 0
            && r.amountThb().compareTo(new BigDecimal("60000")) == 0);
    }

    @Test
    void columnDefaultIsNow30Percent() {
        String columnDefault = jdbc.queryForObject("""
            SELECT column_default FROM information_schema.columns
             WHERE table_schema = 'sales' AND table_name = 'pricing_formula_config'
               AND column_name = 'default_margin_pct'
            """, Map.of(), String.class);

        assertThat(columnDefault).startsWith("0.3");
    }

    @Test
    void ceoCustomisedMargin_isNotOverwrittenWhenTheMigrationSqlRuns() {
        PricingFormulaConfigDto customised = formulaConfigs.createNewVersion(requestWithMargin("0.24"), null);
        int versionsBefore = versionCount();

        runMigrationSql();

        PricingFormulaConfigDto after = formulaConfigs.findCurrent().orElseThrow();
        assertThat(after.formulaConfigId()).isEqualTo(customised.formulaConfigId());
        assertThat(after.defaultMarginPct()).isEqualByComparingTo("0.24");
        assertThat(versionCount()).isEqualTo(versionsBefore);
    }

    @Test
    void stillUntouchedTwentyPercent_isRaisedTo30AndPublishedAsANewCurrentVersion() {
        // Simulates a database where the CEO never touched the 20% seed default.
        PricingFormulaConfigDto untouched = formulaConfigs.createNewVersion(requestWithMargin("0.20"), null);
        int versionsBefore = versionCount();

        runMigrationSql();

        PricingFormulaConfigDto after = formulaConfigs.findCurrent().orElseThrow();
        assertThat(after.defaultMarginPct()).isEqualByComparingTo("0.30");
        assertThat(after.version()).isEqualTo(untouched.version() + 1);
        assertThat(versionCount()).isEqualTo(versionsBefore + 1);
        // The 20% row it replaced is kept as history, not edited.
        BigDecimal oldMargin = jdbc.queryForObject(
            "SELECT default_margin_pct FROM sales.pricing_formula_config WHERE formula_config_id = :id",
            Map.of("id", untouched.formulaConfigId()), BigDecimal.class);
        assertThat(oldMargin).isEqualByComparingTo("0.20");
        // Its single child rows were copied across, so the new current version is still priceable.
        assertThat(after.freightRates()).hasSize(1);
        assertThat(after.dutyRates()).hasSize(1);
        assertThat(after.clearanceFees()).hasSize(1);
    }

    @Test
    void runningTheMigrationSqlTwice_doesNotPublishASecondVersion() {
        int versionsBefore = versionCount();

        runMigrationSql();

        assertThat(versionCount()).isEqualTo(versionsBefore);
        assertThat(formulaConfigs.findCurrent().orElseThrow().defaultMarginPct()).isEqualByComparingTo("0.30");
    }

    private CreatePricingFormulaConfigRequest requestWithMargin(String margin) {
        return new CreatePricingFormulaConfigRequest(
            new BigDecimal("1.15"), new BigDecimal("0.0045"), new BigDecimal("1.07"),
            new BigDecimal("1.07"), new BigDecimal("1.07"), new BigDecimal(margin),
            new BigDecimal("10"), null,
            List.of(new FreightRateRequest("CN", new BigDecimal("3"), new BigDecimal("8"),
                new BigDecimal("1"), null, new BigDecimal("60000"))),
            List.of(new DutyRateRequest("TILE", "กระเบื้อง", new BigDecimal("0.30"))),
            List.of(new ClearanceFeeRequest(new BigDecimal("1"), null, new BigDecimal("8000"))));
    }

    private int childCount(String table, long formulaConfigId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales." + table + " WHERE formula_config_id = :id",
            Map.of("id", formulaConfigId), Integer.class);
    }

    private int versionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.pricing_formula_config", Map.of(), Integer.class);
    }

    private void runMigrationSql() {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration file %s must exist on the classpath", MIGRATION).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Plain JDBC, not NamedParameterJdbcTemplate: the script contains PL/pgSQL (':=').
            jdbc.getJdbcOperations().execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
