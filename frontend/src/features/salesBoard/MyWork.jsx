import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { addDaysIso, bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { MarkLostModal } from '../tickets/MarkLostModal.jsx';
import { TicketCreateModal } from '../tickets/TicketCreateModal.jsx';
import { useStageCatalog } from '../tickets/stageCatalog.js';
import { currentQuarter, defaultBoardFilter, otherCurrencyTotals, sumValue, totalLabel } from './boardFormat.js';
import { CashCard } from './CashCard.jsx';
import { DealBoard } from './DealBoard.jsx';
import { FollowUpList } from './FollowUpList.jsx';
import { CallModal } from './CallModal.jsx';
import { CallPicker, TodayOnPhone } from './TodayOnPhone.jsx';
import { LogVisitModal, PlanVisitModal, VisitDoneModal } from './VisitDialogs.jsx';
import { CarriedOverVisits, VisitsThisWeek } from './VisitsThisWeek.jsx';
import { carryOverRange, NEXT_VISIT_DAYS, weekRangeLabel, weekStartIso } from './visitWeek.js';

// ดูหน้าเต็ม on a phone is remembered for the browser session (fix round 1, ruling 4). Storage can be refused (private
// mode, blocked site data): then it is plain state for this mount.
const PHONE_VIEW_KEY = 'glr.myWork.phoneView';
function readFullOnPhone() {
  try {
    return globalThis.sessionStorage?.getItem(PHONE_VIEW_KEY) === 'full';
  } catch {
    return false;
  }
}
function rememberFullOnPhone(full) {
  try {
    if (full) globalThis.sessionStorage?.setItem(PHONE_VIEW_KEY, 'full');
    else globalThis.sessionStorage?.removeItem(PHONE_VIEW_KEY);
  } catch {
    // Not remembered; the switch itself still works.
  }
}

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
  { key: 'quiet', label: 'งานเงียบ' },
];

const BOARD_ID = 'my-work-board';

/**
 * งานของฉัน — the rep's landing (spec 2026-11-15 §7.3): ควรตามต่อ and นัดเข้าพบสัปดาห์นี้ side by side, then the
 * five-column board, from GET /api/sales/my-work (already scoped server-side to the caller's own deals and visits).
 * `before` is rendered between the header and ควรตามต่อ — SalesOverview puts the money band and the next-action
 * worklist there. เงินที่จะเข้า (slice 3) is card C beside them: the payload's `cash` month. บันทึกเข้าพบ is in the
 * header on desktop. Action failures use the app's toast.
 *
 * At phone width (≤720px, useIsMobile — the app's `mobile` breakpoint) the page opens as วันนี้ของฉัน (TodayOnPhone,
 * design a-mywork's phone block) instead of the stacked page; "ดูหน้าเต็ม" switches to the stacked page, whose sticky
 * bottom บันทึกเข้าพบ then takes over. Desktop and tablet render exactly as before. Every dialog stays owned here.
 */
