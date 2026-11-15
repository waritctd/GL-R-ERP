import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { downloadBlob } from '../../utils/download.js';
import { formatBangkokTime, formatThaiDate } from '../../utils/format.js';
import { baht } from '../salesBoard/boardFormat.js';
import { UnmatchedReceipts } from '../salesBoard/UnmatchedReceipts.jsx';

import { queryKeys } from '../../api/queryKeys.js';

export const receiptImportLatestKey = () => queryKeys.receiptImportLatest();

const AMOUNT = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const amount = (v) => AMOUNT.format(Number(v ?? 0));

/** "พฤ. 13 พ.ย. 2569 09:12 · file · 42 รายการ · คุณบุษบา" — the m-upload / a-fin-land last-upload line. */
export function latestLine(latest) {
  if (!latest) return null;
  return [`${formatThaiDate(latest.uploadedAt)} ${formatBangkokTime(latest.uploadedAt)}`.trim(), latest.fileName,
    `${latest.totalRows} รายการ`, latest.uploadedByName].filter(Boolean).join(' · ');
}

const CHIP = 'whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-bold';
const LINK_BUTTON = 'inline-flex min-h-[38px] items-center justify-center rounded-md px-3.5 text-sm font-bold no-underline';

/**
 * The account landing's top card (spec §7.11 item 5, design a-fin-land): the last Express upload and how many rows
 * still wait, with the way in to upload and to match. Renders nothing if the summary cannot load (the worklist below
 * still works without it).
 */
export function ExpressUploadCard() {
  const query = useQuery({ queryKey: receiptImportLatestKey(), queryFn: () => api.receiptImports.latest() });
  // Hidden while the owner has not switched the upload on (app.receipts.express-upload.enabled, review H4).
  if (query.isError || !query.data || !query.data.uploadEnabled) return null;
  const { latest, openUnmatched } = query.data;
  return (
    <section
      aria-labelledby="express-upload-card"
      className="flex flex-wrap items-center gap-4 rounded-md border border-border bg-surface p-5 mobile:p-3.5"
    >
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-primary-bg text-primary" aria-hidden="true">
        <Icon name="upload" size={20} />
      </span>
      <div className="grid min-w-0 flex-1 basis-[260px] gap-1.5">
        <h2 id="express-upload-card" className="m-0 text-lg">อัปโหลดไฟล์รับเงินจาก Express</h2>
        <p className="m-0 text-sm text-text-muted [overflow-wrap:anywhere]">
          {latest ? `ล่าสุด ${latestLine(latest)}` : 'ยังไม่เคยอัปโหลด — รับเงินจะขึ้นเองเมื่อจับคู่ได้'}
        </p>
        {latest ? (
          <div className="flex flex-wrap gap-1.5">
            <span className={`${CHIP} bg-success-bg text-success`}>จับคู่แล้ว {latest.matched} · {baht(latest.matchedAmount)}</span>
            <span className={`${CHIP} bg-surface-subtle text-text-secondary`}>ซ้ำ (เคยนำเข้าแล้ว) {latest.duplicates}</span>
            <span className={`${CHIP} bg-warning-bg text-warning-dark`}>ยังจับคู่ไม่ได้ {openUnmatched}</span>
          </div>
        ) : null}
      </div>
      <div className="flex flex-col gap-2 mobile:w-full mobile:flex-row">
        <Link to="/finance/receipts-import" className={`${LINK_BUTTON} bg-primary text-surface mobile:flex-1`}>อัปโหลดไฟล์</Link>
        {openUnmatched > 0 ? (
          <Link to="/finance/receipts-import" className={`${LINK_BUTTON} border border-border-input bg-surface text-text mobile:flex-1`}>
            จับคู่ {openUnmatched} รายการ
          </Link>
        ) : null}
      </div>
    </section>
  );
}

function OutcomeTable({ result }) {
  const rows = [
    { label: 'จับคู่แล้ว', tone: 'text-success', count: result.matched, sum: result.matchedAmount, next: 'บันทึกเป็นรับเงินของดีลแล้ว' },
    { label: 'ตรงกับรายการที่บันทึกเอง', tone: 'text-success', count: result.matchedManual ?? 0, sum: null, next: 'ผูกกับรายการที่บันทึกเองแล้ว ไม่นับซ้ำ' },
    { label: 'ซ้ำ (เคยนำเข้าแล้ว)', tone: 'text-text', count: result.duplicates, sum: result.duplicateAmount, next: 'ไม่นับซ้ำ' },
    { label: 'ยังจับคู่ไม่ได้', tone: 'text-warning-dark', count: result.unmatched, sum: result.unmatchedAmount, next: 'จับคู่เองด้านล่าง' },
    { label: 'ไม่ใช่ใบเสร็จรับเงิน', tone: 'text-text-muted', count: result.nonCash ?? 0, sum: null, next: 'เช่น ใบแจ้งหนี้ (IV) ไม่นับเป็นเงินเข้า' },
  ];
  if (result.invalid > 0) {
    rows.push({ label: 'อ่านไม่ได้', tone: 'text-danger', count: result.invalid, sum: null, next: 'แก้ไฟล์แล้วอัปโหลดใหม่ได้' });
  }
  if (result.failed > 0) {
    rows.push({ label: 'บันทึกไม่สำเร็จ', tone: 'text-danger', count: result.failed, sum: null, next: 'อัปโหลดไฟล์เดิมซ้ำได้ — รายการที่บันทึกแล้วจะไม่นับซ้ำ' });
  }
  return (
    <div className="grid gap-2">
      <div className="overflow-x-auto">
        <table aria-label="ผลการอัปโหลด" className="w-full border-collapse text-sm tabular-nums">
          <thead>
            <tr className="text-left text-xs text-text-muted">
              <th scope="col" className="py-1.5 pr-3 font-bold">ผล</th>
              <th scope="col" className="py-1.5 pr-3 text-right font-bold">รายการ</th>
              <th scope="col" className="py-1.5 pr-3 text-right font-bold">ยอด</th>
              <th scope="col" className="py-1.5 font-bold mobile:hidden">ทำอะไรต่อ</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label} className="border-t border-border">
                <td className={`py-2 pr-3 font-bold ${r.tone}`}>{r.label}</td>
                <td className="py-2 pr-3 text-right">{r.count}</td>
                <td className="py-2 pr-3 text-right">{r.sum == null ? '—' : amount(r.sum)}</td>
                <td className="py-2 text-text-secondary mobile:hidden">{r.next}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result.errors?.length ? (
        <ul className="m-0 grid list-none gap-1 p-0 text-sm text-danger">
          {result.errors.map((e) => <li key={e.line}>บรรทัด {e.line}: {e.message}</li>)}
        </ul>
      ) : null}
    </div>
  );
}

