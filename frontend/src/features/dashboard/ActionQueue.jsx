import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';

/**
 * "ต้องดำเนินการ" needs-action panel — ui-responsive-repair-plan.md Step 7.
 *
 * Built ONLY from counts the dashboards already fetch (dashboardSummary.pendingApprovals,
 * dashboardSummary.notifications, ticket status counts). No new data, no invented
 * urgency/SLA/priority — items are just filtered to value > 0 and rendered in the
 * order the caller passes them.
 *
 * `items`: [{ key, label, value, to?: () => void }]
 *   - `to` present  -> row is a real <button>, navigates, gets hover/focus affordance.
 *   - `to` omitted  -> row is informational only (e.g. unread notifications has no
 *     route — the bell dropdown is the only UI for it), so it must NOT look clickable.
 *
 * Desktop/tablet only (`max-[720px]:hidden`): ui-responsive-repair-plan.md Step 7's
 * item 10 ("mobile dashboard must not become too tall") is explicit, and Step 4
 * already fought hard to cut the mobile stat block from 1541px to 661px — this
 * panel measured +130-200px depending on how many items are active, which would
 * regress that. Mobile still gets the "needs action" signal for free: the
 * StatCard entries these dashboards already render become tappable (Step 7 item 3)
 * with zero added height, since only hover/focus/cursor styling changed.
 */
export function ActionQueue({ items }) {
  const active = items.filter((item) => Number(item.value) > 0);

  if (active.length === 0) {
    return (
      <section className="panel max-[720px]:hidden">
        <div className="flex items-center gap-3 text-text-muted">
          <Icon name="check" size={18} />
          <span className="text-sm font-bold">ไม่มีรายการที่ต้องดำเนินการตอนนี้</span>
        </div>
      </section>
    );
  }

  return (
    <section className="panel !p-0 overflow-hidden max-[720px]:hidden">
      <div className="panel-header !mb-0 px-5 py-4">
        <h2>ต้องดำเนินการ</h2>
      </div>
      <div className="flex flex-col">
        {active.map((item) => {
          const Tag = item.to ? 'button' : 'div';
          return (
            <Tag
              key={item.key}
              type={item.to ? 'button' : undefined}
              onClick={item.to}
              className={cn(
                'flex min-h-11 w-full items-center justify-between gap-3 border-t border-border px-5 py-3 text-left',
                item.to
                  ? 'cursor-pointer transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover focus-visible:shadow-[inset_var(--shadow-focus-ring)]'
                  : null,
              )}
            >
              <span className="min-w-0 truncate text-sm font-bold text-text">{item.label}</span>
              <span className="flex shrink-0 items-center gap-2">
                <StatusBadge tone="warning">{item.value}</StatusBadge>
                {item.to ? <Icon name="chevronRight" size={16} className="text-text-faint" /> : null}
              </span>
            </Tag>
          );
        })}
      </div>
    </section>
  );
}
