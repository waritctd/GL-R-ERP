import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SALES_ENABLED } from '../../app/features.js';
import { hasPermission, isDivisionManager } from '../../app/permissions.js';
import { PRODUCT_NAME } from '../../app/product.js';
import { cn } from '../../utils/cn.js';
import { roleLabel } from '../../utils/format.js';
import { Button } from '../common/Button.jsx';
import { ErrorBoundary } from '../common/ErrorBoundary.jsx';
import { RouteFallback } from '../common/RouteFallback.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { Sidebar } from './Sidebar.jsx';
import { UserMenu } from './UserMenu.jsx';

// Tailwind port of `.content-scroll` (styles.css): the gutters are CSS custom
// properties (not plain utilities) because `.content-scroll`'s own padding
// references them, TicketDetailPage.jsx overwrites `--deal-header-h` via
// ResizeObserver, and DepositNoticePage.jsx measures scrollWidth against this
// same padding — every one of those call sites keys off these exact
// custom-property names, so the names themselves must survive the port
// unchanged even though the declarations move from a CSS rule to inline
// arbitrary-property utilities.
const CONTENT_SCROLL_CLASS = cn(
  'min-w-0 max-w-full flex-1 overflow-auto [scrollbar-gutter:stable]',
  '[--deal-scroll-pad-y:28px] [--deal-scroll-pad-x:max(32px,env(safe-area-inset-right))] [--deal-scroll-pad-left:max(32px,env(safe-area-inset-left))]',
  'pt-[var(--deal-scroll-pad-y)] pr-[var(--deal-scroll-pad-x)] pb-[42px] pl-[var(--deal-scroll-pad-left)]',
  'scroll-pt-[calc(var(--deal-header-h,18rem)_+_var(--space-4))] scroll-pb-8',
  'mobile:[--deal-scroll-pad-y:16px] mobile:[--deal-scroll-pad-x:max(16px,env(safe-area-inset-right))] mobile:[--deal-scroll-pad-left:max(16px,env(safe-area-inset-left))]',
  'mobile:pb-[32px] mobile:scroll-pt-4 mobile:scroll-pb-[calc(112px_+_env(safe-area-inset-bottom))]',
  // Item 5 (owner-approved polish): the content gutter widens from 32px to
  // 40px at the 1366px desktop band — nothing between 721-1365px changes.
  'min-[1366px]:[--deal-scroll-pad-x:max(40px,env(safe-area-inset-right))]',
  'min-[1366px]:[--deal-scroll-pad-left:max(40px,env(safe-area-inset-left))]',
);

