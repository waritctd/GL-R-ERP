package th.co.glr.hr.salesboard.pipelineimport;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.commission.QuotationDisplayNameEligibility;

/**
 * Reads and blank-only writes for the pipeline import. Every deal lookup carries {@code t.created_by = :ownerId} in
 * its WHERE clause: a row resolved to rep A can never match — and so never touch — rep B's deal.
 */
@Repository
public class PipelineImportRepository {

    /** The SQL restatement of {@link PipelineFields#normalise}: trim, collapse whitespace, lower-case. */
    private static final String NORM = "lower(regexp_replace(btrim(%s), '\\s+', ' ', 'g'))";

    private final NamedParameterJdbcTemplate jdbc;

    public PipelineImportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An employee the rep column names. {@code eligible}: may own a deal (the เจ้าของงาน picker's set). */
    record Rep(long employeeId, String name, boolean eligible) {}

    /** By e-mail when the text has an '@', else by employee code; case-insensitive; an active employee first. */
    Optional<Rep> findRep(String codeOrEmail) {
        String key = codeOrEmail.strip();
        String where = key.contains("@")
            ? "e.email IS NOT NULL AND (LOWER(btrim(e.email)) = LOWER(:key)"
                + " OR LOWER(substring(e.email from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+')) = LOWER(:key))"
            : "LOWER(btrim(e.employee_code)) = LOWER(:key)";
        List<long[]> ids = jdbc.query("SELECT e.employee_id FROM hr.employee e WHERE " + where
                + " ORDER BY e.is_active DESC, e.employee_id LIMIT 1",
            Map.of("key", key), (rs, i) -> new long[] {rs.getLong(1)});
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0)[0];
        String name = jdbc.queryForObject("""
            SELECT COALESCE(NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), ''), employee_code, email)
              FROM hr.employee WHERE employee_id = :id""", Map.of("id", id), String.class);
        boolean eligible = QuotationDisplayNameEligibility.isEligible(jdbc, id,
            DivisionAccessPolicy.SALES_DIVISION_CODE);
        return Optional.of(new Rep(id, name, eligible));
    }

    /** The fields of an existing deal the import may fill when blank. */
    record ExistingDeal(long ticketId, String code, String lifecycle, String salesStage, String designerName,
                        String ownerName, String buyerName, String expectedQuarter, Integer winProbability,
                        BigDecimal importedValue, String importedQuotationNo, String note) {}

    private static final String DEAL_SELECT = """
        SELECT t.ticket_id, t.code, t.lifecycle, t.sales_stage, t.designer_name, t.owner_name, t.buyer_name,
               t.expected_quarter, t.win_probability, t.imported_value, t.imported_quotation_no, t.note,
               t.title, p.name AS project_name,
               (SELECT string_agg(q.number, ',') FROM sales.quotation q WHERE q.ticket_id = t.ticket_id)
                   AS quotation_numbers
          FROM sales.ticket t
          LEFT JOIN customers.project p ON p.project_id = t.project_id
         WHERE t.created_by = :ownerId
        """;

    /** An owner's deal with what the import matches on: its title, its project name, and every quotation number it
     * carries (the imported one(s) and its own quotations'), all as raw text — the caller folds them. */
    record OwnerDeal(ExistingDeal deal, String title, String projectName, String quotationNumbers,
                     List<String> quotationBases, String titleKey, String projectKey) {
        /** Folds once, at load: matching then compares precomputed keys for every row (review round 2, perf). */
        OwnerDeal(ExistingDeal deal, String title, String projectName, String quotationNumbers) {
            this(deal, title, projectName, quotationNumbers, PipelineFields.quotationBases(quotationNumbers),
                PipelineFields.normalise(title), projectName == null ? null : PipelineFields.normalise(projectName));
        }
    }

    /** Every deal of this owner, any lifecycle — the import decides; never another owner's deal. */
    List<OwnerDeal> ownerDeals(long ownerId) {
        return jdbc.query(DEAL_SELECT + " ORDER BY t.ticket_id", Map.of("ownerId", ownerId),
            (rs, i) -> new OwnerDeal(map(rs, i), rs.getString("title"), rs.getString("project_name"),
                joinNumbers(rs.getString("imported_quotation_no"), rs.getString("quotation_numbers"))));
    }

