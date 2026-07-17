import { NavLink } from 'react-router-dom';

// The งานขาย (Sales) workspace is one sidebar menu. ใบขอราคา and โครงการ were
// merged into the single deal-pipeline page (/tickets — one ticket = one deal);
// ภาพรวม is the overview dashboard. The sidebar item stays highlighted for both
// via AppShell `match`, and deep links keep working unchanged.
const TABS = [
  { path: '/tickets', label: 'ดีลทั้งหมด' },
  { path: '/ticket-overview', label: 'ภาพรวม' },
];

export function SalesTabs() {
  return (
    <nav
      className="flex w-fit gap-1 rounded-xl border border-border bg-surface p-1"
      aria-label="งานขาย (Sales)"
    >
      {TABS.map((tab) => (
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
