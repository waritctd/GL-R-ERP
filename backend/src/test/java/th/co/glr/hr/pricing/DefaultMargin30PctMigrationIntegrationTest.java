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

/** BUSINESS-LOGIC CHANGE, owner-requested 2026-10-01: the default margin (กำไร) on sales.pricing_formula_config
 * moves from 20% to 30% (migration V197). The last is proven by re-running the migration file's own text
 * against rows the test sets up, since the golden-template DB has already applied it. */
class DefaultMargin30PctMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String MIGRATION = "/db/migration/V197__pricing_formula_default_margin_30pct.sql";

    private PricingFormulaConfigRepository formulaConfigs;

    @BeforeEach
    void wireRepository() {
        formulaConfigs = new PricingFormulaConfigRepository(jdbc);
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
