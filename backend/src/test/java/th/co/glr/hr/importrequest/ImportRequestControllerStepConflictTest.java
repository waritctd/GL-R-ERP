package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UndoImportStepRequest;

/**
 * Review H1 (slice 5): advance and undo both lock the ใบขอซื้อ row and the deal. Should Postgres ever pick one of them
 * as a deadlock / serialization victim, the caller gets the same Thai 409 a lost compare-and-set gets, never a 500.
 * The translation happens at the controller, OUTSIDE the @Transactional proxy, because a serialization failure can
 * surface at commit. The real-database lock order is pinned by ImportStepLockOrderIntegrationTest; the service's
 * {@code stepUndoUntil} is pinned by ImportStepUndoIntegrationTest.
 */
class ImportRequestControllerStepConflictTest {

    private final ImportRequestService service = mock(ImportRequestService.class);
    private final SessionContext sessions = mock(SessionContext.class);
    private final HttpSession session = new MockHttpSession();
    private ImportRequestController controller;

    @BeforeEach
    void wire() {
        UserPrincipal user = new UserPrincipal(5L, "imp@glr.co.th", "import", "import", 5L, true,
            LocalDate.of(2020, 1, 1), false, null, false);
        when(sessions.requireUser(any())).thenReturn(user);
        controller = new ImportRequestController(service, sessions);
    }

    @Test
    void aDeadlockVictimOnUndoIsThe409_notA500() {
        when(service.undoStep(anyLong(), any(), any())).thenThrow(new PessimisticLockingFailureException("40P01 deadlock detected"));
        assertThatThrownBy(() -> controller.undoStep(1L, new UndoImportStepRequest("ORDERED"), session))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aLockOrSerializationFailureOnAdvanceIsThe409_notA500() {
        when(service.advanceStep(anyLong(), any(), any())).thenThrow(new CannotAcquireLockException("40001"));
        assertThatThrownBy(() -> controller.advanceStep(1L, new AdvanceImportStepRequest("ORDERED", null, null), session))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anAdvanceAnswersWithWhenItsUndoWindowCloses() {
        Instant until = Instant.parse("2026-11-15T03:00:30Z");
        when(service.advanceStep(anyLong(), any(), any())).thenReturn(null);
        when(service.stepUndoUntil(1L)).thenReturn(until);
        Map<String, Object> body = controller.advanceStep(1L, new AdvanceImportStepRequest("ORDERED", null, null), session);
        assertThat(body).containsEntry("undoUntil", until);
    }
}