    private static String joinNumbers(String imported, String own) {
        if (imported == null) {
            return own;
        }
        return own == null ? imported : imported + "," + own;
    }

    /** id + name of every customer, for matching with {@code SalesNames#normaliseCustomer} (not expressible in
     * SQL). One read per analysis. */
    record CustomerName(long id, String name) {}

    List<CustomerName> customerNames() {
        return jdbc.query("SELECT customer_id, name FROM customers.customer ORDER BY customer_id", Map.of(),
            (rs, i) -> new CustomerName(rs.getLong(1), rs.getString(2)));
    }

    private ExistingDeal map(ResultSet rs, int rowNum) throws SQLException {
        int p = rs.getInt("win_probability");
        Integer probability = rs.wasNull() ? null : p;
        return new ExistingDeal(rs.getLong("ticket_id"), rs.getString("code"), rs.getString("lifecycle"),
            rs.getString("sales_stage"), rs.getString("designer_name"), rs.getString("owner_name"),
            rs.getString("buyer_name"), rs.getString("expected_quarter"), probability,
            rs.getBigDecimal("imported_value"), rs.getString("imported_quotation_no"), rs.getString("note"));
    }

    /** The values a row brings; null = the row says nothing about that field. */
    record Fill(String designerName, String ownerName, String buyerName, String expectedQuarter,
                Integer winProbability, BigDecimal importedValue, String importedQuotationNo, String note) {}

    /** Writes each non-null value only into a column that is NULL or blank — never over what is there. */
    void fillBlanks(long ticketId, Fill f) {
        jdbc.update("""
            UPDATE sales.ticket SET
                   designer_name = CASE WHEN NULLIF(btrim(designer_name), '') IS NULL
                                        THEN COALESCE(:designer, designer_name) ELSE designer_name END,
                   owner_name = CASE WHEN NULLIF(btrim(owner_name), '') IS NULL
                                     THEN COALESCE(:owner, owner_name) ELSE owner_name END,
                   buyer_name = CASE WHEN NULLIF(btrim(buyer_name), '') IS NULL
                                     THEN COALESCE(:buyer, buyer_name) ELSE buyer_name END,
                   expected_quarter = COALESCE(expected_quarter, :quarter),
                   win_probability = COALESCE(win_probability, :probability),
                   imported_value = COALESCE(imported_value, :value),
                   imported_quotation_no = CASE WHEN NULLIF(btrim(imported_quotation_no), '') IS NULL
                                                THEN COALESCE(:qn, imported_quotation_no)
                                                ELSE imported_quotation_no END,
                   note = CASE WHEN NULLIF(btrim(note), '') IS NULL THEN COALESCE(:note, note) ELSE note END,
                   updated_at = now()
             WHERE ticket_id = :id""",
            new MapSqlParameterSource()
                .addValue("id", ticketId)
                .addValue("designer", f.designerName())
                .addValue("owner", f.ownerName())
                .addValue("buyer", f.buyerName())
                .addValue("quarter", f.expectedQuarter())
                .addValue("probability", f.winProbability(), java.sql.Types.SMALLINT)
                .addValue("value", f.importedValue(), java.sql.Types.NUMERIC)
                .addValue("qn", f.importedQuotationNo())
                .addValue("note", f.note()));
    }

    /** The lowest-id project of that customer whose name normalises to the same text. */
    Optional<Long> projectByName(long customerId, String normalisedName) {
        return jdbc.query("SELECT project_id FROM customers.project WHERE customer_id = :customerId AND "
                + NORM.formatted("name") + " = :name ORDER BY project_id LIMIT 1",
            Map.of("customerId", customerId, "name", normalisedName), (rs, i) -> rs.getLong(1))
            .stream().findFirst();
    }

    /** Serialises concurrent commits for the whole transaction, so two uploads of one file cannot both create. */
    void lockForCommit() {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('sales.pipeline_import'))) l",
            Map.of(), Integer.class);
    }
}
