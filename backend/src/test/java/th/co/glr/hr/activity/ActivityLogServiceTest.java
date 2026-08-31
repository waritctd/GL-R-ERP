package th.co.glr.hr.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * Proves the admin gate picks the right branch. This is step 1 of CLAUDE.md's two-step requirement
 * for an authorization change; {@link ActivityLogAuthzIntegrationTest} is step 2, and a mocked
 * repository here cannot stand in for it — a mock happily "passes" while the SQL does something
 * else entirely.
 */
class ActivityLogServiceTest {

    private final ActivityLogRepository repository = mock(ActivityLogRepository.class);
    private final ActivityLogService service = new ActivityLogService(repository);

    @Test
    void refusesACallerWhoIsNotAnAdmin() {
        when(repository.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.list(principal(42L, "hr"), null, null, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).findRecent(any(), any(), any(), anyInt());
    }

    @Test
    void refusesAnAnonymousCaller() {
        assertThatThrownBy(() -> service.list(null, null, null, null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).isAdmin(anyLong());
        verify(repository, never()).findRecent(any(), any(), any(), anyInt());
    }

    @Test
    void refusesTheSummaryForANonAdminToo() {
        // The gate has to sit on every entry point, not just the one anyone remembered to test.
        when(repository.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.summarize(principal(42L, "ceo"), null, null))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).summarize(any(), any());
    }

    @Test
    void neverConsultsTheDerivedRole() {
        // Admin is orthogonal to the role. No role may grant it, so a `ceo` who is not flagged is
        // refused exactly like anyone else — this is what stops the gate drifting into a role check.
        when(repository.isAdmin(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.list(principal(7L, "ceo"), null, null, null, null))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(principal(7L, "hr"), null, null, null, null))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(principal(7L, "sales_manager"), null, null, null, null))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void admitsAFlaggedAdminWhateverTheirRole() {
        when(repository.isAdmin(216L)).thenReturn(true);
        when(repository.findRecent(any(), any(), any(), anyInt())).thenReturn(List.of());

        // วริศรา is division SA, so her derived role is `sales` — the gate must not care.
        service.list(principal(216L, "sales"), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31),
            null, null);

        verify(repository).findRecent(any(), any(), eq(null), eq(ActivityLogService.DEFAULT_LIMIT));
    }

    @Test
    void clampsTheLimitToAFloorAndACeiling() {
        assertThat(ActivityLogService.clampLimit(null)).isEqualTo(ActivityLogService.DEFAULT_LIMIT);
        assertThat(ActivityLogService.clampLimit(0)).isEqualTo(ActivityLogService.DEFAULT_LIMIT);
        assertThat(ActivityLogService.clampLimit(-5)).isEqualTo(ActivityLogService.DEFAULT_LIMIT);
        assertThat(ActivityLogService.clampLimit(50)).isEqualTo(50);
        assertThat(ActivityLogService.clampLimit(999_999)).isEqualTo(ActivityLogService.MAX_LIMIT);
    }

    private UserPrincipal principal(long id, String role) {
        return new UserPrincipal(id, "u" + id + "@glr.co.th", "U" + id, role, id, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
