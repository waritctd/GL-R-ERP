import { cn } from '../../utils/cn.js';
import { Icon } from './Icon.jsx';

/**
 * Tone -> background/text utilities for the icon tile. Ported from the
 * shared `.stat-indigo/.stat-teal/.stat-amber/.stat-blue/.stat-rose` rules
 * that used to live in styles.css — those selectors were shared with
 * StatusBadge's own `.status-*` tones there (see StatusBadge.jsx for that
 * half; `indigo`/`teal` reuse the same colors on both sides deliberately,
 * same as the legacy CSS did).
 *
 * `rose` carries the bare `stat-rose` token alongside its real utilities:
 * CompactStatRow.test.jsx asserts `[class*="stat-rose"]` is absent from that
 * component, i.e. this exact substring is a live test hook elsewhere in the
 * suite, so the marker has to keep existing even though no CSS rule targets
 * it any more.
 *
 * `neutral` has no entry on purpose. No `.stat-neutral` rule ever existed in
 * styles.css either — TicketDashboard.jsx passes tone="neutral" for its
 * "cancelled"/"dormant" cards and that has always rendered an unstyled icon
 * tile; an absent key here reproduces that exact gap instead of inventing a
 * new tone.
 */
export const STAT_TONE_CLASSES = {
  indigo: 'bg-info-bg-alt text-info-dark',
  teal: 'bg-success-bg text-success-dark',
  amber: 'bg-warning-bg text-warning',
  blue: 'bg-info-bg text-info',
  rose: 'stat-rose bg-danger-bg text-danger-dark',
};

/**
 * The icon tile itself, reproducing `.stat-icon` (40px square, collapsing to
 * 30px with a forced 16px child svg at <=720px). Exported so the handful of
 * call sites that render a single stat-icon standalone, outside a full
 * StatCard, can reuse the exact same tile instead of hand-rolling it a
 * second time: EmployeeSelfService.jsx, EmployeeDashboard.jsx and
 * TaxAllowanceActionRow.jsx. All three already override the margin
 * themselves (`!mb-0`), so no margin utility is baked in here — StatCard
 * below adds its own.
 */
export const STAT_ICON_TILE_CLASSES =
  'grid h-10 w-10 place-items-center rounded-md mobile:h-[30px] mobile:w-[30px] mobile:[&_svg]:h-4 mobile:[&_svg]:w-4';

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
        // Bare marker, no CSS rule behind it: TicketDashboard.test.jsx and
        // CeoOverview.test.jsx locate a card via `.closest('.stat-card')`,
        // and EmployeeDashboard.test.jsx via `.closest('div.stat-card')`.
        'stat-card',
        'bg-surface border border-border rounded-md p-4 mobile:p-3',
        onClick && [
          'w-full text-left cursor-pointer transition-colors',
          'hover:border-primary/50 hover:bg-surface-hover',
          'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover',
        ],
      )}
    >
      <div className={cn(STAT_ICON_TILE_CLASSES, 'mb-3.5 mobile:mb-2', STAT_TONE_CLASSES[tone])}>
        <Icon name={icon} size={21} />
      </div>
      {/* Bare marker, no CSS rule behind it: 6 unit tests do
          `querySelector('.stat-value')` (CeoOverview/SalesOverview/TicketDashboard). */}
      <div className="stat-value text-[length:var(--text-3xl)] font-extrabold leading-[1.1] text-text mobile:text-[length:var(--text-xl)]">
        {value}
      </div>
      {/* Bare marker, no CSS rule behind it: EmployeeDashboard.test.jsx and
          CeoOverview.test.jsx find this via `el.classList.contains('stat-label')`. */}
      <div className="stat-label mt-[5px] font-bold mobile:mt-[3px] mobile:text-[length:var(--text-xs)]">
        {label}
      </div>
      {helper ? (
        <div className="block text-[length:var(--text-xs)] font-medium text-text-muted mobile:mt-px mobile:truncate">
          {helper}
        </div>
      ) : null}
    </Tag>
  );
}
