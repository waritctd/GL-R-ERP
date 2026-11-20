/* Tokens only. The ไฟล์ tab's "files by delivery round" (phone design M19): one block per round, newest first, the tax
 * invoice first and then each file with who attached it. The facts are the rounds GET's: it lists exactly the files this
 * viewer may open (the server decides; nothing is filtered here). A file opens through /api/attachments/{id}/file.
 */
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { Skeleton } from '../../../components/common/Skeleton.jsx';
import { formatMoney, formatThaiDate } from '../../../utils/format.js';
import { deliveryRoundsQueryOptions } from './deliveryRoundsQuery.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { attachTypeLabel } from '../dealTrackingMeta.js';
import { fileCaption, fileSizeText, useFileOpener } from './deliveryRound.js';

const DAY_MS = 24 * 60 * 60 * 1000;

function FileRow({ file, openFile, title = file.fileName, caption = fileCaption(file) }) {
  const url = api.attachments.fileUrl(file.attachmentId);
  return (
    <li className="flex">
      <a
        href={url}
        onClick={openFile(url, () => api.attachments.fileBlob(file.attachmentId))}
        target="_blank"
        rel="noreferrer"
        aria-label={`${title} ${caption}`.trim()}
        data-testid={`round-file-${file.attachmentId}`}
        className="flex min-h-14 min-w-0 flex-1 items-center gap-3 px-4 py-2 text-text no-underline"
      >
        <Icon name={file.mimeType?.startsWith('image/') ? 'image' : 'fileText'} size={20} />
        <span className="flex min-w-0 flex-1 flex-col">
          <b className="truncate text-[0.9375rem]">{title}</b>
          <small className="text-xs text-text-muted">{caption}</small>
        </span>
      </a>
    </li>
  );
}

function RoundBlock({ round, now, openFile }) {
  // Only the confirmed invoice's file is headed as the invoice; an unconfirmed or voided one reads as a plain file.
  const invoiceFile = round.files.find((f) => f.attachmentId === round.invoice?.attachmentId) ?? null;
  const others = round.files.filter((f) => f !== invoiceFile);
  const when = round.sentAt ?? round.files[0]?.uploadedAt ?? null;
  const isNew = when != null && now - new Date(when).getTime() < DAY_MS;
  return (
    <section className="flex flex-col gap-2" data-testid={`round-block-${round.roundNo}`}>
      <h3 className="m-0 flex flex-wrap items-baseline gap-x-2 px-1 text-[0.8125rem] font-bold text-text-secondary">
        <span>รอบส่งที่ {round.roundNo}</span>
        {isNew ? <span className="rounded-full bg-primary-bg px-2 text-xs font-semibold text-primary" data-testid="round-new">ใหม่</span> : null}
        <span className="font-normal text-text-muted">{[when ? formatThaiDate(when) : null, `${round.files.length} ไฟล์`].filter(Boolean).join(' · ')}</span>
      </h3>
      <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
        {invoiceFile ? (
          <FileRow
            file={invoiceFile}
            openFile={openFile}
            title={`ใบกำกับภาษี ${round.invoice?.number ?? ''}`.trim()}
            caption={['ผู้ออกใบกำกับแนบ', round.invoice?.total != null ? formatMoney(round.invoice.total) : null].filter(Boolean).join(' · ')}
          />
        ) : null}
        {others.map((file) => <FileRow key={file.attachmentId} file={file} openFile={openFile} />)}
      </ul>
    </section>
  );
}

/** The order's own files (no round): read-only rows, for the all-orders view where the single-order panel is not shown. */
function OwnFiles({ ticketId, openFile }) {
  const query = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
    retry: false,
  });
  const files = (query.data ?? []).filter((a) => a.deliveryRequestId == null);
  if (files.length === 0) return null;
  return (
    <section className="flex flex-col gap-2" data-testid="order-own-files">
      <h3 className="m-0 px-1 text-[0.8125rem] font-bold text-text-secondary">ไฟล์อื่นของงาน <span className="font-normal text-text-muted">{files.length} ไฟล์</span></h3>
      <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
        {files.map((a) => (
          <FileRow
            key={a.id}
            file={{ attachmentId: a.id, mimeType: a.mimeType }}
            openFile={openFile}
            title={a.fileName}
            caption={[attachTypeLabel(a.attachType), fileSizeText(a.fileSize)].filter(Boolean).join(' · ')}
          />
        ))}
      </ul>
    </section>
  );
}

/** `heading`: shown above the rounds (the order's code, on the all-orders view); `withOwnFiles`: also list the order's own files there. Renders nothing for a viewer the rounds read refuses. */
export function RoundFilesSection({ ticketId, heading = null, withOwnFiles = false }) {
  const [openFile, openError] = useFileOpener();
  const query = useQuery({ ...deliveryRoundsQueryOptions(ticketId), retry: false });
  const [now] = useState(() => Date.now());
  if (query.isError && [403, 404].includes(query.error?.status)) return null;
  const rounds = (query.data?.rounds ?? []).filter((r) => r.files.length > 0).sort((a, b) => b.roundNo - a.roundNo);
  return (
    <div className="flex min-w-0 flex-col gap-3" data-testid="round-files-section">
      {heading ? <h2 className="m-0 text-base">{heading}</h2> : null}
      {withOwnFiles ? <OwnFiles ticketId={ticketId} openFile={openFile} /> : null}
      {openError ? <p role="alert" className="m-0 text-sm text-danger" data-testid="open-file-error">{openError}</p> : null}
      {query.isLoading ? (
        <div className="flex flex-col gap-2" aria-busy="true" aria-label="กำลังโหลดไฟล์ของรอบส่ง">
          <Skeleton height={20} width="40%" />
          <Skeleton height={56} />
        </div>
      ) : query.isError ? (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-md border border-danger px-3 py-2">
          <span className="text-sm text-danger">โหลดไฟล์ของรอบส่งไม่สำเร็จ</span>
          <Button type="button" variant="secondary" className="min-h-11 shrink-0" onClick={() => query.refetch()}>ลองใหม่</Button>
        </div>
      ) : rounds.length === 0 ? (
        <p className="m-0 text-sm text-text-muted" data-testid="round-files-empty">ยังไม่มีไฟล์ของรอบส่ง</p>
      ) : rounds.map((round) => <RoundBlock key={round.id} round={round} now={now} openFile={openFile} />)}
    </div>
  );
}
