import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { TicketCreateModal } from '../tickets/TicketCreateModal.jsx';
import { CallModal } from './CallModal.jsx';
import { withFollowUp } from './FollowUpPicks.jsx';
import { actionTicketId } from './dealPickGroups.js';
import { CallPicker } from './CallPicker.jsx';
import { SampleSentModal } from './SampleSentModal.jsx';
import { LogVisitModal, PlanVisitModal, VisitDoneModal } from './VisitDialogs.jsx';

/**
 * The rep's flow host (spec 0003 D10): it owns GET /api/sales/my-work and every dialog and mutation behind the
 * landing's actions (call log, sample sent, visit plan / tick / cancel / reschedule, resume a paused deal, new deal), and
 * hands the page body (SalesOverview) the openers in `children(ctx)`. Action failures use the app's toast.
 */
export function MyWork({ user, showToast = () => {}, children }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  // { deal, replacing }: `replacing` is the visit being rescheduled, cancelled once its replacement is saved.
  const [planning, setPlanning] = useState(null);
  const [ticking, setTicking] = useState(null);
  const [cancelling, setCancelling] = useState(null);
  const [logging, setLogging] = useState(false);
  // โทรแล้ว from the header picks a deal first; from a worklist row the deal is already known.
  const [pickingCall, setPickingCall] = useState(false);
  const [calling, setCalling] = useState(null);
  const [sampling, setSampling] = useState(false);
  const todayIso = bangkokTodayIso();

  const workQuery = useQuery({ queryKey: queryKeys.salesBoardMyWork(), queryFn: () => api.salesBoard.myWork() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const fail = (error) => showToast('error', error?.message || 'บันทึกไม่สำเร็จ');

  const callMutation = useMutation({
    // Slice 6.4: a picked ตามครั้งถัดไป rides on the same request as the deal's nextFollowUpAt; none, no key at all.
    mutationFn: ({ deal, note, nextFollowUpAt }) => api.tickets.addActivity(actionTicketId(deal), withFollowUp({
      kind: 'CALL', activityDate: bangkokTodayIso(), note,
    }, nextFollowUpAt)),
    onSuccess: refresh,
  });
  // Slice 6d: ส่งตัวอย่าง is a SAMPLE_SENT deal activity on the same endpoint as a call; what was sent is its note.
  const sampleMutation = useMutation({
    mutationFn: ({ deal, note, nextFollowUpAt }) => api.tickets.addActivity(actionTicketId(deal), withFollowUp({
      kind: 'SAMPLE_SENT', activityDate: bangkokTodayIso(), note,
    }, nextFollowUpAt)),
    onSuccess: refresh,
  });
  // A returning paused deal (V220) is resumed with the same POST /resume the deal page uses.
  const resumeMutation = useMutation({
    mutationFn: (deal) => api.tickets.resume(actionTicketId(deal), {}),
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
    mutationFn: ({ visit, note, nextFollowUpAt }) => api.salesVisits.done(visit.id, withFollowUp({ note }, nextFollowUpAt)),
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

  async function logCall(deal, note, nextFollowUpAt) {
    try {
      await callMutation.mutateAsync({ deal, note, nextFollowUpAt });
      return true;
    } catch (error) {
      fail(error);
      return false;
    }
  }

  /** The new date first; the old visit is cancelled only once its replacement is saved. */
  async function saveVisit(payload) {
    const replacing = planning.replacing ?? null;
    try {
      await createVisit.mutateAsync(payload);
      if (replacing) await cancelVisit.mutateAsync(replacing);
      setPlanning(null);
      showToast('success', replacing ? 'เลื่อนนัดแล้ว' : 'บันทึกนัดแล้ว');
    } catch (error) {
      fail(error);
    }
  }

  const work = workQuery.data;
  const todaysPlanned = (work?.visitsThisWeek ?? []).filter((v) => v.status === 'PLANNED' && v.plannedDate === todayIso);

  const ctx = {
    work,
    workQuery,
    todayIso,
    openLog: () => setLogging(true),
    openCallPicker: () => setPickingCall(true),
    openCall: setCalling,
    openSample: () => setSampling(true),
    openCreate: () => setCreating(true),
    openPlan: () => setPlanning({ deal: null }),
    tickVisit: setTicking,
    cancelVisit: setCancelling,
    reschedule: (visit) => setPlanning({ deal: null, replacing: visit }),
    resume: (deal) => resumeMutation.mutate(deal),
    resuming: resumeMutation.isPending,
  };

  return (
    <PageStack>
      {children(ctx)}

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
          // A returning paused deal (V220) is not ACTIVE: the server refuses a follow-up date on it.
          followUpAllowed={!calling.returning}
          onSubmit={async (note, nextFollowUpAt) => {
            // A failure is toasted and the dialog stays open with what was typed.
            if (await logCall(calling, note, nextFollowUpAt)) setCalling(null);
          }}
        />
      ) : null}
      {sampling ? (
        <SampleSentModal
          followUp={work?.followUp ?? []}
          board={work?.board ?? []}
          submitting={sampleMutation.isPending}
          onClose={() => setSampling(false)}
          onSubmit={async (deal, note, nextFollowUpAt) => {
            // A failure is toasted and the sheet stays open with what was typed, as the call dialog does.
            try {
              await sampleMutation.mutateAsync({ deal, note, nextFollowUpAt });
              setSampling(false);
              showToast('success', 'บันทึกส่งตัวอย่างแล้ว');
            } catch (error) {
              fail(error);
            }
          }}
        />
      ) : null}
      {planning ? (
        <PlanVisitModal
          deals={work?.board ?? []}
          initialDeal={planning.deal}
          initialVisit={planning.replacing}
          todayIso={todayIso}
          submitting={createVisit.isPending || cancelVisit.isPending}
          onClose={() => setPlanning(null)}
          onSubmit={saveVisit}
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
          deals={work?.board ?? []}
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
