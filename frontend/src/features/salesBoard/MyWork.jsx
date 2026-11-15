import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { MarkLostModal } from '../tickets/MarkLostModal.jsx';
import { TicketCreateModal } from '../tickets/TicketCreateModal.jsx';
import { useStageCatalog } from '../tickets/stageCatalog.js';
import { currentQuarter, otherCurrencyTotals, sumValue, totalLabel } from './boardFormat.js';
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

const BOARD_FILTERS = [
  { key: 'quarter', label: 'ไตรมาสนี้' },
  { key: 'all', label: 'ทั้งหมด' },
];

/**
 * งานของฉัน — the rep's landing (spec 2026-11-15 §7.3, slice 1): ควรตามต่อ, then the five-column board, from
 * GET /api/sales/my-work (already scoped server-side to the caller's own deals). `before` is rendered between the
 * header and ควรตามต่อ — SalesOverview puts the money band and the next-action worklist there. Visits (slice 2) and
 * expected cash (slice 3) are not built yet, so they are not shown. Action failures use the app's toast.
 */
export function MyWork({ subtitle, before = null, user, showToast = () => {} }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { catalog } = useStageCatalog();
  const [losing, setLosing] = useState(null);
  const [creating, setCreating] = useState(false);
  const [boardFilter, setBoardFilter] = useState('all');

  const workQuery = useQuery({ queryKey: queryKeys.salesBoardMyWork(), queryFn: () => api.salesBoard.myWork() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const fail = (error) => showToast('error', error?.message || 'บันทึกไม่สำเร็จ');

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
  const createMutation = useMutation({
    mutationFn: (payload) => api.tickets.create(payload),
    onSuccess: (response) => {
      setCreating(false);
      showToast('success', 'สร้างดีลเรียบร้อย');
      refresh();
      const id = response?.ticket?.summary?.id;
      if (id != null) navigate(`/tickets/${id}`);
    },
  });

  async function logCall(deal, note) {
    try {
      await callMutation.mutateAsync({ deal, note });
      return true;
    } catch (error) {
      fail(error);
      return false;
    }
  }

  const work = workQuery.data;
  const quietDays = work?.quietDays ?? 14;
  const quarter = currentQuarter();
  const boardDeals = !work ? [] : boardFilter === 'quarter'
    ? work.board.filter((d) => d.expectedQuarter === quarter)
    : work.board;

  return (
    <PageStack>
      <PageHeader
        title="งานของฉัน"
        subtitle={subtitle ?? formatThaiDate(bangkokTodayIso())}
        actions={(
          <>
            <Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูรายการดีลทั้งหมด</Button>
            <Button type="button" onClick={() => setCreating(true)}>
              <Icon name="plus" size={16} />
              งานใหม่
            </Button>
          </>
        )}
      />

      {before}

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
            onLogCall={logCall}
            onCloseDeal={setLosing}
          />
        )}
      </Panel>

      <Panel
        title="ดีลทั้งหมดของฉัน"
        actions={work ? (
          <span className="flex flex-wrap items-center gap-2 text-sm text-text-muted">
            <span>
              {boardDeals.length} ดีล · มูลค่ารวม{' '}
              <b data-testid="board-total" className="tabular-nums text-text">
                {totalLabel(sumValue(boardDeals), otherCurrencyTotals(boardDeals))}
              </b>
            </span>
            <span className="flex gap-1" role="group" aria-label="กรองตามไตรมาส">
              {BOARD_FILTERS.map((f) => (
                <Button
                  key={f.key}
                  type="button"
                  size="sm"
                  variant={boardFilter === f.key ? 'primary' : 'secondary'}
                  aria-pressed={boardFilter === f.key}
                  onClick={() => setBoardFilter(f.key)}
                >
                  {f.label}
                </Button>
              ))}
            </span>
          </span>
        ) : null}
      >
        {workQuery.isError ? <Failed /> : !work ? <Loading label="กำลังโหลดดีลทั้งหมด" /> : <DealBoard deals={boardDeals} />}
      </Panel>

      {losing ? (
        <MarkLostModal
          catalog={catalog}
          submitting={lostMutation.isPending}
          onClose={() => setLosing(null)}
          onSubmit={async (payload) => {
            try {
              await lostMutation.mutateAsync({ deal: losing, payload });
              setLosing(null);
            } catch (error) {
              fail(error);
            }
          }}
        />
      ) : null}
      {creating ? (
        <TicketCreateModal
          user={user}
          onClose={() => setCreating(false)}
          onSubmit={async (payload) => {
            try {
              await createMutation.mutateAsync(payload);
            } catch (error) {
              fail(error);
              throw error;
            }
          }}
        />
      ) : null}
    </PageStack>
  );
}
