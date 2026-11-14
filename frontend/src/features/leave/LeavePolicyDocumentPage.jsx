import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { POLICY_PDF_HREF } from './LeavePolicyBar.jsx';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

/** HR/CEO upload surface for the §5 announcement PDF (POST /api/leave/policy-document, issue #744). ── NOBODY
 * HAS EVER UPLOADED ONE ───────────────────────────────────────────── V133 records that rows reach
 * `hr.leave_policy_document` through this endpoint alone, and the endpoint has never had a client. */

// Mirrors LeaveController.MAX_POLICY_DOCUMENT_BYTES. A pre-flight size check is honest to mirror — unlike
// content type, a file's size is not something the client merely asserts — and it saves pushing a large body
// only to be refused. The server still enforces it regardless.
const MAX_POLICY_DOCUMENT_BYTES = 10 * 1024 * 1024;

function formatFileSize(bytes) {
  if (!bytes && bytes !== 0) return '-';
  if (bytes < 1024) return `${bytes} ไบต์`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function LeavePolicyDocumentPage({ showToast }) {
  const queryClient = useQueryClient();
  const fileRef = useRef(null);
  const [file, setFile] = useState(null);
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [fileError, setFileError] = useState('');
  const [dateError, setDateError] = useState('');
  const [formError, setFormError] = useState('');
  // Metadata for the version uploaded in THIS session. The GET returns the file's bytes, not its metadata, so
  // after a reload the page can only know WHETHER a document is current (the HEAD probe) — never its name or
  // effective date.
  const [justUploaded, setJustUploaded] = useState(null);
  // Remount key for FileUploadField. Bumping the key remounts the field and resets both together.
  const [fileFieldKey, setFileFieldKey] = useState(0);

  const availabilityQuery = useQuery({
    queryKey: queryKeys.leavePolicyDocumentAvailable(),
    // Keep `api.leave.policyDocumentAvailable(` contiguous on one line: serverContract.test.js's reachability scan
    // greps component source for exactly that string.
    queryFn: () => api.leave.policyDocumentAvailable(),
  });

  const uploadMutation = useMutation({
    mutationFn: ({ nextFile, nextEffectiveFrom }) => api.leave.uploadPolicyDocument(nextFile, nextEffectiveFrom),
    onSuccess: (response) => {
      setJustUploaded(response?.document ?? null);
      setFile(null);
      setEffectiveFrom('');
      setFormError('');
      if (fileRef.current) fileRef.current.value = '';
      setFileFieldKey((key) => key + 1);
      setFileError('');
      queryClient.invalidateQueries({ queryKey: queryKeys.leavePolicyDocumentAvailable() });
      showToast?.('อัปโหลดเอกสารประกาศแล้ว');
    },
    // Inline rather than a toast: the backend's own Thai message names which rule was broken ("รองรับเฉพาะไฟล์
    // PDF" / "ไฟล์มีขนาดใหญ่เกินไป" / "ไฟล์ว่างเปล่า"), and it belongs beside the field that has to change.
    onError: (error) => setFormError(error?.message || 'อัปโหลดเอกสารประกาศไม่สำเร็จ'),
  });

  function handleFileChange(event) {
    const picked = event.target.files?.[0] || null;
    setFile(picked);
    setFormError('');
    // Size only. There is deliberately no client-side check that the file "is a PDF": the server's own check reads
    // the same client-declared type this could read, so a check here would add no protection while implying a
    // verification nobody performs.
    if (picked && picked.size > MAX_POLICY_DOCUMENT_BYTES) {
      setFileError('ไฟล์มีขนาดใหญ่เกิน 10 MB');
    } else {
      setFileError('');
    }
  }

  function handleSubmit(event) {
    event.preventDefault();
    let hasError = false;
    if (!file) {
      setFileError('กรุณาเลือกไฟล์ PDF');
      hasError = true;
    } else if (file.size > MAX_POLICY_DOCUMENT_BYTES) {
      hasError = true;
    }
    if (!effectiveFrom) {
      setDateError('กรุณาระบุวันที่มีผลบังคับใช้');
      hasError = true;
    } else {
      setDateError('');
    }
    if (hasError) return;
    setFormError('');
    uploadMutation.mutate({ nextFile: file, nextEffectiveFrom: effectiveFrom });
  }

  const isAvailable = availabilityQuery.data === 'available';
  // 'unverified' and 'check-failed' share the retry branch below with a genuine query isError: with no confirmed
  // server answer, "ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย" is a claim this page cannot support.
  const checkFailed = availabilityQuery.isError
    || availabilityQuery.data === 'unverified'
    || availabilityQuery.data === 'check-failed';
  const notYetInForce = justUploaded && justUploaded.effectiveFrom > bangkokTodayIso();

  return (
    <PageStack>
      <PageHeader
        title="ประกาศวันลา (PDF)"
        subtitle="Leave policy announcement"
      />

      <Panel>
        <div className="flex items-start gap-3">
          <Icon name="info" className="mt-[2px] shrink-0 text-icon-muted" />
          <div className="grid gap-2 text-text-secondary leading-normal">
            <p className="m-0">
              {/* Explicit {' '}: JSX strips the newline between an element and the following text line, which would run two
               * Thai SENTENCES together. */}
              หน้านี้ใช้เก็บ<strong>ไฟล์ประกาศฉบับจริงไว้บนเซิร์ฟเวอร์</strong>{' '}
              ระบบจะเก็บทุกฉบับที่อัปโหลดไว้ตามวันที่มีผลบังคับใช้ ไม่มีการเขียนทับฉบับเดิม
              ทำให้ย้อนดูได้ว่าวันใดใช้ประกาศฉบับไหน
            </p>
            {/* The single most important sentence on the page: reversed 2026-08-14. An uploader used to be told nothing
             * changes for employees; now it DOES, once this version's effective date arrives and LeavePolicyBar.jsx's
             * probe can confirm it -- see that file's header comment for the full state table. */}
            <p className="m-0">
              <strong>ไฟล์ที่พนักงานเห็นในหน้า “การลา” จะเปลี่ยนตามฉบับนี้เมื่อถึงวันมีผลบังคับใช้</strong>{' '}
              ลิงก์ในหน้านั้นตรวจสอบกับเซิร์ฟเวอร์ก่อนทุกครั้ง หากพบฉบับปัจจุบันจะแสดงไฟล์ที่อัปโหลดไว้ที่นี่
              หากยังไม่มีฉบับที่ใช้ได้ หรือตรวจสอบกับเซิร์ฟเวอร์ไม่ได้ จะแสดงไฟล์สำรองที่ติดมากับระบบแทน
            </p>
            {/* The link text below used to assert "the file employees see right now" as an unconditional fact -- true only
             * while the reader had no way to prefer an upload. Reworded to describe the bundled copy for what it now is --
             * the fallback -- so it does not contradict the paragraph above. */}
            <p className="m-0">
              ไฟล์สำรองที่ติดมากับระบบ (ใช้เมื่อยังไม่มีฉบับอัปโหลดที่ใช้ได้ หรือตรวจสอบกับเซิร์ฟเวอร์ไม่ได้):{' '}
              <a href={POLICY_PDF_HREF} target="_blank" rel="noopener noreferrer" className="font-bold text-primary">
                ประกาศวันลาฉบับที่ติดมากับระบบ
              </a>
            </p>
          </div>
        </div>
      </Panel>

      <Panel title="เอกสารในระบบตอนนี้">
        {availabilityQuery.isLoading ? (
          <p className="m-0 text-text-muted">กำลังตรวจสอบ...</p>
        ) : checkFailed ? (
          <div className="flex flex-wrap items-center gap-3">
            <p className="m-0 text-text-secondary">ตรวจสอบเอกสารในระบบไม่สำเร็จ</p>
            <Button type="button" variant="secondary" onClick={() => availabilityQuery.refetch()}>ลองใหม่</Button>
          </div>
        ) : isAvailable ? (
          <div className="flex flex-wrap items-center gap-3">
            <Icon name="fileText" size={16} className="shrink-0 text-icon-muted" />
            <p className="m-0 flex-1 text-text-secondary">
              มีเอกสารประกาศเก็บอยู่ในระบบ และเป็นฉบับที่มีผลบังคับใช้อยู่ในขณะนี้
            </p>
            <Button
              type="button"
              variant="secondary"
              onClick={async () => {
                try {
                  const blob = await api.leave.downloadPolicyDocument();
                  const url = URL.createObjectURL(blob);
                  window.open(url, '_blank', 'noopener');
                  // Revoked on the next tick, not immediately: the newly opened tab needs the URL to still resolve when it
                  // starts loading.
                  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
                } catch (error) {
                  showToast?.(error?.message || 'ดาวน์โหลดเอกสารไม่สำเร็จ');
                }
              }}
            >
              <Icon name="fileText" size={15} />
              เปิดเอกสารในระบบ
            </Button>
          </div>
        ) : (
          // The state EVERY environment is in today, so it is phrased as a normal starting point rather than a fault,
          // and it does not tell the reader anything is broken.
          <div className="flex items-start gap-3">
            <Icon name="info" className="mt-[2px] shrink-0 text-icon-muted" />
            <p className="m-0 text-text-secondary leading-normal">
              ยังไม่มีเอกสารประกาศเก็บอยู่ในระบบ — ยังไม่เคยมีการอัปโหลดไฟล์เข้ามาเลย
              พนักงานยังเปิดประกาศได้ตามปกติจากไฟล์ที่ติดมากับระบบ การอัปโหลดที่นี่เป็นการเก็บฉบับจริงไว้เป็นหลักฐาน
            </p>
          </div>
        )}
      </Panel>

      {justUploaded ? (
        <Panel title="อัปโหลดล่าสุดในรอบนี้">
          <dl className="m-0 grid gap-2">
            <div className="flex flex-wrap gap-x-2">
              <dt className="font-bold text-text-secondary">ชื่อไฟล์</dt>
              <dd className="m-0 break-all">{justUploaded.fileName}</dd>
            </div>
            <div className="flex flex-wrap gap-x-2">
              <dt className="font-bold text-text-secondary">ขนาด</dt>
              <dd className="m-0">{formatFileSize(justUploaded.fileSize)}</dd>
            </div>
            <div className="flex flex-wrap gap-x-2">
              <dt className="font-bold text-text-secondary">มีผลบังคับใช้</dt>
              <dd className="m-0">{formatThaiDate(justUploaded.effectiveFrom)}</dd>
            </div>
          </dl>
          {notYetInForce ? (
            // Without this the upload looks like it did nothing: the file is stored, but findCurrent will not select it
            // until its effective date, so the panel above still shows the previous state.
            <p className="m-0 mt-3 rounded-md bg-surface-subtle p-3 text-text-secondary leading-normal">
              ฉบับนี้ตั้งวันที่มีผลไว้<strong>ล่วงหน้า</strong> ระบบจึงเก็บไฟล์ไว้แล้วแต่ยังไม่ถือเป็นฉบับปัจจุบัน
              จนกว่าจะถึงวันที่ {formatThaiDate(justUploaded.effectiveFrom)} — ไม่ใช่ข้อผิดพลาด
            </p>
          ) : null}
        </Panel>
      ) : null}

      <Panel title="อัปโหลดประกาศฉบับใหม่">
        <SafeForm id="leave-policy-upload-form" onSubmit={handleSubmit} className="grid gap-3.5 max-w-[560px]">
          <FormField
            label="ไฟล์ประกาศ"
            htmlFor="leave-policy-file"
            error={fileError}
            // States the RULE the server applies. It deliberately does not say the system checks that the file really is a
            // PDF — the server only reads the type the browser declares.
            hint="ต้องเป็นไฟล์ PDF ขนาดไม่เกิน 10 MB"
            required
          >
            <FileUploadField
              key={fileFieldKey}
              id="leave-policy-file"
              ref={fileRef}
              accept="application/pdf,.pdf"
              onChange={handleFileChange}
            />
          </FormField>

          <FormField
            label="วันที่มีผลบังคับใช้"
            htmlFor="leave-policy-effective-from"
            error={dateError}
            hint="ถ้าตั้งเป็นวันที่ในอนาคต ระบบจะเก็บไฟล์ไว้ก่อน และจะเริ่มใช้ฉบับนี้เมื่อถึงวันดังกล่าว"
            required
          >
            <ThaiDateInput
              id="leave-policy-effective-from"
              value={effectiveFrom}
              onChange={(event) => { setEffectiveFrom(event.target.value); setDateError(''); }}
            />
          </FormField>

          {formError ? (
            <p role="alert" className="m-0 rounded-md border border-danger-border bg-surface p-3 text-xs font-bold text-danger">
              {formError}
            </p>
          ) : null}

          <Button
            type="submit"
            form="leave-policy-upload-form"
            variant="primary"
            className="justify-self-start"
            disabled={uploadMutation.isPending}
          >
            <Icon name="upload" size={15} />
            {uploadMutation.isPending ? 'กำลังอัปโหลด...' : 'อัปโหลดประกาศ'}
          </Button>
        </SafeForm>
      </Panel>
    </PageStack>
  );
}
