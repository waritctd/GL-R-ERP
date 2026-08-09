package th.co.glr.hr.payroll.declaration;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared fixture for the one precondition every successful approve now has: a signed ล.ย.01.
 *
 * <p>{@code TaxAllowanceDeclarationService#approve} refuses a declaration with no {@code
 * signed_form} attachment (owner decision #3), so sixteen fixtures across five classes that used to
 * approve a bare declaration would otherwise fail. Extracted here rather than copied into each of
 * them: the alternative is five subtly different inserts, and the day the attachment shape changes
 * only some of them get fixed.
 *
 * <p>Inserts the row directly rather than going through {@code FileStorageService} deliberately —
 * these tests are about the approve workflow, not about upload plumbing, and a multipart round trip
 * in every one of them would be slower and would couple them to a component they do not exercise.
 * {@code TaxAllowanceAttachmentScopeIntegrationTest} is where the real upload path is tested.
 */
final class TaxAllowanceTestSupport {

    private TaxAllowanceTestSupport() {
    }

    static void attachSignedForm(NamedParameterJdbcTemplate jdbc, long declarationId) {
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, section_key, storage_state)
            VALUES ('tax_allowance_declaration', :ownerId, 'signed-loryor01.pdf', 'test://signed-form',
                    'application/pdf', 2048, 'signed_form', 'DATABASE')
            """, Map.of("ownerId", declarationId));
    }
}
