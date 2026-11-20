package th.co.glr.hr.activity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;

/** Proves the admin gate picks the right branch. This is step 1 of CLAUDE.md's two-step requirement for an
 * authorization change; ActivityLogAuthzIntegrationTest is step 2, and a mocked repository here cannot stand
 * in for it — a mock happily "passes" while the SQL does something else entirely. */
class ActivityLogServiceTest {

    private final ActivityLogRepository repository = mock(ActivityLogRepository.class);
    private final ActivityLogService service = new ActivityLogService(repository);

    @Test
    void admitsAFlaggedAdminWhateverTheirRole() {
        when(repository.isAdmin(216L)).thenReturn(true);
        when(repository.findRecent(any(), any(), any(), anyInt())).thenReturn(List.of());

        // วริศรา is division SA, so her derived role is `sales` — the gate must not care.
        service.list(principal(216L, "sales"), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31),
            null, null);

        verify(repository).findRecent(any(), any(), eq(null), eq(ActivityLogService.DEFAULT_LIMIT));
    }

    private UserPrincipal principal(long id, String role) {
        return new UserPrincipal(id, "u" + id + "@glr.co.th", "U" + id, role, id, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