/**
 * อัปโหลดไฟล์รับเงินจาก Express (spec 2026-11-15 §7.8, design m-upload): account / ceo upload the receipts export;
 * the server matches each row (invoice no → quotation no → customer + amount), writes what it can as Express receipts,
 * counts re-uploaded rows once, and queues the rest below for a one-tap match. Reads the documented column template —
 * no real Express export has been seen yet, so the template download is the format reference.
 */
export function ReceiptsImportPage({ showToast = () => {} }) {
  const ids = useId();
  const queryClient = useQueryClient();
  const [file, setFile] = useState(null);
  const [result, setResult] = useState(null);
  const latestQuery = useQuery({ queryKey: receiptImportLatestKey(), queryFn: () => api.receiptImports.latest() });
  const upload = useMutation({
    mutationFn: (f) => api.receiptImports.upload(f),
    onSuccess: (summary) => {
      setResult(summary);
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: ['finance'] });
    },
  });
  const latest = latestQuery.data?.latest ?? null;
  const enabled = latestQuery.data?.uploadEnabled === true;

  async function submit() {
    if (!file) return;
    try {
      await upload.mutateAsync(file);
      showToast('success', 'อ่านไฟล์แล้ว');
    } catch (error) {
      showToast('error', error?.message || 'อัปโหลดไม่สำเร็จ');
    }
  }

  async function template() {
    try {
      downloadBlob(await api.receiptImports.template(), 'express-receipts-template.csv');
    } catch (error) {
      showToast('error', error?.message || 'ดาวน์โหลดแม่แบบไม่สำเร็จ');
    }
  }

  return (
    <PageStack>
      <PageHeader
        title="อัปโหลดไฟล์รับเงินจาก Express"
        subtitle="ไฟล์ส่งออก RE / AI / IV · อัปโหลดซ้ำได้ ระบบนับรายการละครั้งเดียว"
      />

      {latestQuery.data && !enabled ? (
        <Panel>
          <p className="m-0 font-bold text-text">ยังไม่เปิดใช้การอัปโหลดไฟล์ Express</p>
          <p className="m-0 mt-1 text-sm text-text-muted">
            ยังไม่เคยเห็นไฟล์ส่งออกจริงจาก Express — เปิดใช้หลังตรวจไฟล์ตัวอย่างแล้ว ระหว่างนี้บันทึกรับชำระที่หน้าดีลตามเดิม
          </p>
        </Panel>
      ) : null}
      {enabled ? (
      <Panel>
        <div className="grid gap-4">
          <div className="grid justify-items-center gap-2 rounded-md border-2 border-dashed border-border-input bg-surface-muted px-4 py-6 text-center">
            <Icon name="upload" size={22} />
            <label htmlFor={`${ids}-file`} className="font-bold text-text">เลือกไฟล์</label>
            <input
              id={`${ids}-file`}
              type="file"
              accept=".xlsx,.csv"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }}
              className="max-w-full text-sm"
            />
            <span className="text-xs text-text-muted">
              .xlsx หรือ .csv ·{' '}
              <button type="button" className="cursor-pointer border-0 bg-transparent p-0 font-bold text-primary underline" onClick={template}>
                ดาวน์โหลดแม่แบบ
              </button>
            </span>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm text-text-muted">
              {file ? file.name : latest ? `ล่าสุด ${latestLine(latest)}` : 'ยังไม่เคยอัปโหลด'}
            </span>
            <Button type="button" disabled={!file || upload.isPending} onClick={submit}>
              {upload.isPending ? 'กำลังอ่านไฟล์…' : 'อัปโหลดไฟล์'}
            </Button>
          </div>
          {result ? <OutcomeTable result={result} /> : null}
          <p className="m-0 text-xs text-text-muted">จับคู่จากเลขใบแจ้งหนี้ก่อน แล้วเลขใบเสนอราคา แล้วลูกค้า + ยอดเงิน</p>
        </div>
      </Panel>
      ) : null}

      <UnmatchedReceipts showToast={showToast} />
    </PageStack>
  );
}
