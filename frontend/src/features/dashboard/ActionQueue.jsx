import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { Panel } from '../../components/common/Layout.jsx';

/** "ต้องดำเนินการ" needs-action panel — ui-responsive-repair-plan.md Step 7. Built ONLY from counts the
 * dashboards already fetch (dashboardSummary.pendingApprovals, dashboardSummary.notifications, ticket status
 * counts). */
export function ActionQueue({ items }) {
  const active = items.filter((item) => Number(item.value) > 0);

  if (active.length === 0) {
    return (
      <Panel className="mobile:hidden">
        <div className="flex items-center gap-3 text-text-muted">
          <Icon name="check" size={18} />
          <span className="text-sm font-bold">ไม่มีรายการที่ต้องดำเนินการตอนนี้</span>
        </div>
      </Panel>
    );
  }

  return (
    <Panel flush title="ต้องดำเนินการ" className="mobile:hidden">
      <div className="flex flex-col">
        {active.map((item) => {
          const Tag = item.to ? 'button' : 'div';
          return (
            <Tag
              key={item.key}
              type={item.to ? 'button' : undefined}
              onClick={item.to}
              className={cn(
                'flex min-h-11 w-full items-center justify-between gap-3 border-0 border-t border-border px-5 py-3 text-left',
                item.to
                  ? 'cursor-pointer bg-transparent text-text transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover focus-visible:shadow-[inset_var(--shadow-focus-ring)]'
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
    </Panel>
  );
}
