import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Modal } from '../../components/common/Modal.jsx';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { formatMoney } from './specialMoneyRules.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

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
 *
 * **Approval-ceiling preview** (GET `/api/special-money/{id}/approval-preview`): fetched while the
 * dialog is open, keyed on the request id, via `SpecialMoneyService#approvalPreview` -- it uses
 * the same `computeApprovalCeiling` calculation `ceoApproveFrom` itself enforces, evaluated at
 * PREVIEW time. Usage/cutoff/payroll-month can all change between the preview fetch and the actual
 * approve call (another request for the same employee gets approved in between, the payroll
 * cutoff rolls over), so the server remains the sole authority -- this is a best-effort display,
 * not a guarantee, and `ceoApproveFrom`'s own 400 is still the backstop (see the inline
 * server-error path below). The dialog stays fully usable while it loads: nothing here blocks on
 * it, and any failure (network error, 404, mock mode -- `mockApi.js` cannot reimplement the policy
 * evaluator, see its own comment) is treated exactly like "no ceiling available" -- no error toast,
 * no extra UI, the dialog behaves as it always did.
 *
 * **Staleness (Opus review, post-#1015-stack):** `queryClient.js`'s global `staleTime: 30_000`
 * means a dialog reopened within 30s of ANOTHER request's approval could otherwise show a cached
 * ceiling that the intervening approval has already invalidated server-side (or the reverse: a
 * ceiling that has since gone UP, forcing an unnecessary reason). Three layers close this:
 * `staleTime: 0` + `refetchOnMount: 'always'` below force a fresh fetch every time this query
 * becomes enabled; `SpecialMoneyPanel.jsx`'s `invalidateSpecialMoney()` also invalidates the
 * `['specialMoney', 'approvalPreview']` prefix after every approve/reject/cancel/create; and the
 * cache-clearing `useEffect` below (keyed on the request id) removes THIS query's cached preview
 * the moment the dialog closes, so a later reopen always starts from a genuinely empty cache
 * (`status: 'pending'`, no `data`) rather than being able to show a leftover figure for even an
 * instant while the fresh fetch is in flight. `status === 'success'` (not just "has data") gates
 * the last piece: react-query keeps the last successful `data` in place across a FAILED refetch
 * (status flips to `'error'`), which would otherwise let a stale ceiling survive a refetch that
 * genuinely failed while the dialog stayed open (no close/reopen involved).
 *
 * An earlier version of this gate used a manually incremented ref ("epoch") compared during
 * render to decide whether cached data belonged to the current opening. This project's ESLint
 * config enforces `react-hooks/refs`, which refuses ANY ref read or write during render (stricter
 * than the classic "compare against a ref to detect a prop change" pattern some React code uses)
 * -- so that version could not ship. Clearing the cache entry in an effect on close sidesteps the
 * need for any render-time comparison at all: there is nothing stale left to accidentally read.
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
  const queryClient = useQueryClient();

  const previewQuery = useQuery({
    queryKey: queryKeys.specialMoneyApprovalPreview(request?.id),
    queryFn: () => api.specialMoney.approvalPreview(request.id).then((response) => response.preview),
    enabled: open && request?.id != null,
    retry: false,
    // See this component's Javadoc "Staleness" section: the app-wide default (queryClient.js,
    // staleTime: 30_000) is wrong for this query specifically -- an approve/reject/cancel/create
    // elsewhere changes the ceiling this ID would now compute to, and 30s of a stale cached figure
    // is long enough for a CEO to act on it. Force a real network round trip every time this query
    // is (re-)enabled rather than trusting the cache at all.
    staleTime: 0,
    refetchOnMount: 'always',
  });

  // Clears THIS request's cached preview the moment the dialog closes (or swaps to a different
  // request while open) -- an effect CLEANUP, not a render-time check, so a later reopen for the
  // SAME id always finds an empty cache (no `data`, `status: 'pending'`) rather than a leftover
  // figure that could flash before the fresh fetch above resolves. See the component's Javadoc
  // "Staleness" section for why this replaced an earlier ref-based approach.
  useEffect(() => {
    const idToClear = request?.id;
    return () => {
      if (idToClear != null) {
        queryClient.removeQueries({ queryKey: queryKeys.specialMoneyApprovalPreview(idToClear), exact: true });
      }
    };
  }, [open, request?.id, queryClient]);

  // Trusted ONLY when the preview's most recent fetch actually SUCCEEDED. A FAILED refetch is the
  // one case the cache-clear-on-close above does not by itself cover: react-query keeps the last
  // successful `data` in place across a failed refetch (flipping only `status` to `'error'`), which
  // could otherwise let a stale ceiling survive a refetch that failed while the dialog stayed open
  // (no close/reopen in between) -- e.g. an `invalidateSpecialMoney()`-triggered background
  // refetch that 5xx'd. `status === 'success'`, not merely "data is present", excludes that.
  const previewIsFresh = previewQuery.status === 'success';
  // `eligibleAmount` is only ever a genuine ceiling when the preview is fresh (see above) AND the
  // value itself is a finite number -- the mock's deliberate `eligibleAmount: null` (see
  // mockApi.js's specialMoney.approvalPreview) collapses to the same "nothing to show" state here,
  // per this dialog's contract above. `!= null`, not a truthy check: ZERO is a real ceiling (an
  // excluded-per-diem province, an exhausted MEDICAL balance) and must still render as "฿0", not
  // fall through to "no ceiling available".
  const eligibleAmount = previewIsFresh
    && typeof previewQuery.data?.eligibleAmount === 'number'
    && Number.isFinite(previewQuery.data.eligibleAmount)
    ? previewQuery.data.eligibleAmount
    : null;
  const hasCeiling = eligibleAmount != null;
  // A ceiling of exactly ฿0 (or, defensively, a negative one) means there is no remaining
  // allowance at all -- the "ใช้ยอดเพดาน" shortcut would fill in an amount `amountIsPositiveFinite`
  // rejects anyway, so it is hidden rather than offered, and the warning below is worded for "no
  // budget left" rather than "here is the lower number to use".
  const ceilingIsExhausted = hasCeiling && eligibleAmount <= 0;
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

  // Static: what the EMPLOYEE originally asked for versus the ceiling -- independent of whatever
  // the CEO has typed into the amount field right now. Drives the persistent over-ceiling warning
  // and its "ใช้ยอดเพดาน" shortcut.
  const requestedExceedsCeiling = hasCeiling && Number(request.requestedAmount) > eligibleAmount;
  // Dynamic: what is CURRENTLY typed versus the ceiling -- mirrors the exact comparison
  // `SpecialMoneyService#ceoApproveFrom` makes (`approvedAmount.compareTo(eligibleAmount) > 0`)
  // client-side, so the CEO is not let through a form the server will 400.
  const enteredExceedsCeiling = hasCeiling && amountIsPositiveFinite && amountNumber > eligibleAmount;
  const reasonBlank = reason.trim() === '';
  const reasonRequiredForCeiling = enteredExceedsCeiling && reasonBlank;
  const confirmDisabled = busy || !amountValid || reasonRequiredForCeiling;

  function handleAmountChange(event) {
    setAmountInput(event.target.value);
    setLocalError(null);
  }

  function handleUseCeilingAmount() {
    if (!hasCeiling) return;
    setAmountInput(String(eligibleAmount));
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
          {hasCeiling ? (
            <p className="m-0 mt-0.5">เพดานตามระเบียบสำหรับคำขอนี้ {formatMoney(eligibleAmount)}</p>
          ) : null}
        </div>

        {requestedExceedsCeiling ? (
          <div className="rounded-md border border-warning-border bg-warning-bg p-3" role="status">
            <p className="m-0 text-xs font-bold text-warning-dark">
              {ceilingIsExhausted
                ? 'ไม่มีวงเงินคงเหลือตามระเบียบ — ต้องระบุเหตุผลหากต้องการอนุมัติ'
                : `ยอดที่ขอเบิกเกินเพดาน — อนุมัติได้ไม่เกิน ${formatMoney(eligibleAmount)} โดยไม่ต้องระบุเหตุผล`}
            </p>
            {/* No shortcut when there is nothing to shortcut TO: filling in ฿0 (or less) is not a
                usable approved amount -- amountIsPositiveFinite would reject it -- so the button is
                hidden rather than offered for a ceiling this exhausted. */}
            {ceilingIsExhausted ? null : (
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="mt-2"
                onClick={handleUseCeilingAmount}
                disabled={busy}
              >
                ใช้ยอดเพดาน ({formatMoney(eligibleAmount)})
              </Button>
            )}
          </div>
        ) : null}

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
            aria-invalid={reasonRequiredForCeiling ? true : undefined}
            aria-describedby={reasonRequiredForCeiling ? 'approve-special-money-reason-required' : undefined}
          />
          {/* Mirrors the amount field's own pattern above: aria-describedby, not FormField's
              role="alert" error slot, so this is not announced on every keystroke while the CEO is
              still typing a reason. */}
          {reasonRequiredForCeiling ? (
            <p id="approve-special-money-reason-required" className="m-0 text-danger text-xs font-bold">
              ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ
            </p>
          ) : null}
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
