package th.co.glr.hr.auth;

import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Who may enter a deal into the system from scratch — create a ticket, or create/read a customer,
 * contact, or project on the deal-entry flow ({@code TicketCreateModal} /
 * {@code /quotations/new} with no {@code ?ticket=}). Owner ruling (Ploy, 2026-09-10, see
 * {@code docs/sales/quotation-v2-plan.md}'s inline-deal-creation addendum and
 * {@code layout-spec.md}'s sibling spec file): widened from the historical {@code sales}-only gate
 * ({@code TicketService.SALES_ROLES}, {@code CustomerController}'s hand-written
 * {@code requireAnyRole(..., "sales")}) to also admit {@code sales_manager} and any employee
 * holding the live {@code canCreateQuotation} grant ({@link EmployeeAuthRepository#canCreateQuotation}
 * — {@code hr.employee.can_create_quotation}, active rows only) — e.g. ภิญญดา (employee 144,
 * QC&amp;ISO / role {@code qc}), who needs to create deal quotations without being handed the
 * {@code sales} or {@code sales_manager} role wholesale.
 *
 * <p><strong>Scope — deliberately narrow.</strong> This is a NEW, separate gate for deal-ENTRY
 * only (ticket create, customer/contact/project create+read on that flow). It must never be
 * confused with, or substituted for, {@code TicketService.SALES_ROLES} (which still governs every
 * other sales-only ticket action — submit, edit, cancel, stock declaration, etc.) or
 * {@code TicketAccessPolicy.VIEWER_ROLES} (deal *viewing*, a wider audience that also includes
 * {@code import}/{@code ceo}/{@code account}). Widening either of THOSE shared constants would
 * silently widen every other gate built on them — this class exists specifically so that does not
 * happen: each caller ORs this check alongside its own existing role set, never replaces it.
 *
 * <p>The grant is re-read live on every call (never cached on the session principal), matching the
 * same discipline {@link EmployeeAuthRepository#isAdmin} already uses — a grant added or revoked
 * takes effect on the caller's very next request, not at their next login.
 */
public final class DealEntryAccess {
    private DealEntryAccess() {}

    public static boolean canEnterDeal(UserPrincipal actor, EmployeeAuthRepository employeeAuth) {
        String role = actor.role();
        return "sales".equals(role)
            || "sales_manager".equals(role)
            || employeeAuth.canCreateQuotation(actor.id());
    }

    public static void requireCanEnterDeal(UserPrincipal actor, EmployeeAuthRepository employeeAuth) {
        if (!canEnterDeal(actor, employeeAuth)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
