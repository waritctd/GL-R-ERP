import React, { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { api } from './api/index.js';
import { AppShell } from './components/layout/AppShell.jsx';
import { Toast } from './components/common/Toast.jsx';
import { RouteFallback } from './components/common/RouteFallback.jsx';
import { LoginPage } from './features/auth/LoginPage.jsx';
import { useHrData } from './hooks/useHrData.js';
import { useToast } from './hooks/useToast.js';
import { SALES_ENABLED } from './app/features.js';
import { hasPermission, isDivisionManager } from './app/permissions.js';
import { RequireAccess } from './app/RequireAccess.jsx';

// Route pages are code-split: each import() becomes its own chunk, loaded on
// navigation. This keeps the entry bundle lean (heavy deps like zod, react-hook-form,
// and @tanstack/react-table travel with the pages that use them, not the initial load).
// LoginPage stays eager — it is the first paint for logged-out users.
const toDefault = (name) => (module) => ({ default: module[name] });
const ChangePasswordModal = lazy(() => import('./features/auth/ChangePasswordModal.jsx').then(toDefault('ChangePasswordModal')));
const HrDashboard = lazy(() => import('./features/dashboard/HrDashboard.jsx').then(toDefault('HrDashboard')));
const HrOverview = lazy(() => import('./features/dashboard/HrOverview.jsx').then(toDefault('HrOverview')));
const EmployeeDashboard = lazy(() => import('./features/dashboard/EmployeeDashboard.jsx').then(toDefault('EmployeeDashboard')));
const SalesOverview = lazy(() => import('./features/dashboard/SalesOverview.jsx').then(toDefault('SalesOverview')));
// Role-scoped views program (docs/role-scoped-views.md) — CEO's exec-cockpit landing.
const CeoOverview = lazy(() => import('./features/dashboard/CeoOverview.jsx').then(toDefault('CeoOverview')));
const ImportOverview = lazy(() => import('./features/dashboard/ImportOverview.jsx').then(toDefault('ImportOverview')));
const EmployeeSelfService = lazy(() => import('./features/dashboard/EmployeeSelfService.jsx').then(toDefault('EmployeeSelfService')));
const DivisionManagerOverview = lazy(() => import('./features/dashboard/DivisionManagerOverview.jsx').then(toDefault('DivisionManagerOverview')));
// Role-scoped views: Sales Manager's team-cockpit Overview (landing).
const ManagerOverview = lazy(() => import('./features/dashboard/ManagerOverview.jsx').then(toDefault('ManagerOverview')));
const EmployeeListPage = lazy(() => import('./features/employees/EmployeeListPage.jsx').then(toDefault('EmployeeListPage')));
const EmployeeDetailPage = lazy(() => import('./features/employees/EmployeeDetailPage.jsx').then(toDefault('EmployeeDetailPage')));
const ProfileRequestsPage = lazy(() => import('./features/profileRequests/ProfileRequestsPage.jsx').then(toDefault('ProfileRequestsPage')));
const ProfilePage = lazy(() => import('./features/profile/ProfilePage.jsx').then(toDefault('ProfilePage')));
const AttendancePage = lazy(() => import('./features/attendance/AttendancePage.jsx').then(toDefault('AttendancePage')));
const RequestsPage = lazy(() => import('./features/requests/RequestsPage.jsx').then(toDefault('RequestsPage')));
// Leave-surface IA rebuild Phase A1: LeavePage.jsx (one flat component) was replaced by a tabbed
// shell -- see LeaveSurfacePage.jsx's own doc comment.
const LeaveSurfacePage = lazy(() => import('./features/leave/LeaveSurfacePage.jsx').then(toDefault('LeaveSurfacePage')));
// Leave-request composer, Phase A2 (#485): the 3-step /leave/new route the "ยื่นคำขอลา" CTA now
// navigates to, replacing the inline form MyLeaveTab.jsx used to render -- see LeaveRequestPage.jsx's
// own doc comment.
const LeaveRequestPage = lazy(() => import('./features/leave/LeaveRequestPage.jsx').then(toDefault('LeaveRequestPage')));
const TicketListPage = lazy(() => import('./features/tickets/TicketListPage.jsx').then(toDefault('TicketListPage')));
const TicketDetailPage = lazy(() => import('./features/tickets/TicketDetailPage.jsx').then(toDefault('TicketDetailPage')));
const CommissionPage = lazy(() => import('./features/commissions/CommissionPage.jsx').then(toDefault('CommissionPage')));
// Role-scoped views: Account's money Overview (landing) + งานการเงิน worklist.
const AccountOverview = lazy(() => import('./features/dashboard/AccountOverview.jsx').then(toDefault('AccountOverview')));
const AccountFinancePage = lazy(() => import('./features/finance/AccountFinancePage.jsx').then(toDefault('AccountFinancePage')));
const PayrollPage = lazy(() => import('./features/payroll/PayrollPage.jsx').then(toDefault('PayrollPage')));
// ล.ย.01 tax-allowance declaration (issue #387): employee self-service form + HR review register.
const TaxAllowancePage = lazy(() => import('./features/taxAllowance/TaxAllowancePage.jsx').then(toDefault('TaxAllowancePage')));
const TaxAllowanceReviewPage = lazy(() => import('./features/taxAllowance/TaxAllowanceReviewPage.jsx').then(toDefault('TaxAllowanceReviewPage')));
const DepositNoticePage = lazy(() => import('./features/deposits/DepositNoticePage.jsx').then(toDefault('DepositNoticePage')));
const CeoSettingsPage = lazy(() => import('./features/ceoSettings/CeoSettingsPage.jsx').then(toDefault('CeoSettingsPage')));
const PriceImportPage = lazy(() => import('./features/catalog/PriceImportPage.jsx').then(toDefault('PriceImportPage')));
const CatalogSearchPage = lazy(() => import('./features/catalog/CatalogSearchPage.jsx').then(toDefault('CatalogSearchPage')));
const PricingRequestQueuePage = lazy(() => import('./features/pricingRequests/PricingRequestQueuePage.jsx').then(toDefault('PricingRequestQueuePage')));
const PricingRequestDetailPage = lazy(() => import('./features/pricingRequests/PricingRequestDetailPage.jsx').then(toDefault('PricingRequestDetailPage')));
const ProcurementListPage = lazy(() => import('./features/procurement/ProcurementListPage.jsx').then(toDefault('ProcurementListPage')));
const ProcurementDetailPage = lazy(() => import('./features/procurement/ProcurementDetailPage.jsx').then(toDefault('ProcurementDetailPage')));
const ProcurementFulfilmentPage = lazy(() => import('./features/procurement/ProcurementFulfilmentPage.jsx').then(toDefault('ProcurementFulfilmentPage')));
// Attendance calendar admin (PR #480 shipped the hr.holiday / hr.work_schedule_assignment write
// API with no UI at all) — HR/CEO only, gated via canManageAttendanceCalendar. Never gated on
// SALES_ENABLED: this is attendance/HR-core, not the sales/CRM stack.
const AttendanceCalendarPage = lazy(() => import('./features/attendanceCalendar/AttendanceCalendarPage.jsx').then(toDefault('AttendanceCalendarPage')));
// e2e-only fixture (#safe-form-primitive review round, F2) — see the component's own doc comment
// for why it exists. Route registration below is gated on VITE_USE_MOCKS, so this is unreachable
// in a production build (no VITE_ vars are set there at all) even though the lazy chunk itself
// still ships, same as every other route in this file.
const SafeFormSubmitterProbe = lazy(() => import('./dev/SafeFormSubmitterProbe.jsx').then(toDefault('SafeFormSubmitterProbe')));

// Thin wrappers that source the ticket id from the URL for the frozen sales
// pages (they already fetch by id internally — branch 5 only rewires how the
// id and navigation arrive).
function TicketDetailRoute({ user, showToast }) {
  const { id } = useParams();
  const navigate = useNavigate();
  return (
    <TicketDetailPage
      user={user}
      ticketId={id}
      // navigate(-1) (not a fixed '/tickets') so the list's status filter and
      // search text — now carried in the URL query string, see
      // TicketListPage.jsx — survive the round trip instead of resetting.
      onBack={() => navigate(-1)}
      showToast={showToast}
    />
  );
}

function DepositNoticeRoute({ user, showToast }) {
  const { ticketId } = useParams();
  const navigate = useNavigate();
  return (
    <DepositNoticePage
      user={user}
      ticketId={ticketId}
      onBack={() => navigate(`/tickets/${ticketId}`)}
      onNavigateTickets={() => navigate('/tickets')}
      showToast={showToast}
    />
  );
}

export function App() {
  const [user, setUser] = useState(null);
  const [restoringSession, setRestoringSession] = useState(true);
  const [loading, setLoading] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const navigate = useNavigate();
  const { toast, showToast, dismissToast } = useToast();
  const {
    currentEmployee,
    employees,
    profileRequests,
    dashboardSummary,
    taxAllowanceSummary,
    resetData,
    createEmployee,
    updateEmployee,
    createProfileRequest,
    reviewProfileRequest,
  } = useHrData({ user, showToast });

  const ownRequests = useMemo(
    () => profileRequests.filter((request) => request.employeeId === currentEmployee?.id),
    [profileRequests, currentEmployee],
  );
  const isEmployeeExperience = hasPermission(user?.role, 'canUseEmployeeExperience');
  const pendingCount = (isEmployeeExperience ? ownRequests : profileRequests).filter((request) => request.status === 'pending').length;
  const dashboardRequests = isEmployeeExperience && !user?.manager ? ownRequests : profileRequests;
  // A plain, non-managing employee (role==='employee', not a division manager)
  // lands on the self-service view instead of the shared EmployeeDashboard.
  // 'warehouse'/'qc' keep EmployeeDashboard for now — they get the same
  // canUseEmployeeExperience data wiring but this branch is scoped to
  // role==='employee' only, per this task. `!isDivisionManager(user)` reuses
  // the single source of truth for "is this a division manager" (permissions.js)
  // instead of re-deriving `!user?.manager` inline — a manager-flagged
  // employee lands on DivisionManagerOverview above, never here.
  const isPlainEmployee = user?.role === 'employee' && !isDivisionManager(user);

  useEffect(() => {
    let alive = true;
    async function restoreSession() {
      try {
        const response = await api.auth.me();
        if (!alive) return;
        setUser(response.user);
      } catch {
        if (alive) setUser(null);
      } finally {
        if (alive) setRestoringSession(false);
      }
    }
    restoreSession();
    return () => { alive = false; };
  }, []);

  async function handleLogin(payload) {
    setLoading(true);
    setLoginError('');
    try {
      const response = await api.auth.login(payload);
      setUser(response.user);
      showToast('success', 'เข้าสู่ระบบสำเร็จ');
    } catch (error) {
      // Never surface the raw server message on the login screen (it's English —
      // see AuthService.INVALID_CREDENTIALS / mockApi's matching 401). Map by
      // HTTP status (client.js's ApiError carries one) to Thai copy instead.
      setLoginError(
        error.status === 401
          ? 'อีเมลหรือรหัสผ่านไม่ถูกต้อง'
          : 'เข้าสู่ระบบไม่สำเร็จ กรุณาลองใหม่อีกครั้ง'
      );
    } finally {
      setLoading(false);
    }
  }

  async function handleChangePassword(payload) {
    setChangingPassword(true);
    try {
      const response = await api.auth.changePassword(payload);
      setUser(response.user);
      showToast('success', 'เปลี่ยนรหัสผ่านเรียบร้อย');
    } finally {
      setChangingPassword(false);
    }
  }

  async function handleLogout() {
    await api.auth.logout();
    setUser(null);
    resetData();
    navigate('/');
  }

  if (restoringSession) {
    return <RouteFallback />;
  }

  if (!user) {
    return (
      <>
        <LoginPage onLogin={handleLogin} loading={loading} error={loginError} />
        <Toast toast={toast} onDismiss={dismissToast} />
      </>
    );
  }

  if (user.mustChangePassword) {
    return (
      <>
        <Suspense fallback={<RouteFallback />}>
          <ChangePasswordModal
            forced
            loading={changingPassword}
            onSubmit={handleChangePassword}
            onLogout={handleLogout}
          />
        </Suspense>
        <Toast toast={toast} onDismiss={dismissToast} />
      </>
    );
  }

  return (
    <>
      <Routes>
        <Route
          element={(
            <AppShell
              user={user}
              employee={currentEmployee}
              onLogout={handleLogout}
              pendingRequestCount={pendingCount}
            />
          )}
        >
          <Route
            path="/"
            element={(
              // Role-scoped views: each role's landing branches off `/` to its own
              // Overview instead of the generic EmployeeDashboard — this generalizes
              // into a role -> Overview map as more roles ship their own (see
              // docs/role-scoped-views.md). Sales-gated roles degrade to
              // EmployeeDashboard whenever SALES_ENABLED is off (SALES_ENABLED is
              // the off-switch — see app/features.js), same as every other
              // sales-gated surface in this file; hr is people-ops and is NOT gated
              // on SALES_ENABLED. The base `employee` role further splits by
              // manager flag: a division manager lands on DivisionManagerOverview,
              // a plain employee on EmployeeSelfService; any other/unknown role
              // (or a manager-flagged non-employee role, which shouldn't happen)
              // falls through to the generic EmployeeDashboard as a safety net.
              user.role === 'hr' ? (
                <HrOverview
                  employees={employees}
                  dashboardSummary={dashboardSummary}
                />
              ) : user.role === 'sales_manager' && SALES_ENABLED ? (
                <ManagerOverview user={user} employee={currentEmployee} showToast={showToast} />
              ) : user.role === 'sales' && SALES_ENABLED ? (
                <SalesOverview user={user} employee={currentEmployee} />
              ) : user?.role === 'ceo' && SALES_ENABLED ? (
                <CeoOverview
                  user={user}
                  employee={currentEmployee}
                  dashboardSummary={dashboardSummary}
                />
              ) : user.role === 'import' && SALES_ENABLED ? (
                <ImportOverview user={user} employee={currentEmployee} />
              ) : user.role === 'account' && SALES_ENABLED ? (
                <AccountOverview user={user} employee={currentEmployee} showToast={showToast} />
              ) : isDivisionManager(user) ? (
                <DivisionManagerOverview
                  user={user}
                  employee={currentEmployee}
                  dashboardSummary={dashboardSummary}
                  showToast={showToast}
                />
              ) : isPlainEmployee ? (
                <EmployeeSelfService
                  user={user}
                  employee={currentEmployee}
                  profileRequests={dashboardRequests}
                  dashboardSummary={dashboardSummary}
                  taxAllowanceSummary={taxAllowanceSummary}
                  showToast={showToast}
                />
              ) : (
                <EmployeeDashboard
                  user={user}
                  employee={currentEmployee}
                  profileRequests={dashboardRequests}
                  dashboardSummary={dashboardSummary}
                  taxAllowanceSummary={taxAllowanceSummary}
                  showToast={showToast}
                />
              )
            )}
          />

          <Route element={<RequireAccess user={user} />}>
            <Route
              path="/hr"
              element={(
                <HrDashboard
                  employee={currentEmployee}
                  employees={employees}
                  profileRequests={profileRequests}
                  dashboardSummary={dashboardSummary}
                />
              )}
            />
            <Route
              path="/employees"
              element={(
                <EmployeeListPage
                  user={user}
                  employees={employees}
                  onCreateEmployee={createEmployee}
                />
              )}
            />
            <Route
              path="/employees/:id"
              element={<EmployeeDetailPage user={user} onUpdateEmployee={updateEmployee} />}
            />
            <Route
              path="/requests"
              element={<ProfileRequestsPage profileRequests={profileRequests} onReview={reviewProfileRequest} showToast={showToast} />}
            />
            {/* The request table now lives on /profile. This route stays
                registered as an alias so existing notification deep-links
                resolve instead of falling through to the 404 route. */}
            <Route path="/my-requests" element={<Navigate to="/profile" replace />} />
            <Route
              path="/profile"
              element={(
                <ProfilePage
                  user={user}
                  employee={currentEmployee}
                  profileRequests={ownRequests}
                  onCreateRequest={createProfileRequest}
                  taxAllowanceSummary={taxAllowanceSummary}
                />
              )}
            />
            {/* ล.ย.01 tax-allowance declaration (issue #387). Guarded in permissions.js's
                PATH_GUARDS — `/tax-allowance` needs an employeeId (same shape as /profile),
                `/tax-allowance-review` needs canViewTaxAllowanceRegister (hr/ceo). */}
            <Route
              path="/tax-allowance"
              element={<TaxAllowancePage user={user} showToast={showToast} />}
            />
            <Route
              path="/tax-allowance-review"
              element={<TaxAllowanceReviewPage user={user} showToast={showToast} />}
            />
            <Route
              path="/employee-requests"
              element={<RequestsPage user={user} currentEmployee={currentEmployee} showToast={showToast} />}
            />
            {/* /overtime moves into the combined /employee-requests tab bar (OT +
                welfare). This alias keeps existing notification deep-links
                (OvertimeService hardcodes "/overtime") resolving to the OT tab
                instead of falling through to the 404 route. */}
            <Route path="/overtime" element={<Navigate to="/employee-requests?tab=ot" replace />} />
            <Route
              path="/leave"
              element={<LeaveSurfacePage user={user} currentEmployee={currentEmployee} showToast={showToast} />}
            />
            {/* Leave-request composer, Phase A2 (#485): deep-linkable so the empty state, a
                rejected row's "ยื่นคำขอใหม่", and the auto-rejection notification can all link
                straight into a prefilled step (?type=&start=&end=). */}
            <Route
              path="/leave/new"
              element={<LeaveRequestPage user={user} currentEmployee={currentEmployee} showToast={showToast} />}
            />
            <Route
              path="/payroll"
              element={<PayrollPage user={user} showToast={showToast} />}
            />
            {/* Attendance calendar admin (PR #480's API, this branch's UI) — HR/CEO only. */}
            <Route
              path="/settings/attendance-calendar"
              element={<AttendanceCalendarPage user={user} showToast={showToast} />}
            />
            {/* Frozen sales stack — param-wired to keep working / URL-addressable. */}
            {SALES_ENABLED && (
              <>
                <Route
                  path="/tickets"
                  element={<TicketListPage user={user} showToast={showToast} />}
                />
                <Route
                  path="/tickets/:id"
                  element={<TicketDetailRoute user={user} showToast={showToast} />}
                />
                <Route
                  path="/tickets/:ticketId/deposit"
                  element={<DepositNoticeRoute user={user} showToast={showToast} />}
                />
                <Route
                  path="/pricing-requests"
                  element={<PricingRequestQueuePage user={user} showToast={showToast} />}
                />
                <Route
                  path="/pricing-requests/:id"
                  element={<PricingRequestDetailPage user={user} showToast={showToast} />}
                />
                <Route
                  path="/commissions"
                  element={<CommissionPage user={user} showToast={showToast} />}
                />
                {/* Account's money-lifecycle worklist (Account role-scoped views). */}
                <Route
                  path="/finance"
                  element={<AccountFinancePage user={user} showToast={showToast} />}
                />
                <Route
                  path="/price-import"
                  element={<PriceImportPage showToast={showToast} />}
                />
                <Route
                  path="/ceo-settings"
                  element={<CeoSettingsPage showToast={showToast} />}
                />
                <Route
                  path="/factory-purchase-orders"
                  element={<ProcurementListPage showToast={showToast} />}
                />
                <Route
                  path="/factory-purchase-orders/:id"
                  element={<ProcurementDetailPage showToast={showToast} />}
                />
                {/* Role-scoped views (Import build): combined "จัดซื้อ & นำเข้า" —
                    fulfilment worklist (section 1) + the raw factory-PO list
                    reused wholesale (section 2). /factory-purchase-orders(/:id)
                    stay registered above for direct PO-detail deep-links. */}
                <Route
                  path="/procurement"
                  element={<ProcurementFulfilmentPage user={user} showToast={showToast} />}
                />
                {/* Catalog browsing — the `canViewCatalog` guard in PATH_GUARDS
                    (sales/import/ceo/account/sales_manager) is only enforced if the
                    route is INSIDE RequireAccess. It previously sat outside (dead
                    guard — any logged-in user could reach /catalog); moved in here
                    (fix/catalog-route-guard). This guard is a UX choice about who
                    sees the screen, NOT a security boundary: GET /api/catalog and
                    /api/catalog/prices are open to any authenticated user by
                    product decision (#205; owner ruling 2026-08-01 closing #388).
                    Nothing here is protecting the data. */}
                <Route
                  path="/catalog"
                  element={<CatalogSearchPage user={user} showToast={showToast} />}
                />
              </>
            )}
          </Route>

          <Route
            path="/attendance"
            element={<AttendancePage user={user} employees={employees} showToast={showToast} />}
          />

          {/* e2e-only (#safe-form-primitive review round, F2) — see SafeFormSubmitterProbe.jsx's
              own doc comment. VITE_USE_MOCKS is never 'true' in production (no VITE_ vars are set
              there at all), so this route does not exist outside a local/mock/e2e context. */}
          {import.meta.env.VITE_USE_MOCKS === 'true' && (
            <Route path="/__e2e/safe-form-submitter-probe" element={<SafeFormSubmitterProbe />} />
          )}

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
      <Toast toast={toast} onDismiss={dismissToast} />
      {loading ? (
        <div
          className="fixed inset-0 z-[90] grid place-items-center bg-[var(--color-veil)] font-extrabold text-text"
          role="status"
          aria-live="polite"
          aria-busy="true"
        >
          กำลังเข้าสู่ระบบ...
        </div>
      ) : null}
    </>
  );
}
