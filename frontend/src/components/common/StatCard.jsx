import { cn } from '../../utils/cn.js';
import { Icon } from './Icon.jsx';

/**
 * Tailwind port of `.stat-card` / `.stat-icon` / `.stat-*` / `.stat-value` /
 * `.stat-label` / `.stat-helper` (styles.css, retired), including their ≤720px
 * overrides — a phone shrinks the tile, its glyph and the value so a grid of
 * these stays scannable instead of eating several screens.
 *
 * The pieces are exported because five call sites render a stat tile without
 * this component's fixed icon/value/label/helper structure (AccountOverview ×2,
 * EmployeeDashboard, EmployeeSelfService, TaxAllowanceActionRow) and must keep
 * matching it exactly.
 *
 * `statToneClass` reproduces the old selector list EXACTLY, gaps included: the
 * stylesheet defined `.stat-indigo/.stat-teal/.stat-amber/.stat-blue/.stat-rose`
 * and nothing else, so a tone outside that set rendered an uncolored tile and
 * still does. Filling the gaps would be a visual change, not a port.
 *
 * `stat-value`/`stat-label` stay as class names with no rule behind them —
 * CeoOverview, SalesOverview and EmployeeDashboard tests select on them.
 */
const STAT_TONE_CLASSNAMES = {
  indigo: 'bg-info-bg-alt text-info-dark',
  teal: 'bg-success-bg text-success-dark',
  amber: 'bg-warning-bg text-warning',
  blue: 'bg-info-bg text-info',
  rose: 'bg-danger-bg text-danger-dark',
};

export function statToneClass(tone) {
  return STAT_TONE_CLASSNAMES[tone] ?? '';
}

export const statCardClass = 'stat-card bg-surface border border-border rounded-md p-4 mobile:p-3';

export const statIconClass = [
  'stat-icon w-10 h-10 grid place-items-center rounded-md mb-[14px]',
  'mobile:w-[30px] mobile:h-[30px] mobile:mb-2 mobile:[&_svg]:w-4 mobile:[&_svg]:h-4',
].join(' ');

export const statValueClass = 'stat-value text-text text-3xl font-extrabold leading-[1.1] mobile:text-xl';

export const statLabelClass = 'stat-label mt-[5px] font-bold mobile:mt-[3px] mobile:text-xs/[inherit]';

/**
 * Stat card captions ("Headcount", "Approvals", …) are data text, not an
 * icon/placeholder, so per DESIGN.md's Muted Floor Rule they must not go below
 * Ink Muted (#5c6b80) — Ink Faint is reserved for icons/placeholders.
 */
export const statHelperClass = [
  'stat-helper block text-text-muted text-xs/[inherit] font-medium',
  'mobile:mt-px mobile:overflow-hidden mobile:whitespace-nowrap mobile:text-ellipsis',
].join(' ');

/**
 * `onClick` is optional. When present the card renders as a real <button>
 * with a hover/focus affordance so it reads as navigable (dashboard "needs
 * action" step, ui-responsive-repair-plan.md Step 7 — "metric cards that
 * navigate must look clickable ... cards that don't navigate must NOT look
 * clickable"). Every other caller (Attendance/Commission/Payroll/Leave/OT
 * pages) omits `onClick` and renders exactly as before, unchanged.
 */
export function StatCard({ icon, label, value, helper, tone = 'indigo', onClick }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      onClick={onClick}
      className={cn(
        statCardClass,
        onClick && [
          'w-full text-left cursor-pointer transition-colors',
          'hover:border-primary/50 hover:bg-surface-hover',
          'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover',
        ],
      )}
    >
      <div className={cn(statIconClass, statToneClass(tone))}>
        <Icon name={icon} size={21} />
      </div>
      <div className={statValueClass}>{value}</div>
      <div className={statLabelClass}>{label}</div>
      {helper ? <div className={statHelperClass}>{helper}</div> : null}
    </Tag>
  );
}
