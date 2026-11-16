import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { publishPayload } from './audiences.js';

const PRESS = 'transition-transform duration-150 ease-out active:scale-[0.98] motion-reduce:transition-none motion-reduce:active:scale-100';

function publishMessage(audience) {
  if (!audience) return 'กำลังนับผู้รับ…';
  const already = audience.alreadyNotified > 0 ? ` (แจ้งไปแล้ว ${audience.alreadyNotified} คน จะไม่ส่งซ้ำ)` : '';
  return `จะแจ้งเตือนในกระดิ่งถึง ${audience.pendingNotification} คน${already} — ไม่ส่งอีเมล`;
}

function emailMessage(audience) {
  if (!audience) return 'กำลังนับผู้รับ…';
  const already = audience.alreadyEmailed > 0 ? ` · ส่งไปแล้ว ${audience.alreadyEmailed} คน จะไม่ส่งซ้ำ` : '';
  const missing = audience.withoutEmail > 0 ? ` · ไม่มีอีเมล ${audience.withoutEmail} คน จะไม่ได้รับ` : '';
  return `จะส่งอีเมลถึง ${audience.pendingEmail} คน${already}${missing} — ส่งแล้วยกเลิกไม่ได้`;
}

/**
 * CEO/HR control for one release: publish the bell notice, and — as a separate, deliberate step — email it. Each
 * opens a confirm that first counts how many people it will reach (POST /api/release-notes/audience, which writes
 * nothing). Both endpoints are idempotent server-side, so a double click or a second visit cannot double-send.
 * Visibility here is a UI hint; ReleaseNoticeService is the gate.
 */
export function ReleaseNoticeAdmin({ release, showToast }) {
  const queryClient = useQueryClient();
  const [action, setAction] = useState(null);
  const payload = publishPayload(release);

  const audienceQuery = useQuery({
    queryKey: queryKeys.releaseNoteAudience(release.id, action),
    queryFn: () => api.releaseNotes.audience(payload),
    enabled: action != null,
    staleTime: 0,
    gcTime: 0,
  });
  const audience = audienceQuery.data ?? null;

  const publishMutation = useMutation({
    mutationFn: () => api.releaseNotes.publish(payload),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
      showToast?.('success', `เผยแพร่แล้ว แจ้งเตือน ${result?.inserted ?? 0} คน`);
      setAction(null);
    },
    onError: (error) => showToast?.('error', error?.message || 'เผยแพร่ไม่สำเร็จ'),
  });

  const emailMutation = useMutation({
    mutationFn: () => api.releaseNotes.email(payload),
    onSuccess: (result) => {
      showToast?.('success', `ส่งอีเมลแล้ว ${result?.sent ?? 0} คน`);
      setAction(null);
    },
    onError: (error) => showToast?.('error', error?.message || 'ส่งอีเมลไม่สำเร็จ'),
  });

  const isPublish = action === 'publish';
  const pendingCount = audience ? (isPublish ? audience.pendingNotification : audience.pendingEmail) : 0;
  const busy = publishMutation.isPending || emailMutation.isPending;

  return (
    <section
      aria-label="เผยแพร่ประกาศ (HR / CEO)"
      className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-dashed border-border-strong bg-surface px-4 py-3"
    >
      <p className="m-0 min-w-0 flex-1 basis-64 text-sm text-text-muted">
        <strong className="text-text">สำหรับ HR / CEO</strong> · เผยแพร่ขึ้นกระดิ่งของพนักงานตามแผนก
        ส่วนอีเมลส่งแยกเมื่อพร้อม ทั้งสองอย่างกดซ้ำได้โดยไม่ส่งซ้ำ
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" className={PRESS} onClick={() => setAction('publish')}>
          <Icon name="bell" size={16} />
          เผยแพร่ประกาศ
        </Button>
        <Button type="button" variant="secondary" className={PRESS} onClick={() => setAction('email')}>
          <Icon name="mail" size={16} />
          ส่งอีเมล
        </Button>
      </div>

      <ConfirmDialog
        open={action != null}
        title={isPublish ? 'เผยแพร่ประกาศนี้?' : 'ส่งอีเมลประกาศนี้?'}
        message={audienceQuery.isError
          ? 'นับผู้รับไม่สำเร็จ ลองใหม่อีกครั้ง'
          : (isPublish ? publishMessage(audience) : emailMessage(audience))}
        confirmLabel={isPublish ? 'เผยแพร่' : 'ส่งอีเมล'}
        busy={busy}
        confirmDisabled={!audience || pendingCount === 0}
        onConfirm={() => (isPublish ? publishMutation.mutate() : emailMutation.mutate())}
        onCancel={() => setAction(null)}
      />
    </section>
  );
}
