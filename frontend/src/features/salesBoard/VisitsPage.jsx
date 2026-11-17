import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { withFollowUp } from './FollowUpPicks.jsx';
import { LogVisitModal, PlanVisitModal, VisitDoneModal } from './VisitDialogs.jsx';
import { CarriedOverVisits, VisitRow, WeekNav } from './VisitsThisWeek.jsx';
import { VisitWeekGrid } from './VisitWeekGrid.jsx';
import { carryOverRange, visitCounts, weekRangeLabel, weekStartIso } from './visitWeek.js';

const PAD = 'px-5 py-4 mobile:px-3.5';
const Failed = () => <p className={`m-0 text-sm font-bold text-danger ${PAD}`}>โหลดไม่สำเร็จ</p>;

function Loading({ label }) {
  return (
    <div className="grid gap-2 rounded-md border border-border bg-surface p-5 mobile:p-3.5" role="status" aria-busy="true" aria-label={label}>
      <Skeleton height={36} />
      <Skeleton height={36} />
    </div>
  );
}

/** One column of the week: planned, done or cancelled. `empty` = the line shown when the column has none. */
function VisitColumn({ testId, title, visits, empty, todayIso, onDone, onCancel }) {
  return (
    <Panel flush data-testid={testId} title={`${title} (${visits.length})`}>
      {visits.length === 0 ? <p className={`m-0 text-sm text-text-muted ${PAD}`}>{empty}</p> : (
        <ul className="m-0 list-none p-0">
          {visits.map((visit) => (
            <VisitRow key={visit.id} visit={visit} todayIso={todayIso} onDone={onDone} onCancel={onCancel} />
          ))}
        </ul>
      )}
    </Panel>
  );
}

/**
 * A rep's own week (spec 2026-11-15 §7.6; design a-mywork card B as a page): GET /api/sales/visits for the ISO week
 * on screen — VisitService scopes it to the caller — split into นัดไว้ and ไปแล้ว. Plan, log, tick and cancel are the
 * existing dialogs and endpoints; a tick needs the visit's day to have come (VisitRow / VisitService).
 */
