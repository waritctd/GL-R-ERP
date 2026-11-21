import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { SALES_ENABLED } from '../../app/features.js';
import { hasPermission, isDivisionManager, isMyOrdersGrantUser, isOversightReader, isQuotationReleaseUser, isReaderPath, isReleasedPath, isSelfServiceLocked } from '../../app/permissions.js';
import { PRODUCT_NAME } from '../../app/product.js';
import { cn } from '../../utils/cn.js';
import { displayRole, roleLabel } from '../../utils/format.js';
import { ErrorBoundary } from '../common/ErrorBoundary.jsx';
import { RouteFallback } from '../common/RouteFallback.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { WhatsNewBubble } from '../../features/whatsNew/WhatsNewBubble.jsx';
import { Launcher } from './Launcher.jsx';
import { UserMenu } from './UserMenu.jsx';
import { TestSiteSwitcher } from './TestSiteSwitcher.jsx';
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
  const expressQuery = useQuery({
    queryKey: queryKeys.receiptImportLatest(),
    queryFn: () => api.receiptImports.latest(),
    enabled: user.role === 'account' && SALES_ENABLED,
    retry: false,
  });
  const expressUploadEnabled = expressQuery.data?.uploadEnabled === true;
  const [isLauncherOpen, setIsLauncherOpen] = useState(false);
  const [activeTopbarPopover, setActiveTopbarPopover] = useState(null);
  const [isContentScrolled, setIsContentScrolled] = useState(false);
  const [viewAsPickerOpen, setViewAsPickerOpen] = useState(false);
  const launcherRef = useRef(null);
  const menuButtonRef = useRef(null);
  // One ref for one element: <main> IS the scroll container (it carries `.content-scroll`), so the skip link's
  // focus target and the scrolled box are the same node — see the JSX comment on <main> below.
  const mainRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const launcherId = 'primary-navigation';
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

  // WCAG 2.2 §2.4.1 Bypass Blocks: a bare `href="#main-content"` moves the viewport but focus-follows-fragment
  // behaviour is inconsistent across browsers, so this focuses the target explicitly rather than relying on it.
  const handleSkipLinkClick = useCallback((event) => {
    event.preventDefault();
    mainRef.current?.focus();
  }, []);
  const isTeamManager = isDivisionManager(user);
  const navLocked = isSelfServiceLocked(user);
  // v2.3.0 read-all: the 7 sales/finance pages open for sales managers and grant holders; the lock filter below keeps them in step with canAccessPath.
  const reader = isOversightReader(user);
  const navItems = [
    { path: '/', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    // Admin capability, not a role: `user.admin` comes from AuthResponse and is held by exactly one person.
    { path: '/activity-log', label: 'บันทึกการใช้งาน', helper: 'Portal activity log', icon: 'clipboard', group: 'settings', lockExempt: true, show: Boolean(user.admin) },
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
    // งานขาย is one workspace: the deal pipeline (/tickets — one ticket = one deal, คำขอราคา and โครงการ merged
    // into one page) plus the ภาพรวม dashboard as a tab (SalesTabs). `match` keeps this item highlighted across
    // both and detail pages.
    {
      path: '/tickets',
      label: 'งานทั้งหมด',
      helper: 'All deals',
      icon: 'briefcase',
      group: 'sales',
      // Role-scoped views: the pipeline BROWSER is canViewDealPipeline (sales/
      // sales_manager/ceo), narrower than canViewTickets (detail-read, kept
      // for import/account). Import gets คิวขอราคา + งานนำเข้า and Account gets
      // งานการเงิน instead, all below. งานนำเข้า is not the same page as the
      // dashboard worklist: see .design/import-fulfilment/INFORMATION_ARCHITECTURE.md §0.
      // See docs/role-scoped-views.md.
      show: (hasPermission(user.role, 'canViewDealPipeline') || reader) && SALES_ENABLED,
      match: ['/tickets'],
    },
    // Direct deal quotation. Mirrors ROLE_PERMISSIONS.canViewDealQuotations
    // (sales/sales_manager/ceo/import/account) OR'd with the per-employee canCreateQuotation
    // capability grant, same as the '/quotations' PATH_GUARDS entry (app/permissions.js): a
    // grant-holder's role need not be in that list at all. `sales` sees only its own deals'
    // quotations, scoped server-side, not by this nav item.
    {
      path: '/quotations',
      label: 'เอกสารขาย',
      helper: 'Sales documents',
      icon: 'fileText',
      group: 'sales',
      show: (hasPermission(user.role, 'canViewDealQuotations') || Boolean(user.canCreateQuotation) || reader) && SALES_ENABLED,
      match: ['/quotations'],
    },
    // นัดเข้าพบ — after เอกสารขาย (was ใบเสนอราคา until 2026-11-20), where the design's NAV puts it for sales. Same audience as the '/visits'
    // PATH_GUARDS entry (canViewVisits = VisitService.VISIT_ROLES): a rep's own week; sales_manager / ceo the team's.
    { path: '/visits', label: 'นัดเข้าพบ', helper: 'Visits', icon: 'calendar', group: 'sales', show: (hasPermission(user.role, 'canViewVisits') || reader) && SALES_ENABLED },
    // ของที่ฉันสั่ง: the rep's one page of all incoming goods (the ONLY new sales page for shipments), from the menu.
    { path: '/my-orders', label: 'ของที่ฉันสั่ง', helper: 'My incoming orders', icon: 'building', group: 'sales', show: (hasPermission(user.role, 'canViewMyOrders') || reader || Boolean(user.canCreateQuotation) || Boolean(user.canSeeShowroomOrders)) && SALES_ENABLED },
    { path: '/ceo-settings', label: 'ตั้งค่าราคา', helper: 'CEO price config', icon: 'setting', group: 'settings', show: user.role === 'ceo' && SALES_ENABLED },
    // Catalog browsing is scoped to the sales/CRM audience (canViewCatalog), not just the
    // feature flag.
    { path: '/catalog', label: 'แคตตาล็อกสินค้า', helper: 'Product catalog', icon: 'search', group: 'sales', show: (hasPermission(user.role, 'canViewCatalog') || reader) && SALES_ENABLED },
    { path: '/price-import', label: 'นำเข้าราคา', helper: 'Price import', icon: 'upload', group: 'settings', show: hasPermission(user.role, 'canManagePriceImport') && SALES_ENABLED },
    // Import's cross-deal PricingRequest queue — see permissions.js's PATH_GUARDS comment for why this is a
    // narrower audience than a single request's detail page.
    { path: '/pricing-requests', label: 'คิวขอราคา', helper: 'Pricing request queue', icon: 'clipboard', group: 'sales', show: (hasPermission(user.role, 'canViewPricingRequestQueue') || reader) && SALES_ENABLED },
    // Import's stream-2 workspace, placed immediately after stream 1 so its two obligations to Sales read as two
    // adjacent items in the order a deal travels (price, then goods). `building`, not `clipboard`: the neighbour
    // already owns that glyph, and the chain ends at the warehouse.
    { path: '/fulfilment', label: 'การขนส่ง', helper: 'Import shipments — the one place a step is advanced', icon: 'building', group: 'sales', show: (hasPermission(user.role, 'canActOnFulfilment') || reader) && SALES_ENABLED },
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
    { path: '/finance', label: 'งานการเงิน', helper: 'Finance worklist', icon: 'badgeDollar', group: 'finance', show: (hasPermission(user.role, 'canConfirmPayments') || reader) && SALES_ENABLED },
    // The Express receipts upload: the same audience as /finance (account, ceo = ReceiptImportService.UPLOAD_ROLES).
    // The Express receipts page: ACCOUNT only (review H2/H3), and only once the owner has switched the upload on
    // (app.receipts.express-upload.enabled, review H4) — read from GET /api/sales/receipt-imports/latest.
    { path: '/finance/receipts-import', label: 'รับเงินจาก Express', helper: 'Express receipts', icon: 'upload', group: 'finance', show: user.role === 'account' && SALES_ENABLED && expressUploadEnabled },
    { path: '/hr', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    // Leave + check-in analytics: ceo/hr company-wide, a team head for their team. Follows the server's flag, not the role.
    { path: '/people-insights', label: 'วิเคราะห์การลาและเวลาเข้างาน', helper: 'Leave & check-in insights', icon: 'dashboard', group: 'hr', lockExempt: true, show: Boolean(user.canViewPeopleInsights) },
    { path: '/employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', group: 'hr', show: hasPermission(user.role, 'canViewEmployees') },
    // ข้อมูลของฉัน / คำขอของฉัน are deliberately absent: personal admin lives in the topbar UserMenu, and the two
    // pages are merged into /profile. /requests stays here because an HR review queue is work, not personal admin.
    { path: '/requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', group: 'hr', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    // Tax-allowance (ล.ย.01) HR register: mirrors the route's own guard
    // (canViewTaxAllowanceRegister: hr+ceo) so CEO's read-only visibility into the register
    // still gets a nav entry, even though only hr can act on a row (canReviewTaxAllowances).
    { path: '/tax-allowance-review', label: 'ตรวจสอบค่าลดหย่อนภาษี', helper: 'Tax allowance review (ล.ย.01)', icon: 'badgeCheck', group: 'hr', show: hasPermission(user.role, 'canViewTaxAllowanceRegister') },
    // Attendance calendar admin: hr.holiday CRUD + the manual
    // BOT-fetch trigger, hr.work_schedule_assignment CRUD. HR/CEO only.
    { path: '/settings/attendance-calendar', label: 'ปฏิทินวันหยุด & ตารางงาน', helper: 'Holiday & work-schedule calendar', icon: 'calendar', group: 'settings', show: hasPermission(user.role, 'canManageAttendanceCalendar') },
    // §5 announcement PDF upload. Sits beside the calendar admin above because they are
    // the same job and the same audience.
    // `fileText` matches the glyph the leave page's own reference bar uses for this document, so the
    // nav item and the thing it administers read as the same object.
    { path: '/settings/leave-policy', label: 'ประกาศวันลา (PDF)', helper: 'Leave policy announcement', icon: 'fileText', group: 'settings', show: hasPermission(user.role, 'canManageLeavePolicyDocument') },
    // Read access (hr+ceo); CEO gets a read-only view (writes gate on canManagePayroll inside PayrollPage). The
    // deduction ledgers are linked from that page, not from the nav.
    { path: '/payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', group: 'hr', show: hasPermission(user.role, 'canViewPayroll') },
    { path: '/attendance', label: 'เวลาทำงาน', helper: 'Attendance', icon: 'calendar', group: 'self', show: true },
    // Combined OT + welfare/special-money page (RequestsPage.jsx, tabs carried in ?tab=). `match` keeps this item
    // highlighted on both /employee-requests and the /overtime redirect alias, mirroring the /tickets pattern
    // above.
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
  ]
    .filter((item) => item.show)
    // Release lockdown (SELF_SERVICE_ONLY — see app/features.js). Everyone
    // except HR and CEO keeps only the ungrouped '/' dashboard and the 'self'
    // group (เวลาทำงาน, คำขอเงินพิเศษ, วันลา); the 'sales',
    // 'hr', 'finance', 'team' and 'settings' groups disappear entirely.
    //
    // Group-based by default, and deliberately so: a new nav item is
    // hidden by default under the lock unless it is explicitly filed under
    // 'self' (or flagged `lockExempt`), which matches how permissions.js's
    // SELF_SERVICE_PATHS allowlist treats routes. The two lists have to agree — a visible item pointing at a
    // path canAccessPath refuses would render a nav link straight to the
    // access-denied page.
    //
    // Dropping the 'team' group costs a division manager nothing but duplicate
    // links: its three entries point at /employee-requests, /leave and
    // /attendance, which are the same routes the surviving 'self' group lists.
    //
    // Exceptions: an item flagged `lockExempt` always stays (บันทึกการใช้งาน, which
    // is admin-only through its own `show`, the twin of canAccessPath's admin
    // activity-log exemption). ใบเสนอราคา stays for the quotation audience, the nav
    // twin of permissions.js's quotation-release path exemption, keyed on the same predicate so
    // the two cannot disagree. It keeps its 'sales' group, so that header shows with this one item.
    .filter((item) => !navLocked || !item.group || item.group === 'self'
      || item.lockExempt
      || (item.path === '/quotations' && isQuotationReleaseUser(user))
      // import's งานนำเข้า + คิวขอราคา and account's งานการเงิน survive the lock: the same
      // allowlist canAccessPath applies.
      || (reader && isReaderPath(item.path))
      || (item.path === '/my-orders' && isMyOrdersGrantUser(user))
      || isReleasedPath(item.path, user));

  const closeTopbarPopover = useCallback(() => setActiveTopbarPopover(null), []);
  const closeLauncher = useCallback(() => setIsLauncherOpen(false), []);

  useEffect(() => {
    if (activeTopbarPopover) closeLauncher();
  }, [activeTopbarPopover, closeLauncher]);

  useEffect(() => {
    closeLauncher();
    closeTopbarPopover();
  }, [closeLauncher, closeTopbarPopover, location.pathname]);

  useEffect(() => {
    if (!isLauncherOpen) return undefined;

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
      .from(launcherRef.current?.querySelectorAll(focusableSelector) || [])
      .filter((element) => element.getClientRects().length > 0);

    const focusableItems = getFocusableItems();
    (focusableItems[0] || launcherRef.current)?.focus();

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        closeLauncher();
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

    // Outside = neither the panel nor the menu button (the button's own click toggles).
    const handlePointer = (event) => {
      if (launcherRef.current?.contains(event.target) || menuButton?.contains(event.target)) return;
      closeLauncher();
    };

    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('mousedown', handlePointer);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('mousedown', handlePointer);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      } else {
        menuButton?.focus();
      }
    };
  }, [closeLauncher, isLauncherOpen]);

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
      {/* The topbar is a SIBLING of <main>, never a child: `<header>` scoped to <body> maps to
          the `banner` landmark, and a banner nested inside `main` is an a11y defect (landmark
          navigation expects them side by side, and `getByRole('main')` would include the
          notification bell and account menu). A plain <div> wraps header+content so the header
          stays a `banner`; the flex-none header + flex-1 overflow-auto scroller keeps the topbar
          pinned at every breakpoint. */}
      <div className="flex min-w-0 flex-1 flex-col">
        <TestSiteSwitcher user={user} />
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
          {/* The menu's layer: above the page and its own overlays (drawers / filter sheets top out at z 45) so the
              launcher covers the page, but BELOW the modal layer (Modal's backdrop, z-50) — at z-[201] it drew over
              every open dialog that reached the top of a phone screen (slice 6d review L5). */}
          <div className="relative z-[49] flex-none">
            <button
              ref={menuButtonRef}
              type="button"
              className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-md border-0 bg-transparent px-2 text-sm font-bold text-text hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]"
              onClick={() => {
                closeTopbarPopover();
                setIsLauncherOpen((open) => !open);
              }}
              aria-controls={isLauncherOpen ? launcherId : undefined}
              aria-expanded={isLauncherOpen}
            >
              <span data-testid="brand-mark" aria-hidden="true" className="grid size-6 flex-none grid-cols-2 gap-[2px]">
                <i className="rounded-sm bg-accent" />
                <i className="rounded-sm bg-primary-hover" />
                <i className="rounded-sm bg-icon-muted" />
                <i className="rounded-sm bg-accent" />
              </span>
              เมนู
              <Icon name="chevronDown" size={16} />
            </button>
            {isLauncherOpen ? <Launcher id={launcherId} panelRef={launcherRef} items={navItems} onNavigate={closeLauncher} /> : null}
          </div>
          <div className="topbar-title grid min-w-0 flex-1 gap-0.5" aria-label="พื้นที่ทำงานปัจจุบัน">
            <span className="overflow-hidden text-ellipsis whitespace-nowrap font-extrabold" translate="no">{PRODUCT_NAME}</span>
            <small className="overflow-hidden text-ellipsis whitespace-nowrap text-xs text-text-muted">{roleLabel(displayRole(user))}</small>
          </div>
          <div className="flex flex-none items-center gap-2.5 mobile:gap-1">
            <div className="text-right mobile:hidden">
              <strong className="text-text">{employee?.nameTh || user.name}</strong>
              <span className="block text-xs font-medium text-text-muted">{user.email}</span>
            </div>
            <WhatsNewBubble
              user={user}
              open={activeTopbarPopover === 'whatsNew'}
              onOpenChange={(open) => setActiveTopbarPopover(open ? 'whatsNew' : null)}
              onNavigate={(link) => navigate(link)}
            />
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
