import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { FactoryProgressBar } from './FactoryProgressBar.jsx';
import { importStepIndex } from './importSteps.js';

const ARRIVED_TH_INDEX = importStepIndex('CUSTOMS_CLEARANCE'); // S16 = "ถึงไทยแล้ว"

// Per-factory import progress (S12–S17) shown INSIDE the deal — everyone who can open the ticket
// sees it (the data is price-free); import/ceo also get the "advance step" control. Renders
// nothing until the deal actually has factories being tracked, so it never clutters a deal that
// has not reached the import stage.
// `embedded` renders just the rollup chip + bars (no outer Panel), for hosting inside another
// panel's section (DealFulfilmentPanel's "นำเข้าสินค้า" step). Default renders its own Panel.
export function FactoryImportProgressPanel({ ticketId, user, showToast, embedded = false, onSummary }) {
  const queryClient = useQueryClient();
  const editable = user.role === 'import' || user.role === 'ceo';

  const rowsQuery = useQuery({
    queryKey: queryKeys.importProgressForTicket(ticketId),
    queryFn: () => api.importProgress.listForTicket(ticketId).then((r) => r.items ?? []),
    enabled: ticketId != null,
    // Don't churn the deal page: a background refetch on window focus (or a retry storm against a
    // stray endpoint) forces re-renders that can fight the ticket page's scroll/sticky chrome.
    refetchOnWindowFocus: false,
    retry: false,
    staleTime: 30_000,
  });

  const advance = useMutation({
    mutationFn: ({ row, targetStep }) => api.importProgress.advanceStep(row.id, { targetStep }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.importProgressForTicket(ticketId) });
      // The deal's fulfilment (and its delivery gate) rolls up from the per-factory steps —
      // refresh the ticket AND its available actions so "รับครบทุกโรงงาน" unlocks ส่งมอบสินค้า
      // (the delivery button reads ticketActions, not ticketDetail) without a separate click.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      showToast?.('success', 'อัปเดตสถานะรายโรงงานแล้ว');
    },
    onError: (e) => showToast?.('error', e?.message ?? 'อัปเดตไม่สำเร็จ'),
  });

  const [orderEmail, setOrderEmail] = useState(null); // { factoryName, subject, body } | null
  const generateEmail = useMutation({
    mutationFn: (row) => api.importProgress.generateOrderEmail(row.pricingRequestId, { factoryName: row.factoryName }),
    onSuccess: (res) => setOrderEmail(res?.template ?? null),
    onError: (e) => showToast?.('error', e?.message ?? 'สร้างอีเมลไม่สำเร็จ'),
  });

  const copyBody = async () => {
    try {
      await navigator.clipboard.writeText(orderEmail?.body ?? '');
      showToast?.('success', 'คัดลอกข้อความอีเมลแล้ว');
    } catch {
      showToast?.('error', 'คัดลอกไม่สำเร็จ — เลือกข้อความแล้วคัดลอกเอง');
    }
  };

  const rows = rowsQuery.data ?? [];
  const hasRows = rows.length > 0;
  const allReceived = hasRows && rows.every((r) => r.importStep === 'RECEIVED');
  useEffect(() => {
    onSummary?.({ hasRows, allReceived });
  }, [hasRows, allReceived, onSummary]);
  if (!rowsQuery.isLoading && rows.length === 0) {
    return null;
  }
  const arrived = rows.filter((r) => importStepIndex(r.importStep) >= ARRIVED_TH_INDEX).length;
  const rollupChip = rows.length ? (
    <span className="rounded-full bg-info-bg px-2.5 py-0.5 text-2xs font-bold text-info">
      ถึงไทย {arrived}/{rows.length} โรงงาน
    </span>
  ) : null;
  const bars = rowsQuery.isLoading ? (
    <SkeletonText lines={4} />
  ) : (
    <div className="flex flex-col gap-2">
      {rows.map((row) => (
        <FactoryProgressBar
          key={row.id}
          row={row}
          editable={editable}
          advancing={advance.isPending}
          onAdvance={(r, targetStep) => advance.mutate({ row: r, targetStep })}
          onGenerateEmail={editable ? (r) => generateEmail.mutate(r) : undefined}
        />
      ))}
    </div>
  );

  return (
    <>
      {embedded ? (
        <div className="flex flex-col gap-2">
          {rollupChip ? <div>{rollupChip}</div> : null}
          {bars}
        </div>
      ) : (
        <Panel title="ติดตามนำเข้ารายโรงงาน" actions={rollupChip}>
          {bars}
        </Panel>
      )}

      {orderEmail ? (
        <Modal
          title="อีเมลสั่งซื้อ (ร่าง)"
          subtitle={orderEmail.factoryName}
          onClose={() => setOrderEmail(null)}
          testId="order-email-modal"
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setOrderEmail(null)}>ปิด</Button>
              <Button type="button" variant="primary" onClick={copyBody}>คัดลอกข้อความ</Button>
            </>
          )}
        >
          <div className="grid gap-2">
            <ol className="m-0 list-decimal pl-5 text-xs text-text-muted">
              <li>คัดลอกข้อความด้านล่าง</li>
              <li>วางในอีเมล/ช่องทางที่ใช้ส่งโรงงานเอง แล้วส่ง</li>
              <li>กลับมากดเลื่อนสถานะรายโรงงาน</li>
            </ol>
            <div className="text-xs font-bold text-text-muted">หัวข้อ: {orderEmail.subject}</div>
            <textarea
              className="form-input min-h-64 font-mono text-xs"
              readOnly
              value={orderEmail.body}
            />
          </div>
        </Modal>
      ) : null}
    </>
  );
}
