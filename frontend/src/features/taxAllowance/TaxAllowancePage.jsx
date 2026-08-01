import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
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
const EDITABLE_STATUS_KEYS = new Set(['NONE', 'REJECTED', 'EXPIRED']);

export function TaxAllowancePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const taxYear = new Date().getFullYear();
  const [editing, setEditing] = useState(false);
  const [estimateState, setEstimateState] = useState({ loading: false, error: null, result: null });
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
  const canStartEdit = EDITABLE_STATUS_KEYS.has(statusInfo.key);

  useEffect(() => {
    setEditing(statusInfo.key === 'NONE');
    setEstimateState({ loading: false, error: null, result: null });
  }, [statusInfo.key, current?.declarationId]);

  const defaultValues = useMemo(() => defaultAllowanceValues(current), [current]);

  const submitMutation = useMutation({
    mutationFn: (body) => api.payroll.submitMyTaxAllowanceDeclaration(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยื่นแบบแจ้งค่าลดหย่อนเรียบร้อย รอ HR ตรวจสอบ');
      setEditing(false);
    },
    onError: (error) => showToast?.('error', error.message || 'ยื่นแบบแจ้งไม่สำเร็จ'),
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
        actions={<StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>}
      />

      {statusInfo.key === 'REJECTED' && statusInfo.note ? (
        <Panel className="border-danger-border bg-danger-bg">
          <p className="m-0 text-sm font-bold text-danger">เหตุผลที่ปฏิเสธ: {statusInfo.note}</p>
        </Panel>
      ) : null}

      {!editing && canStartEdit ? (
        <div className="flex justify-end">
          <Button type="button" variant="secondary" onClick={() => setEditing(true)}>
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

      {editing ? <TaxAllowanceEstimateCard {...estimateState} /> : null}

      <TaxAllowanceEvidencePanel
        declarationId={current?.declarationId ?? null}
        canEdit={current?.status === 'PENDING'}
        showToast={showToast}
      />
    </PageStack>
  );
}
