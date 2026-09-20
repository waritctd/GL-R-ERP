import { useEffect, useRef, useState } from 'react';
import { Modal } from '../../components/common/Modal.jsx';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { formatMoney } from './specialMoneyRules.js';

const MAX_APPROVED_AMOUNT = 9999999999.99; // special_money_request.approved_amount is NUMERIC(12,2)
// (V66__special_money_request_schema.sql) -- 10 integer digits + 2 decimal places is the largest
// value Postgres (and therefore the Java BigDecimal column) can actually store.

/**
 * Fixes the CEO approve dead-end proven by
 * `backend/.../specialmoney/SpecialMoneyApproveDeadEndIntegrationTest.java`: the panel used to
 * call `api.specialMoney.approve(id, {})` with no way to set `approvedAmount` or
 * `capOverrideReason`, so any request the policy evaluator let through SUBMITTED with an
 * over-ceiling amount (a pre-probation kit claim typed above the fixed kit total, or a second
 * MEDICAL claim once an earlier one used up the year's cap) 400s on the CEO's only in-UI action
 * and is stuck forever. `SpecialMoneyService#ceoApproveFrom` accepts two ways out -- approve a
 * lower `approvedAmount`, or supply a non-blank `capOverrideReason` -- and this dialog is what
 * exposes both to the CEO instead of the API-only escape routes the IT demonstrates.
 *
 * Built on `Modal` (focus trap / Escape / backdrop close already handled there) and styled to
 * match `ConfirmDialog`, but not built on `ConfirmDialog` itself: this needs two independent
 * fields (a pre-filled amount, an optional reason) plus an inline server-error region that
 * survives a failed submit, none of which `ConfirmDialog`'s single optional-reason shape covers.
 *
 * `onConfirm(approvedAmount, capOverrideReason)` is expected to return a promise (e.g. a
 * react-query `mutateAsync` call) -- a rejection is caught HERE and rendered inline, with the
 * entered amount/reason left exactly as typed, rather than only surfacing as a toast that leaves
 * the CEO nowhere. A resolved promise is assumed to mean the parent already updated `open` to
 * false; the dialog does not close itself.
 *
 * `busy` (from the caller's mutation `isPending`) disables Confirm, but only a tick after click --
 * a `useRef` in-flight flag (`submittingRef` below) closes that gap so three fast clicks before
 * `busy` catches up still produce exactly one `onConfirm` call.
 */
