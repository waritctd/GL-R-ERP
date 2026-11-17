import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { noticePayload } from './audiences.js';

const PRESS = 'transition-transform duration-150 ease-out active:scale-[0.98] motion-reduce:transition-none motion-reduce:active:scale-100';
const RETRY_LABEL = 'ส่งอีเมลให้คนที่ยังไม่ได้รับ';
/** While any mail of this release is still with the sender, re-read the status so the outcome appears by itself. */
const SENDING_POLL_MS = 4000;

const names = (list) => (list?.length ? list.join(', ') : '');

/** The per-person truth (POST /audience → email). Every targeted person is in exactly one bucket. Rendered only when
 * the server sends it, so a backend without per-person outcomes shows nothing rather than a guess. */
function EmailStatus({ status }) {
  if (!status) return null;
  const stats = [
    { key: 'sent', label: 'ส่งสำเร็จ', value: status.sent, tone: 'text-success', always: true },
    { key: 'failed', label: 'ส่งไม่สำเร็จ', value: status.failed, tone: 'text-danger' },
    { key: 'sending', label: 'กำลังส่ง', value: status.sending, tone: 'text-info' },
    { key: 'unconfirmed', label: 'ไม่ทราบผล', value: status.unconfirmed, tone: 'text-warning' },
    { key: 'notYetEmailed', label: 'ยังไม่ได้ส่ง', value: status.notYetEmailed, tone: 'text-text' },
    { key: 'withoutEmail', label: 'ไม่มีอีเมล', value: status.withoutEmail, tone: 'text-text-muted' },
  ].filter((stat) => stat.always || stat.value > 0);
  const notes = [
    status.failed > 0 && `ส่งไม่สำเร็จ: ${names(status.failedNames)} — กด "${RETRY_LABEL}" เพื่อส่งใหม่`,
    status.unconfirmed > 0
      && `ไม่ทราบผล: ${names(status.unconfirmedNames)} — ระบบไม่ได้รับผลการส่ง อาจได้รับอีเมลแล้วหรือยังก็ได้ จึงไม่ส่งซ้ำเอง`,
    status.withoutEmail > 0
      && `ไม่มีอีเมลในระบบ: ${names(status.withoutEmailNames)} — ได้รับแจ้งเตือนในกระดิ่งเท่านั้น`,
  ].filter(Boolean);

  return (
    <section aria-label="ผลการส่งอีเมล" className="grid gap-2 border-t border-border pt-3">
      <div className="flex flex-wrap items-baseline gap-x-5 gap-y-1 text-sm">
        <p className="m-0 font-bold text-text">ผลการส่งอีเมล</p>
        <dl className="m-0 flex flex-wrap items-baseline gap-x-5 gap-y-1" aria-live="polite">
          {stats.map((stat) => (
            <div key={stat.key} className="flex items-baseline gap-1.5">
              <dt className="text-text-muted">{stat.label}</dt>
              <dd className={`m-0 font-bold tabular-nums ${stat.tone}`}>{stat.value}</dd>
            </div>
          ))}
        </dl>
      </div>
      {notes.length > 0 ? (
        <ul className="m-0 grid list-none gap-1 p-0 text-sm text-text-secondary">
          {notes.map((note) => <li key={note}>{note}</li>)}
        </ul>
      ) : null}
    </section>
  );
}

function PublishMessage({ audience }) {
  if (!audience) return <p className="m-0 text-text-secondary">กำลังนับผู้รับ…</p>;
  const missing = audience.email?.withoutEmailNames ?? [];
  return (
    <div className="grid gap-2 text-text-secondary leading-normal">
      <p className="m-0">
        แจ้งเตือนในกระดิ่ง <strong className="text-text">{audience.pendingNotification} คน</strong>
        {audience.alreadyNotified > 0 ? ` (แจ้งไปแล้ว ${audience.alreadyNotified} คน ไม่แจ้งซ้ำ)` : ''}
      </p>
      <p className="m-0">
        ส่งอีเมล <strong className="text-text">{audience.pendingEmail} คน</strong>
        {audience.alreadyEmailed > 0 ? ` (ได้รับหรือกำลังส่งแล้ว ${audience.alreadyEmailed} คน ไม่ส่งซ้ำ)` : ''}
        {' '}— ส่งแล้วยกเลิกไม่ได้
      </p>
      {audience.withoutEmail > 0 ? (
        <p className="m-0">
          ไม่มีอีเมล {audience.withoutEmail} คน จะไม่ได้รับอีเมล{missing.length ? `: ${names(missing)}` : ''}
        </p>
      ) : null}
    </div>
  );
}

