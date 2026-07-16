import { cn } from '../../utils/cn.js';
import { Icon } from './Icon.jsx';

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
        'stat-card',
        onClick && [
          'w-full text-left cursor-pointer transition-colors',
          'hover:border-primary/50 hover:bg-surface-hover',
          'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover',
        ],
      )}
    >
      <div className={`stat-icon stat-${tone}`}>
        <Icon name={icon} size={21} />
      </div>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {helper ? <div className="stat-helper">{helper}</div> : null}
    </Tag>
  );
}