export function ApproveSpecialMoneyDialog({
  open,
  request,
  typeLabel,
  busy = false,
  onConfirm,
  onCancel,
}) {
  const [amountInput, setAmountInput] = useState('');
  const [reason, setReason] = useState('');
  const [localError, setLocalError] = useState(null);
  const amountRef = useRef(null);
  // Closes the gap `busy` (the caller's `isPending`) leaves open: that flag only flips a render
  // AFTER `onConfirm` is first awaited, so a second and third click landing before that re-render
  // each fired their own `approve` call (proven: 3 rapid clicks -> 3 calls). A ref is synchronous
  // and mutates immediately, with no re-render in between, so the very next click -- even in the
  // same tick -- already sees it set.
  const submittingRef = useRef(false);

  // Fields (and any leftover error from a previous request's failed attempt) reset whenever the
  // dialog opens for a -- possibly different -- request. Keyed on request?.id as well as `open`:
  // a CEO who cancels one row's dialog and immediately opens another's must not see the first
  // request's half-typed amount.
  useEffect(() => {
    if (!open) return;
    setAmountInput(request?.requestedAmount != null ? String(request.requestedAmount) : '');
    setReason('');
    setLocalError(null);
    submittingRef.current = false;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, request?.id]);

  if (!open || !request) return null;

  const trimmedAmount = amountInput.trim();
  const amountNumber = Number(trimmedAmount);
  const amountIsPositiveFinite = trimmedAmount !== '' && Number.isFinite(amountNumber) && amountNumber > 0;
  // Checked against the raw STRING, not the parsed number: `Number('1960.004')` is a finite,
  // positive float and would otherwise pass silently, then get sent to a BigDecimal(12,2) column
  // that only keeps 2 of those places.
  const hasTooManyDecimals = /\.\d{3,}/.test(trimmedAmount);
  const exceedsMax = amountIsPositiveFinite && amountNumber > MAX_APPROVED_AMOUNT;
  const amountValid = amountIsPositiveFinite && !hasTooManyDecimals && !exceedsMax;
  const amountErrorMessage = amountValid ? null
    : !amountIsPositiveFinite ? 'กรุณาระบุจำนวนเงินให้มากกว่า 0'
      : hasTooManyDecimals ? 'ระบุทศนิยมได้ไม่เกิน 2 ตำแหน่ง'
        : 'จำนวนเงินเกินขีดจำกัดที่ระบบรองรับ';
  const confirmDisabled = busy || !amountValid;

  function handleAmountChange(event) {
    setAmountInput(event.target.value);
    setLocalError(null);
  }

  function handleReasonChange(event) {
    setReason(event.target.value);
    setLocalError(null);
  }

  async function handleConfirm() {
    // `confirmDisabled` alone isn't enough: it reads `busy`, which is the CALLER's mutation
    // `isPending` and only flips true a render AFTER the first click's `await` below actually
    // starts -- so a second and third click landing in that same tick both saw `confirmDisabled`
    // still false and both fired. `submittingRef` is set synchronously, before anything is
    // awaited, so click #2 sees it immediately and returns without calling `onConfirm` again.
    if (confirmDisabled || submittingRef.current) return;
    submittingRef.current = true;
    const trimmedReason = reason.trim();
    try {
      await onConfirm(amountNumber, trimmedReason || null);
    } catch (error) {
      // The request stays open on the entered values -- correcting the amount or adding a reason
      // and retrying is the whole point of this dialog existing; resetting the form here would
      // recreate the exact dead-end this fixes.
      setLocalError(error?.message || 'อนุมัติไม่สำเร็จ');
    } finally {
      submittingRef.current = false;
    }
  }

  return (
    <Modal
      title="ยืนยันการอนุมัติ"
      onClose={busy ? undefined : onCancel}
      initialFocusRef={amountRef}
      footer={(
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            ยกเลิก
          </Button>
          <Button variant="primary" onClick={handleConfirm} disabled={confirmDisabled}>
            {busy ? 'กำลังดำเนินการ...' : 'อนุมัติ'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-3.5">
        <div className="text-sm text-text-secondary">
          <p className="m-0 font-bold text-text">{typeLabel}</p>
          <p className="m-0 mt-0.5">{request.employeeName || request.employeeCode}</p>
          <p className="m-0 mt-0.5">ยอดที่ขอเบิก {formatMoney(request.requestedAmount)}</p>
        </div>

        <FormField
          label="จำนวนเงินที่อนุมัติ (บาท)"
          htmlFor="approve-special-money-amount"
          required
        >
          {/* Not FormField's own `error` slot: that always renders with role="alert" (see
              FormField.jsx), which would announce this message to a screen reader on every single
              keystroke while the CEO is still typing. Wired by hand via aria-describedby/
              aria-invalid instead, so it is read when the field itself is reached, not blasted
              continuously. */}
          <input
            id="approve-special-money-amount"
            ref={amountRef}
            type="number"
            min="0"
            max={MAX_APPROVED_AMOUNT}
            step="0.01"
            value={amountInput}
            onChange={handleAmountChange}
            disabled={busy}
            aria-invalid={amountValid ? undefined : true}
            aria-describedby={amountValid ? undefined : 'approve-special-money-amount-error'}
          />
          {amountErrorMessage ? (
            <p id="approve-special-money-amount-error" className="m-0 text-danger text-xs font-bold">
              {amountErrorMessage}
            </p>
          ) : null}
        </FormField>

        <FormField
          label="เหตุผลในการอนุมัติเกินเพดาน (ถ้ามี)"
          htmlFor="approve-special-money-reason"
          hint="ต้องระบุเหตุผลหากจำนวนเงินที่อนุมัติเกินกว่าที่ระเบียบกำหนดสำหรับคำขอนี้ — หากไม่ระบุ ระบบจะปฏิเสธการอนุมัติ"
        >
          <textarea
            id="approve-special-money-reason"
            rows={3}
            maxLength={2000}
            value={reason}
            onChange={handleReasonChange}
            disabled={busy}
          />
        </FormField>

        {localError ? (
          <div className="rounded-md border border-danger-border bg-danger-bg p-3" role="alert">
            <p className="m-0 text-sm font-bold text-danger">{localError}</p>
          </div>
        ) : null}
      </div>
    </Modal>
  );
}
