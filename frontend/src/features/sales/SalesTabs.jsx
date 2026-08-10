import { NavLink } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';

// The งานขาย (Sales) workspace is one sidebar menu. คำขอราคา and โครงการ were
// merged into the single deal-pipeline page (/tickets — one ticket = one deal).
// The sidebar item stays highlighted across it and the detail pages via AppShell
// `match`, and deep links keep working unchanged.
//
// There used to be a second tab here, ภาพรวม (/ticket-overview, TicketDashboard).
// Removed 2026-08-10, owner ruling: it served no purpose. Everything it showed is
// either on the deal list itself or on the role's own landing Overview.
const DEAL_LIST_TAB = { path: '/tickets', label: 'ดีลทั้งหมด' };

const PRICING_QUEUE_TAB = { path: '/pricing-requests', label: 'คิวขอราคา' };

export function SalesTabs({ role }) {
  // Import's cross-deal PricingRequest queue is its own tab, shown only to roles
  // that may view it (import/ceo/sales_manager) — mirrors
  // ROLE_PERMISSIONS.canViewPricingRequestQueue / app/permissions.js PATH_GUARDS
  // for '/pricing-requests'.
  //
  // Role-scoped views: the deal-list tab is gated on canViewDealPipeline — the
  // pipeline BROWSER, not plain ticket-detail read — so this bar never offers a
  // tab the router (permissions.js PATH_GUARDS) would immediately bounce back
  // from. That drops both import and account from it (defensive: neither reaches
  // /tickets any more, but PricingRequestQueuePage still renders this bar for
  // import), leaving import with just its pricing queue tab below and account
  // with no tabs from this bar at all.
  const canViewPipeline = hasPermission(role, 'canViewDealPipeline');
  const pipelineTabs = canViewPipeline ? [DEAL_LIST_TAB] : [];
  // Phase A: import's day starts at the pricing queue, not the deal list, so it
  // leads the tab order when both are present. ceo/sales_manager also pass
  // canViewPricingRequestQueue but are oversight roles browsing everything rather
  // than a role with one obvious first stop — they keep the deal-list-first
  // order. account has no dedicated tab here: its worklist IS ดีลทั้งหมด
  // (TicketListPage defaults account to its money-worklist inbox — see
  // salesViewScope.dealInScope), so no extra tab is needed to "lead" with it.
  const canViewQueue = hasPermission(role, 'canViewPricingRequestQueue');
  const tabs = canViewQueue
    ? (role === 'import' ? [PRICING_QUEUE_TAB, ...pipelineTabs] : [...pipelineTabs, PRICING_QUEUE_TAB])
    : pipelineTabs;

  // A tab bar offering ONE destination is chrome that navigates nowhere: the tab
  // is always the page you are already on. That was invisible while every
  // pipeline role had two, and dropping ภาพรวม leaves `sales` with exactly one
  // (ดีลทั้งหมด) and `import` with exactly one (คิวขอราคา). Both keep their
  // sidebar entries — /tickets and /pricing-requests are separate nav items in
  // AppShell — so nothing becomes unreachable by hiding this.
  if (tabs.length < 2) return null;

  return (
    <nav
      className="flex w-fit gap-1 rounded-xl border border-border bg-surface p-1"
      aria-label="งานขาย (Sales)"
    >
      {tabs.map((tab) => (
        <NavLink
          key={tab.path}
          to={tab.path}
          className={({ isActive }) => `rounded-lg px-3.5 py-1.5 text-sm font-bold no-underline ${
            isActive ? 'bg-info-bg-alt text-info' : 'text-text-muted hover:text-text'
          }`}
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