function RetryMessage({ audience, resendUnconfirmed, onResendUnconfirmed }) {
  if (!audience) return <p className="m-0 text-text-secondary">กำลังนับผู้รับ…</p>;
  const status = audience.email;
  const missing = status?.withoutEmailNames ?? [];
  // Ticked, the headline is the real reach: the people the next send will mail plus those re-sent on purpose.
  const secondCopies = resendUnconfirmed ? (status?.unconfirmed ?? 0) : 0;
  const reach = audience.pendingEmail + secondCopies;
  return (
    <div className="grid gap-2 text-text-secondary leading-normal">
      <p className="m-0">
        จะส่งอีเมลถึง <strong className="text-text">{reach} คน</strong>
        {secondCopies > 0 ? ` (${secondCopies} คนอาจได้รับ 2 ฉบับ)` : ''} — ส่งแล้วยกเลิกไม่ได้
      </p>
      {status?.failed > 0 ? (
        <p className="m-0">ส่งไม่สำเร็จครั้งก่อน {status.failed} คน จะส่งใหม่: {names(status.failedNames)}</p>
      ) : null}
      {audience.alreadyEmailed - secondCopies > 0 ? (
        <p className="m-0">ได้รับหรือกำลังส่งแล้ว {audience.alreadyEmailed - secondCopies} คน ไม่ส่งซ้ำ</p>
      ) : null}
      {audience.withoutEmail > 0 ? (
        <p className="m-0">ไม่มีอีเมล {audience.withoutEmail} คน จะไม่ได้รับ{missing.length ? `: ${names(missing)}` : ''}</p>
      ) : null}
      {status?.unconfirmed > 0 ? (
        <label className="mt-1 flex items-start gap-2.5 rounded-md border border-warning-border bg-warning-bg-soft px-3 py-2.5 text-sm text-text">
          <input
            type="checkbox"
            className="mt-0.5 size-4 shrink-0"
            checked={resendUnconfirmed}
            onChange={(event) => onResendUnconfirmed(event.target.checked)}
          />
          <span>
            ส่งซ้ำให้คนที่ไม่ทราบผล {status.unconfirmed} คนด้วย ({names(status.unconfirmedNames)})
            {' '}— บางคนอาจได้รับอีเมล 2 ฉบับ
          </span>
        </label>
      ) : null}
    </div>
  );
}

/**
 * CEO/HR control for one release. "เผยแพร่ประกาศ" publishes the bell notice AND emails it, behind one confirm that
 * counts both (POST /audience, which writes nothing). "ส่งอีเมลให้คนที่ยังไม่ได้รับ" is the retry: it mails only those
 * who have not received it, and re-sends a mail with no known outcome only when explicitly ticked. Both are
 * idempotent server-side, so a double click or a second visit cannot double-send. Below the buttons, the real
 * per-person result (sent / failed / no address …), refreshed while mails are still going out. Visibility here is
 * a UI hint; ReleaseNoticeService is the gate.
 */