function RepWeek({ showToast }) {
  const queryClient = useQueryClient();
  const todayIso = bangkokTodayIso();
  const thisMonday = weekStartIso(todayIso);
  const [weekOffset, setWeekOffset] = useState(0);
  const [landed, setLanded] = useState(null);
  const [planning, setPlanning] = useState(false);
  const [logging, setLogging] = useState(false);
  const [ticking, setTicking] = useState(null);
  const [cancelling, setCancelling] = useState(null);

  const monday = addDaysIso(thisMonday, 7 * weekOffset);
  const sunday = addDaysIso(monday, 6);
  const weekQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(monday, sunday),
    queryFn: () => api.salesVisits.list({ from: monday, to: sunday }),
  });
  // บันทึกเข้าพบ offers today's planned visits whichever week is on screen (the same query when it is this week).
  const thisSunday = addDaysIso(thisMonday, 6);
  const thisWeekQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(thisMonday, thisSunday),
    queryFn: () => api.salesVisits.list({ from: thisMonday, to: thisSunday }),
  });
  // ค้างจากสัปดาห์ก่อน, on the current week only (fix round 1, ruling 1): the same request and key as MyWork's.
  const carry = carryOverRange(thisMonday);
  const carriedQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(carry.from, carry.to),
    queryFn: () => api.salesVisits.list(carry),
    enabled: weekOffset === 0,
  });
  const carriedOver = (carriedQuery.data ?? []).filter((v) => v.status === 'PLANNED');
  // The rep's open deals, for the dialogs' optional deal picker (already scoped to the caller server-side).
  const workQuery = useQuery({ queryKey: queryKeys.salesBoardMyWork(), queryFn: () => api.salesBoard.myWork() });
  const deals = workQuery.data?.board ?? [];
  const todaysPlanned = (thisWeekQuery.data ?? []).filter((v) => v.status === 'PLANNED' && v.plannedDate === todayIso);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const createVisit = useMutation({ mutationFn: (payload) => api.salesVisits.create(payload), onSuccess: refresh });
  const doneVisit = useMutation({
    // Slice 6.4: ตามต่อ's date only when picked — otherwise exactly the old request.
    mutationFn: ({ visit, note, nextFollowUpAt }) => api.salesVisits.done(visit.id, withFollowUp({ note }, nextFollowUpAt)),
    onSuccess: refresh,
  });
  const cancelVisit = useMutation({ mutationFn: (visit) => api.salesVisits.cancel(visit.id), onSuccess: refresh });

  /** Runs a visit mutation; a failure is toasted and the dialog stays open with what was typed. */
  async function attempt(mutation, variables, onDone, success) {
    try {
      const result = await mutation.mutateAsync(variables);
      onDone();
      // VisitDto.dealMovedToName: the deal is someone else's, so the tick stayed on the visit (review NEW-2).
      showToast('success', !result?.dealMovedToName ? success : result.dealMoved
        ? `งานนี้ย้ายไปให้ ${result.dealMovedToName} แล้ว — บันทึกการเข้าพบไว้กับนัดเท่านั้น`
        : `บันทึกการเข้าพบไว้กับนัดเท่านั้น — งานนี้เป็นของ ${result.dealMovedToName}`);
    } catch (error) {
      showToast('error', error?.message || 'บันทึกไม่สำเร็จ');
    }
  }

  const showWeek = (offset) => {
    setWeekOffset(offset);
    setLanded(null);
  };
  /** After a plan is saved into a week that is not on screen: say where, and offer the jump. */
  function noteLanding(plannedDate) {
    const target = weekStartIso(plannedDate);
    if (target === monday) return;
    const offset = Math.round((Date.parse(`${target}T00:00:00Z`) - Date.parse(`${thisMonday}T00:00:00Z`)) / (7 * 86_400_000));
    setLanded({ weekOffset: offset, label: weekRangeLabel(target, todayIso) });
  }

  const visits = weekQuery.data ?? null;
  const { done, planned } = visitCounts(visits);
  const label = weekOffset === 0 ? `สัปดาห์นี้ · ${weekRangeLabel(monday)}` : weekRangeLabel(monday, todayIso);
  const plannedRows = (visits ?? []).filter((v) => v.status === 'PLANNED');
  const doneRows = (visits ?? []).filter((v) => v.status === 'DONE');
  const cancelledRows = (visits ?? []).filter((v) => v.status === 'CANCELLED');

  return (
    <>
      <PageHeader
        title="นัดเข้าพบ"
        subtitle="วางแผนเข้าพบลูกค้า แล้วติ๊กเมื่อไปจริง"
        actions={(
          <>
            <Button type="button" variant="secondary" onClick={() => setPlanning(true)}>
              <Icon name="plus" size={16} />
              นัดเข้าพบ
            </Button>
            <Button type="button" onClick={() => setLogging(true)}>
              <Icon name="calendar" size={16} />
              บันทึกเข้าพบ
            </Button>
          </>
        )}
      />

      <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-surface px-5 py-3 mobile:px-3.5">
        <span className="grid gap-0.5">
          <b className="text-base text-text">{label}</b>
          {visits ? <small className="text-xs text-text-muted">{`${done} จาก ${planned} ไปแล้ว`}</small> : null}
        </span>
        <WeekNav weekOffset={weekOffset} onWeek={showWeek} />
      </div>

      {landed ? (
        <p className="m-0">
          <button
            type="button"
            className="min-h-11 text-left text-sm font-bold text-primary underline-offset-2 hover:underline"
            onClick={() => showWeek(landed.weekOffset)}
          >
            {`นัดใหม่อยู่ในสัปดาห์ ${landed.label} — ไปที่สัปดาห์นั้น`}
          </button>
        </p>
      ) : null}

      {weekOffset === 0 ? (
        <CarriedOverVisits visits={carriedOver} todayIso={todayIso} onDone={setTicking} onCancel={setCancelling} />
      ) : null}

      {weekQuery.isError ? <Panel flush><Failed /></Panel> : !visits ? <Loading label="กำลังโหลดนัดเข้าพบ" /> : visits.length === 0 ? (
        <Panel flush>
          <div className={`grid gap-1 ${PAD}`}>
            <p className="m-0 text-sm font-bold text-text">
              {weekOffset === 0 ? 'ยังไม่มีนัดเข้าพบสัปดาห์นี้' : 'ไม่มีนัดเข้าพบในสัปดาห์นี้'}
            </p>
            <p className="m-0 text-sm text-text-muted">กด “นัดเข้าพบ” เพื่อวางแผน หรือ “บันทึกเข้าพบ” หลังไปพบลูกค้าวันนี้</p>
          </div>
        </Panel>
      ) : (
        <>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] items-start gap-[18px]">
            <VisitColumn
              testId="visits-planned"
              title="นัดไว้"
              visits={plannedRows}
              empty="ไม่มีนัดที่รอไปในสัปดาห์นี้"
              todayIso={todayIso}
              onDone={setTicking}
              onCancel={setCancelling}
            />
            <VisitColumn
              testId="visits-done"
              title="ไปแล้ว"
              visits={doneRows}
              empty="ยังไม่มีนัดที่ติ๊กว่าไปแล้ว"
              todayIso={todayIso}
              onDone={setTicking}
              onCancel={setCancelling}
            />
          </div>
          {cancelledRows.length ? (
            <VisitColumn
              testId="visits-cancelled"
              title="ยกเลิก"
              visits={cancelledRows}
              todayIso={todayIso}
              onDone={setTicking}
              onCancel={setCancelling}
            />
          ) : null}
        </>
      )}

      {planning ? (
        <PlanVisitModal
          deals={deals}
          todayIso={todayIso}
          submitting={createVisit.isPending}
          onClose={() => setPlanning(false)}
          onSubmit={(payload) => attempt(createVisit, payload, () => {
            setPlanning(false);
            noteLanding(payload.plannedDate);
          }, 'บันทึกนัดแล้ว')}
        />
      ) : null}
      {ticking ? (
        <VisitDoneModal
          visit={ticking}
          submitting={doneVisit.isPending}
          onClose={() => setTicking(null)}
          onSubmit={(note, nextFollowUpAt) => attempt(doneVisit, { visit: ticking, note, nextFollowUpAt },
            () => setTicking(null), 'บันทึกเข้าพบแล้ว')}
        />
      ) : null}
      {logging ? (
        <LogVisitModal
          todaysPlanned={todaysPlanned}
          deals={deals}
          submitting={doneVisit.isPending || createVisit.isPending}
          onClose={() => setLogging(false)}
          onSubmit={({ visit, contactName, ticketId, note, nextFollowUpAt }) => (visit
            ? attempt(doneVisit, { visit, note, nextFollowUpAt }, () => setLogging(false), 'บันทึกเข้าพบแล้ว')
            : attempt(createVisit, withFollowUp({ contactName, ticketId, plannedDate: todayIso, note, done: true },
              nextFollowUpAt), () => setLogging(false), 'บันทึกเข้าพบแล้ว'))}
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
    </>
  );
}

