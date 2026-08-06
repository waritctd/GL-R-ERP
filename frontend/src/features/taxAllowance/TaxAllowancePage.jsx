import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { buildAllowanceSubmitBody, defaultAllowanceValues, UNCATEGORIZED_EVIDENCE_KEY } from './taxAllowanceSchema.js';
import { selectCurrentDeclaration, taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

// Editable directly (or via "แก้ไข / ยื่นฉบับใหม่"): no declaration yet, or the current one was
// rejected/expired. PENDING and both APPROVED variants stay permanently read-only here — a direct
// resubmission would collide with `submitMyTaxAllowanceDeclaration`'s "already pending" 409, and an
// approved-and-applied declaration is superseded through HR's flow, not a silent employee edit.
// PENDING's way out is withdrawal, not editing — see the withdraw action below.
const EDITABLE_STATUS_KEYS = new Set(['NONE', 'REJECTED', 'EXPIRED']);

export function TaxAllowancePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const currentYear = new Date().getFullYear();
  // Past years are viewable but never editable: submitting always targets the year in the body,
  // and there is no product story for filing a fresh ล.ย.01 against a closed tax year.
  const [taxYear, setTaxYear] = useState(currentYear);
  const yearOptions = useMemo(
    () => [currentYear, currentYear - 1, currentYear - 2],
    [currentYear],
  );
  const isCurrentYear = taxYear === currentYear;
  const [editing, setEditing] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);

  // Staged (not-yet-uploaded) evidence, keyed by TAX_ALLOWANCE_GROUPS' `key` -- the null key holds
  // step-1's "general/uncategorized" bucket. See TaxAllowanceEvidencePanel's own javadoc for why
  // this exists: while `editing` there is no declarationId a real upload could attach to yet (a
  // brand-new declaration doesn't exist server-side until submit; a REJECTED/EXPIRED one being
  // re-prepared has only an OLD, no-longer-current declarationId), so files picked mid-fill-in are
  // held here and actually uploaded once submit succeeds and a real declarationId exists
  // (see `flushStagedEvidence` below) -- this is the fix for "I couldn't attach a PDF while first
  // filling in the form".
  const [stagedEvidence, setStagedEvidence] = useState({});
  const stagedEvidenceKey = (sectionKey) => sectionKey ?? UNCATEGORIZED_EVIDENCE_KEY;

  function stageEvidence(sectionKey, file) {
    const key = stagedEvidenceKey(sectionKey);
    const tempId = `staged-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setStagedEvidence((prev) => ({
      ...prev,
      [key]: [...(prev[key] ?? []), { tempId, file, fileName: file.name, fileSize: file.size }],
    }));
  }

  function unstageEvidence(sectionKey, tempId) {
    const key = stagedEvidenceKey(sectionKey);
    setStagedEvidence((prev) => ({
      ...prev,
      [key]: (prev[key] ?? []).filter((item) => item.tempId !== tempId),
    }));
  }

  const capsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceCaps(taxYear),
    queryFn: () => api.payroll.getTaxAllowanceCaps(taxYear).then((response) => response.caps || []),
  });
  const caps = capsQuery.data ?? [];

  const declarationsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear),
    queryFn: () => api.payroll.getMyTaxAllowanceDeclarations(taxYear).then((response) => response.items || []),
    enabled: !!user?.employeeId,
  });
  const current = useMemo(() => selectCurrentDeclaration(declarationsQuery.data ?? []), [declarationsQuery.data]);
  const statusInfo = useMemo(() => taxAllowanceStatusInfo(current), [current]);
  const canStartEdit = EDITABLE_STATUS_KEYS.has(statusInfo.key) && isCurrentYear;

  // Three-way evidence mode (#tax-allowance-sections) -- see TaxAllowanceEvidencePanel's own
  // javadoc for what each does. `editing` is never true while `current.status === 'PENDING'`
  // (PENDING is outside EDITABLE_STATUS_KEYS), so these two branches are mutually exclusive with
  // the pre-existing `canEdit={current?.status === 'PENDING'}` behaviour this generalizes.
  const evidenceMode = editing ? 'staging' : (current?.status === 'PENDING' ? 'direct' : 'readonly');

  useEffect(() => {
    setEditing(statusInfo.key === 'NONE' && isCurrentYear);
    // A different declaration (or none) is now current -- any staged-but-unsent evidence belonged
    // to whatever was being edited a moment ago; carrying it forward across an identity change
    // would attach it to the wrong declaration once flushed.
    setStagedEvidence({});
  }, [statusInfo.key, current?.declarationId, isCurrentYear]);

  const defaultValues = useMemo(() => defaultAllowanceValues(current), [current]);

  const submitMutation = useMutation({
    mutationFn: (body) => api.payroll.submitMyTaxAllowanceDeclaration(body),
    onSuccess: async (created) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยื่นแบบแจ้งเรียบร้อย รอ HR ตรวจสอบ');
      setEditing(false);
      await flushStagedEvidence(created.declarationId);
    },
    onError: (error) => showToast?.('error', error.message || 'ยื่นแบบแจ้งไม่สำเร็จ'),
  });

  // Sends every staged file (see the `stagedEvidence` state's own comment above) against the
  // declarationId the submit above JUST created -- the earliest point a real one exists. Runs
  // sequentially rather than Promise.all so one failure does not abort files already in flight, and
  // reports a single summary toast rather than one per file. The declaration itself is ALREADY
  // submitted by the time this runs (submitMutation's onSuccess already fired) -- an attachment
  // failure here is reported separately and never rolls back or blocks the submission.
  async function flushStagedEvidence(declarationId) {
    const entries = Object.entries(stagedEvidence).flatMap(([key, files]) =>
      files.map((item) => ({ sectionKey: key === UNCATEGORIZED_EVIDENCE_KEY ? null : key, item })));
    if (entries.length === 0) return;
    let failureCount = 0;
    for (const { sectionKey, item } of entries) {
      try {
        // Sequential by design, see the comment above -- no-await-in-loop isn't enabled in this
        // project's ESLint config, so no disable directive is needed here.
        await api.payroll.uploadTaxAllowanceAttachment(declarationId, item.file, sectionKey ?? undefined);
      } catch {
        failureCount += 1;
      }
    }
    setStagedEvidence({});
    queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
    if (failureCount > 0) {
      showToast?.('error', `แนบหลักฐานไม่สำเร็จ ${failureCount} ไฟล์ — ยื่นแบบแจ้งสำเร็จแล้ว กรุณาแนบไฟล์ที่เหลือใหม่อีกครั้ง`);
    } else {
      showToast?.('success', 'แนบหลักฐานที่เตรียมไว้เรียบร้อย');
    }
  }

  // The endpoint has been live since issue #387 (DELETE /declarations/{id}) but nothing called it,
  // so a PENDING declaration was a dead end: read-only here, and only HR rejecting it could
  // release the employee. Ownership is enforced server-side — a foreign id 404s rather than 403s
  // (TaxAllowanceDeclarationService#withdrawOwn), covered by
  // TaxAllowanceDeclarationScopeIntegrationTest#employeeCannotWithdrawAnotherEmployeesDeclaration...
  const withdrawMutation = useMutation({
    mutationFn: (id) => api.payroll.withdrawMyTaxAllowanceDeclaration(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยกเลิกการยื่นแล้ว แก้ไขและยื่นใหม่ได้');
      setWithdrawing(false);
      setEditing(true);
    },
    onError: (error) => {
      showToast?.('error', error.message || 'ยกเลิกการยื่นไม่สำเร็จ');
      setWithdrawing(false);
    },
  });

  function handleSubmit(values) {
    // No `employeeId` field, ever — the server resolves the caller from the session (decision
    // in issue #387's endpoint table: "no employeeId field exists on the body").
    const body = buildAllowanceSubmitBody(values, { taxYear, effectiveMonth: values.effectiveMonth });
    submitMutation.mutate(body);
  }

  return (
    <PageStack>
      <PageHeader
        title="ค่าลดหย่อนภาษี (แบบ ล.ย.01)"
        subtitle="แจ้งรายการเพื่อการหักลดหย่อนภาษี พร้อมแนบสำเนาหลักฐานแสดงสิทธิ"
        actions={(
          // Year + status share the header's action slot rather than each claiming a box of their
          // own; `flex-wrap` so they stack instead of overflowing at 360px.
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="ปีภาษี"
              value={taxYear}
              onChange={(event) => setTaxYear(Number(event.target.value))}
            >
              {yearOptions.map((year) => (
                <option key={year} value={year}>ปีภาษี {year}</option>
              ))}
            </select>
            <StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>
          </div>
        )}
      />

      {statusInfo.key === 'REJECTED' && statusInfo.note ? (
        <Panel className="border-danger-border bg-danger-bg">
          <p className="m-0 text-sm font-bold text-danger">เหตุผลที่ปฏิเสธ: {statusInfo.note}</p>
        </Panel>
      ) : null}

      {!isCurrentYear ? (
        <p className="m-0 text-sm text-text-muted">
          กำลังดูข้อมูลย้อนหลังของปีภาษี {taxYear} — ยื่นหรือแก้ไขได้เฉพาะปีภาษี {currentYear}
        </p>
      ) : null}

      {/* PENDING is read-only, and until now that was the end of the road: no edit, no cancel, no
          stated next step. The withdrawal is offered with the reason attached rather than as a
          bare button, so "why can't I fix this?" and "here is how" arrive together. */}
      {statusInfo.key === 'PENDING' && isCurrentYear ? (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="m-0 text-sm text-text-muted">
            แก้ไขไม่ได้ระหว่างรอ HR ตรวจสอบ — ยกเลิกการยื่นเพื่อแก้ไขแล้วยื่นใหม่
          </p>
          <Button
            type="button"
            variant="danger"
            className="max-[720px]:w-full"
            onClick={() => setWithdrawing(true)}
          >
            ยกเลิกการยื่น
          </Button>
        </div>
      ) : null}

      {!editing && canStartEdit ? (
        <div className="flex justify-end max-[720px]:block">
          <Button
            type="button"
            variant="secondary"
            className="max-[720px]:w-full"
            onClick={() => setEditing(true)}
          >
            แก้ไข / ยื่นฉบับใหม่
          </Button>
        </div>
      ) : null}

      <Panel title="แบบแจ้งรายการเพื่อการหักลดหย่อน">
        <TaxAllowanceForm
          caps={caps}
          defaultValues={defaultValues}
          readOnly={!editing}
          submitting={submitMutation.isPending}
          submitLabel={statusInfo.key === 'NONE' ? 'ยื่นแบบแจ้ง' : 'ยื่นฉบับใหม่'}
          onSubmit={handleSubmit}
          evidenceMode={evidenceMode}
          evidenceDeclarationId={current?.declarationId ?? null}
          stagedEvidenceBySection={stagedEvidence}
          onStageEvidence={stageEvidence}
          onUnstageEvidence={unstageEvidence}
          showToast={showToast}
        />
      </Panel>

      <ConfirmDialog
        open={withdrawing}
        title="ยกเลิกการยื่นแบบแจ้ง ล.ย.01"
        message="แบบแจ้งฉบับนี้จะถูกยกเลิก และกลับมาแก้ไขเพื่อยื่นใหม่ได้ หลักฐานที่แนบไว้จะยังอยู่กับฉบับที่ยกเลิก"
        tone="danger"
        confirmLabel="ยกเลิกการยื่น"
        cancelLabel="ไม่ใช่ตอนนี้"
        busy={withdrawMutation.isPending}
        onConfirm={() => withdrawMutation.mutate(current.declarationId)}
        onCancel={() => setWithdrawing(false)}
      />
    </PageStack>
  );
}
