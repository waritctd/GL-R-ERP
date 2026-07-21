import { NavLink } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';

// The งานขาย (Sales) workspace is one sidebar menu. ใบขอราคา and โครงการ were
// merged into the single deal-pipeline page (/tickets — one ticket = one deal);
// ภาพรวม is the overview dashboard. The sidebar item stays highlighted for both
// via AppShell `match`, and deep links keep working unchanged.
const BASE_TABS = [
  { path: '/tickets', label: 'ดีลทั้งหมด' },
  { path: '/ticket-overview', label: 'ภาพรวม' },
];

const PRICING_QUEUE_TAB = { path: '/pricing-requests', label: 'คิวขอราคา' };

export function SalesTabs({ role }) {
  // Commit 6: Import's cross-deal PricingRequest queue is its own tab, shown
  // only to roles that may view it (import/ceo/sales_manager) — mirrors
  // ROLE_PERMISSIONS.canViewPricingRequestQueue / app/permissions.js
  // PATH_GUARDS for '/pricing-requests'.
  //
  // Role-scoped views, Phase A: import's day starts at the pricing queue, not
  // the deal list, so it leads the tab order. ceo/sales_manager also pass
  // canViewPricingRequestQueue but are oversight roles browsing everything
  // rather than a role with one obvious first stop — they keep the deal-list-
  // first order. account has no dedicated tab here: its worklist IS
  // ดีลทั้งหมด (TicketListPage defaults account to its money-worklist inbox —
  // see salesViewScope.dealInScope), so no extra tab is needed to "lead"
  // with it.
  const canViewQueue = hasPermission(role, 'canViewPricingRequestQueue');
  const tabs = canViewQueue
    ? (role === 'import' ? [PRICING_QUEUE_TAB, ...BASE_TABS] : [...BASE_TABS, PRICING_QUEUE_TAB])
    : BASE_TABS;

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
