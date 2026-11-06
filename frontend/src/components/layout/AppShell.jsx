import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SALES_ENABLED } from '../../app/features.js';
import { hasPermission, isDivisionManager, isQuotationReleaseUser, isReleasedPath, isSelfServiceLocked } from '../../app/permissions.js';
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
import { ViewAsBanner } from './ViewAsBanner.jsx';
import { ViewAsPickerModal } from './ViewAsPickerModal.jsx';

// Tailwind port of `.content-scroll`: the gutters are CSS custom properties (not plain
// utilities) because DepositNoticePage.jsx measures scrollWidth against this same padding,
// so the names must stay stable. `--deal-header-h` is written by TicketDetailPage.jsx's
// ResizeObserver and consumed only by the deal page's content scroll-margin.
//
// There is deliberately no desktop scroll-padding-top here. Native focus scrolls the focused
// element clear of scroll-padding, so a padding sized to the sticky deal header made focusing
// any control inside that header (and, via the 18rem fallback, anything in the top ~300px of
// every page) scroll the page. The deal page instead puts a scroll-margin-top on its content's
// descendants (TicketDetailPage.jsx content wrapper).
const CONTENT_SCROLL_CLASS = cn(
  // `content-scroll` carries no CSS of its own; it is a *selector* hook. TicketDetailPage.jsx's
  // `chrome.closest('.content-scroll')` walks up to this element to read its scrollTop, so
  // renaming the class breaks it.
  'content-scroll',
  // `relative` is load-bearing: it makes the scroll container its own subtree's containing
  // block. Without it, a `position: absolute` descendant with no other positioned ancestor
  // resolves against the initial containing block (the document) at its static position, which
  // on a long page lands far below the viewport and gives `<html>` a second, outer scrollbar
  // over a shell that should be exactly viewport-height. Tailwind's `sr-only` is
  // `position: absolute`, so invisible elements (FileUploadField's hidden file input,
  // `<span class="sr-only">` link suffixes, DataTable's live region and caption) hit this.
  // Fixed here, not per page: it is a property of the scroll container. `overflow-auto` does not
  // help (containing block != scroll container). `z-index` stays `auto`, so no stacking context
  // is created; portalled/`fixed` chrome (Modal, Toast, OverflowMenu) is unaffected.
  'relative',
  'min-w-0 max-w-full flex-1 overflow-auto [scrollbar-gutter:stable]',
  '[--deal-scroll-pad-y:28px] [--deal-scroll-pad-x:max(32px,env(safe-area-inset-right))] [--deal-scroll-pad-left:max(32px,env(safe-area-inset-left))]',
  'pt-[var(--deal-scroll-pad-y)] pr-[var(--deal-scroll-pad-x)] pb-[42px] pl-[var(--deal-scroll-pad-left)]',
  // A condensing sticky chrome shrinks ~200-230px; with scroll anchoring on, the browser subtracts
  // that from scrollTop, which clamps to 0 and un-condenses the chrome, in a loop. Any page that
  // mounts a `data-sticky-chrome` element (TicketDetailPage) opts this scroller out of anchoring.
  'scroll-pb-8 has-[[data-sticky-chrome]]:[overflow-anchor:none]',
  'mobile:[--deal-scroll-pad-y:16px] mobile:[--deal-scroll-pad-x:max(16px,env(safe-area-inset-right))] mobile:[--deal-scroll-pad-left:max(16px,env(safe-area-inset-left))]',
  'mobile:pb-[32px] mobile:scroll-pt-4 mobile:scroll-pb-[calc(112px_+_env(safe-area-inset-bottom))]',
  // The content gutter widens from 32px to 40px at the 1366px desktop band.
  'min-[1366px]:[--deal-scroll-pad-x:max(40px,env(safe-area-inset-right))]',
  'min-[1366px]:[--deal-scroll-pad-left:max(40px,env(safe-area-inset-left))]',
);

