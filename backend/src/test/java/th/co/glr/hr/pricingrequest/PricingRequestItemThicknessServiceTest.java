package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.ThicknessDefaultRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemThicknessRequest;

/**
 * "Unit-test the decision" half of the CLAUDE.md authz-evidence requirement for {@link
 * PricingRequestItemThicknessService#setItemThickness} — proves the {@code
 * import}/{@code ceo}-only role gate runs, and runs FIRST (before any collaborator that could
 * only be proved against a real database), with every collaborator mocked so this class needs no
 * Postgres at all. {@code PricingRequestItemThicknessIntegrationTest} is the other half: it proves
 * the decision survives into real SQL (the {@code v_priceable_product} join, the {@code
 * collection_thickness_default} upsert) — something a mocked repository cannot demonstrate.
 *
 * <p>Written wrong-way-round: every rejected-role case also asserts NOTHING downstream was ever
 * called, so a role check that accidentally ran too late (after a collaborator was already
 * touched) would fail here even if the final exception still happened to be FORBIDDEN.
 */
class PricingRequestItemThicknessServiceTest {

    private final PricingRequestRepository requests = mock(PricingRequestRepository.class);
    private final PricingRequestService pricingRequestService = mock(PricingRequestService.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ThicknessDefaultRepository thicknessDefaults = mock(ThicknessDefaultRepository.class);
    private final PricingRequestItemThicknessService service = new PricingRequestItemThicknessService(
        requests, pricingRequestService, catalog, thicknessDefaults);

    private final SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

    private static UserPrincipal actor(String role) {
        return new UserPrincipal(1L, role + "@glr.co.th", "Actor", role, 1L, true, LocalDate.now(), false, null, false);
    }

    // ── wrong-way-round: every non-{import,ceo} role must be rejected, before touching anything ──

    @Test
    void rejectsSales_beforeTouchingAnyCollaborator() {
        assertRejectedWithoutSideEffects("sales");
    }

    @Test
    void rejectsSalesManager_beforeTouchingAnyCollaborator() {
        assertRejectedWithoutSideEffects("sales_manager");
    }

    @Test
    void rejectsAccount_beforeTouchingAnyCollaborator() {
        assertRejectedWithoutSideEffects("account");
    }

    @Test
    void rejectsPlainEmployee_beforeTouchingAnyCollaborator() {
        assertRejectedWithoutSideEffects("employee");
    }

    private void assertRejectedWithoutSideEffects(String role) {
        UserPrincipal wrongActor = actor(role);

        assertThatThrownBy(() -> service.setItemThickness(1L, 2L, request, wrongActor))
            .as("role '%s' must be rejected", role)
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // The strongest part of this assertion: a role check that ran AFTER, say, loading the
        // pricing request would still throw FORBIDDEN eventually, but would have already leaked a
        // side effect (or a timing/existence oracle) to a caller who should never have reached it.
        verifyNoInteractions(requests, pricingRequestService, catalog, thicknessDefaults);
    }

    // ── the two allowed roles pass the gate — proved by reaching the NEXT step, not by a fabricated success ──

    @Test
    void importPassesTheRoleGate_andReachesPricingRequestServiceGet() {
        UserPrincipal importActor = actor("import");
        // The role check is proved to have passed by observing what happens NEXT, not by faking a
        // full success: real success (the routing/refuse/write logic) is what
        // PricingRequestItemThicknessIntegrationTest proves against a real Postgres — a mocked
        // repository here would only be evidence about the mock.
        when(pricingRequestService.get(anyLong(), eq(importActor)))
            .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));

        assertThatThrownBy(() -> service.setItemThickness(1L, 2L, request, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(pricingRequestService).get(1L, importActor);
    }

    @Test
    void ceoPassesTheRoleGate_andReachesPricingRequestServiceGet() {
        UserPrincipal ceoActor = actor("ceo");
        when(pricingRequestService.get(anyLong(), eq(ceoActor)))
            .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));

        assertThatThrownBy(() -> service.setItemThickness(1L, 2L, request, ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(pricingRequestService).get(1L, ceoActor);
    }
}
