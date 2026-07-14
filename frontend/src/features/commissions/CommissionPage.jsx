import { useCallback, useEffect, useMemo, useState } from 'react';
import imageCompression from 'browser-image-compression';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';

const today = new Date().toISOString().slice(0, 10);
const thisMonth = new Date().toISOString().slice(0, 7);

const emptyForm = {
  salesRepId: '',
  invoiceNumber: '',
  invoiceDate: today,
  grossAmount: '',
  bankFees: '0',
  suspenseVat: '0',
  transportFee: '0',
  cutFee: '0',
  shortfall: '0',
  invoiceAttachment: null,
};

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
    MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    VOID: { label: 'ยกเลิก', tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}

function kindLabel(kind) {
  return kind === 'CLAWBACK' ? 'คืน/ยกเลิก' : 'ขาย';
}

function numberOrNull(value) {
  if (value === '' || value === null || value === undefined) return null;
  return Number(value);
}

export function CommissionPage({ user, showToast }) {
  const [month, setMonth] = useState(thisMonth);
  const [records, setRecords] = useState([]);
  const [summary, setSummary] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [simulation, setSimulation] = useState(null);
  const [editingId, setEditingId] = useState(null);
  const [deductionDraft, setDeductionDraft] = useState({ transportFee: 0, cutFee: 0, shortfall: 0 });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [clawbackId, setClawbackId] = useState(null); // record id pending clawback reason, or null
  const [rejectId, setRejectId] = useState(null);
  const [fileInputKey, setFileInputKey] = useState(0);

  const canSubmit = ROLE_PERMISSIONS.canSubmitCommissions.includes(user.role);
  const canApprove = ROLE_PERMISSIONS.canApproveCommissions.includes(user.role);
  const payrollOnly = ROLE_PERMISSIONS.canViewPayrollCommissions.includes(user.role) && !canSubmit && !canApprove;
  const salesReadOnlyDeductions = user.role === 'sales';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (payrollOnly) {
        const response = await api.commissions.payrollReady({ payrollMonth: month });
        setSummary(response.summary);
        setRecords([]);
      } else {
        const response = await api.commissions.list({ payrollMonth: month });
        setRecords(response.commissions ?? []);
        setSummary(null);
      }
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลค่าคอมไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, [month, payrollOnly, showToast]);

  useEffect(() => { load(); }, [load]);

  const totals = useMemo(() => {
    const base = records.reduce((sum, item) => sum + Number(item.commissionableBase || 0), 0);
    const approved = records.filter((item) => item.status === 'APPROVED').length;
    const submitted = records.filter((item) => item.status === 'SUBMITTED').length;
    const managerApproved = records.filter((item) => item.status === 'MANAGER_APPROVED').length;
    return { base, approved, submitted, managerApproved };
  }, [records]);

  function canManagerReview(record) {
    return record.status === 'SUBMITTED' && user.role === 'sales_manager';
  }

  function canCeoReview(record) {
    return record.status === 'MANAGER_APPROVED' && user.role === 'ceo';
  }

  function canReviewRecord(record) {
    return canManagerReview(record) || canCeoReview(record);
  }

  const commissionColumns = [
    {
      key: 'invoiceNumber',
      header: 'Invoice',
      sortable: true,
      sortAccessor: (record) => record.invoiceDetails.invoiceNumber,
      searchAccessor: (record) => record.invoiceDetails.invoiceNumber,
      render: (record) => (
        <span>
          <strong>{record.invoiceDetails.invoiceNumber}</strong>
          <small className="block text-text-muted">{kindLabel(record.kind)} · {formatThaiDate(record.invoiceDetails.invoiceDate)}</small>
          <small className="block text-text-muted">ไฟล์: {record.invoiceDetails.invoiceAttachmentFileName || '-'}</small>
        </span>
      ),
    },
    {
      key: 'salesRepName',
      header: 'Sales',
      sortable: true,
      sortAccessor: (record) => record.salesRepName || record.salesRepId,
      searchAccessor: (record) => record.salesRepName || record.salesRepId,
      render: (record) => <span>{record.salesRepName || record.salesRepId}</span>,
    },
    {
      key: 'actualReceived',
      header: 'ยอดรับจริง',
      sortable: true,
      sortAccessor: (record) => Number(record.actualReceived || 0),
      render: (record) => <code>{formatMoney(record.actualReceived)}</code>,
    },
    {
      key: 'commissionableBase',
      header: 'ฐานค่าคอม',
      sortable: true,
      sortAccessor: (record) => Number(record.commissionableBase || 0),
      render: (record) => <code>{formatMoney(record.commissionableBase)}</code>,
    },
    {
      key: 'status',
      header: 'สถานะ',
      render: (record) => {
        const status = statusInfo(record.status);
        return (
          <span>
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            <small className="mt-1 block text-text-muted">
              ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}
            </small>
            <small className="block text-text-muted">
              CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}
            </small>
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '',
      render: (record) => (
        <span className="flex flex-wrap justify-end gap-[6px]">
          {canApprove && (
            <>
              <button type="button" className="icon-button" title="แก้ไขค่าหัก" aria-label="แก้ไขค่าหัก" onClick={() => beginEdit(record)}>
                <Icon name="pencil" size={14} />
              </button>
              {canReviewRecord(record) && (
                <button
                  type="button"
                  className="icon-button"
                  title={canCeoReview(record) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ'}
                  aria-label={canCeoReview(record) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ'}
                  disabled={saving}
                  onClick={() => approve(record.id)}
                >
                  <Icon name="check" size={14} />
                </button>
              )}
              {canReviewRecord(record) && (
                <button
                  type="button"
                  className="icon-button"
                  title={canCeoReview(record) ? 'CEO ปฏิเสธ' : 'ผู้จัดการปฏิเสธ'}
                  aria-label={canCeoReview(record) ? 'CEO ปฏิเสธ' : 'ผู้จัดการปฏิเสธ'}
                  disabled={saving}
                  onClick={() => reject(record.id)}
                >
                  <Icon name="close" size={14} />
                </button>
              )}
              {record.kind === 'SALE' && record.status === 'APPROVED' && (
                <button type="button" className="icon-button" title="บันทึกหักคืน" aria-label="บันทึกหักคืน" disabled={saving} onClick={() => clawback(record.id)}>
                  <Icon name="close" size={14} />
                </button>
              )}
            </>
          )}
        </span>
      ),
    },
  ];

  function updateForm(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function payloadFromForm() {
    return {
      salesRepId: form.salesRepId ? Number(form.salesRepId) : null,
      invoiceNumber: form.invoiceNumber.trim(),
      invoiceDate: form.invoiceDate,
      grossAmount: numberOrNull(form.grossAmount),
      bankFees: numberOrNull(form.bankFees) ?? 0,
      suspenseVat: numberOrNull(form.suspenseVat) ?? 0,
      transportFee: salesReadOnlyDeductions ? 0 : (numberOrNull(form.transportFee) ?? 0),
      cutFee: salesReadOnlyDeductions ? 0 : (numberOrNull(form.cutFee) ?? 0),
      shortfall: salesReadOnlyDeductions ? 0 : (numberOrNull(form.shortfall) ?? 0),
    };
  }

  async function prepareInvoiceAttachment(file) {
    if (!file) throw new Error('กรุณาแนบไฟล์ใบกำกับภาษี');
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    }
    if (!file.type.startsWith('image/')) return file;
    return imageCompression(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1600,
      useWebWorker: true,
    });
  }

  async function submitCommission(event) {
    event.preventDefault();
    setSaving(true);
    try {
      const invoiceAttachment = await prepareInvoiceAttachment(form.invoiceAttachment);
      await api.commissions.create({ ...payloadFromForm(), invoiceAttachment });
      setForm(emptyForm);
      setFileInputKey((key) => key + 1);
      setSimulation(null);
      showToast('success', 'บันทึกค่าคอมเรียบร้อย');
      await load();
    } catch (error) {
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function runSimulation() {
    setSaving(true);
    try {
      const response = await api.commissions.simulate({ ...payloadFromForm(), payrollMonth: `${month}-01` });
      setSimulation(response.simulation);
    } catch (error) {
      showToast('error', error.message || 'คำนวณไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function beginEdit(record) {
    setEditingId(record.id);
    setDeductionDraft({
      transportFee: Number(record.invoiceDetails.transportFee || 0),
      cutFee: Number(record.invoiceDetails.cutFee || 0),
      shortfall: Number(record.invoiceDetails.shortfall || 0),
    });
  }

  async function saveDeductions(id) {
    setSaving(true);
    try {
      await api.commissions.updateDeductions(id, deductionDraft);
      setEditingId(null);
      showToast('success', 'อัปเดตค่าหักแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'อัปเดตไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function approve(id) {
    setSaving(true);
    try {
      await api.commissions.approve(id);
      showToast('success', 'อนุมัติค่าคอมแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'อนุมัติไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function reject(id) {
    setRejectId(id);
  }

  async function confirmReject(reviewerNote) {
    setSaving(true);
    try {
      await api.commissions.reject(rejectId, { reviewerNote });
      showToast('success', 'ปฏิเสธค่าคอมแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'ปฏิเสธไม่สำเร็จ');
    } finally {
      setSaving(false);
      setRejectId(null);
    }
  }

  function clawback(id) {
    setClawbackId(id);
  }

  async function confirmClawback(reason) {
    setSaving(true);
    try {
      await api.commissions.clawback(clawbackId, { reason });
      showToast('success', 'บันทึกรายการหักคืนแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'บันทึกหักคืนไม่สำเร็จ');
    } finally {
      setSaving(false);
      setClawbackId(null);
    }
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="ค่าคอมมิชชัน"
        subtitle="Sales & Commission Management"
        actions={(
          <label className="inline-flex items-center gap-2 text-[13px] font-bold">
            รอบเดือน
            <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} className="w-[150px]" />
          </label>
        )}
      />

      {payrollOnly ? (
        <PayrollSummary summary={summary} loading={loading} />
      ) : (
        <>
          <div className="stat-grid">
            <StatCard icon="badge" label="ฐานค่าคอมเดือนนี้" value={formatMoney(totals.base)} helper="Commissionable base" tone="indigo" />
            <StatCard icon="check" label="อนุมัติแล้ว" value={totals.approved} helper="Approved records" tone="teal" />
            <StatCard icon="clock" label="รอผู้จัดการ" value={totals.submitted} helper="Submitted records" tone="amber" />
            <StatCard icon="clock" label="รอ CEO" value={totals.managerApproved} helper="Manager approved" tone="indigo" />
          </div>

          {canSubmit && (
            <section className={cn('panel', 'p-0')}>
              <div className={cn('panel-header', 'px-[18px] py-[14px]')}>
                <h2>บันทึกใบกำกับ / ใบเสร็จ</h2>
              </div>
              <form className={cn('form-grid', 'p-[18px]')} onSubmit={submitCommission}>
                {!salesReadOnlyDeductions && (
                  <label>
                    Sales Rep ID
                    <input value={form.salesRepId} onChange={(event) => updateForm('salesRepId', event.target.value)} placeholder="เว้นว่างเพื่อใช้ผู้บันทึก" />
                  </label>
                )}
                <label>
                  Invoice Number *
                  <input value={form.invoiceNumber} onChange={(event) => updateForm('invoiceNumber', event.target.value)} required />
                </label>
                <label>
                  Tax Invoice File *
                  <input
                    key={fileInputKey}
                    type="file"
                    accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                    onChange={(event) => updateForm('invoiceAttachment', event.target.files?.[0] || null)}
                    required
                  />
                  <small>{form.invoiceAttachment ? form.invoiceAttachment.name : 'PDF, JPG หรือ PNG'}</small>
                </label>
                <label>
                  Invoice Date *
                  <input type="date" value={form.invoiceDate} onChange={(event) => updateForm('invoiceDate', event.target.value)} required />
                </label>
                <label>
                  Gross Amount *
                  <input type="number" min="0" step="0.01" value={form.grossAmount} onChange={(event) => updateForm('grossAmount', event.target.value)} required />
                </label>
                <label>
                  Bank Fees
                  <input type="number" min="0" step="0.01" value={form.bankFees} onChange={(event) => updateForm('bankFees', event.target.value)} />
                </label>
                <label>
                  Suspense VAT
                  <input type="number" min="0" step="0.01" value={form.suspenseVat} onChange={(event) => updateForm('suspenseVat', event.target.value)} />
                </label>
                <label>
                  ค่าขนส่ง
                  <input type="number" min="0" step="0.01" value={form.transportFee} disabled={salesReadOnlyDeductions} onChange={(event) => updateForm('transportFee', event.target.value)} />
                </label>
                <label>
                  ค่าตัด
                  <input type="number" min="0" step="0.01" value={form.cutFee} disabled={salesReadOnlyDeductions} onChange={(event) => updateForm('cutFee', event.target.value)} />
                </label>
                <label>
                  รับเงินขาด
                  <input type="number" min="0" step="0.01" value={form.shortfall} disabled={salesReadOnlyDeductions} onChange={(event) => updateForm('shortfall', event.target.value)} />
                </label>

                {simulation && (
                  <div className={cn('span-2', 'grid grid-cols-[repeat(4,minmax(0,1fr))] gap-[10px]')}>
                    <MiniMetric label="รับเงินจริง" value={formatMoney(simulation.actualReceived)} />
                    <MiniMetric label="ฐานหลังหัก VAT" value={formatMoney(simulation.commissionableBase)} />
                    <MiniMetric label="ฐานรวมเดือน" value={formatMoney(simulation.projectedMonthlyBase)} />
                    <MiniMetric label="ค่าคอมเพิ่ม" value={formatMoney(simulation.incrementalCommission)} />
                  </div>
                )}

                <div className={cn('span-2', 'flex justify-end gap-[10px]')}>
                  <button type="button" className="secondary-button" onClick={runSimulation} disabled={saving || !form.grossAmount}>
                    <Icon name="badge" size={14} />
                    Simulator
                  </button>
                  <button type="submit" className="primary-button" disabled={saving}>
                    <Icon name="check" size={14} />
                    บันทึก
                  </button>
                </div>
              </form>
            </section>
          )}

          <DataTable
            columns={commissionColumns}
            rows={records}
            getRowKey={(record) => record.id}
            gridClassName="commission-table"
            pageSize={20}
            searchable
            searchPlaceholder="ค้นหาเลขที่ใบกำกับ / ชื่อ Sales"
            loading={loading}
            emptyState={{
              icon: 'badge',
              title: 'ยังไม่มีรายการค่าคอม',
              description: 'เลือกรอบเดือนอื่นหรือบันทึกใบเสร็จใหม่',
            }}
          />

          {editingId != null && (() => {
            const editingRecord = records.find((record) => record.id === editingId);
            if (!editingRecord) return null;
            return (
              <div className="commission-row-wrap">
                <div className="grid grid-cols-[repeat(3,minmax(0,1fr))_auto] items-end gap-2 border-b border-border bg-surface-muted px-[18px] py-[10px]">
                  {[
                    ['transportFee', 'ค่าขนส่ง'],
                    ['cutFee', 'ค่าตัด'],
                    ['shortfall', 'รับเงินขาด'],
                  ].map(([key, label]) => (
                    <label key={key} className="m-0 text-xs">
                      {label}
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        value={deductionDraft[key]}
                        onChange={(event) => setDeductionDraft((current) => ({ ...current, [key]: Number(event.target.value) }))}
                        className="mt-1"
                      />
                    </label>
                  ))}
                  <div className="flex gap-[6px]">
                    <button type="button" className="primary-button" disabled={saving} onClick={() => saveDeductions(editingRecord.id)}>บันทึก</button>
                    <button type="button" className="secondary-button" disabled={saving} onClick={() => setEditingId(null)}>ยกเลิก</button>
                  </div>
                </div>
              </div>
            );
          })()}
        </>
      )}

      <ConfirmDialog
        open={clawbackId != null}
        tone="danger"
        title="บันทึกหักคืน"
        message="ยืนยันการยกเลิก/คืนเงินค่าคอมมิชชันรายการนี้?"
        requireReason
        reasonLabel="เหตุผลการยกเลิก/คืนเงิน"
        busy={saving}
        onCancel={() => setClawbackId(null)}
        onConfirm={confirmClawback}
      />
      <ConfirmDialog
        open={rejectId != null}
        tone="danger"
        title="ปฏิเสธค่าคอม"
        message="ยืนยันการปฏิเสธรายการค่าคอมนี้?"
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        busy={saving}
        onCancel={() => setRejectId(null)}
        onConfirm={confirmReject}
      />
    </div>
  );
}

function MiniMetric({ label, value }) {
  return (
    <div className="rounded-md border border-border-subtle bg-surface-muted px-3 py-[10px]">
      <small className="block font-bold text-text-muted">{label}</small>
      <strong className="mt-1 block text-text">{value}</strong>
    </div>
  );
}

function PayrollSummary({ summary, loading }) {
  if (loading) {
    return (
      <div className="stat-grid" aria-busy="true" aria-label="กำลังโหลดสรุปค่าคอมมิชชัน Payroll">
        <SkeletonCard lines={2} />
        <SkeletonCard lines={2} />
        <SkeletonCard lines={2} />
      </div>
    );
  }
  if (!summary) {
    return <EmptyState icon="badge" title="ยังไม่มีข้อมูลค่าคอม" description="เลือกรอบเดือนอื่นเพื่อตรวจสอบ" />;
  }
  return (
    <>
      <div className="stat-grid">
        <StatCard icon="badge" label="ฐานค่าคอมรวม" value={formatMoney(summary.totalCommissionableBase)} helper="Payroll-ready base" tone="indigo" />
        <StatCard icon="check" label="ค่าคอมที่ต้องจ่าย" value={formatMoney(summary.totalCommissionAmount)} helper={summary.status} tone="teal" />
        <StatCard icon="users" label="จำนวน Sales" value={summary.salesReps?.length ?? 0} helper="Sales reps" tone="blue" />
      </div>
      <section className="table-panel">
        <div className="commission-payroll-table table-head">
          <span>Sales Rep</span>
          <span>ฐานค่าคอม</span>
          <span>ค่าคอม</span>
        </div>
        {(summary.salesReps ?? []).length === 0 ? (
          <EmptyState icon="badge" title="ไม่มีรายการอนุมัติ" description="ยังไม่มีค่าคอมที่พร้อมเข้า Payroll ในเดือนนี้" />
        ) : summary.salesReps.map((rep) => (
          <div key={rep.salesRepId} className="commission-payroll-table table-row">
            <strong>{rep.salesRepName || rep.salesRepId}</strong>
            <code>{formatMoney(rep.commissionableBase)}</code>
            <code>{formatMoney(rep.commissionAmount)}</code>
          </div>
        ))}
      </section>
    </>
  );
}
