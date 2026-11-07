/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app
 * Tokens only. "บันทึกผลประมูล" (docs/decisions/deal-party-model.md, prototype screen 3): the rep
 * records the bid outcome ONCE, ticking one or more winners. Each extra winner forks a child deal.
 */
import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { dealQuotationStatusLabel } from '../quotations/quotationMeta.js';
import {
  BID_OUTCOME_REASONS, NOT_AWARDED_REASON_LABEL, childDealCode, isBiddingParty,
} from './dealPartyMeta.js';

const PILL = 'inline-flex min-h-[38px] mobile:min-h-[44px] cursor-pointer items-center gap-2 rounded-md border px-3 text-sm font-bold';
const PILL_ON = 'border-primary bg-primary/10 text-primary';
const PILL_OFF = 'border-border bg-surface text-text-secondary';

function Fieldset({ legend, children, className }) {
  return (
    <fieldset className={cn('m-0 grid gap-2.5 rounded-md border border-border p-3.5', className)}>
      <legend className="px-1 text-sm font-extrabold text-text">{legend}</legend>
      {children}
    </fieldset>
  );
}

/**
 * `parties`: the deal's parties (GET /tickets/{id}/parties). `quotations`: the deal's quotation rows,
 * only to show each bid's date. `summary`: the deal summary (code). `projectName`: the dialog's
 * subtitle. A 409 from the server (e.g. a bid whose quotation was un-approved meanwhile) is shown in
 * the dialog, in the server's own words.
 */