export function MyWork({ subtitle, before = null, user, showToast = () => {} }) {
  const navigate = useNavigate();
  const isPhone = useIsMobile();
  const [fullOnPhone, setFullOnPhoneState] = useState(readFullOnPhone);
  const setFullOnPhone = (full) => {
    rememberFullOnPhone(full);
    setFullOnPhoneState(full);
  };
  const phoneToday = isPhone && !fullOnPhone;
  const queryClient = useQueryClient();
  const { catalog } = useStageCatalog();
  const [losing, setLosing] = useState(null);
  const [creating, setCreating] = useState(false);
  // null until the rep picks one: the default follows the data (defaultBoardFilter).
  const [boardFilterChoice, setBoardFilter] = useState(null);
  const [planning, setPlanning] = useState(null); // { deal } — deal is null for a visit with no deal yet
  const [ticking, setTicking] = useState(null);
  const [cancelling, setCancelling] = useState(null);
  const [logging, setLogging] = useState(false);
  // The phone's โทรแล้ว: pick a deal, then the call dialog. Owned here like every other dialog, so crossing the
  // breakpoint (TodayOnPhone unmounting) keeps it open with what was typed (ruling 3).
  const [pickingCall, setPickingCall] = useState(false);
  const [calling, setCalling] = useState(null);
  const todayIso = bangkokTodayIso();
  const thisMonday = weekStartIso(todayIso);
  const [weekOffset, setWeekOffset] = useState(0);
  const [landed, setLanded] = useState(null);
  const shownMonday = addDaysIso(thisMonday, 7 * weekOffset);
  const shownSunday = addDaysIso(shownMonday, 6);
  const otherWeekQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(shownMonday, shownSunday),
    queryFn: () => api.salesVisits.list({ from: shownMonday, to: shownSunday }),
    enabled: weekOffset !== 0,
  });
  const { from: carryFrom, to: carryTo } = carryOverRange(thisMonday);
  const carriedQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(carryFrom, carryTo),
    queryFn: () => api.salesVisits.list({ from: carryFrom, to: carryTo }),
  });
  const carriedOver = (carriedQuery.data ?? []).filter((v) => v.status === 'PLANNED');
  // The phone's นัดถัดไป looks ahead across the week boundary: today to today + 7 (ruling 5).
  const aheadTo = addDaysIso(todayIso, NEXT_VISIT_DAYS);
  const aheadQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(todayIso, aheadTo),
    queryFn: () => api.salesVisits.list({ from: todayIso, to: aheadTo }),
    enabled: isPhone,
  });
  const showWeek = (offset) => {
    setWeekOffset(offset);
    setLanded(null);
  };

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
  // A returning paused deal (V220) leaves ควรตามต่อ when resumed — the same POST /resume the deal page uses.
  const resumeMutation = useMutation({
    mutationFn: (deal) => api.tickets.resume(deal.ticketId, {}),
    onSuccess: () => { refresh(); showToast('success', 'ดำเนินการต่อแล้ว'); },
    onError: fail,
  });
  const createMutation = useMutation({
    mutationFn: (payload) => api.tickets.create(payload),
    onSuccess: (response) => {
      setCreating(false);
      showToast('success', 'สร้างงานเรียบร้อย');
      refresh();
      const id = response?.ticket?.summary?.id;
      if (id != null) navigate(`/tickets/${id}`);
    },
  });

  const createVisit = useMutation({ mutationFn: (payload) => api.salesVisits.create(payload), onSuccess: refresh });
  const doneVisit = useMutation({
    mutationFn: ({ visit, note }) => api.salesVisits.done(visit.id, { note }),
    onSuccess: refresh,
  });
  const cancelVisit = useMutation({ mutationFn: (visit) => api.salesVisits.cancel(visit.id), onSuccess: refresh });

  /** Runs a visit mutation; a failure is toasted and the dialog stays open with what was typed. */
  async function attempt(mutation, variables, onDone, success) {
    try {
      const result = await mutation.mutateAsync(variables);
      onDone();
      // VisitDto.dealMovedToName: the deal belongs to someone else, so the tick stayed on the visit (NEW-2).
      // dealMoved says why: it moved after the visit was planned, or the visit's owner never held it (review LOW).
      showToast('success', !result?.dealMovedToName ? success : result.dealMoved
        ? `งานนี้ย้ายไปให้ ${result.dealMovedToName} แล้ว — บันทึกการเข้าพบไว้กับนัดเท่านั้น`
        : `บันทึกการเข้าพบไว้กับนัดเท่านั้น — งานนี้เป็นของ ${result.dealMovedToName}`);
    } catch (error) {
      fail(error);
    }
  }

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
  const thisWeekVisits = work ? (work.visitsThisWeek ?? []) : null;
  const visits = weekOffset === 0 ? thisWeekVisits : (otherWeekQuery.data ?? null);
  const todaysPlanned = (thisWeekVisits ?? []).filter((v) => v.status === 'PLANNED' && v.plannedDate === todayIso);

  /** After a plan is saved: when it landed outside the week on screen, say where and offer the jump. */
  function noteLanding(plannedDate) {
    const monday = weekStartIso(plannedDate);
    if (monday === shownMonday) return;
    const offset = Math.round((Date.parse(`${monday}T00:00:00Z`) - Date.parse(`${thisMonday}T00:00:00Z`)) / (7 * 86_400_000));
    setLanded({ weekOffset: offset, label: weekRangeLabel(monday, todayIso) });
  }
  const openLog = () => setLogging(true);
  const quietDays = work?.quietDays ?? 14;
  const quarter = currentQuarter();
  const boardFilter = boardFilterChoice ?? (work ? defaultBoardFilter(work.board) : 'all');
  // Returning paused deals are not on the board, so they are counted from ควรตามต่อ itself.
  const quietTotal = work
    ? work.board.filter((d) => d.quiet).length + work.followUp.filter((d) => d.returning).length : 0;
  const boardDeals = !work ? [] : boardFilter === 'quarter'
    ? work.board.filter((d) => d.expectedQuarter === quarter)
    // งานเงียบ (where ดูทั้งหมด leads) also lists the returning paused deals: they are not ACTIVE board rows, but every
    // one of them is in ควรตามต่อ (fix round 1, ruling 4), so the full set is complete.
    : boardFilter === 'quiet'
      ? [...work.followUp.filter((d) => d.returning), ...work.board.filter((d) => d.quiet)]
      : work.board;

  function showAllQuiet() {
    setBoardFilter('quiet');
    document.getElementById(BOARD_ID)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }

  return (
    <PageStack>
      {phoneToday ? (
        <TodayOnPhone
          work={work}
          failed={workQuery.isError}
          onRetry={() => workQuery.refetch()}
          todayIso={todayIso}
          user={user}
          ahead={aheadQuery.data ?? []}
          aheadTo={aheadTo}
          carriedOver={carriedOver}
          onLog={openLog}
          onCall={() => setPickingCall(true)}
          onPlan={() => setPlanning({ deal: null })}
          onTick={setTicking}
          onCancelVisit={setCancelling}
          onCreate={() => setCreating(true)}
          onShowFull={() => setFullOnPhone(true)}
        />
      ) : (
        <>
      {isPhone ? (
        // As visible as the ดูหน้าเต็ม tile that led here (ruling 4): full width, 52px.
        <Button type="button" variant="secondary" className="min-h-[52px] mobile:min-h-[52px] w-full" onClick={() => setFullOnPhone(false)}>
          <Icon name="chevronLeft" size={16} />
          กลับไปหน้าวันนี้
        </Button>
      ) : null}
      <PageHeader
        title="งานของฉัน"
        subtitle={subtitle ?? formatThaiDate(bangkokTodayIso())}
        actions={(
          <>
            <Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูงานทั้งหมด</Button>
            <Button type="button" variant="secondary" onClick={() => setCreating(true)}>
              <Icon name="plus" size={16} />
              งานใหม่
            </Button>
            <Button type="button" data-testid="log-visit-header" className="mobile:hidden" onClick={openLog}>
              <Icon name="calendar" size={16} />
              บันทึกเข้าพบ
            </Button>
          </>
        )}
      />

      {before}

      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,300px),1fr))] items-start gap-[18px]">
        <Panel
          flush
          title={(
            <span className="grid gap-0.5">
              <span>ควรตามต่อ</span>
              <small className="text-xs font-normal text-text-muted">งานที่เงียบเกิน {quietDays} วัน</small>
            </span>
          )}
          actions={work ? <CountPill count={work.followUp.length} /> : null}
        >
          {workQuery.isError ? <Failed /> : !work ? <Loading label="กำลังโหลดงานที่ควรตามต่อ" /> : (
            <FollowUpList
              deals={work.followUp}
              quietDays={quietDays}
              quietTotal={quietTotal}
              onShowAll={showAllQuiet}
              loggingCall={callMutation.isPending}
              onLogCall={logCall}
              onBookVisit={(deal) => setPlanning({ deal })}
              onCloseDeal={setLosing}
              onResume={(deal) => resumeMutation.mutate(deal)}
              resuming={resumeMutation.isPending}
            />
          )}
        </Panel>

        <VisitsThisWeek
          visits={visits}
          todayIso={todayIso}
          mondayIso={shownMonday}
          weekOffset={weekOffset}
          onWeek={showWeek}
          landed={landed}
          loading={weekOffset === 0 ? !work : otherWeekQuery.isLoading}
          failed={weekOffset === 0 ? workQuery.isError : otherWeekQuery.isError}
          onPlan={() => setPlanning({ deal: null })}
          onDone={setTicking}
          onCancel={setCancelling}
        />

        {work?.cash ? <CashCard cash={work.cash} /> : null}
      </div>

      <CarriedOverVisits visits={carriedOver} todayIso={todayIso} onDone={setTicking} onCancel={setCancelling} />

      <Panel
        id={BOARD_ID}
        title="งานทั้งหมดของฉัน"
        actions={work ? (
          <span className="flex flex-wrap items-center gap-2 text-sm text-text-muted">
            <span>
              {boardDeals.length} งาน · มูลค่ารวม{' '}
              <b data-testid="board-total" className="tabular-nums text-text">
                {totalLabel(sumValue(boardDeals), otherCurrencyTotals(boardDeals))}
              </b>
            </span>
            <span className="flex flex-wrap gap-1" role="group" aria-label="กรองงาน">
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
        {workQuery.isError ? <Failed /> : !work ? <Loading label="กำลังโหลดงานทั้งหมด" /> : <DealBoard deals={boardDeals} />}
      </Panel>

      {/* บันทึกเข้าพบ stays reachable on phone (spec §7.3): a sticky bottom button, with a spacer so it never covers
          the last row. Toasts sit above it (Toast.jsx's mobile bottom offset is 72px). */}
      <div aria-hidden="true" className="hidden h-14 mobile:block" />
      <div className="hidden mobile:fixed mobile:inset-x-4 mobile:bottom-[max(12px,env(safe-area-inset-bottom))] mobile:z-20 mobile:grid">
        <Button type="button" data-testid="log-visit-sticky" className="min-h-[48px] w-full shadow-lg" onClick={openLog}>
          <Icon name="calendar" size={18} />
          บันทึกเข้าพบ
        </Button>
      </div>
        </>
      )}

      {pickingCall ? (
        <CallPicker
          followUp={work?.followUp ?? []}
          board={work?.board ?? []}
          onClose={() => setPickingCall(false)}
          onPick={(deal) => {
            setPickingCall(false);
            setCalling(deal);
          }}
        />
      ) : null}
      {calling ? (
        <CallModal
          deal={calling}
          submitting={callMutation.isPending}
          onClose={() => setCalling(null)}
          onSubmit={async (note) => {
            // A failure is toasted and the dialog stays open with what was typed.
            if (await logCall(calling, note)) setCalling(null);
          }}
        />
      ) : null}
      {planning ? (
        <PlanVisitModal
          deals={work?.board ?? []}
          initialDeal={planning.deal}
          todayIso={todayIso}
          submitting={createVisit.isPending}
          onClose={() => setPlanning(null)}
          onSubmit={(payload) => attempt(createVisit, payload, () => {
            setPlanning(null);
            noteLanding(payload.plannedDate);
          }, 'บันทึกนัดแล้ว')}
        />
      ) : null}
      {ticking ? (
        <VisitDoneModal
          visit={ticking}
          submitting={doneVisit.isPending}
          onClose={() => setTicking(null)}
          onSubmit={(note) => attempt(doneVisit, { visit: ticking, note }, () => setTicking(null), 'บันทึกเข้าพบแล้ว')}
        />
      ) : null}
      {logging ? (
        <LogVisitModal
          todaysPlanned={todaysPlanned}
          deals={work?.board ?? []}
          submitting={doneVisit.isPending || createVisit.isPending}
          onClose={() => setLogging(false)}
          onSubmit={({ visit, contactName, ticketId, note }) => (visit
            ? attempt(doneVisit, { visit, note }, () => setLogging(false), 'บันทึกเข้าพบแล้ว')
            : attempt(createVisit, { contactName, ticketId, plannedDate: todayIso, note, done: true },
              () => setLogging(false), 'บันทึกเข้าพบแล้ว'))}
        />
      ) : null}
      <ConfirmDialog
        open={Boolean(cancelling)}
        title="ยกเลิกนัดนี้?"
        message={cancelling ? `${cancelling.contactName} · ยกเลิกแล้วจะเปลี่ยนกลับไม่ได้` : ''}
        confirmLabel="ยกเลิกนัด"
        cancelLabel="ไม่ยกเลิก"
        tone="danger"
        busy={cancelVisit.isPending}
        onConfirm={() => attempt(cancelVisit, cancelling, () => setCancelling(null), 'ยกเลิกนัดแล้ว')}
        onCancel={() => setCancelling(null)}
      />

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