export function AppShell({ user, employee, onLogout, pendingRequestCount }) {
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [activeTopbarPopover, setActiveTopbarPopover] = useState(null);
  const [isContentScrolled, setIsContentScrolled] = useState(false);
  const [viewAsPickerOpen, setViewAsPickerOpen] = useState(false);
  const drawerRef = useRef(null);
  const menuButtonRef = useRef(null);
  // One ref for one element: <main> IS the scroll container (it carries
  // `.content-scroll`), so the skip link's focus target and the scrolled box
  // are the same node — see the JSX comment on <main> below.
  const mainRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const drawerId = 'mobile-navigation-drawer';
  const mainContentId = 'main-content';

  // The topbar's bottom border only appears once content is scrolled underneath it. Tracks
  // `.content-scroll`'s own scrollTop (<main> is the real scroll container), not the window's.
  const updateContentScrolled = useCallback(() => {
    setIsContentScrolled((mainRef.current?.scrollTop ?? 0) > 0);
  }, []);

  useEffect(() => {
    const el = mainRef.current;
    if (!el) return undefined;
    updateContentScrolled();
    el.addEventListener('scroll', updateContentScrolled, { passive: true });
    return () => el.removeEventListener('scroll', updateContentScrolled);
  }, [updateContentScrolled]);

  // A route change swaps `.content-scroll`'s children without remounting it, so scrollTop is
  // not reset on its own; reset the border so it doesn't stay "stuck on" for a shorter page.
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
  const navLocked = isSelfServiceLocked(user);
  const navItems = [
    { path: '/', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    // Admin capability, not a role: `user.admin` comes from AuthResponse and is held by exactly
    // one person. Ungrouped and directly under the dashboard because it is a whole-portal view,
    // not part of any one ฝ่าย's work. Hiding it is cosmetic — ActivityLogService.requireAdmin is
    // what actually refuses everyone else.
    { path: '/activity-log', label: 'บันทึกการใช้งาน', helper: 'Portal activity log', icon: 'clipboard', show: Boolean(user.admin) },
    // Division-manager (non-sales) "ทีมของฉัน" group: reuses the /employee-requests, /leave,
    // /attendance routes every role already has (dual submit+approve pages for anyone with
    // reports), framed as team-facing entry points. The 'self' entries below stay the manager's
    // own self-service. No roster/`/employees` link: canViewEmployees is hr-only, and
    // `/attendance` already gives a division manager a per-employee view of their team.
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
      // sales_manager/ceo), narrower than canViewTickets (detail-read, kept
      // for import/account). Import gets คิวขอราคา + งานนำเข้า and Account gets
      // งานการเงิน instead, all below. งานนำเข้า is not the same page as the
      // dashboard worklist: see .design/import-fulfilment/INFORMATION_ARCHITECTURE.md §0.
      // See docs/role-scoped-views.md.
      show: hasPermission(user.role, 'canViewDealPipeline') && SALES_ENABLED,
      match: ['/tickets'],
    },
    // Direct deal quotation. Mirrors ROLE_PERMISSIONS.canViewDealQuotations
    // (sales/sales_manager/ceo/import/account) OR'd with the per-employee canCreateQuotation
    // capability grant, same as the '/quotations' PATH_GUARDS entry (app/permissions.js): a
    // grant-holder's role need not be in that list at all. `sales` sees only its own deals'
    // quotations, scoped server-side, not by this nav item.
    {
      path: '/quotations',
      label: 'ใบเสนอราคา',
      helper: 'Direct deal quotations',
      icon: 'fileText',
      group: 'sales',
      show: (hasPermission(user.role, 'canViewDealQuotations') || Boolean(user.canCreateQuotation)) && SALES_ENABLED,
      match: ['/quotations'],
    },
    { path: '/ceo-settings', label: 'ตั้งค่าราคา', helper: 'CEO price config', icon: 'setting', group: 'sales', show: user.role === 'ceo' && SALES_ENABLED },
    // Catalog browsing is scoped to the sales/CRM audience (canViewCatalog), not just the
    // feature flag.
    { path: '/catalog', label: 'แคตตาล็อกสินค้า', helper: 'Product catalog', icon: 'search', group: 'sales', show: hasPermission(user.role, 'canViewCatalog') && SALES_ENABLED },
    { path: '/price-import', label: 'นำเข้าราคา', helper: 'Price import', icon: 'upload', group: 'sales', show: hasPermission(user.role, 'canManagePriceImport') && SALES_ENABLED },
    // Import's cross-deal PricingRequest queue — see permissions.js's PATH_GUARDS
    // comment for why this is a narrower audience than a single request's detail page.
    { path: '/pricing-requests', label: 'คิวขอราคา', helper: 'Pricing request queue', icon: 'clipboard', group: 'sales', show: hasPermission(user.role, 'canViewPricingRequestQueue') && SALES_ENABLED },
    // Import's stream-2 workspace, placed immediately after stream 1 so its two
    // obligations to Sales read as two adjacent items in the order a deal travels
    // (price, then goods). NARROWER audience than its neighbour on purpose:
    // canActOnFulfilment mirrors TicketService.FULFILMENT_ROLES {import, ceo},
    // while canViewPricingRequestQueue also includes sales_manager — who would
    // find every button on this page 403s. `building`, not `clipboard`: the
    // neighbour already owns that glyph, and the chain ends at the warehouse.
    { path: '/fulfilment', label: 'งานนำเข้า', helper: 'Import fulfilment worklist', icon: 'building', group: 'sales', show: hasPermission(user.role, 'canActOnFulfilment') && SALES_ENABLED },
    // Account has NO ค่าคอมมิชชัน nav item: the invoice+commission step (บันทึกใบกำกับ /
    // create-from-deal at close) is the LAST STAGE of the งานการเงิน money lifecycle + the
    // Overview worklist (รับมัดจำ → รับชำระส่วนที่เหลือ → ยืนยันพร้อมปิดงาน → บันทึกใบกำกับ), not a
    // standalone menu. The /commissions ROUTE stays reachable (canViewCommissions still
    // includes account) so /commissions?ticketId=NN still deep-links into the create-from-deal
    // flow; account has no list access (canListCommissionRecords excludes it: GET
    // /api/commissions is sales/sales_manager/ceo only).
    { path: '/commissions', label: 'ค่าคอมมิชชัน', helper: 'Commissions', icon: 'badgeDollar', group: 'sales', show: hasPermission(user.role, 'canViewCommissions') && SALES_ENABLED && user.role !== 'account' },
    // Account's money-lifecycle worklist: deposit -> final payment -> close-ready ->
    // record-invoice/commission, one page. Deliberately NOT OR'd with the per-employee
    // canIssueBillingNote grant: see permissions.js's isBillingNoteReleaseUser for why.
    { path: '/finance', label: 'งานการเงิน', helper: 'Finance worklist', icon: 'badgeDollar', group: 'finance', show: hasPermission(user.role, 'canConfirmPayments') && SALES_ENABLED },
    { path: '/hr', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    { path: '/employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    // ข้อมูลของฉัน / คำขอของฉัน are deliberately absent: personal admin lives in
    // the topbar UserMenu, and the two pages are merged into /profile. /requests
    // stays here because an HR review queue is work, not personal admin.
    { path: '/requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', group: 'hr', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    // Tax-allowance (ล.ย.01) HR register: mirrors the route's own guard
    // (canViewTaxAllowanceRegister: hr+ceo) so CEO's read-only visibility into the register
    // still gets a nav entry, even though only hr can act on a row (canReviewTaxAllowances).
    { path: '/tax-allowance-review', label: 'ตรวจสอบค่าลดหย่อนภาษี', helper: 'Tax allowance review (ล.ย.01)', icon: 'badgeCheck', group: 'hr', show: hasPermission(user.role, 'canViewTaxAllowanceRegister') },
    // Attendance calendar admin: hr.holiday CRUD + the manual
    // BOT-fetch trigger, hr.work_schedule_assignment CRUD. HR/CEO only.
    { path: '/settings/attendance-calendar', label: 'ปฏิทินวันหยุด & ตารางงาน', helper: 'Holiday & work-schedule calendar', icon: 'calendar', group: 'hr', show: hasPermission(user.role, 'canManageAttendanceCalendar') },
    // §5 announcement PDF upload. Sits beside the calendar admin above because they are
    // the same job and the same audience.
    // `fileText` matches the glyph the leave page's own reference bar uses for this document, so the
    // nav item and the thing it administers read as the same object.
    { path: '/settings/leave-policy', label: 'ประกาศวันลา (PDF)', helper: 'Leave policy announcement', icon: 'fileText', group: 'hr', show: hasPermission(user.role, 'canManageLeavePolicyDocument') },
    // Nav visibility follows read access (hr+ceo); CEO lands on a read-only
    // view of the same page (PayrollPage.jsx gates writes on canManagePayroll internally).
    // `exact` because /payroll/deduction-shortfalls below is a SIBLING item, not a detail page of
    // this one — without it prefix matching would light both rows at once (see Sidebar.jsx).
    { path: '/payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', group: 'finance', exact: true, show: hasPermission(user.role, 'canViewPayroll') },
    // Garnishment shortfall ledger. Same audience as /payroll —
    // `fileText`, not `triangleAlert`: that glyph already means "live warning" across six feature
    // surfaces (PayrollPage, TaxAllowanceReviewPage, SpecialMoneyPanel …), and no other nav item
    // uses it. Permanent navigation chrome pointing at a routine ledger would dilute it.
    { path: '/payroll/deduction-shortfalls', label: 'ยอดค้างหักตามหมายบังคับคดี', helper: 'Deduction shortfalls', icon: 'fileText', group: 'finance', show: hasPermission(user.role, 'canViewPayroll') },
    // Written-consent register. Same audience as its two siblings
    // record action once inside. `clipboard`, not `shield` or `badgeCheck`: this is HR's filing
    // record of a signed letter, and a shield/check glyph would imply the app is verifying or
    // clearing something, which is precisely what this register does NOT do.
    { path: '/payroll/deduction-consents', label: 'ทะเบียนหนังสือยินยอมหักเงิน', helper: 'Written deduction consents', icon: 'clipboard', group: 'finance', show: hasPermission(user.role, 'canViewPayroll') },
    { path: '/attendance', label: 'เวลาทำงาน', helper: 'Attendance', icon: 'calendar', group: 'self', show: true },
    // Combined OT + welfare/special-money page (RequestsPage.jsx, tabs carried
    // in ?tab=). `match` keeps this item highlighted on both /employee-requests and
    // the /overtime redirect alias, mirroring the /tickets pattern above.
    {
      path: '/employee-requests',
      // Matches the page's own <PageHeader> title (RequestsPage.jsx).
      label: 'คำขอเงินพิเศษ',
      helper: 'Requests (overtime / welfare)',
      icon: 'clock',
      group: 'self',
      show: !!user.employeeId || hasPermission(user.role, 'canViewAllOvertime') || hasPermission(user.role, 'canViewAllSpecialMoney'),
      match: ['/employee-requests', '/overtime'],
    },
    { path: '/leave', label: 'วันลา', helper: 'Leave', icon: 'clipboard', group: 'self', show: !!user.employeeId || hasPermission(user.role, 'canViewAllLeave') },
    // Tax-allowance (ล.ย.01) self-service declaration — mirrors the route's own
    // guard in permissions.js's PATH_GUARDS (`!!u.employeeId`, same shape as /profile).
    { path: '/tax-allowance', label: 'ค่าลดหย่อนภาษี', helper: 'Tax allowance (ล.ย.01)', icon: 'calculator', group: 'self', show: !!user.employeeId },
  ]
    .filter((item) => item.show)
    // Release lockdown (SELF_SERVICE_ONLY — see app/features.js). Everyone
    // except HR and CEO keeps only the ungrouped '/' dashboard and the 'self'
    // group (เวลาทำงาน, คำขอเงินพิเศษ, วันลา, ค่าลดหย่อนภาษี); the 'sales',
    // 'hr', 'finance' and 'team' groups disappear entirely.
    //
    // Group-based, not path-based, and deliberately so: a new nav item is
    // hidden by default under the lock unless it is explicitly filed under
    // 'self', which matches how permissions.js's SELF_SERVICE_PATHS allowlist
    // treats routes. The two lists have to agree — a visible item pointing at a
    // path canAccessPath refuses would render a nav link straight to the
    // access-denied page.
    //
    // Dropping the 'team' group costs a division manager nothing but duplicate
    // links: its three entries point at /employee-requests, /leave and
    // /attendance, which are the same routes the surviving 'self' group lists.
    //
    // One exception: ใบเสนอราคา stays for the quotation audience, the nav
    // twin of permissions.js's quotation-release path exemption, keyed on the same predicate so
    // the two cannot disagree. It keeps its 'sales' group, so that header shows with this one item.
    .filter((item) => !navLocked || !item.group || item.group === 'self'
      || (item.path === '/quotations' && isQuotationReleaseUser(user))
      // import's งานนำเข้า + คิวขอราคา and account's งานการเงิน survive the lock: the same
      // allowlist canAccessPath applies.
      || isReleasedPath(item.path, user));

  const closeTopbarPopover = useCallback(() => setActiveTopbarPopover(null), []);
  const closeDrawer = useCallback(() => setIsDrawerOpen(false), []);

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
      {/* Bypass Blocks (WCAG 2.2 §2.4.1): the first focusable element, ahead of the nav items.
          sr-only until focused. */}
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
      {/* The topbar is a SIBLING of <main>, never a child: `<header>` scoped to <body> maps to
          the `banner` landmark, and a banner nested inside `main` is an a11y defect (landmark
          navigation expects them side by side, and `getByRole('main')` would include the
          notification bell and account menu). A plain <div> wraps header+content so the header
          stays a `banner`; the flex-none header + flex-1 overflow-auto scroller keeps the topbar
          pinned at every breakpoint. */}
      <div className="flex min-w-0 flex-1 flex-col">
        {user.viewAs ? <ViewAsBanner user={user} /> : null}
        <header
          className={cn(
            // `topbar`/`topbar-title` carry no CSS of their own; kept as selector hooks.
            'topbar',
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
          <div className="topbar-title grid min-w-0 flex-1 gap-0.5" aria-label="พื้นที่ทำงานปัจจุบัน">
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
              onViewAs={user.canViewAs && !user.viewAs ? () => setViewAsPickerOpen(true) : undefined}
            />
          </div>
        </header>
        {/* tabIndex={-1}: focusable via the skip link's .focus() call, but not part of the Tab
            sequence. The global `:focus-visible` rule excludes `[tabindex="-1"]`, and an outset
            outline would clip against this shell's `overflow-hidden`, so an inset ring is used.
            <main> *is* the scroll container, so the landmark and the scrolled box are the same
            node: `.content-scroll` stays a direct parent of page content (LeaveSurfacePage.jsx's
            positioning-context fix depends on that) and no extra flex level needs its own
            `min-h-0`. */}
        <main
          id={mainContentId}
          ref={mainRef}
          tabIndex={-1}
          className={cn(
            CONTENT_SCROLL_CLASS,
            'outline-none focus-visible:shadow-[inset_0_0_0_3px_var(--color-indigo-ring)]',
          )}
        >
          <ErrorBoundary key={location.pathname}><Suspense fallback={<RouteFallback />}><Outlet /></Suspense></ErrorBoundary>
        </main>
      </div>
      {viewAsPickerOpen ? <ViewAsPickerModal onClose={() => setViewAsPickerOpen(false)} /> : null}
    </div>
  );
}