export function AppShell({ user, employee, onLogout, pendingRequestCount }) {
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [activeTopbarPopover, setActiveTopbarPopover] = useState(null);
  const [isContentScrolled, setIsContentScrolled] = useState(false);
  const drawerRef = useRef(null);
  const menuButtonRef = useRef(null);
  const mainRef = useRef(null);
  const contentScrollRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const drawerId = 'mobile-navigation-drawer';
  const mainContentId = 'main-content';

  // Item 2 (owner-approved polish): the topbar's bottom border is chrome that
  // should only announce itself once there is content scrolled underneath it
  // — a permanent 1px line reads as static decoration, an on-scroll one reads
  // as "there's more above". Tracks `.content-scroll`'s own scrollTop rather
  // than the window's, since that inner div is the app's real scroll
  // container (see its own comment below).
  const updateContentScrolled = useCallback(() => {
    setIsContentScrolled((contentScrollRef.current?.scrollTop ?? 0) > 0);
  }, []);

  useEffect(() => {
    const el = contentScrollRef.current;
    if (!el) return undefined;
    updateContentScrolled();
    el.addEventListener('scroll', updateContentScrolled, { passive: true });
    return () => el.removeEventListener('scroll', updateContentScrolled);
  }, [updateContentScrolled]);

  // A route change swaps `.content-scroll`'s children (this div itself never
  // remounts), which does not reset scrollTop on its own — without this, a
  // border "stuck on" from a long, scrolled page would wrongly persist onto
  // the next, shorter page until the user next scrolled.
  useEffect(() => {
    updateContentScrolled();
  }, [location.pathname, updateContentScrolled]);

  // WCAG 2.2 §2.4.1 Bypass Blocks: a bare `href="#main-content"` moves the
  // viewport but focus-follows-fragment behaviour is inconsistent across
  // browsers, so this focuses the target explicitly rather than relying on
  // it. `tabIndex={-1}` on <main> (below) makes it programmatically
  // focusable without adding it to the normal tab sequence.
  const handleSkipLinkClick = useCallback((event) => {
    event.preventDefault();
    mainRef.current?.focus();
  }, []);
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
    // คำขอราคา and โครงการ merged into one page) plus the ภาพรวม dashboard as a tab
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
    { path: '/pricing-requests', label: 'คิวคำขอราคา', helper: 'Pricing request queue', icon: 'clipboard', group: 'sales', show: hasPermission(user.role, 'canViewPricingRequestQueue') && SALES_ENABLED },
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
    // Account has NO ค่าคอมมิชชัน nav item (finalized Account design, owner-confirmed
    // 2026-07-24): the invoice+commission step (บันทึกใบกำกับ + ออกค่าคอม / create-from-deal
    // at close) is the LAST STAGE of the งานการเงิน money lifecycle + the Overview worklist
    // (รับมัดจำ → รับชำระส่วนที่เหลือ → ยืนยันพร้อมปิดงาน → บันทึกใบกำกับ + ออกค่าคอม), not a
    // standalone menu. The /commissions ROUTE stays reachable (canViewCommissions still
    // includes account) so the งานการเงิน worklist row can deep-link /commissions?ticketId=NN
    // into the create-from-deal flow; account has no list access (canListCommissionRecords
    // excludes it — GET /api/commissions is sales/sales_manager/ceo only).
    { path: '/commissions', label: 'ค่าคอมมิชชัน', helper: 'Commissions', icon: 'badgeDollar', group: 'sales', show: hasPermission(user.role, 'canViewCommissions') && SALES_ENABLED && user.role !== 'account' },
    // Account's money-lifecycle worklist (Account role-scoped views): deposit
    // -> final payment -> close-ready -> record-invoice/commission, one page.
    { path: '/finance', label: 'งานการเงิน', helper: 'Finance worklist', icon: 'badgeDollar', group: 'finance', show: hasPermission(user.role, 'canConfirmPayments') && SALES_ENABLED },
    { path: '/hr', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    { path: '/employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    // ข้อมูลของฉัน / คำขอของฉัน are deliberately absent: personal admin lives in
    // the topbar UserMenu, and the two pages are merged into /profile. /requests
    // stays here because an HR review queue is work, not personal admin.
    { path: '/requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', group: 'hr', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    // Tax-allowance (ล.ย.01) HR register (issue #387) — mirrors the route's own guard
    // (canViewTaxAllowanceRegister: hr+ceo) so CEO's read-only visibility into the register
    // still gets a nav entry, even though only hr can act on a row (canReviewTaxAllowances).
    { path: '/tax-allowance-review', label: 'ตรวจสอบค่าลดหย่อนภาษี', helper: 'Tax allowance review (ล.ย.01)', icon: 'badgeCheck', group: 'hr', show: hasPermission(user.role, 'canViewTaxAllowanceRegister') },
    // Attendance calendar admin (PR #480's API, this branch's UI): hr.holiday CRUD + the manual
    // BOT-fetch trigger, hr.work_schedule_assignment CRUD. HR/CEO only.
    { path: '/settings/attendance-calendar', label: 'ปฏิทินวันหยุด & ตารางงาน', helper: 'Holiday & work-schedule calendar', icon: 'calendar', group: 'hr', show: hasPermission(user.role, 'canManageAttendanceCalendar') },
    // Split (issue #390): nav visibility follows read access (hr+ceo); CEO lands on a read-only
    // view of the same page (PayrollPage.jsx gates writes on canManagePayroll internally).
    { path: '/payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', group: 'finance', show: hasPermission(user.role, 'canViewPayroll') },
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
    // Tax-allowance (ล.ย.01) self-service declaration (issue #387) — mirrors the route's own
    // guard in permissions.js's PATH_GUARDS (`!!u.employeeId`, same shape as /profile).
    { path: '/tax-allowance', label: 'ค่าลดหย่อนภาษี', helper: 'Tax allowance (ล.ย.01)', icon: 'calculator', group: 'self', show: !!user.employeeId },
  ].filter((item) => item.show);

  const closeTopbarPopover = useCallback(() => setActiveTopbarPopover(null), []);
  const closeDrawer = useCallback(() => setIsDrawerOpen(false), []);

  // Close the mobile drawer whenever the URL changes (navigation happened).
  useEffect(() => {
    closeDrawer();
    closeTopbarPopover();
  }, [closeDrawer, closeTopbarPopover, location.pathname]);

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
    <div className="flex h-dvh w-full max-w-full overflow-hidden">
      {/* WCAG 2.2 §2.4.1 Bypass Blocks: the first focusable element in the
          shell, ahead of the sidebar's ~10 nav items + group headers, so
          keyboard/screen-reader users don't re-traverse the nav on every
          route. sr-only until focused; `focus:not-sr-only` brings it on
          screen only while focused. */}
      <a
        href={`#${mainContentId}`}
        onClick={handleSkipLinkClick}
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[200] focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-surface focus:shadow-lg"
      >
        ข้ามไปยังเนื้อหาหลัก
      </a>
      <Sidebar
        id={drawerId}
        drawerRef={drawerRef}
        isDrawerOpen={isDrawerOpen}
        items={navItems}
      />
      <button
        type="button"
        className={cn(
          'hidden nav-drawer:block nav-drawer:fixed nav-drawer:inset-0 nav-drawer:z-30 nav-drawer:w-full nav-drawer:min-h-dvh',
          'nav-drawer:border-0 nav-drawer:bg-overlay-drawer nav-drawer:p-0 nav-drawer:opacity-0 nav-drawer:pointer-events-none',
          'nav-drawer:transition-opacity nav-drawer:duration-[160ms] nav-drawer:ease-[ease] motion-reduce:transition-none',
          isDrawerOpen && 'nav-drawer:opacity-100 nav-drawer:pointer-events-auto',
        )}
        aria-label="ปิดเมนู"
        onClick={closeDrawer}
        tabIndex={-1}
      />
      {/* tabIndex={-1}: focusable via the skip link's .focus() call, but not
          part of the normal Tab sequence. The global `:focus-visible`
          outline rule in styles.css excludes `[tabindex="-1"]`, and an
          outset outline would clip against this shell's `overflow-hidden`,
          so an inset ring (reusing the same focus-ring token) is used here
          to confirm the landing without changing layout. */}
      <main
        id={mainContentId}
        ref={mainRef}
        tabIndex={-1}
        className="flex min-w-0 flex-1 flex-col outline-none focus-visible:shadow-[inset_0_0_0_3px_var(--color-indigo-ring)]"
      >
        <header
          className={cn(
            'h-[var(--app-topbar-h)] max-w-full flex-none flex items-center justify-between gap-4',
            'pr-[max(28px,env(safe-area-inset-right))] pl-[max(28px,env(safe-area-inset-left))]',
            'mobile:gap-[10px] mobile:pr-[max(16px,env(safe-area-inset-right))] mobile:pl-[max(16px,env(safe-area-inset-left))]',
            'bg-surface border-b transition-colors duration-[var(--motion-standard)]',
            isContentScrolled ? 'border-border' : 'border-transparent',
          )}
        >
          <Button
            ref={menuButtonRef}
            variant="icon"
            className="!hidden nav-drawer:!inline-flex nav-drawer:flex-[0_0_44px]"
            type="button"
            onClick={() => {
              closeTopbarPopover();
              setIsDrawerOpen((open) => !open);
            }}
            aria-label="เปิดเมนูนำทาง"
            aria-controls={drawerId}
            aria-expanded={isDrawerOpen}
          >
            <Icon name="menu" />
          </Button>
          <div className="grid min-w-0 flex-1 gap-0.5" aria-label="พื้นที่ทำงานปัจจุบัน">
            <span className="overflow-hidden text-ellipsis whitespace-nowrap font-extrabold" translate="no">{PRODUCT_NAME}</span>
            <small className="overflow-hidden text-ellipsis whitespace-nowrap text-xs text-text-muted">{roleLabel(user.role)}</small>
          </div>
          <div className="flex flex-none items-center gap-2.5 mobile:gap-1">
            <div className="text-right mobile:hidden">
              <strong className="text-text">{employee?.nameTh || user.name}</strong>
              <span className="block text-xs font-medium text-text-muted">{user.email}</span>
            </div>
            <NotificationBell
              open={activeTopbarPopover === 'notifications'}
              onOpenChange={(open) => setActiveTopbarPopover(open ? 'notifications' : null)}
              onNavigate={(link) => navigate(link)}
            />
            <UserMenu
              open={activeTopbarPopover === 'user'}
              onOpenChange={(open) => setActiveTopbarPopover(open ? 'user' : null)}
              user={user}
              employee={employee}
              canViewProfile={!!user.employeeId}
              onNavigate={(link) => navigate(link)}
              onLogout={onLogout}
            />
          </div>
        </header>
        <div ref={contentScrollRef} className={CONTENT_SCROLL_CLASS}>
          <ErrorBoundary key={location.pathname}><Suspense fallback={<RouteFallback />}><Outlet /></Suspense></ErrorBoundary>
        </div>
      </main>
    </div>
  );
}
