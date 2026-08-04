import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { TaxAllowanceEstimateCard } from './TaxAllowanceEstimateCard.jsx';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';
import { buildAllowanceSubmitBody, defaultAllowanceValues } from './taxAllowanceSchema.js';
import { selectCurrentDeclaration, taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

const ESTIMATE_DEBOUNCE_MS = 600;
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
  const [estimateState, setEstimateState] = useState({ loading: false, error: null, result: null });
  const [withdrawing, setWithdrawing] = useState(false);
  const estimateTimer = useRef(null);

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

  useEffect(() => {
    setEditing(statusInfo.key === 'NONE' && isCurrentYear);
  }, [statusInfo.key, current?.declarationId, isCurrentYear]);

  // Read-only "what this saves me" for a declaration that has already been filed. The estimate
  // endpoint is a pure calculation over a submit-shaped body (no employeeId — the server resolves
  // the caller), so the stored declaration can be replayed through it. Previously the card was
  // mounted only while `editing`, so the number that motivates the whole feature vanished the
  // moment the employee submitted, and never came back on a later visit.
  useEffect(() => {
    if (editing || !current) return undefined;
    let alive = true;
    setEstimateState({ loading: true, error: null, result: null });
    api.payroll
      .estimateMyTaxAllowanceDeclaration(buildAllowanceSubmitBody(defaultAllowanceValues(current), {
        taxYear,
        effectiveMonth: current.effectiveMonth,
      }))
      .then((result) => { if (alive) setEstimateState({ loading: false, error: null, result }); })
      .catch((error) => {
        if (alive) setEstimateState({ loading: false, error: error.message || 'ไม่สามารถประมาณการได้ในขณะนี้', result: null });
      });
    return () => { alive = false; };
  }, [editing, current, taxYear]);

  const defaultValues = useMemo(() => defaultAllowanceValues(current), [current]);

  const submitMutation = useMutation({
    mutationFn: (body) => api.payroll.submitMyTaxAllowanceDeclaration(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยื่นแบบแจ้งเรียบร้อย รอ HR ตรวจสอบ');
      setEditing(false);
    },
    onError: (error) => showToast?.('error', error.message || 'ยื่นแบบแจ้งไม่สำเร็จ'),
  });

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

  function handleValuesChange(values) {
    if (!editing) return;
    if (estimateTimer.current) clearTimeout(estimateTimer.current);
    estimateTimer.current = setTimeout(async () => {
      const body = buildAllowanceSubmitBody(values, { taxYear, effectiveMonth: values?.effectiveMonth });
      setEstimateState((prev) => ({ ...prev, loading: true, error: null }));
      try {
        const result = await api.payroll.estimateMyTaxAllowanceDeclaration(body);
        setEstimateState({ loading: false, error: null, result });
      } catch (error) {
        setEstimateState({ loading: false, error: error.message || 'ไม่สามารถประมาณการได้ในขณะนี้', result: null });
      }
    }, ESTIMATE_DEBOUNCE_MS);
  }

  useEffect(() => () => {
    if (estimateTimer.current) clearTimeout(estimateTimer.current);
  }, []);

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
          onValuesChange={handleValuesChange}
        />
      </Panel>

      {/* Shown whenever there is something to show — while editing (live, debounced) and after
          filing (replayed from the stored declaration), not only during the edit. */}
      {editing || current ? <TaxAllowanceEstimateCard {...estimateState} /> : null}

      <TaxAllowanceEvidencePanel
        declarationId={current?.declarationId ?? null}
        canEdit={current?.status === 'PENDING'}
        showToast={showToast}
      />

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
