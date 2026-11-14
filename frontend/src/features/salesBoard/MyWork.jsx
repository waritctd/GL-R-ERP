import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { MarkLostModal } from '../tickets/MarkLostModal.jsx';
import { useStageCatalog } from '../tickets/stageCatalog.js';
import { baht, sumValue } from './boardFormat.js';
import { DealBoard } from './DealBoard.jsx';
import { FollowUpList } from './FollowUpList.jsx';

function CountPill({ count }) {
  return (
    <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold tabular-nums text-surface">{count}</span>
  );
}

function Loading({ label }) {
  return (
    <div className="grid gap-2 px-5 py-4 mobile:px-3.5" role="status" aria-busy="true" aria-label={label}>
      <Skeleton height={36} />
      <Skeleton height={36} />
    </div>
  );
}

const Failed = () => <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>;

/**
 * งานของฉัน — the rep's landing (spec 2026-11-15 §7.3, slice 1): ควรตามต่อ on top, then the five-column board.
 * Everything comes from GET /api/sales/my-work, already scoped server-side to the caller's own deals. Visits
 * (slice 2) and expected cash (slice 3) are not built yet, so they are not shown.
 */
export function MyWork({ subtitle }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { catalog } = useStageCatalog();
  const [losing, setLosing] = useState(null);

  const workQuery = useQuery({ queryKey: queryKeys.salesBoardMyWork(), queryFn: () => api.salesBoard.myWork() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });

  const callMutation = useMutation({
    mutationFn: ({ deal, note }) => api.tickets.addActivity(deal.ticketId, {
      kind: 'CALL', activityDate: bangkokTodayIso(), note,
    }),
    onSuccess: refresh,
  });
  const lostMutation = useMutation({
    mutationFn: ({ deal, payload }) => api.tickets.markLost(deal.ticketId, payload),
    onSuccess: refresh,
  });

  const work = workQuery.data;
  const quietDays = work?.quietDays ?? 14;

  return (
    <PageStack>
      <PageHeader
        title="งานของฉัน"
        subtitle={subtitle ?? formatThaiDate(bangkokTodayIso())}
        actions={<Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูรายการดีลทั้งหมด</Button>}
      />

      <Panel
        flush
        title={(
          <span className="grid gap-0.5">
            <span>ควรตามต่อ</span>
            <small className="text-xs font-normal text-text-muted">ดีลที่เงียบเกิน {quietDays} วัน</small>
          </span>
        )}
        actions={work ? <CountPill count={work.followUp.length} /> : null}
      >
        {workQuery.isError ? <Failed /> : !work ? <Loading label="กำลังโหลดดีลที่ควรตามต่อ" /> : (
          <FollowUpList
            deals={work.followUp}
            quietDays={quietDays}
            loggingCall={callMutation.isPending}
            onLogCall={(deal, note) => callMutation.mutateAsync({ deal, note })}
            onCloseDeal={setLosing}
          />
        )}
      </Panel>

      <Panel
        title="ดีลทั้งหมดของฉัน"
        actions={work ? (
          <span className="text-sm text-text-muted">
            {work.board.length} ดีล · มูลค่ารวม <b className="tabular-nums text-text">{baht(sumValue(work.board))}</b>
          </span>
        ) : null}
      >
        {workQuery.isError ? <Failed /> : !work ? <Loading label="กำลังโหลดดีลทั้งหมด" /> : <DealBoard deals={work.board} />}
      </Panel>

      {losing ? (
        <MarkLostModal
          catalog={catalog}
          submitting={lostMutation.isPending}
          onClose={() => setLosing(null)}
          onSubmit={async (payload) => {
            await lostMutation.mutateAsync({ deal: losing, payload });
            setLosing(null);
          }}
        />
      ) : null}
    </PageStack>
  );
}
