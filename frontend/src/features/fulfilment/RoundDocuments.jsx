import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { invalidateAfterRoundChange } from './useRoundUndo.jsx';
import { DOC_KINDS, docKindLabel } from './shipmentRounds.js';

/** Add or edit one document: a number, a file, or both (a document needs one of them). */
function DocumentModal({ round, doc, onClose, showToast }) {
  const queryClient = useQueryClient();
  const fileInput = useRef(null);
  const [kind, setKind] = useState(doc?.kind ?? 'PROFORMA');
  const [docNo, setDocNo] = useState(doc?.docNo ?? '');
  const [docDate, setDocDate] = useState(doc?.docDate ?? '');
  const [note, setNote] = useState(doc?.note ?? '');
  const [file, setFile] = useState(null);
  const done = (message) => { invalidateAfterRoundChange(queryClient, round); showToast?.('success', message); onClose(); };
  const fail = (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ');
  const save = useMutation({
    mutationFn: async () => {
      if (!doc) return api.shipmentRounds.addDocument(round.id, { kind, docNo: docNo.trim(), docDate, note: note.trim(), file });
      await api.shipmentRounds.updateDocument(round.id, doc.id, { kind, docNo: docNo.trim() || null, docDate: docDate || null, note: note.trim() || null });
      return file ? api.shipmentRounds.attachFile(round.id, doc.id, file) : null;
    },
    onSuccess: () => done(doc ? 'บันทึกเอกสารแล้ว' : 'เพิ่มเอกสารแล้ว'),
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: () => api.shipmentRounds.deleteDocument(round.id, doc.id),
    onSuccess: () => done('ลบเอกสารแล้ว'),
    onError: fail,
  });
  const removeFile = useMutation({
    mutationFn: () => api.shipmentRounds.removeFile(round.id, doc.id),
    onSuccess: () => done('ลบไฟล์แล้ว'),
    onError: fail,
  });
  const empty = !docNo.trim() && !file && !doc?.fileName;
  return (
    <Modal
      title={doc ? 'แก้ไขเอกสาร' : 'เพิ่มเอกสาร'}
      onClose={onClose}
      placement="sheet-on-phone"
      testId="round-document-modal"
      footer={(
        <>
          {doc ? <Button type="button" variant="secondary" disabled={remove.isPending} onClick={() => remove.mutate()}>ลบเอกสาร</Button> : null}
          <Button type="button" variant="primary" disabled={save.isPending || empty} data-testid="round-document-save" onClick={() => save.mutate()}>บันทึก</Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label htmlFor="doc-kind" className="text-xs font-bold text-text-secondary">ประเภทเอกสาร</label>
          <select id="doc-kind" className="min-h-11 w-full text-base" value={kind} onChange={(e) => setKind(e.target.value)}>
            {DOC_KINDS.map((k) => <option key={k.code} value={k.code}>{k.label}</option>)}
          </select>
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="doc-no" className="text-xs font-bold text-text-secondary">เลขที่เอกสาร</label>
          <input id="doc-no" type="text" maxLength={100} className="min-h-11 w-full text-base" value={docNo} onChange={(e) => setDocNo(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="doc-date" className="text-xs font-bold text-text-secondary">ลงวันที่</label>
          <ThaiDateInput id="doc-date" className="min-h-11 text-base" value={docDate} onChange={(e) => setDocDate(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="doc-note" className="text-xs font-bold text-text-secondary">หมายเหตุ (เช่น PO ที่เกี่ยวข้อง)</label>
          <input id="doc-note" type="text" maxLength={255} className="min-h-11 w-full text-base" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <input ref={fileInput} type="file" accept="application/pdf,image/*" className="sr-only" tabIndex={-1} aria-label="เลือกไฟล์เอกสาร"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <Button type="button" variant="secondary" onClick={() => fileInput.current?.click()} data-testid="document-attach">
            {file || doc?.fileName ? 'เปลี่ยนไฟล์' : 'แนบไฟล์'}
          </Button>
          <span className="min-w-0 text-xs text-text-muted [overflow-wrap:anywhere]">{file?.name ?? doc?.fileName ?? 'แนบไฟล์ได้ ไม่บังคับ (ถ่ายรูปหรือเลือกไฟล์ PDF)'}</span>
          {doc?.fileName && doc.docNo ? <Button type="button" size="sm" variant="text" disabled={removeFile.isPending} onClick={() => removeFile.mutate()}>ลบไฟล์</Button> : null}
        </div>
        <p className="m-0 text-xs text-text-muted">ต้องมีเลขที่เอกสารหรือไฟล์อย่างน้อยหนึ่งอย่าง</p>
      </div>
    </Modal>
  );
}

/** เอกสาร: ONE compact list (proforma and invoice first, the rest under them) and a single เพิ่มเอกสาร button. A round may
 * carry several of any kind; a file is never required. */
export function RoundDocuments({ round, editable, showToast }) {
  const [editing, setEditing] = useState(null); // null | 'new' | a document
  const portal = round.source === 'PORTAL';
  async function download(doc) {
    try {
      const blob = await api.shipmentRounds.downloadFile(round.id, doc.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = doc.fileName; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
    } catch (e) { showToast?.('error', e?.message ?? 'ดาวน์โหลดไม่สำเร็จ'); }
  }
  return (
    <div className="flex flex-col" data-testid="round-documents">
      {round.documents.length === 0 ? <p className="m-0 py-2 text-sm text-text-muted">ยังไม่มีเอกสาร</p> : null}
      {round.documents.map((d) => (
        <div key={d.id} className="flex min-h-11 flex-wrap items-center justify-between gap-2 border-t border-border-subtle py-2 first:border-t-0" data-testid={`doc-${d.kind}`}>
          <span className="flex min-w-0 flex-col">
            <b className="text-sm">{docKindLabel(d.kind)} · {d.docNo ? <code className="text-xs">{d.docNo}</code> : <span className="font-normal text-text-muted">-</span>}</b>
            <small className="text-text-muted [overflow-wrap:anywhere]">
              {[d.docDate ? `ลงวันที่ ${formatThaiDate(d.docDate)}` : null, d.note, d.fileName].filter(Boolean).join(' · ') || ' '}
            </small>
          </span>
          {editable ? (
            <span className="flex flex-wrap items-center gap-2">
              {d.fileName ? <Button type="button" size="sm" variant="secondary" onClick={() => download(d)}>ดาวน์โหลด</Button> : null}
              <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(d)} data-testid={`doc-edit-${d.id}`}>แก้ไข</Button>
            </span>
          ) : null}
        </div>
      ))}
      {editable ? (
        <Button type="button" variant="secondary" className="mt-2 w-full sm:w-auto" onClick={() => setEditing('new')} data-testid="doc-add">เพิ่มเอกสาร</Button>
      ) : null}
      <p className="m-0 pt-2 text-xs text-text-muted">
        {portal ? 'ต้องมีเลขที่ Proforma และ Invoice อย่างน้อยอย่างละ 1 ฉบับก่อนถึงโกดัง · ' : ''}
        แนบไฟล์ได้ ไม่บังคับ · รอบหนึ่งมีได้หลายฉบับ และ Proforma ฉบับเดียวใช้กับหลายรอบได้
      </p>
      {editing ? <DocumentModal round={round} doc={editing === 'new' ? null : editing} showToast={showToast} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}
