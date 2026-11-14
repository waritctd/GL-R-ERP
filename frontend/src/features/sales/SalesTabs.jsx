/* Hallmark · component: tab-nav · genre: modern-minimal · theme: design.md (primary selection tint) states:
 * default · hover · focus-visible · active (aria-current) contrast: active #4f46e5 on #e0e7ff ~6:1 (pass) ·
 * ring outline-2 primary, offset -2px */
import { NavLink } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';

// The งานขาย (Sales) workspace is one sidebar menu. คำขอราคา and โครงการ were merged into the single
// deal-pipeline page (/tickets — one ticket = one deal). The sidebar item stays highlighted across it and the
// detail pages via AppShell `match`, and deep links keep working unchanged.
const DEAL_LIST_TAB = { path: '/tickets', label: 'ดีลทั้งหมด' };

const PRICING_QUEUE_TAB = { path: '/pricing-requests', label: 'คิวขอราคา' };

export function SalesTabs({ role }) {
  // Import's cross-deal PricingRequest queue is its own tab, shown only to roles that may view it
  // (import/ceo/sales_manager) — mirrors ROLE_PERMISSIONS.canViewPricingRequestQueue / app/permissions.js
  // PATH_GUARDS for '/pricing-requests'.
  const canViewPipeline = hasPermission(role, 'canViewDealPipeline');
  const pipelineTabs = canViewPipeline ? [DEAL_LIST_TAB] : [];
  // Phase A: import's day starts at the pricing queue, not the deal list, so it leads the tab order when both
  // are present.
  const canViewQueue = hasPermission(role, 'canViewPricingRequestQueue');
  const tabs = canViewQueue
    ? (role === 'import' ? [PRICING_QUEUE_TAB, ...pipelineTabs] : [...pipelineTabs, PRICING_QUEUE_TAB])
    : pipelineTabs;

  // A tab bar offering ONE destination is chrome that navigates nowhere: the tab is always the page you are
  // already on. That was invisible while every pipeline role had two, and dropping ภาพรวม leaves `sales` with
  // exactly one (ดีลทั้งหมด) and `import` with exactly one (คิวขอราคา).
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
          className={({ isActive }) => `rounded-lg px-3.5 py-1.5 text-sm no-underline transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-primary ${
            isActive ? 'bg-primary-bg font-bold text-primary' : 'font-semibold text-text-muted hover:text-text'
          }`}
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