export function ReleaseNoticeAdmin({ release, showToast }) {
  const queryClient = useQueryClient();
  const [action, setAction] = useState(null);
  const [resendUnconfirmed, setResendUnconfirmed] = useState(false);
  const payload = noticePayload(release);
  const statusKey = queryKeys.releaseNoteAudience(release.id, 'status');

  const statusQuery = useQuery({
    queryKey: statusKey,
    queryFn: () => api.releaseNotes.audience(payload),
    refetchInterval: (query) => (query.state.data?.email?.sending > 0 ? SENDING_POLL_MS : false),
    // Outcomes arrive after the press, so coming back to the page re-reads them rather than showing a 30 s old view.
    staleTime: 0,
  });

  const audienceQuery = useQuery({
    queryKey: queryKeys.releaseNoteAudience(release.id, action),
    queryFn: () => api.releaseNotes.audience(payload),
    enabled: action != null,
    staleTime: 0,
    gcTime: 0,
  });
  const audience = audienceQuery.data ?? null;

  const settle = () => {
    queryClient.invalidateQueries({ queryKey: statusKey });
    setAction(null);
  };

  const publishMutation = useMutation({
    mutationFn: () => api.releaseNotes.publishWithEmail(payload),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
      const bell = `เผยแพร่แล้ว แจ้งเตือน ${result?.inserted ?? 0} คน`;
      if (result?.email && !(result.email.notQueued > 0)) {
        showToast?.('success', `${bell} · กำลังส่งอีเมล ${result.email.queued} คน ดูผลการส่งใต้ปุ่ม`);
      } else {
        const count = result?.email?.notQueued > 0 ? ` ${result.email.notQueued} คน` : '';
        showToast?.('error', `${bell} แต่ส่งอีเมลไม่สำเร็จ${count}: ${result?.emailError || 'เริ่มส่งอีเมลไม่สำเร็จ'} กด "${RETRY_LABEL}" เพื่อลองใหม่`);
      }
      settle();
    },
    onError: (error) => showToast?.('error', error?.message || 'เผยแพร่ไม่สำเร็จ'),
  });

  const emailMutation = useMutation({
    mutationFn: () => api.releaseNotes.email({ ...payload, resendUnconfirmed }),
    onSuccess: (result) => {
      if (result?.notQueued > 0) {
        showToast?.('error', `ส่งอีเมลไม่สำเร็จ ${result.notQueued} คน: ${result.error || 'ระบบส่งอีเมลไม่รับงาน'} ลองกดอีกครั้งภายหลัง`);
      } else {
        showToast?.('success', `กำลังส่งอีเมล ${result?.queued ?? 0} คน ดูผลการส่งใต้ปุ่ม`);
      }
      settle();
    },
    onError: (error) => showToast?.('error', error?.message || 'ส่งอีเมลไม่สำเร็จ'),
  });

  // A backend from before per-person outcomes answers /audience without `email`. Against it, neither button may be
  // used: its /publish-with-email does not exist and its /email would send the old mail with optimistic rows. The
  // buttons also wait for the first status read, so a click never races the check.
  const statusKnown = statusQuery.data != null;
  const backendReady = statusKnown && statusQuery.data.email != null;
  const open = (next) => {
    if (!backendReady) return;
    setResendUnconfirmed(false);
    setAction(next);
  };

  const isPublish = action === 'publish';
  const nothingToDo = !audience || (isPublish
    ? audience.pendingNotification === 0 && audience.pendingEmail === 0
    : audience.pendingEmail === 0 && !(resendUnconfirmed && audience.email?.unconfirmed > 0));
  const busy = publishMutation.isPending || emailMutation.isPending;

  let message;
  if (audienceQuery.isError) message = 'นับผู้รับไม่สำเร็จ ลองใหม่อีกครั้ง';
  else if (isPublish) message = <PublishMessage audience={audience} />;
  else {
    message = (
      <RetryMessage
        audience={audience}
        resendUnconfirmed={resendUnconfirmed}
        onResendUnconfirmed={setResendUnconfirmed}
      />
    );
  }

  return (
    <section
      aria-label="เผยแพร่ประกาศ (HR / CEO)"
      className="grid gap-3 rounded-lg border border-dashed border-border-strong bg-surface px-4 py-3"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="m-0 min-w-0 flex-1 basis-64 text-sm text-text-muted">
          <strong className="text-text">สำหรับ HR / CEO</strong> · กดเผยแพร่ครั้งเดียว พนักงานได้ทั้งแจ้งเตือนในกระดิ่งและอีเมล
          ตามแผนก กดซ้ำได้ ไม่มีใครได้รับซ้ำ
        </p>
        <div className="flex flex-wrap gap-2">
          <Button type="button" className={PRESS} disabled={!backendReady} onClick={() => open('publish')}>
            <Icon name="bell" size={16} />
            เผยแพร่ประกาศ
          </Button>
          <Button
            type="button"
            variant="secondary"
            className={PRESS}
            disabled={!backendReady}
            onClick={() => open('email')}
          >
            <Icon name="mail" size={16} />
            {RETRY_LABEL}
          </Button>
        </div>
      </div>

      {statusKnown && !backendReady ? (
        <p role="status" className="m-0 rounded-md border border-warning-border bg-warning-bg-soft px-3 py-2 text-sm text-text">
          เซิร์ฟเวอร์ยังไม่ได้อัปเดตเป็นรุ่นที่ส่งอีเมลพร้อมการเผยแพร่ จึงยังกดเผยแพร่หรือส่งอีเมลจากหน้านี้ไม่ได้
          แจ้งผู้ดูแลระบบให้อัปเดตเซิร์ฟเวอร์ก่อน
        </p>
      ) : null}
      {statusQuery.isError ? (
        <p role="status" className="m-0 text-sm text-danger">อ่านสถานะการส่งไม่สำเร็จ ลองโหลดหน้าใหม่</p>
      ) : null}

      <EmailStatus status={statusQuery.data?.email} />

      <ConfirmDialog
        open={action != null}
        title={isPublish ? 'เผยแพร่ประกาศและส่งอีเมล?' : 'ส่งอีเมลให้คนที่ยังไม่ได้รับ?'}
        message={message}
        confirmLabel={isPublish ? 'เผยแพร่และส่งอีเมล' : 'ส่งอีเมล'}
        busy={busy}
        confirmDisabled={nothingToDo}
        onConfirm={() => (isPublish ? publishMutation.mutate() : emailMutation.mutate())}
        onCancel={() => setAction(null)}
      />
    </section>
  );
}
