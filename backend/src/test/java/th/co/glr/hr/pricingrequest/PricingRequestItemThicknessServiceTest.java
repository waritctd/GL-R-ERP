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
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemThicknessRequest;

/**
 * "Unit-test the decision" half of the CLAUDE.md authz-evidence requirement for {@link
 * PricingRequestItemThicknessService#setItemThickness} — proves the {@code
 * sales}/{@code ceo}-only role gate runs, and runs FIRST (before any collaborator that could
 * only be proved against a real database), with every collaborator mocked so this class needs no
 * Postgres at all. {@code PricingRequestItemThicknessIntegrationTest} is the other half: it proves
 * the decision survives into real SQL (the {@code v_priceable_product} join, the owner-scoping
 * inherited from {@code PricingRequestService#get}, the status-window split per role) — something
 * a mocked repository cannot demonstrate.
 *
 * <p>Written wrong-way-round: every rejected-role case also asserts NOTHING downstream was ever
 * called, so a role check that accidentally ran too late (after a collaborator was already
 * touched) would fail here even if the final exception still happened to be FORBIDDEN.
 *
 * <p><b>Owner-ruled scope change, 2026-09-06</b> ("sales need to be forced to fill in the
 * thickness, not import"): this file used to assert {@code import}/{@code ceo} were the only
 * allowed roles ({@code sales} itself was in the REJECTED list). It now asserts the opposite —
 * {@code sales}/{@code ceo} allowed, {@code import} refused — which is the entire point of the
 * change, not an incidental fixture update. {@code importPassesTheRoleGate_...} became {@code
 * rejectsImport_beforeTouchingAnyCollaborator}; {@code rejectsSales_...} became {@code
 * salesPassesTheRoleGate_...}.
 */
class PricingRequestItemThicknessServiceTest {

    private final PricingRequestRepository requests = mock(PricingRequestRepository.class);
    private final PricingRequestService pricingRequestService = mock(PricingRequestService.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PricingRequestItemThicknessService service = new PricingRequestItemThicknessService(
        requests, pricingRequestService, catalog);

    private final SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

    private static UserPrincipal actor(String role) {
        return new UserPrincipal(1L, role + "@glr.co.th", "Actor", role, 1L, true, LocalDate.now(), false, null, false);
    }

    // ── wrong-way-round: every non-{sales,ceo} role must be rejected, before touching anything ──

    @Test
    void rejectsImport_beforeTouchingAnyCollaborator() {
        // The entire point of the 2026-09-06 scope change: ฝ่ายนำเข้า loses this capability
        // outright. Mutation-check target — re-add "import" to ITEM_THICKNESS_ROLES and confirm
        // THIS test (and no other) goes red.
        assertRejectedWithoutSideEffects("import");
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
        verifyNoInteractions(requests, pricingRequestService, catalog);
    }

    // ── the two allowed roles pass the gate — proved by reaching the NEXT step, not by a fabricated success ──

    @Test
    void salesPassesTheRoleGate_andReachesPricingRequestServiceGet() {
        UserPrincipal salesActor = actor("sales");
        // The role check is proved to have passed by observing what happens NEXT, not by faking a
        // full success: real success (the ownership/status/refuse/write logic) is what
        // PricingRequestItemThicknessIntegrationTest proves against a real Postgres — a mocked
        // repository here would only be evidence about the mock. Note this is ALSO where sales's
        // owner-scoping is enforced in production (PricingRequestService#get -> requireViewable) —
        // this test only proves the role gate lets sales THROUGH to that check, not what that
        // check itself decides; the integration test covers that half.
        when(pricingRequestService.get(anyLong(), eq(salesActor)))
            .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));

        assertThatThrownBy(() -> service.setItemThickness(1L, 2L, request, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(pricingRequestService).get(1L, salesActor);
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
