package th.co.glr.hr.commission;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Who may appear as a ผู้พิมพ์/พนักงานขาย print-name — the union of (a) active employees in the
 * sales division and (b) any active employee holding the {@code hr.employee.can_create_quotation}
 * grant (e.g. ภิญญดา, who is {@code qc} role, not sales division).
 *
 * <p>Extracted from {@code DealQuotationRepository} (V179, owner feedback #4, 2026-09-14) so a
 * SECOND caller could reuse the exact same predicate rather than copying it. That second caller is
 * {@code PricingRequestRepository} — the second review pass on V185/GLA-125 (finding N5,
 * 2026-09-19) found that {@code PricingRequestService.validateHeaderTerms} never validated its own
 * {@code printedByDisplayId}/{@code salesRepDisplayId} at all: an unknown id 500'd on the FK
 * constraint instead of a clean 400, and an ineligible employee (any active id, sales division or
 * not, holding no grant) was silently accepted. {@code DealQuotationRepository} keeps its own
 * public {@code isEligibleQuotationDisplayName}/{@code findEligibleQuotationDisplayNameOptions}
 * methods (its own callers, and the option-list DTO shape, stay unchanged) but both now delegate
 * to this class's static methods, so the predicate itself is defined in exactly one place. Static
 * and stateless on purpose: it needs nothing but the caller's own {@code NamedParameterJdbcTemplate}
 * as a parameter, which lets {@code PricingRequestRepository} (a plain {@code @Repository} with no
 * {@code DealQuotationRepository}/{@code CatalogRepository} dependency of its own) reuse it without
 * either class's constructor gaining a new bean — the alternative (injecting one repository into
 * the other, or a new shared {@code @Component}) would have forced every one of the ~30 hand-wired
 * test fixtures that construct {@code PricingRequestService}/{@code DealQuotationRepository}
 * directly (rather than through the Spring context) to learn a brand-new constructor argument for
 * a check most of them never exercise.
 */
public final class QuotationDisplayNameEligibility {

    private static final String ELIGIBLE_PREDICATE = """
        e.is_active AND (
            EXISTS (SELECT 1 FROM hr.division d WHERE d.division_id = e.division_id
                      AND LOWER(TRIM(COALESCE(NULLIF(TRIM(d.source_code), ''), split_part(d.name_th, '-', 1))))
                          = :salesDivisionCode)
            OR e.can_create_quotation
        )
        """;

    private QuotationDisplayNameEligibility() {
    }

    /** The options list for the ผู้พิมพ์/พนักงานขาย print-name selectors. */
    public static java.util.List<CommissionRepOptionDto> options(NamedParameterJdbcTemplate jdbc,
            String salesDivisionCode) {
        return jdbc.query("""
            SELECT e.employee_id,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.employee_code)
                       AS display_name
              FROM hr.employee e
             WHERE %s
             ORDER BY display_name, e.employee_id
            """.formatted(ELIGIBLE_PREDICATE),
            new MapSqlParameterSource().addValue("salesDivisionCode", salesDivisionCode),
            (rs, rowNum) -> new CommissionRepOptionDto(rs.getLong("employee_id"), rs.getString("display_name")));
    }

    /** Whether {@code employeeId} is in the SAME eligible union {@link #options} lists. */
    public static boolean isEligible(NamedParameterJdbcTemplate jdbc, long employeeId, String salesDivisionCode) {
        Boolean found = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM hr.employee e WHERE e.employee_id = :employeeId AND (%s))
            """.formatted(ELIGIBLE_PREDICATE),
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("salesDivisionCode", salesDivisionCode),
            Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
