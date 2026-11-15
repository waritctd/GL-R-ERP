import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { cn } from '../../utils/cn.js';

const NUMBER = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });

// One badge per server action (PipelineImportDtos). Text carries the meaning; colour only reinforces it.
const ACTION = {
  CREATE: { label: '✓ สร้าง', className: 'bg-success-bg text-success-dark' },
  UPDATE: { label: '↻ อัปเดต', className: 'bg-info-bg text-info-dark' },
  UNCHANGED: { label: '= ไม่เปลี่ยน', className: 'bg-surface-subtle text-text-muted' },
  SKIP: { label: '! ข้าม', className: 'bg-warning-bg text-warning-dark' },
};

const STEPS = ['อัปโหลด', 'ตรวจก่อนนำเข้า', 'นำเข้า'];

function Steps({ current }) {
  return (
    <ol className="m-0 mb-4 flex list-none flex-wrap items-center gap-2 p-0 text-xs font-bold text-text-muted" aria-label="ขั้นตอน">
      {STEPS.map((label, i) => (
        <li key={label} className="flex items-center gap-2" aria-current={i === current ? 'step' : undefined}>
          {i > 0 ? <span aria-hidden="true" className="h-px w-5 bg-border" /> : null}
          <span
            className={cn(
              'grid size-5 place-items-center rounded-full text-2xs',
              i < current && 'bg-primary text-surface',
              i === current && 'bg-primary-bg text-primary',
              i > current && 'bg-surface-subtle',
            )}
          >
            {i < current ? '✓' : i + 1}
          </span>
          <span className={i === current ? 'text-text' : undefined}>{label}</span>
        </li>
      ))}
    </ol>
  );
}

/** The per-row note: the server's issues for that row, then what an UPDATE fills, then the matched deal. */
function rowNote(plan, issues) {
  const parts = issues.filter((i) => i.row === plan.row).map((i) => i.message);
  if (plan.action === 'UPDATE' && plan.changes?.length) parts.push(`เพิ่ม ${plan.changes.join(', ')}`);
  if ((plan.action === 'UPDATE' || plan.action === 'UNCHANGED') && plan.ticketCode) parts.push(`ตรงกับ ${plan.ticketCode}`);
  return parts;
}

function Chip({ tone, children }) {
  return <span className={cn('rounded-full px-2.5 py-0.5 text-xs font-bold', tone)}>{children}</span>;
}

/**
 * นำเข้า pipeline จากชีท (spec 2026-11-15 §7.9, mock `m-pipeline`): upload the template → dry run (POST
 * /api/sales/pipeline-imports?dryRun=true, writes nothing) → นำเข้า (dryRun=false, the whole file in one
 * transaction). The commit re-sends the SAME file; the server re-analyses it rather than trusting this screen.
 */
