import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { holdsAllSalesDocumentsGrant, isSalesOwner } from '../tickets/dealOwnership.js';

const TAP_TARGET = 'min-h-11 cursor-pointer border-0 bg-transparent px-1 py-1';

/** Display only. Mirrors TicketService#canManageQuotation; the server decides. */
export function canManageQuotationSample(user, deal) {
  if (!user) return false;
  return user.role === 'ceo' || holdsAllSalesDocumentsGrant(user) || (Boolean(deal) && isSalesOwner(deal, user));
}

export function canChangeQuotationSample(user, quotation, summary) {
  if (!quotation || (quotation.origin ?? 'DEAL_DIRECT') !== 'DEAL_DIRECT') return false;
  if (['SUPERSEDED', 'CANCELLED'].includes(quotation.docStatus)) return false;
  return canManageQuotationSample(user, summary);
}

function useSetSample(quotation, showToast) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (isSample) => api.dealQuotations.setSample(quotation.id, isSample),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(quotation.id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      showToast?.('success', res.quotation.isSample ? 'ทำเป็นใบตัวอย่างแล้ว' : 'เอาเครื่องหมายตัวอย่างออกแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'เปลี่ยนเครื่องหมายตัวอย่างไม่สำเร็จ'),
  });
}

/** The job's quotation lists: a small tag, tapping it offers to take the mark off. */
export function QuotationSampleTag({ quotation, user, summary, showToast }) {
  const [confirming, setConfirming] = useState(false);
  const setSample = useSetSample(quotation, showToast);
  if (!quotation.isSample) return null;
  const tag = <StatusBadge tone="warning">ตัวอย่าง</StatusBadge>;
  if (!canChangeQuotationSample(user, quotation, summary)) return tag;
  return (
    <>
      <button type="button" className={TAP_TARGET} data-testid={`quotation-sample-tag-${quotation.id}`}
        aria-label={`ใบตัวอย่าง ${quotation.number ?? ''} แตะเพื่อเอาเครื่องหมายออก`} onClick={() => setConfirming(true)}>
        {tag}
      </button>
      <ConfirmDialog
        open={confirming}
        title="เอาเครื่องหมายตัวอย่างออก?"
        message={`${quotation.number ?? 'ใบเสนอราคานี้'} จะกลับมานับเป็นใบเสนอราคาจริงของงาน`}
        confirmLabel="เอาออก"
        busy={setSample.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => setSample.mutate(false, { onSettled: () => setConfirming(false) })}
      />
    </>
  );
}

/** The job's quotation lists: one tap to mark a live real quotation as a sample. */
export function QuotationSampleMarkButton({ quotation, user, summary, showToast }) {
  const setSample = useSetSample(quotation, showToast);
  if (quotation.isSample || !canChangeQuotationSample(user, quotation, summary)) return null;
  return (
    <button type="button" className={`${TAP_TARGET} text-xs font-bold text-link`} disabled={setSample.isPending}
      data-testid={`quotation-sample-mark-${quotation.id}`} onClick={() => setSample.mutate(true)}>
      ทำเป็นใบตัวอย่าง
    </button>
  );
}

/**
 * The editor's one line ใบตัวอย่าง switch. A saved quotation changes at once through PATCH .../sample, so it
 * never waits for a draft save. A new one holds the choice in `newValue` until its first save.
 */
export function QuotationSampleSwitch({ quotation, newValue, onNewChange, canMarkNew, user, summary, showToast }) {
  const setSample = useSetSample(quotation ?? {}, showToast);
  const saved = Boolean(quotation?.id);
  // The editor only loads the deal for a draft. The same cache entry serves a saved quotation in any other status.
  const dealQuery = useQuery({
    queryKey: [...queryKeys.ticketDetail(quotation?.ticketId), 'summary'],
    queryFn: () => api.tickets.get(quotation.ticketId).then((r) => r.ticket?.summary),
    enabled: saved && !summary,
    retry: false,
  });
  if (saved && (quotation.origin ?? 'DEAL_DIRECT') !== 'DEAL_DIRECT') return null;
  if (!saved && !canMarkNew) return null;
  const checked = saved ? quotation.isSample === true : newValue === true;
  const canChange = !saved || canChangeQuotationSample(user, quotation, summary ?? dealQuery.data);
  const change = (next) => (saved ? setSample.mutate(next) : onNewChange(next));
  return (
    <div className="flex flex-wrap items-center gap-x-3 px-1" data-testid="quotation-sample-switch">
      <label htmlFor="quotationIsSample" className="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-bold">
        <input id="quotationIsSample" type="checkbox" role="switch" className="h-4 w-4 shrink-0" checked={checked}
          disabled={!canChange || setSample.isPending} onChange={(e) => change(e.target.checked)} />
        ใบตัวอย่าง
      </label>
      {saved && quotation.looksLikeSample && !checked && canChange ? (
        <button type="button" className={`${TAP_TARGET} text-xs font-bold text-link`} disabled={setSample.isPending}
          data-testid="quotation-sample-hint" onClick={() => change(true)}>
          นี่เป็นใบตัวอย่างไหม
        </button>
      ) : null}
    </div>
  );
}