export function BidOutcomeModal({
  ticketId, summary, projectName, parties = [], quotations = [], onClose, showToast,
}) {
  const queryClient = useQueryClient();
  const bidders = useMemo(() => parties.filter(isBiddingParty), [parties]);
  // A deal already awarded takes a LATER outcome: every newly ticked winner forks its own deal and
  // there is no second primary.
  const alreadyAwarded = parties.some((p) => p.status === 'AWARDED');
  const existingChildren = parties.filter((p) => p.status === 'AWARDED_SPLIT').length;
  const [checked, setChecked] = useState([]);
  const [primaryChoice, setPrimaryChoice] = useState(null);
  const [othersOutcome, setOthersOutcome] = useState('NOT_AWARDED');
  const [reason, setReason] = useState('OTHER_BIDDER');
  const [error, setError] = useState(null);

  const won = bidders.filter((p) => checked.includes(p.id));
  const primaryId = alreadyAwarded ? null
    : (won.some((p) => p.id === primaryChoice) ? primaryChoice : won[0]?.id ?? null);
  const unselected = bidders.length - won.length;
  const code = summary?.code ?? `#${ticketId}`;

  // Which deal each winner lands on: the primary keeps the deal's code, the others `-A`, `-B`, …
  const destinations = useMemo(() => {
    let k = existingChildren;
    return won.map((p) => ({ party: p, code: p.id === primaryId ? code : childDealCode(code, k++) }));
  }, [won, primaryId, code, existingChildren]);

  function toggle(party) {
    setError(null);
    setChecked((prev) => (prev.includes(party.id) ? prev.filter((id) => id !== party.id) : [...prev, party.id]));
  }

  const mutation = useMutation({
    mutationFn: async () => {
      const awardedPartyIds = bidders.filter((p) => checked.includes(p.id)).map((p) => p.id);
      const res = await api.tickets.recordBidOutcome(ticketId, {
        awardedPartyIds,
        ...(primaryId != null ? { primaryPartyId: primaryId } : {}),
        othersOutcome,
        ...(othersOutcome === 'NOT_AWARDED' ? { notAwardedReason: reason } : {}),
      });
      const childIds = res?.result?.childTicketIds ?? [];
      if (childIds.length === 0) return { childCodes: [] };
      // The server names the child deals; read them back rather than predict them.
      try {
        const after = await api.tickets.parties(ticketId);
        const childCodes = (after?.parties ?? [])
          .filter((p) => awardedPartyIds.includes(p.id) && p.splitTicketCode)
          .map((p) => p.splitTicketCode);
        return { childCodes: childCodes.length ? childCodes : childIds.map((id) => `#${id}`) };
      } catch {
        return { childCodes: childIds.map((id) => `#${id}`) };
      }
    },
    onSuccess: ({ childCodes }) => {
      // Ticket detail, parties, actions and lists all live under ['tickets'].
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      queryClient.invalidateQueries({ queryKey: ['finance'] });
      showToast?.('success', childCodes.length
        ? `บันทึกผลประมูลแล้ว · แยกดีล ${childCodes.join(', ')}`
        : 'บันทึกผลประมูลแล้ว');
      onClose();
    },
    onError: (err) => setError(err.message || 'บันทึกผลประมูลไม่สำเร็จ'),
  });

  const quotationDate = (party) => quotations.find((q) => q.id === party.latestQuotationId)?.quotationDate ?? null;

  return (
    <Modal
      title="บันทึกผลประมูล"
      subtitle={`${code} · ${projectName ?? summary?.title ?? ''} · เลือกผู้รับเหมาที่ได้งาน (เลือกได้มากกว่า 1 ราย)`}
      onClose={onClose}
      testId="bid-outcome-modal"
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={mutation.isPending}>ยกเลิก</Button>
          <Button
            variant="primary"
            data-testid="bid-outcome-submit"
            disabled={won.length === 0}
            loading={mutation.isPending}
            onClick={() => { setError(null); mutation.mutate(); }}
          >
            บันทึกผล
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <Fieldset legend="ผู้รับเหมาที่ได้งาน">
          {bidders.length === 0 ? <p className="m-0 text-sm text-text-muted">ยังไม่มีผู้รับเหมาที่เสนอราคา</p> : null}
          {bidders.map((party) => {
            const approved = party.latestQuotationStatus === 'APPROVED';
            const isChecked = checked.includes(party.id);
            const status = party.latestQuotationStatus ? dealQuotationStatusLabel(party.latestQuotationStatus).label : null;
            const date = quotationDate(party);
            return (
              <label
                key={party.id}
                data-testid={`bid-winner-${party.id}`}
                className={cn(
                  'flex min-h-11 items-start gap-3 rounded-md border px-3 py-2.5',
                  approved ? 'cursor-pointer' : 'cursor-not-allowed opacity-60',
                  isChecked ? 'border-success bg-success-bg shadow-[inset_3px_0_0_var(--color-success)]' : 'border-border bg-surface',
                )}
              >
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4 shrink-0 accent-success"
                  checked={isChecked}
                  disabled={!approved || mutation.isPending}
                  onChange={() => toggle(party)}
                />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <strong className="text-sm text-text">{party.customerName ?? '-'}</strong>
                  <span className="text-xs text-text-muted">
                    <code>{party.latestQuotationNumber ?? '-'}</code>
                    {status ? ` · ${status}` : ''}
                    {date ? ` · ${formatThaiDate(date)}` : ''}
                  </span>
                  {!approved ? <span className="text-xs font-bold text-warning-dark">ใบเสนอราคายังไม่อนุมัติ</span> : null}
                </span>
                <span className="shrink-0 tabular-nums text-sm font-bold text-text">
                  {party.latestQuotationAmount != null ? formatMoney(party.latestQuotationAmount) : '—'}
                </span>
              </label>
            );
          })}
        </Fieldset>

        {won.length >= 2 && !alreadyAwarded ? (
          <Fieldset legend="ได้งานมากกว่า 1 ราย · แยกดีล">
            <p className="m-0 text-xs text-text-muted">
              ใบแจ้งหนี้ มัดจำ และคอมมิชชั่นคิดต่อลูกค้า ระบบจึงแยกเป็นหนึ่งดีลต่อผู้รับเหมาที่ได้งาน ทุกดีลยังอยู่ใต้โครงการเดียวกันและนับผลชนะครั้งเดียว
            </p>
            <div role="radiogroup" aria-label="ดีลหลัก" className="grid gap-2">
              {destinations.map(({ party, code: dest }) => {
                const isPrimary = party.id === primaryId;
                return (
                  <label key={party.id} className={cn('flex min-h-11 cursor-pointer items-center gap-3 rounded-md border px-3 py-2', isPrimary ? 'border-primary bg-primary/5' : 'border-border')}>
                    <input type="radio" name="bid-primary" className="h-4 w-4 shrink-0" checked={isPrimary} onChange={() => setPrimaryChoice(party.id)} />
                    <span className="flex min-w-0 flex-1 flex-col">
                      <strong className="text-sm text-text">{party.customerName}</strong>
                      <span className="text-xs text-text-muted">
                        {isPrimary ? 'ดีลหลัก · เก็บเลขเดิม ประวัติ และเอกสารทั้งหมด' : 'ดีลลูก · ใบเสนอราคาของรายนี้ย้ายตาม'}
                      </span>
                    </span>
                    <code className="shrink-0 text-xs text-text-muted">{dest}</code>
                  </label>
                );
              })}
            </div>
          </Fieldset>
        ) : null}

        {unselected > 0 ? (
          <Fieldset legend={<>ผู้รับเหมาที่ไม่ได้เลือก <span className="text-xs font-normal text-text-muted">{`(${unselected} ราย)`}</span></>}>
            <div role="radiogroup" aria-label="ผลของรายที่ไม่ได้เลือก" className="flex flex-wrap gap-2">
              {[['NOT_AWARDED', 'ไม่ได้งาน'], ['KEEP_WAITING', 'ยังรอผล']].map(([value, label]) => (
                <label key={value} className={cn(PILL, othersOutcome === value ? PILL_ON : PILL_OFF)}>
                  <input type="radio" name="bid-others" className="h-4 w-4" checked={othersOutcome === value} onChange={() => setOthersOutcome(value)} />
                  {label}
                </label>
              ))}
            </div>
            {othersOutcome === 'NOT_AWARDED' ? (
              <div role="radiogroup" aria-label="เหตุผลที่ไม่ได้งาน" className="flex flex-wrap gap-2">
                {BID_OUTCOME_REASONS.map((code_) => (
                  <label key={code_} className={cn(PILL, 'text-xs', reason === code_ ? PILL_ON : PILL_OFF)}>
                    <input type="radio" name="bid-reason" className="h-4 w-4" checked={reason === code_} onChange={() => setReason(code_)} />
                    {NOT_AWARDED_REASON_LABEL[code_]}
                  </label>
                ))}
              </div>
            ) : null}
          </Fieldset>
        ) : null}

        <div
          role="status"
          data-testid="bid-outcome-note"
          className={cn(
            'flex items-start gap-2 rounded-md border px-3 py-2.5 text-sm',
            won.length === 0 ? 'border-warning-border bg-warning-bg text-warning-dark' : 'border-success bg-success-bg text-success-dark',
          )}
        >
          <Icon name={won.length === 0 ? 'triangleAlert' : 'check'} size={16} className="mt-0.5 shrink-0" />
          {won.length === 0 ? (
            <span>ยังไม่ได้เลือกผู้รับเหมาที่ได้งาน · ถ้าแพ้ทั้งโครงการ ให้ใช้ &quot;เสียงาน&quot; ที่ท้ายหน้าดีลแทน</span>
          ) : (
            <span className="flex flex-col gap-0.5">
              <span><strong>{`ได้งาน ${won.length} ราย`}</strong> · ทุกดีลย้ายไปขั้น S10 รับคำสั่งซื้อ</span>
              {destinations.map(({ party, code: dest }) => (
                <span key={party.id}>{`${dest} → ${party.customerName} (${party.latestQuotationAmount != null ? formatMoney(party.latestQuotationAmount) : '—'})`}</span>
              ))}
              {unselected > 0 ? (
                <span>
                  {othersOutcome === 'KEEP_WAITING'
                    ? `อีก ${unselected} ราย ยังรอผล (ดีลหลักยังเปิดรับผลเพิ่มได้)`
                    : `อีก ${unselected} ราย บันทึกเป็น ไม่ได้งาน`}
                </span>
              ) : null}
            </span>
          )}
        </div>

        {error ? <p role="alert" className="m-0 text-sm font-bold text-danger">{error}</p> : null}
      </div>
    </Modal>
  );
}
