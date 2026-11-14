import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { failed, moneySum } from './dashboardParts.jsx';
import { TeamOverview } from '../salesBoard/TeamOverview.jsx';

/**
 * Sales Manager landing: ภาพรวมทีมขาย (spec 2026-11-15 §7.4, slice 1) — TeamOverview's two tiles, per-rep table and
 * the team's quiet deals, all from GET /api/sales/team-overview — then ค่าคอมรอคุณตรวจ (deep-links into
 * CommissionPage's approve flow, never duplicates the approval mutation). The former money band (a misleading
 * ฿0.00 cash figure when nothing was recorded), the blanket 7-day ดีลที่ต้องดูแล flags, the 7-day movement panel and
 * อัตราปิดงาน were replaced by it. Cash and visit tiles come with slices 2–3. Manager keeps every existing nav item.
 */

const MAX_COMMISSIONS = 5;

const PANEL_ROW = 'px-5 py-3 mobile:px-3.5';

function PanelSkeleton({ label }) {
  return (
    <div className="grid gap-2 px-5 py-4 mobile:px-3.5" role="status" aria-busy="true" aria-label={label}>
      <Skeleton height={36} />
      <Skeleton height={36} />
    </div>
  );
}

function Failed() {
  return <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>;
}

function Empty({ children }) {
  return (
    <div className="flex items-center gap-3 px-5 py-4 text-text-muted mobile:px-3.5">
      <Icon name="check" size={18} />
      <span className="text-sm font-bold">{children}</span>
    </div>
  );
}

function CountPill({ count, unit = 'รายการ' }) {
  return (
    <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface">
      <span aria-hidden="true">{count}</span>
      <span className="sr-only">{count} {unit}</span>
    </span>
  );
}

export function ManagerOverview({ user, employee }) {
  const navigate = useNavigate();
  const commissionsQuery = useQuery({
    queryKey: queryKeys.commissionsPendingApproval(),
    queryFn: () => api.commissions.pendingApproval().then((r) => r.commissions ?? []),
  });

  // null while loading: the panel shows its own skeleton, the rest of the page is already up.
  const pendingCommissions = commissionsQuery.data ?? null;
  const today = formatThaiDate(new Date().toISOString());
  const greeting = `สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh || user?.name)}`;
  const commissionsShown = pendingCommissions?.slice(0, MAX_COMMISSIONS) ?? [];

  return (
    <PageStack>
      <PageHeader
        title="ภาพรวมทีมขาย"
        subtitle={`${greeting} · ${today}`}
        actions={<Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูดีลทีม</Button>}
      />

      <TeamOverview showTitle={false} />

      <Panel flush title="ค่าคอมรอคุณตรวจ" actions={pendingCommissions ? <CountPill count={pendingCommissions.length} /> : null}>
        {failed(commissionsQuery) ? (
          <Failed />
        ) : pendingCommissions === null ? (
          <PanelSkeleton label="กำลังโหลดค่าคอมรอคุณตรวจ" />
        ) : pendingCommissions.length === 0 ? (
          <Empty>ไม่มีค่าคอมรออนุมัติตอนนี้</Empty>
        ) : (
          <>
            {commissionsShown.map(({ commission: record, ticketCode }) => {
              const ref = [record.invoiceDetails?.invoiceNumber, ticketCode].filter(Boolean).join(' · ');
              return (
                <div key={record.id} data-testid={`commission-row-${record.id}`} className={`flex items-center justify-between gap-3 border-t border-border first:border-t-0 ${PANEL_ROW}`}>
                  <div className="flex min-w-0 flex-1 items-center gap-3">
                    <Avatar name={record.salesRepName} size="sm" />
                    <div className="min-w-0 flex-1">
                      <strong className="block truncate text-sm font-extrabold text-text">{record.salesRepName || `#${record.salesRepId}`}</strong>
                      <code className="block truncate text-2xs text-text-muted">{ref || '-'}</code>
                    </div>
                  </div>
                  <span className="shrink-0 text-right">
                    <strong className="block tabular-nums text-sm text-text">{formatMoney(record.commissionableBase)}</strong>
                    <span className="block text-2xs text-text-muted">ฐานค่าคอม</span>
                  </span>
                </div>
              );
            })}
            <div className={`flex flex-wrap items-center justify-between gap-3 border-t border-border ${PANEL_ROW}`}>
              <span className="text-sm text-text-muted">
                {pendingCommissions.length > MAX_COMMISSIONS ? `อีก ${pendingCommissions.length - MAX_COMMISSIONS} รายการ · ` : ''}
                รวมฐาน <strong className="tabular-nums text-text">{formatMoney(moneySum(pendingCommissions, (e) => e.commission.commissionableBase))}</strong>
              </span>
              <Button type="button" variant="success" onClick={() => navigate('/commissions?view=pending')} className="mobile:min-h-[44px]">
                เปิดหน้าอนุมัติ
              </Button>
            </div>
          </>
        )}
      </Panel>
    </PageStack>
  );
}