export function PipelineImportDialog({ onClose }) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [done, setDone] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const step = done ? 2 : preview ? 1 : 0;

  async function send(dryRun) {
    setBusy(true);
    setError('');
    try {
      const result = await api.salesBoard.pipelineImport(file, dryRun);
      if (dryRun) {
        setPreview(result);
      } else {
        setDone(result);
        queryClient.invalidateQueries({ queryKey: ['tickets', 'salesBoard'] });
      }
    } catch (e) {
      setError(e.message || 'นำเข้าไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }

  const writable = preview ? preview.toCreate + preview.toUpdate : 0;

  let footer;
  if (done) {
    footer = <Button variant="primary" onClick={onClose}>ปิด</Button>;
  } else if (preview) {
    footer = (
      <>
        <Button variant="secondary" onClick={() => { setPreview(null); setError(''); }} disabled={busy}>กลับไปแก้ไฟล์</Button>
        <Button variant="primary" onClick={() => send(false)} disabled={busy || writable === 0}>
          {`นำเข้า ${writable} แถว`}
        </Button>
      </>
    );
  } else {
    footer = (
      <>
        <Button variant="secondary" onClick={onClose}>ยกเลิก</Button>
        <Button variant="primary" onClick={() => send(true)} disabled={busy || !file}>ตรวจก่อนนำเข้า</Button>
      </>
    );
  }

  return (
    <Modal
      title="นำเข้า pipeline จากชีท"
      subtitle="ใช้ครั้งเดียวตอนเริ่มระบบ เพื่อให้ดีลจริงของเซลส์อยู่ในแอป"
      onClose={onClose}
      footer={footer}
      testId="pipeline-import-dialog"
    >
      <Steps current={step} />

      {error ? (
        <p role="alert" className="m-0 mb-3 rounded-md bg-danger-bg px-3 py-2 text-sm font-bold text-danger">{error}</p>
      ) : null}

      {step === 0 ? (
        <div className="grid gap-3">
          <FileUploadField
            aria-label="ไฟล์ pipeline"
            accept=".xlsx,.csv"
            onChange={(e) => { setFile(e.target.files?.[0] ?? null); setError(''); }}
            helperText="ไฟล์ .xlsx หรือ .csv (UTF-8) จากแม่แบบ ไม่เกิน 5 MB · 2,000 แถว"
          />
          <a
            href={api.salesBoard.pipelineImportTemplateUrl()}
            download
            className="justify-self-start text-sm font-bold text-primary underline-offset-2 hover:underline"
          >
            ดาวน์โหลดแม่แบบ
          </a>
        </div>
      ) : null}

      {step === 1 ? (
        <div className="grid gap-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2 text-sm">
            <span className="min-w-0 break-words">{`${file?.name ?? 'ไฟล์'} · ${preview.rows} แถว`}</span>
            <a href={api.salesBoard.pipelineImportTemplateUrl()} download className="text-sm font-bold text-primary">ดาวน์โหลดแม่แบบ</a>
          </div>
          <table className="w-full table-fixed border-collapse text-sm">
            <thead>
              <tr className="text-left text-xs text-text-muted">
                <th scope="col" className="w-[92px] py-2 pr-2 font-bold mobile:w-[78px]">ผล</th>
                <th scope="col" className="py-2 pr-2 font-bold">โครงการ</th>
                <th scope="col" className="py-2 pr-2 font-bold mobile:hidden">ผู้ออกแบบ</th>
                <th scope="col" className="w-9 py-2 pr-2 font-bold">S</th>
                <th scope="col" className="w-[92px] py-2 pr-2 text-right font-bold mobile:hidden">มูลค่า</th>
                <th scope="col" className="py-2 font-bold">หมายเหตุ</th>
              </tr>
            </thead>
            <tbody>
              {preview.plan.map((p) => {
                const action = ACTION[p.action] ?? ACTION.SKIP;
                const notes = rowNote(p, preview.issues);
                return (
                  <tr key={p.row} className="border-t border-border-subtle align-top">
                    <td className="py-2 pr-2">
                      <span className={cn('inline-block whitespace-nowrap rounded-full px-2 py-px text-2xs font-bold', action.className)}>
                        {action.label}
                      </span>
                    </td>
                    <td className="break-words py-2 pr-2 font-bold text-text">{p.project || '(ไม่มีชื่อโครงการ)'}</td>
                    <td className="break-words py-2 pr-2 text-text-muted mobile:hidden">{p.designer || '—'}</td>
                    <td className="py-2 pr-2 tabular-nums">{p.repCode || '—'}</td>
                    <td className="py-2 pr-2 text-right tabular-nums mobile:hidden">
                      {p.value == null ? '—' : NUMBER.format(Number(p.value))}
                    </td>
                    <td className="break-words py-2 text-xs text-text-muted">
                      {notes.map((n) => <span key={n} className="block">{n}</span>)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="flex flex-wrap items-center gap-2">
            <Chip tone="bg-success-bg text-success-dark">{`สร้าง ${preview.toCreate}`}</Chip>
            <Chip tone="bg-info-bg text-info-dark">{`อัปเดต ${preview.toUpdate}`}</Chip>
            {preview.unchanged > 0 ? <Chip tone="bg-surface-subtle text-text-muted">{`ไม่เปลี่ยน ${preview.unchanged}`}</Chip> : null}
            <Chip tone="bg-warning-bg text-warning-dark">{`ข้าม ${preview.skipped}`}</Chip>
            <span className="text-xs text-text-muted">ยังไม่มีอะไรถูกบันทึกจนกว่าจะกดนำเข้า</span>
          </div>
        </div>
      ) : null}

      {step === 2 ? (
        <div className="grid gap-1.5" role="status">
          <strong className="text-lg text-text">นำเข้าแล้ว</strong>
          <span className="text-sm text-text-muted">
            {`สร้างดีลใหม่ ${done.toCreate} · อัปเดต ${done.toUpdate}`}
            {done.skipped > 0 ? ` · ข้าม ${done.skipped} แถว (แก้ในไฟล์แล้วนำเข้าซ้ำได้ ไม่สร้างดีลซ้ำ)` : ''}
          </span>
        </div>
      ) : null}
    </Modal>
  );
}