/**
 * The team's week (design a-team card D): VisitWeekGrid as on ภาพรวมทีมขาย — its own ‹ สัปดาห์นี้ › bar pages other
 * weeks through GET /api/sales/visits/team. The rows (every rep) and this week come from GET /api/sales/team-overview,
 * the same cached query as the team dashboard. Both endpoints are sales_manager / ceo only.
 */
function TeamWeek() {
  const query = useQuery({ queryKey: queryKeys.salesBoardTeam(), queryFn: () => api.salesBoard.teamOverview() });
  const team = query.data;
  return (
    <>
      <PageHeader title="นัดเข้าพบ" subtitle="นัดเข้าพบของทุกคนในทีม รายสัปดาห์" />
      {query.isError ? <Panel flush><Failed /></Panel> : !team ? <Loading label="กำลังโหลดตารางเข้าพบ" /> : (
        <VisitWeekGrid reps={team.reps} visits={team.teamVisitsThisWeek ?? []} todayIso={bangkokTodayIso()} />
      )}
    </>
  );
}

/**
 * นัดเข้าพบ (`/visits`). Who reaches it is the PATH_GUARDS '/visits' entry (canViewVisits = VisitService.VISIT_ROLES);
 * which view is canViewTeamVisits (VisitService.TEAM_ROLES): sales_manager and ceo read the team grid, a rep works
 * their own week. The server re-checks the role on every call.
 */
export function VisitsPage({ user, showToast = () => {} }) {
  return (
    <PageStack>
      {hasPermission(user?.role, 'canViewTeamVisits') ? <TeamWeek /> : <RepWeek showToast={showToast} />}
    </PageStack>
  );
}
