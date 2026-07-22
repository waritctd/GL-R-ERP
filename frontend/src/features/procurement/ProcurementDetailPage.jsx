import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { factoryPurchaseOrderStatusLabel, formatMoney, formatThaiDate } from '../../utils/format.js';

// Step 7: Factory Purchase Order and Import Execution — minimal Import/CEO-facing detail view.
// Items are read-only here (they trace to sales.pricing_costing_item, frozen at PO creation —
// never re-picked/re-priced on this page); the only mutations offered are the ones
// ProcurementService actually exposes: supplier proforma, shipping detail, goods received,
// cancel.
export function ProcurementDetailPage({ showToast }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const poId = Number(id);

  const [proformaRef, setProformaRef] = useState('');
  const [proformaNote, setProformaNote] = useState('');
  const [containerRef, setContainerRef] = useState('');
  const [etd, setEtd] = useState('');
  const [eta, setEta] = useState('');
  const [customsStatus, setCustomsStatus] = useState('');
  const [actualLandedCost, setActualLandedCost] = useState('');
  const [cancelReason, setCancelReason] = useState('');
  const [showCancelForm, setShowCancelForm] = useState(false);

  const poQuery = useQuery({
    queryKey: queryKeys.factoryPurchaseOrderDetail(poId),
    queryFn: () => api.procurement.get(poId).then((response) => response.factoryPurchaseOrder),
    enabled: Number.isFinite(poId),
  });
  const po = poQuery.data;
  if (poQuery.error) showToast?.('error', poQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.factoryPurchaseOrderDetail(poId) });
    queryClient.invalidateQueries({ queryKey: ['factoryPurchaseOrders'] });
  }

  const recordProforma = useMutation({
    mutationFn: () => api.procurement.recordSupplierProforma(poId, {
      supplierProformaRef: proformaRef,
      supplierPaymentScheduleNote: proformaNote || null,
    }),
    onSuccess: () => {
      showToast?.('success', 'บันทึกใบแจ้งหนี้ล่วงหน้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });

  const recordShipping = useMutation({
    mutationFn: () => api.procurement.recordShippingDetail(poId, {
      containerRef: containerRef || null,
      etd: etd || null,
      eta: eta || null,
      customsStatus: customsStatus || null,
    }),
    onSuccess: () => {
      showToast?.('success', 'บันทึกรายละเอียดการขนส่งแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });

  const recordGoodsReceived = useMutation({
    mutationFn: () => api.procurement.recordGoodsReceived(poId, {
      actualLandedCostThb: Number(actualLandedCost),
    }),
    onSuccess: () => {
      showToast?.('success', 'บันทึกรับสินค้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });

  const cancelPo = useMutation({
    mutationFn: () => api.procurement.cancel(poId, { reason: cancelReason }),
    onSuccess: () => {
      showToast?.('success', 'ยกเลิกใบสั่งซื้อโรงงานแล้ว');
      setShowCancelForm(false);
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });

  if (poQuery.isLoading || !po) {
    return (
      <div className="page-stack">
        <PageHeader title="ใบสั่งซื้อโรงงาน" subtitle="กำลังโหลด..." />
      </div>
    );
  }

  const status = factoryPurchaseOrderStatusLabel(po.status);
  const closed = po.status === 'RECEIVED' || po.status === 'CANCELLED';

  return (
    <div className="page-stack">
      <PageHeader
        title={po.poNumber}
        subtitle={po.factoryName}
        actions={<StatusBadge tone={status.tone}>{status.label}</StatusBadge>}
      />

      <div className="rounded-lg border border-border bg-surface p-4">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ใบขอราคา</span>
            <Link to={`/pricing-requests/${po.pricingRequestId}`} className="text-sm text-primary">{po.pricingRequestCode}</Link>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ดีล</span>
            <Link to={`/tickets/${po.ticketId}`} className="text-sm text-primary">{po.ticketCode}</Link>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">มูลค่ารวม</span>
            <span className="text-sm font-bold text-text">{formatMoney(po.totalAmount)} {po.currency}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">สร้างโดย</span>
            <span className="text-sm text-text">{po.createdByName} · {formatThaiDate(po.createdAt)}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ใบแจ้งหนี้ล่วงหน้า (Proforma)</span>
            <span className="text-sm text-text">{po.supplierProformaRef || '-'}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">เงื่อนไขชำระเงินผู้ผลิต</span>
            <span className="text-sm text-text">{po.supplierPaymentScheduleNote || '-'}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ตู้/การขนส่ง</span>
            <span className="text-sm text-text">{po.containerRef || '-'}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ETD / ETA</span>
            <span className="text-sm text-text">{formatThaiDate(po.etd)} → {formatThaiDate(po.eta)}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">สถานะศุลกากร</span>
            <span className="text-sm text-text">{po.customsStatus || '-'}</span>
          </div>
          <div>
            <span className="block text-2xs font-bold uppercase text-text-muted">ต้นทุนนำเข้าจริง (บาท)</span>
            <span className="text-sm text-text">{po.actualLandedCostThb != null ? formatMoney(po.actualLandedCostThb) : '-'}</span>
          </div>
        </div>
        {po.cancelReason ? (
          <p className="mt-3 text-sm text-danger">เหตุผลยกเลิก: {po.cancelReason}</p>
        ) : null}
      </div>

      <div className="rounded-lg border border-border bg-surface p-4">
        <h3 className="mb-2 text-sm font-extrabold text-text">รายการสินค้า</h3>
        <p className="mb-3 text-xs text-text-muted">
          รายการและราคาอ้างอิงจากราคาต้นทุนที่ CEO อนุมัติแล้ว (pricing_costing_item) ไม่สามารถแก้ไขในหน้านี้ได้
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-2xs font-bold uppercase text-text-muted">
                <th className="py-1.5 pr-2">สินค้า</th>
                <th className="py-1.5 pr-2 text-right">จำนวน</th>
                <th className="py-1.5 pr-2 text-right">ราคาต่อหน่วย</th>
                <th className="py-1.5 pr-2 text-right">รวม</th>
                <th className="py-1.5 pr-2 text-right">ต้นทุนนำเข้าประมาณการ (บาท)</th>
              </tr>
            </thead>
            <tbody>
              {po.items.map((item) => (
                <tr key={item.id} className="border-b border-border last:border-0">
                  <td className="py-1.5 pr-2">
                    <span className="block font-semibold text-text">{[item.brand, item.model].filter(Boolean).join(' ')}</span>
                    <span className="block text-2xs text-text-muted">{item.productDescription}</span>
                  </td>
                  <td className="py-1.5 pr-2 text-right">{item.quantity}</td>
                  <td className="py-1.5 pr-2 text-right">{formatMoney(item.unitPrice)} {item.currency}</td>
                  <td className="py-1.5 pr-2 text-right font-bold">{formatMoney(item.lineTotal)} {item.currency}</td>
                  <td className="py-1.5 pr-2 text-right text-text-muted">
                    {item.estimatedTotalLandedCostThb != null ? formatMoney(item.estimatedTotalLandedCostThb) : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {!closed ? (
        <>
          <div className="rounded-lg border border-border bg-surface p-4">
            <h3 className="mb-2 text-sm font-extrabold text-text">บันทึกใบแจ้งหนี้ล่วงหน้า (Proforma)</h3>
            <div className="flex flex-wrap gap-2">
              <input
                type="text"
                className="form-input min-w-48 flex-1"
                placeholder="เลขที่ Proforma Invoice"
                value={proformaRef}
                onChange={(e) => setProformaRef(e.target.value)}
                aria-label="เลขที่ Proforma Invoice"
              />
              <input
                type="text"
                className="form-input min-w-48 flex-1"
                placeholder="เงื่อนไขการชำระเงิน (เช่น มัดจำ 30% ก่อนส่งของ)"
                value={proformaNote}
                onChange={(e) => setProformaNote(e.target.value)}
                aria-label="เงื่อนไขการชำระเงิน"
              />
              <button
                type="button"
                className="primary-button"
                disabled={!proformaRef.trim() || recordProforma.isPending}
                onClick={() => recordProforma.mutate()}
              >
                <Icon name="save" /> บันทึก
              </button>
            </div>
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            <h3 className="mb-2 text-sm font-extrabold text-text">บันทึกรายละเอียดการขนส่ง</h3>
            <div className="flex flex-wrap gap-2">
              <input
                type="text"
                className="form-input min-w-40 flex-1"
                placeholder="เลขที่ตู้/การขนส่ง"
                value={containerRef}
                onChange={(e) => setContainerRef(e.target.value)}
                aria-label="เลขที่ตู้/การขนส่ง"
              />
              <input
                type="date"
                className="form-input"
                value={etd}
                onChange={(e) => setEtd(e.target.value)}
                aria-label="ETD"
              />
              <input
                type="date"
                className="form-input"
                value={eta}
                onChange={(e) => setEta(e.target.value)}
                aria-label="ETA"
              />
              <input
                type="text"
                className="form-input min-w-40 flex-1"
                placeholder="สถานะศุลกากร"
                value={customsStatus}
                onChange={(e) => setCustomsStatus(e.target.value)}
                aria-label="สถานะศุลกากร"
              />
              <button
                type="button"
                className="primary-button"
                disabled={recordShipping.isPending}
                onClick={() => recordShipping.mutate()}
              >
                <Icon name="save" /> บันทึก
              </button>
            </div>
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            <h3 className="mb-2 text-sm font-extrabold text-text">บันทึกรับสินค้า (ต้นทุนนำเข้าจริง)</h3>
            <div className="flex flex-wrap gap-2">
              <input
                type="number"
                step="0.01"
                min="0"
                className="form-input min-w-40 flex-1"
                placeholder="ต้นทุนนำเข้าจริง (บาท)"
                value={actualLandedCost}
                onChange={(e) => setActualLandedCost(e.target.value)}
                aria-label="ต้นทุนนำเข้าจริง (บาท)"
              />
              <button
                type="button"
                className="primary-button"
                disabled={actualLandedCost === '' || recordGoodsReceived.isPending}
                onClick={() => recordGoodsReceived.mutate()}
              >
                <Icon name="check" /> รับสินค้าแล้ว
              </button>
            </div>
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            {!showCancelForm ? (
              <button type="button" className="secondary-button" onClick={() => setShowCancelForm(true)}>
                <Icon name="x" /> ยกเลิกใบสั่งซื้อ
              </button>
            ) : (
              <div className="flex flex-wrap gap-2">
                <input
                  type="text"
                  className="form-input min-w-48 flex-1"
                  placeholder="เหตุผลการยกเลิก"
                  value={cancelReason}
                  onChange={(e) => setCancelReason(e.target.value)}
                  aria-label="เหตุผลการยกเลิก"
                />
                <button
                  type="button"
                  className="danger-button"
                  disabled={!cancelReason.trim() || cancelPo.isPending}
                  onClick={() => cancelPo.mutate()}
                >
                  ยืนยันยกเลิก
                </button>
                <button type="button" className="secondary-button" onClick={() => setShowCancelForm(false)}>
                  ยกเลิก
                </button>
              </div>
            )}
          </div>
        </>
      ) : null}

      <button type="button" className="secondary-button" onClick={() => navigate('/factory-purchase-orders')}>
        <Icon name="chevronLeft" /> กลับไปรายการใบสั่งซื้อโรงงาน
      </button>
    </div>
  );
}
