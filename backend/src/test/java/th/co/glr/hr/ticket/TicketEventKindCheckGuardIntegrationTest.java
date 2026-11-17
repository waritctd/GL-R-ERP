package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Postgres cannot append to a CHECK, so every migration that adds a ticket event kind re-declares
 * {@code sales.ticket_event.chk_event_kind} IN FULL. Two migrations numbered concurrently each carry the list they saw,
 * so the later one silently drops the earlier one's kind, and the first write of that kind fails in production.
 * This reads every migration that declares the constraint, unions every kind ever declared, and asserts the LIVE
 * constraint (after all migrations) still accepts each of them.
 */
class TicketEventKindCheckGuardIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Pattern DECLARATION = Pattern.compile("chk_event_kind\\s+CHECK\\s*\\(");
    private static final Pattern QUOTED_KIND = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    void theLiveConstraintAcceptsEveryKindAnyMigrationEverDeclared() throws Exception {
        Set<String> declared = new TreeSet<>();
        int declaringFiles = 0;
        for (Resource migration : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            // Comments name kinds too ("re-declare chk_event_kind without X"); only the CHECK list counts.
            String sql = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("--[^\n]*", "");
            Matcher declaration = DECLARATION.matcher(sql);
            while (declaration.find()) {
                int depth = 1;
                int end = declaration.end();
                while (depth > 0) {
                    char c = sql.charAt(end++);
                    depth += c == '(' ? 1 : c == ')' ? -1 : 0;
                }
                Matcher kind = QUOTED_KIND.matcher(sql.substring(declaration.end(), end));
                while (kind.find()) {
                    declared.add(kind.group(1));
                }
                declaringFiles++;
            }
        }
        assertThat(declaringFiles).as("the migrations that declare chk_event_kind were found").isGreaterThan(10);
        assertThat(declared).as("sanity: the parse saw the old and the new kinds")
            .contains("CREATED", "DEAL_PROMOTED_FROM_QUOTATION", TicketEventKind.ORDER_CONFIRMATION_UNDONE);

        String live = jdbc.queryForObject("""
            SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'sales' AND t.relname = 'ticket_event' AND c.conname = 'chk_event_kind'
            """, Map.of(), String.class);

        assertThat(declared).as("every kind some migration declared is still allowed by the live constraint")
            .allSatisfy(kind -> assertThat(live).contains("'" + kind + "'"));
    }
}
