import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProcurementDetailPage } from './ProcurementDetailPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UI-level only — proves this component's own rendering/mutation wiring against a hand-rolled
// mock, NOT server-side authorization or the guard semantics themselves. Those are covered by
// ProcurementServiceIntegrationTest (backend, real Postgres).
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      procurement: {
        get: vi.fn(),
        recordSupplierProforma: vi.fn(),
        recordShippingDetail: vi.fn(),
        recordGoodsReceived: vi.fn(),
        cancel: vi.fn(),
      },
    },
  };
});

function openPo() {
  return {
    id: 1, poNumber: 'FPO-2026-0001', pricingRequestId: 10, pricingRequestCode: 'PCR-2026-0010',
    ticketId: 20, ticketCode: 'TKT-0020', factoryId: null, factoryName: 'Factory A', status: 'OPEN',
    supplierProformaRef: null, supplierPaymentScheduleNote: null, currency: 'THB', totalAmount: 1000,
    etd: null, eta: null, containerRef: null, customsStatus: null, actualLandedCostThb: null,
    cancelReason: null, createdBy: 1, createdByName: 'Import คนหนึ่ง',
    createdAt: '2026-07-21T00:00:00Z', updatedAt: '2026-07-21T00:00:00Z', receivedAt: null, cancelledAt: null,
    items: [{
      id: 1, factoryPurchaseOrderId: 1, pricingCostingItemId: 100, pricingRequestItemId: 100,
      brand: 'SCG', model: 'Tile A', productDescription: 'กระเบื้อง SCG รุ่น A', quantity: 10, unitPrice: 100,
      currency: 'THB', lineTotal: 1000, estimatedLandedCostPerUnitThb: 120, estimatedTotalLandedCostThb: 1200,
    }],
  };
}

function receivedPo() {
  return { ...openPo(), status: 'RECEIVED', actualLandedCostThb: 1250, receivedAt: '2026-07-22T00:00:00Z' };
}

function renderPage(id = '1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/factory-purchase-orders/${id}`]}>
        <Routes>
          <Route path="/factory-purchase-orders/:id" element={<ProcurementDetailPage showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProcurementDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the PO header and its frozen item lines', async () => {
    api.procurement.get.mockResolvedValue({ factoryPurchaseOrder: openPo() });
    renderPage();
    await screen.findByText('FPO-2026-0001');
    expect(screen.getByText('Factory A')).not.toBeNull();
    expect(screen.getByText('กระเบื้อง SCG รุ่น A')).not.toBeNull();
  });

  it('records a supplier proforma reference and payment schedule note', async () => {
    api.procurement.get.mockResolvedValue({ factoryPurchaseOrder: openPo() });
    api.procurement.recordSupplierProforma.mockResolvedValue({ factoryPurchaseOrder: openPo() });
    renderPage();
    await screen.findByText('FPO-2026-0001');

    fireEvent.change(screen.getByLabelText('เลขที่ Proforma Invoice'), { target: { value: 'PI-2026-0001' } });
    fireEvent.change(screen.getByLabelText('เงื่อนไขการชำระเงิน'), { target: { value: '30% deposit' } });
    fireEvent.click(screen.getAllByRole('button', { name: /บันทึก/ })[0]);

    await waitFor(() => expect(api.procurement.recordSupplierProforma).toHaveBeenCalledWith(1, {
      supplierProformaRef: 'PI-2026-0001',
      supplierPaymentScheduleNote: '30% deposit',
    }));
  });

  it('records actual landed cost on goods received', async () => {
    api.procurement.get.mockResolvedValue({ factoryPurchaseOrder: openPo() });
    api.procurement.recordGoodsReceived.mockResolvedValue({ factoryPurchaseOrder: receivedPo() });
    renderPage();
    await screen.findByText('FPO-2026-0001');

    fireEvent.change(screen.getByLabelText('ต้นทุนนำเข้าจริง (บาท)'), { target: { value: '1250' } });
    fireEvent.click(screen.getByRole('button', { name: 'รับสินค้าแล้ว' }));

    await waitFor(() => expect(api.procurement.recordGoodsReceived).toHaveBeenCalledWith(1, { actualLandedCostThb: 1250 }));
  });

  it('requires a reason before cancel is enabled, then calls cancel with it', async () => {
    api.procurement.get.mockResolvedValue({ factoryPurchaseOrder: openPo() });
    api.procurement.cancel.mockResolvedValue({ factoryPurchaseOrder: { ...openPo(), status: 'CANCELLED', cancelReason: 'ลูกค้ายกเลิก' } });
    renderPage();
    await screen.findByText('FPO-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิกใบสั่งซื้อ' }));
    const confirmButton = screen.getByRole('button', { name: 'ยืนยันยกเลิก' });
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('เหตุผลการยกเลิก'), { target: { value: 'ลูกค้ายกเลิก' } });
    expect(confirmButton.disabled).toBe(false);
    fireEvent.click(confirmButton);

    await waitFor(() => expect(api.procurement.cancel).toHaveBeenCalledWith(1, { reason: 'ลูกค้ายกเลิก' }));
  });

  it('hides every mutation form once the PO is closed (RECEIVED)', async () => {
    api.procurement.get.mockResolvedValue({ factoryPurchaseOrder: receivedPo() });
    renderPage();
    await screen.findByText('FPO-2026-0001');

    expect(screen.queryByLabelText('เลขที่ Proforma Invoice')).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยกเลิกใบสั่งซื้อ' })).toBeNull();
  });
});
