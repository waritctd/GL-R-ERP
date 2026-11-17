import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { baht } from './boardFormat.js';
import {
  EXPECTED_KIND_LABEL, EXPECTED_KINDS, EXPECTED_STATUS_LABEL, nextMondays, presetAmount, snapToMonday, weekRangeLabel,
} from './cashFormat.js';

const INPUT = 'w-full rounded-md border border-border-input bg-surface px-2.5 py-2 text-sm font-normal text-text';
const PICK = 'min-h-[38px] rounded-full border px-3 text-sm font-bold mobile:min-h-[44px]';
const pickTone = (on) => (on
  ? 'border-primary bg-primary-bg text-primary'
  : 'border-border-input bg-surface text-text-secondary hover:bg-surface-hover');

const STATUS_TONE = {
  RECEIVED: 'bg-success-bg text-success',
  OPEN: 'bg-surface-muted text-text-secondary',
};

function Radios({ label, options, value, onChange }) {
  return (
    <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={label}>
      {options.map(({ key, text }) => (
        <button
          key={key}
          type="button"
          role="radio"
          aria-checked={value === key}
          onClick={() => onChange(key)}
          className={`${PICK} ${pickTone(value === key)}`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

/**
 * m-expected — add or edit one expected payment. Amount pre-fills are 30% / 50% of the deal value and "what is left
 * after the rows already planned"; the week is this week or one of the next three, or any day (snapped to its Monday,
 * the only day ExpectedPaymentService accepts).
 */
function ExpectedPaymentDialog({ initial, dealValue, plannedOthers, title, submitting, onClose, onSubmit }) {
  const ids = useId();
  const today = bangkokTodayIso();
  const picks = nextMondays(today, 4);
  const [kind, setKind] = useState(initial?.kind ?? 'DEPOSIT');
  const [amount, setAmount] = useState(initial?.amount != null ? String(initial.amount) : '');
  const [week, setWeek] = useState(initial?.expectedWeek ?? picks[0]);
  const [preset, setPreset] = useState(null);
  const numeric = Number(amount);
  const ready = amount !== '' && Number.isFinite(numeric) && numeric > 0 && week;
  const presets = [
    { key: 30, text: '30% ของมูลค่า' },
    { key: 50, text: '50%' },
    { key: 'rest', text: 'ส่วนที่เหลือทั้งหมด' },
  ];
  const applyPreset = (key) => {
    const value = presetAmount({ preset: key, dealValue, planned: plannedOthers });
    if (value == null) return;
    setPreset(key);
    setAmount(String(value));
  };
  const weekOptions = (picks.includes(week) ? picks : [...picks, week])
    .map((iso) => ({ key: iso, text: weekRangeLabel(iso) }));

  return (
    <Modal
      title="เงินที่คาดว่าจะเข้า"
      subtitle={title}
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            disabled={submitting || !ready}
            onClick={() => onSubmit({ kind, amount: Math.round(numeric * 100) / 100, expectedWeek: week })}
          >
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <fieldset className="m-0 grid gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-sm font-bold text-text">ประเภท</legend>
          <Radios
            label="ประเภท"
            options={EXPECTED_KINDS.map((k) => ({ key: k, text: EXPECTED_KIND_LABEL[k] }))}
            value={kind}
            onChange={setKind}
          />
        </fieldset>
        <div className="grid gap-2">
          <label htmlFor={`${ids}-amount`} className="text-sm font-bold text-text">จำนวนเงิน</label>
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative w-[200px] mobile-xs:w-full">
              <span className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm text-text-muted">฿</span>
              <input
                id={`${ids}-amount`}
                inputMode="decimal"
                value={amount}
                onChange={(e) => { setPreset(null); setAmount(e.target.value.replace(/[^\d.]/g, '')); }}
                className={`${INPUT} pl-6 tabular-nums`}
              />
            </div>
            {dealValue ? presets.map((p) => (
              <button
                key={p.key}
                type="button"
                aria-pressed={preset === p.key}
                onClick={() => applyPreset(p.key)}
                className={`${PICK} ${pickTone(preset === p.key)}`}
              >
                {p.text}
              </button>
            )) : null}
          </div>
        </div>
        <fieldset className="m-0 grid gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-sm font-bold text-text">สัปดาห์ที่คาด</legend>
          <div className="flex flex-wrap items-center gap-2">
            <Radios label="สัปดาห์ที่คาด" options={weekOptions} value={week} onChange={setWeek} />
            <label className="flex items-center gap-2 text-sm text-text-secondary">
              <span>เลือกวัน</span>
              <input
                type="date"
                aria-label="เลือกวัน"
                onChange={(e) => { if (e.target.value) setWeek(snapToMonday(e.target.value)); }}
                className={`${INPUT} w-auto`}
              />
            </label>
          </div>
        </fieldset>
        <p className="m-0 rounded-md bg-info-bg px-3 py-2 text-sm text-text">
          ยอดนี้ไปรวมใน “เงินที่จะเข้า” ของคุณและของทีม เมื่อบัญชีอัปโหลดไฟล์ Express แล้วจับคู่ได้ จะขึ้นเป็น “รับแล้ว” เอง
        </p>
      </div>
    </Modal>
  );
}

/**
 * เงินที่คาดว่าจะเข้า on the deal's การเงิน tab (design a-deal-money): the rep's expected-cash rows for this deal, from
 * GET /api/tickets/{id}/expected-payments. `canWrite` is the deal's owner/keyer, sales_manager or ceo (the server
 * re-checks: ExpectedPaymentService). A RECEIVED row (matched from Express) is final, so it carries no actions.
 */
export function ExpectedPaymentsPanel({ ticketId, dealValue = null, dealTitle = '', canWrite = false, showToast = () => {} }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(null); // { row } — row null = a new one
  const [cancelling, setCancelling] = useState(null);
  const listQuery = useQuery({
    queryKey: queryKeys.expectedPayments(ticketId),
    queryFn: () => api.expectedPayments.list(ticketId),
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['tickets'] });
  const save = useMutation({
    mutationFn: ({ row, payload }) => (row
      ? api.expectedPayments.update(ticketId, row.id, payload)
      : api.expectedPayments.create(ticketId, payload)),
    onSuccess: refresh,
  });
  const cancel = useMutation({ mutationFn: (row) => api.expectedPayments.cancel(ticketId, row.id), onSuccess: refresh });

  const rows = listQuery.data ?? [];
  const plannedOthers = (except) => rows
    .filter((r) => r.status !== 'CANCELLED' && r.id !== except?.id)
    .reduce((sum, r) => sum + Number(r.amount || 0), 0);

  async function run(mutation, variables, done, success) {
    try {
      await mutation.mutateAsync(variables);
      done();
      showToast('success', success);
    } catch (error) {
      showToast('error', error?.message || 'บันทึกไม่สำเร็จ');
    }
  }

  return (
    <Panel
      flush
      title="เงินที่คาดว่าจะเข้า"
      actions={canWrite ? (
        <Button type="button" size="sm" variant="secondary" onClick={() => setEditing({ row: null })}>+ เพิ่ม</Button>
      ) : null}
    >
      {listQuery.isError ? (
        <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
      ) : listQuery.isLoading ? (
        <div className="grid gap-2 px-5 py-4" role="status" aria-busy="true"><Skeleton height={32} /></div>
      ) : rows.length === 0 ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">ยังไม่ได้ใส่เงินที่คาดว่าจะเข้า</p>
      ) : (
        <table className="w-full border-collapse text-sm" aria-label="เงินที่คาดว่าจะเข้า">
          <thead>
            <tr className="text-left text-xs text-text-muted">
              <th className="px-5 py-2 font-bold mobile:px-3.5">ประเภท</th>
              <th className="px-2 py-2 text-right font-bold">จำนวน</th>
              <th className="px-2 py-2 font-bold">สัปดาห์ที่คาด</th>
              <th className="px-2 py-2 font-bold">สถานะ</th>
              {canWrite ? <th className="px-5 py-2 mobile:px-3.5"><span className="sr-only">จัดการ</span></th> : null}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-t border-border">
                <td className="px-5 py-2.5 mobile:px-3.5">{EXPECTED_KIND_LABEL[r.kind] ?? r.kind}</td>
                <td className="px-2 py-2.5 text-right font-bold tabular-nums">{baht(r.amount)}</td>
                <td className="px-2 py-2.5 whitespace-nowrap">{weekRangeLabel(r.expectedWeek)}</td>
                <td className="px-2 py-2.5">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-bold ${STATUS_TONE[r.status] ?? STATUS_TONE.OPEN}`}>
                    {EXPECTED_STATUS_LABEL[r.status] ?? r.status}
                  </span>
                </td>
                {canWrite ? (
                  <td className="px-5 py-2 text-right mobile:px-3.5">
                    {r.status === 'OPEN' ? (
                      <span className="inline-flex gap-1">
                        <Button type="button" size="sm" variant="text" onClick={() => setEditing({ row: r })}>
                          แก้ไข
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="text"
                          aria-label={`ยกเลิกรายการ${EXPECTED_KIND_LABEL[r.kind] ?? ''}`}
                          onClick={() => setCancelling(r)}
                        >
                          ยกเลิก
                        </Button>
                      </span>
                    ) : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite ? (
        <p className="m-0 px-5 pb-4 pt-1 text-xs text-text-muted mobile:px-3.5">
          ยอดนี้ไปรวมใน “เงินที่จะเข้า” ของคุณและทีม
        </p>
      ) : null}

      {editing ? (
        <ExpectedPaymentDialog
          initial={editing.row}
          dealValue={dealValue}
          plannedOthers={plannedOthers(editing.row)}
          title={[dealTitle, dealValue ? `มูลค่า ${baht(dealValue)}` : null].filter(Boolean).join(' · ')}
          submitting={save.isPending}
          onClose={() => setEditing(null)}
          onSubmit={(payload) => run(save, { row: editing.row, payload }, () => setEditing(null), 'บันทึกแล้ว')}
        />
      ) : null}
      <ConfirmDialog
        open={Boolean(cancelling)}
        title="ยกเลิกเงินที่คาดว่าจะเข้า?"
        message={cancelling
          ? `${EXPECTED_KIND_LABEL[cancelling.kind] ?? ''} ${baht(cancelling.amount)} · ${weekRangeLabel(cancelling.expectedWeek)}`
          : ''}
        confirmLabel="ยกเลิกรายการนี้"
        cancelLabel="ไม่ยกเลิก"
        tone="danger"
        busy={cancel.isPending}
        onConfirm={() => run(cancel, cancelling, () => setCancelling(null), 'ยกเลิกแล้ว')}
        onCancel={() => setCancelling(null)}
      />
    </Panel>
  );
}
