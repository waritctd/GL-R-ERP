import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { CallModal } from '../salesBoard/CallModal.jsx';
import { withFollowUp } from '../salesBoard/FollowUpPicks.jsx';
import { PlanVisitModal } from '../salesBoard/VisitDialogs.jsx';

/**
 * The deal header's quick actions (drawing a-deal): โทรแล้ว and นัดเข้าพบ, opening the SAME dialogs งานของฉัน uses
 * (salesBoard/CallModal, salesBoard/VisitDialogs' PlanVisitModal). They replace the deal page's old always-open
 * "บันทึกกิจกรรม" form as the everyday way to log a touch; the full form is still under "+ บันทึก" on the timeline.
 *
 * Gates (the page passes them; both mirror the server):
 *   - canLogCall — TicketService#addActivity's requireDealOwnership: the deal's owner/keyer with the sales role, or
 *     sales_manager / ceo. The page's existing canAddActivity.
 *   - canPlanVisit — VisitService#create on a deal: the same requireDealWriteAccess, AND the deal ACTIVE (it refuses
 *     "ดีลนี้ไม่ได้เปิดอยู่" otherwise).
 */
export function DealQuickActions({ deal, canLogCall, canPlanVisit, showToast }) {
  const queryClient = useQueryClient();
  const [calling, setCalling] = useState(false);
  const [planning, setPlanning] = useState(false);
  const ticketId = deal.ticketId;

  function refreshDeal() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActivities(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    // The board, the follow-up list and the visit lists all live under ['tickets', …].
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'salesBoard'] });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'salesVisits'] });
  }

  const callMutation = useMutation({
    // Slice 6.4: ตามครั้งถัดไป's date rides on the activity (it writes only the deal's next follow-up date); none, the
    // request is exactly the old one. It lands in ข้อมูลงาน through the ticketDetail refresh below.
    mutationFn: ({ note, nextFollowUpAt }) => api.tickets.addActivity(ticketId,
      withFollowUp({ kind: 'CALL', activityDate: bangkokTodayIso(), note }, nextFollowUpAt)),
    onSuccess: refreshDeal,
  });
  const visitMutation = useMutation({
    mutationFn: (payload) => api.salesVisits.create(payload),
    onSuccess: refreshDeal,
  });

  if (!canLogCall && !canPlanVisit) return null;

  return (
    <>
      {canLogCall ? (
        <Button type="button" variant="secondary" size="sm" className="mobile:min-h-[44px]" onClick={() => setCalling(true)}>
          <Icon name="phone" size={14} />
          โทรแล้ว
        </Button>
      ) : null}
      {canPlanVisit ? (
        <Button type="button" variant="secondary" size="sm" className="mobile:min-h-[44px]" onClick={() => setPlanning(true)}>
          <Icon name="calendar" size={14} />
          นัดเข้าพบ
        </Button>
      ) : null}

      {calling ? (
        <CallModal
          deal={deal}
          submitting={callMutation.isPending}
          onClose={() => setCalling(false)}
          onSubmit={async (note, nextFollowUpAt) => {
            // A failure is toasted and the dialog stays open with what was typed, as on งานของฉัน.
            try {
              await callMutation.mutateAsync({ note, nextFollowUpAt });
              setCalling(false);
              showToast('success', 'บันทึกการโทรแล้ว');
            } catch (error) {
              showToast('error', error?.message || 'บันทึกไม่สำเร็จ');
            }
          }}
        />
      ) : null}
      {planning ? (
        <PlanVisitModal
          deals={[]}
          initialDeal={deal}
          todayIso={bangkokTodayIso()}
          submitting={visitMutation.isPending}
          onClose={() => setPlanning(false)}
          onSubmit={async (payload) => {
            try {
              await visitMutation.mutateAsync(payload);
              setPlanning(false);
              showToast('success', 'บันทึกนัดแล้ว');
            } catch (error) {
              showToast('error', error?.message || 'บันทึกไม่สำเร็จ');
            }
          }}
        />
      ) : null}
    </>
  );
}
