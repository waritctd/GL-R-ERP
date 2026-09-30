import { useEffect, useId, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { entryChannelLabel } from '../../utils/format.js';

/**
 * The three channels EntryChannel.java accepts as INPUT. UNSPECIFIED is deliberately absent: it is
 * valid as STORED (the V144 column default) but `TicketService.setEntryChannel` 400s on it, because
 * once a channel has been stated it must not be possible to un-state it. Offering it would be
 * offering an action that dies on click.
 */
export const SETTABLE_ENTRY_CHANNELS = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'];

/**
 * "แก้ช่องทางดีล" — the remedy for the route gate's own refusal.
 *
 * The server refuses a move into a stage that is off the deal's route with a message that ends
 * "— แก้ช่องทางดีลก่อน". GLA-156 deleted the only control that could do that, so a rep was told to
 * fix the channel and could not. This is that control, recovered from the deleted
 * `EntryChannelControl` (issue #740) and re-shaped for where the refusal is READ: an inline
 * disclosure under an off-route row of the stepper, and under a blocked row of UpdateStageModal.
 * Inline, never a modal — UpdateStageModal is itself a dialog, and a dialog inside a dialog is not
 * acceptable; using the same shape in both places keeps them consistent.
 *
 * It is a speed bump with an audit trail, not a wall: a rep who genuinely has a designer on an
 * "owner-direct" deal SHOULD correct the record. The required reason (below) and the server's
 * POLICY_CHANGED event are what make that accountable.
 *
 * This component decides nothing about the rules — the server's own answers are passed in:
 *   • `action` is the `SET_ENTRY_CHANNEL` entry of availableActions. No entry -> nothing renders,
 *     so there is no control to click and no request to make. Permission is the server's
 *     (stageCatalog.js forbids client-side `can*`).
 *   • The reason is required iff that entry's `requiredFields` lists `note`. TicketService
 *     .entryChannelIsStated fills it in, and treats BOTH DESIGNER_LED and UNSPECIFIED as UNSTATED
 *     (after V144's non-backfill an untouched deal reads one or the other purely by age), so a
 *     legacy deal's first correction needs no note. Never computed here from the channel value.
 *   • Only SETTABLE_ENTRY_CHANNELS are offered; the channel the deal is already on is shown as the
 *     current state and cannot be re-picked.
 *
 * `onSubmit({ value, note })` — `note` is the trimmed reason when the server asked for one, else
 * null. The parent owns the request; this collapses when the deal reports a different channel, so a
 * successful save is visibly acknowledged and a failed one keeps the rep's draft.
 */
export function EntryChannelFix({
  entryChannel, action, onSubmit, disabled = false, context,
}) {
  const uid = useId();
  const panelId = `${uid}-panel`;
  const hintId = `${uid}-hint`;
  const [open, setOpen] = useState(false);
  const [picked, setPicked] = useState(null);
  const [reason, setReason] = useState('');
  const current = entryChannel ?? 'UNSPECIFIED';

  // The deal now reports a different channel: the save landed (or the deal moved under us). Start
  // clean either way rather than carrying a stale draft against a channel that no longer applies.
  useEffect(() => {
    setOpen(false);
    setPicked(null);
    setReason('');
  }, [current]);

  if (!action || action.action !== 'SET_ENTRY_CHANNEL' || !onSubmit) return null;

  const reasonRequired = action.requiredFields?.includes('note') ?? false;
  const reasonMissing = reasonRequired && reason.trim() === '';
  // Why Save is dead, said in words — a disabled control must explain itself. First applicable
  // reason wins, in the order the rep would clear them.
  let hint = null;
  if (picked == null) hint = 'เลือกช่องทางที่ถูกต้องก่อน จึงจะบันทึกได้';
  else if (reasonMissing) hint = 'ต้องระบุเหตุผลก่อนบันทึก — ดีลนี้ระบุช่องทางไว้แล้ว การแก้ไขต้องมีเหตุผลประกอบ';
  else if (disabled) hint = 'กำลังดำเนินการอื่นอยู่ — รอสักครู่แล้วบันทึกอีกครั้ง';
  const canSave = hint == null;

  function close() {
    setOpen(false);
    setPicked(null);
    setReason('');
  }

  function save() {
    if (!canSave) return;
    onSubmit({ value: picked, note: reasonRequired ? reason.trim() : null });
  }

  return (
    <div data-testid="entry-channel-fix" className="min-w-0">
      <Button
        type="button"
        variant="text"
        className="min-h-11 justify-start gap-1.5 text-xs underline underline-offset-2 mobile:min-h-11"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => (open ? close() : setOpen(true))}
        data-testid="entry-channel-fix-toggle"
      >
        <span>
          แก้ช่องทางดีล
          {context ? <span className="sr-only">{` เพื่อไปขั้น ${context}`}</span> : null}
        </span>
        <span
          className={`shrink-0 transition-transform duration-150 ease-out motion-reduce:transition-none ${open ? 'rotate-180' : ''}`}
          aria-hidden="true"
        >
          <Icon name="chevronDown" size={14} />
        </span>
      </Button>

      {open ? (
        <div
          id={panelId}
          data-testid="entry-channel-fix-panel"
          className="mt-1 flex min-w-0 flex-col gap-2.5 rounded-lg bg-surface-subtle px-3 py-3"
        >
          <p data-testid="entry-channel-fix-current" className="m-0 text-xs text-text-secondary">
            เสนอแก่ตอนนี้: <strong className="font-extrabold text-text">{entryChannelLabel(current).label}</strong>
          </p>

          <div role="radiogroup" aria-label="เสนอแก่ที่ถูกต้อง" className="flex min-w-0 flex-col">
            {SETTABLE_ENTRY_CHANNELS.map((value) => {
              const isCurrent = value === current;
              return (
                <label
                  key={value}
                  className={`m-0 flex min-h-11 min-w-0 items-center gap-2.5 text-sm ${
                    isCurrent ? 'cursor-not-allowed text-text-muted' : 'cursor-pointer text-text'
                  }`}
                >
                  <input
                    type="radio"
                    name={`${uid}-channel`}
                    value={value}
                    checked={picked === value}
                    disabled={isCurrent || disabled}
                    onChange={() => setPicked(value)}
                    className="shrink-0"
                  />
                  <span className="min-w-0 [overflow-wrap:anywhere]">
                    {entryChannelLabel(value).label}
                    {isCurrent ? ' (ตอนนี้)' : ''}
                  </span>
                </label>
              );
            })}
          </div>

          {reasonRequired ? (
            <label className="flex min-w-0 flex-col gap-1.5 text-xs font-bold text-text-secondary">
              เหตุผลที่แก้ช่องทาง (จำเป็น)
              <textarea
                className="min-h-20 min-w-0"
                data-testid="entry-channel-fix-reason"
                value={reason}
                disabled={disabled}
                placeholder="เช่น มีผู้ออกแบบเข้ามาร่วมดีลจริง"
                onChange={(event) => setReason(event.target.value)}
              />
            </label>
          ) : null}

          <p className="m-0 text-2xs leading-snug text-text-muted">
            การแก้ไขนี้เปลี่ยนเส้นทางของดีลทันที และบันทึกไว้ในประวัติดีล
          </p>

          {hint ? (
            <p id={hintId} data-testid="entry-channel-fix-hint" className="m-0 text-xs font-bold text-text-secondary">
              {hint}
            </p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="primary"
              disabled={!canSave}
              aria-describedby={hint ? hintId : undefined}
              onClick={save}
              data-testid="entry-channel-fix-save"
            >
              บันทึกช่องทางใหม่
            </Button>
            <Button type="button" variant="secondary" onClick={close}>ยกเลิก</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
