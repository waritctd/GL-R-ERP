import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SALES_ENABLED } from '../../app/features.js';
import { hasPermission, isDivisionManager } from '../../app/permissions.js';
import { roleLabel } from '../../utils/format.js';
import { Button } from '../common/Button.jsx';
import { ErrorBoundary } from '../common/ErrorBoundary.jsx';
import { RouteFallback } from '../common/RouteFallback.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { Sidebar } from './Sidebar.jsx';
import { UserMenu } from './UserMenu.jsx';

export function AppShell({ user, employee, onLogout, pendingRequestCount }) {
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const drawerRef = useRef(null);
  const menuButtonRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const drawerId = 'mobile-navigation-drawer';
  const isTeamManager = isDivisionManager(user);
  const navItems = [
    { path: '/', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    // Division-manager (non-sales) "ทีมของฉัน" group — reuses the same
    // /employee-requests, /leave, /attendance routes every role already has
    // (they are dual submit+approve pages for anyone with reports; a division
    // manager is no exception), framed here as the team-facing entry points.
    // The unchanged 'self' entries below stay the manager's own personal
    // self-service, same as every other role. No roster/`/employees` link:
    // canViewEmployees is hr-only (ROLE_PERMISSIONS) and this task does not
    // grant it — `/attendance` already gives a division manager a per-employee
    // view of their team, so it stands in for "ทีมในฝ่าย" too.
    {
      path: '/employee-requests',
      label: 'การอนุมัติ OT',
      helper: 'Approve team overtime',
      icon: 'clock',
      group: 'team',
      show: isTeamManager,
      match: ['/employee-requests', '/overtime'],
    },
    { path: '/leave', label: 'การอนุมัติวันลา', helper: 'Approve team leave', icon: 'clipboard', group: 'team', show: isTeamManager },
    { path: '/attendance', label: 'ทีมในฝ่าย', helper: 'Team roster & attendance', icon: 'users', group: 'team', show: isTeamManager },
    // งานขาย is one workspace: the deal pipeline (/tickets — one ticket = one deal,
    // ใบขอราคา and โครงการ merged into one page) plus the ภาพรวม dashboard as a tab
    // (SalesTabs). `match` keeps this item highlighted across both and detail pages.
    // Distinct from the "งานขาย" (Sales) group header it sits under below —
    // repeating the group name on its first item read as a stutter.
    {
      path: '/tickets',
      label: 'รายการดีล',
      helper: 'Deal pipeline',
      icon: 'briefcase',
      group: 'sales',
      // Role-scoped views: the pipeline BROWSER is canViewDealPipeline (sales/
      // sales_manager/ceo) — narrower than canViewTickets (detail-read, kept
      // for import/account). Import gets its own จัดซื้อ & นำเข้า worklist and
      // Account gets its own งานการเงิน worklist below instead of this item.
      // See docs/role-scoped-views.md.
      show: hasPermission(user.role, 'canViewDealPipeline') && SALES_ENABLED,
      match: ['/tickets', '/ticket-overview'],
    },
    { path: '/ceo-settings', label: 'ตั้งค่าราคา', helper: 'CEO price config', icon: 'setting', group: 'sales', show: user.role === 'ceo' && SALES_ENABLED },
    // Catalog browsing is scoped to the sales/CRM audience (canViewCatalog),
    // not just the feature flag — previously any authenticated role could
    // reach it once SALES_ENABLED, even employee.
    { path: '/catalog', label: 'แคตตาล็อกสินค้า', helper: 'Product catalog', icon: 'search', group: 'sales', show: hasPermission(user.role, 'canViewCatalog') && SALES_ENABLED },
    { path: '/price-import', label: 'นำเข้าราคา', helper: 'Price import', icon: 'upload', group: 'sales', show: hasPermission(user.role, 'canManagePriceImport') && SALES_ENABLED },
    // Import's cross-deal PricingRequest queue — see permissions.js's PATH_GUARDS
    // comment for why this is a narrower audience than a single request's detail page.
    { path: '/pricing-requests', label: 'คิวใบขอราคา', helper: 'Pricing request queue', icon: 'clipboard', group: 'sales', show: hasPermission(user.role, 'canViewPricingRequestQueue') && SALES_ENABLED },
    // Role-scoped views (Import build): the raw factory-PO list and Import's
    // deal-level fulfilment worklist are combined into one nav item —
    // Import/CEO only, mirrors ProcurementService.RAW_PO_ROLES. Sales never
    // sees raw supplier PO detail. `match` keeps this highlighted on both the
    // combined page and the raw PO detail route it still deep-links to.
    {
      path: '/procurement',
      label: 'จัดซื้อ & นำเข้า',
      helper: 'Procurement & fulfilment',
      icon: 'fileText',
      group: 'sales',
      show: hasPermission(user.role, 'canManageProcurement') && SALES_ENABLED,
      match: ['/procurement', '/factory-purchase-orders'],
    },
    // Account keeps its ค่าคอมมิชชัน nav item (pre-redesign parity): account's
    // create-from-deal action is the งานการเงิน worklist's primary path (it
    // deep-links /commissions?ticketId=NN), but the standalone console stays
    // reachable from the nav as it was before, so account never loses a
    // discoverable entry point. canViewCommissions is unchanged; account has
    // no commission *list* access (canListCommissionRecords excludes it), so
    // the console opens on the create-from-deal flow.
    { path: '/commissions', label: 'ค่าคอมมิชชัน', helper: 'Commissions', icon: 'badgeDollar', group: 'sales', show: hasPermission(user.role, 'canViewCommissions') && SALES_ENABLED },
    // Account's money-lifecycle worklist (Account role-scoped views): deposit
    // -> final payment -> close-ready -> record-invoice/commission, one page.
    { path: '/finance', label: 'งานการเงิน', helper: 'Finance worklist', icon: 'badgeDollar', group: 'finance', show: hasPermission(user.role, 'canConfirmPayments') && SALES_ENABLED },
    { path: '/hr', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    { path: '/employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    // ข้อมูลของฉัน / คำขอของฉัน are deliberately absent: personal admin lives in
    // the topbar UserMenu, and the two pages are merged into /profile. /requests
    // stays here because an HR review queue is work, not personal admin.
    { path: '/requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', group: 'hr', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    { path: '/payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', group: 'finance', show: hasPermission(user.role, 'canManagePayroll') },
    { path: '/attendance', label: 'เวลาทำงาน', helper: 'Attendance', icon: 'calendar', group: 'self', show: true },
    // Combined OT + welfare/special-money page (RequestsPage.jsx, tabs carried
    // in ?tab=). `match` keeps this item highlighted on both /employee-requests and
    // the /overtime redirect alias, mirroring the /tickets pattern above.
    {
      path: '/employee-requests',
      label: 'คำขอ',
      helper: 'Requests (overtime / welfare)',
      icon: 'clock',
      group: 'self',
      show: !!user.employeeId || hasPermission(user.role, 'canViewAllOvertime') || hasPermission(user.role, 'canViewAllSpecialMoney'),
      match: ['/employee-requests', '/overtime'],
    },
    { path: '/leave', label: 'วันลา', helper: 'Leave', icon: 'clipboard', group: 'self', show: !!user.employeeId || hasPermission(user.role, 'canViewAllLeave') },
  ].filter((item) => item.show);

  const closeDrawer = useCallback(() => setIsDrawerOpen(false), []);

  // Close the mobile drawer whenever the URL changes (navigation happened).
  useEffect(() => {
    closeDrawer();
  }, [closeDrawer, location.pathname]);

  useEffect(() => {
    if (!isDrawerOpen) return undefined;

    const previouslyFocused = document.activeElement;
    const menuButton = menuButtonRef.current;
    const focusableSelector = [
      'a[href]',
      'button:not([disabled])',
      'input:not([disabled])',
      'select:not([disabled])',
      'textarea:not([disabled])',
      '[tabindex]:not([tabindex="-1"])',
    ].join(',');

    const getFocusableItems = () => Array
      .from(drawerRef.current?.querySelectorAll(focusableSelector) || [])
      .filter((element) => element.getClientRects().length > 0);

    const focusableItems = getFocusableItems();
    (focusableItems[0] || drawerRef.current)?.focus();

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        closeDrawer();
        return;
      }

      if (event.key !== 'Tab') return;

      const items = getFocusableItems();
      if (items.length === 0) {
        event.preventDefault();
        return;
      }

      const firstItem = items[0];
      const lastItem = items[items.length - 1];

      if (event.shiftKey && document.activeElement === firstItem) {
        event.preventDefault();
        lastItem.focus();
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault();
        firstItem.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      } else {
        menuButton?.focus();
      }
    };
  }, [closeDrawer, isDrawerOpen]);

  return (
    <div className="app-shell">
      <Sidebar
        id={drawerId}
        drawerRef={drawerRef}
        isDrawerOpen={isDrawerOpen}
        items={navItems}
        user={user}
        employee={employee}
        onLogout={onLogout}
      />
      <button
        type="button"
        className={`mobile-drawer-backdrop ${isDrawerOpen ? 'is-open' : ''}`}
        aria-label="ปิดเมนู"
        onClick={closeDrawer}
        tabIndex={-1}
      />
      <main className="app-main">
        <header className="topbar">
          <Button
            ref={menuButtonRef}
            variant="icon"
            className="!hidden max-[720px]:!inline-flex max-[720px]:flex-[0_0_44px]"
            type="button"
            onClick={() => setIsDrawerOpen((open) => !open)}
            aria-label="เปิดเมนูนำทาง"
            aria-controls={drawerId}
            aria-expanded={isDrawerOpen}
          >
            <Icon name="menu" />
          </Button>
          <div className="topbar-title">
            <span>GL&R HR</span>
            <small>{roleLabel(user.role)}</small>
          </div>
          <div className="topbar-user">
            <div className="topbar-user-text">
              <strong>{employee?.nameTh || user.name}</strong>
              <span>{user.email}</span>
            </div>
            <NotificationBell onNavigate={(link) => navigate(link)} />
            <UserMenu
              user={user}
              employee={employee}
              canViewProfile={!!user.employeeId}
              onNavigate={(link) => navigate(link)}
              onLogout={onLogout}
            />
          </div>
        </header>
        <div className="content-scroll"><ErrorBoundary key={location.pathname}><Suspense fallback={<RouteFallback />}><Outlet /></Suspense></ErrorBoundary></div>
      </main>
    </div>
  );
}
