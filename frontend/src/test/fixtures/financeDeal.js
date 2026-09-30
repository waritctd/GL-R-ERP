// Test fixture (downloadPath values are opaque server-supplied strings, so none is an /api/ literal): a FinanceDealDto-shaped object (backend FinanceDealDto.java). Every field the page
// reads is present, so a test overrides only what it is about.
const TRACK = [
  { key: 'ORDER_RECEIVED', index: 1, label: 'ได้รับคำสั่งซื้อ' },
  { key: 'DEPOSIT_RECEIVED', index: 2, label: 'ได้รับมัดจำ' },
  { key: 'PROCUREMENT', index: 3, label: 'รอสินค้า / นำเข้า' },
  { key: 'DELIVERY', index: 4, label: 'ส่งมอบ — รอชำระส่วนที่เหลือ' },
  { key: 'CLOSED_PAID', index: 5, label: 'ชำระครบ ปิดงาน' },
];

export function makeTrack(currentIndex, { skipDeposit = false } = {}) {
  return TRACK.map((m) => ({ ...m, skipped: skipDeposit && m.index === 2, current: m.index === currentIndex }));
}

export function makeFinanceDeal(overrides = {}) {
  const base = {
    id: 501,
    code: 'PR-2026-0501',
    title: 'ปูกระเบื้องคอนโดสุขุมวิท',
    salesStage: 'DELIVERY_SCHEDULING',
    lifecycle: 'ACTIVE',
    status: 'quotation_issued',
    moneyMilestone: { key: 'DELIVERY', index: 4, label: 'ส่งมอบ — รอชำระส่วนที่เหลือ', skipped: false, current: true },
    milestoneTrack: makeTrack(4),
    customerName: 'บริษัท สุขุมวิท พร็อพเพอร์ตี้ จำกัด',
    customerId: 9,
    projectName: 'คอนโด สุขุมวิท 31',
    contactName: 'คุณสมชาย ใจดี',
    items: [
      { description: 'PADANA 60x60 ผิวเงา', qty: 120, unit: 'ตร.ม.', unitPrice: 850, lineTotal: 102000, vatBasis: 'EXCLUDING_VAT' },
      { description: 'PADANA 30x60 ผิวด้าน', qty: 40, unit: 'ตร.ม.', unitPrice: null, lineTotal: null, vatBasis: 'EXCLUDING_VAT' },
    ],
    money: {
      amountPayable: 120000, amountPaid: 40000, amountOutstanding: 80000,
      depositPolicy: 'REQUIRED', paymentStatus: 'AWAITING_FINAL_PAYMENT', paymentStage: 'BALANCE_PENDING',
      fulfillmentStatus: 'GOODS_RECEIVED', paymentDueDate: '2026-10-15', paymentDueBasis: 'CREDIT_FROM_DELIVERY', paymentDueCreditDays: 30, overdue: false, closeConfirmedAt: null,
      invoiceOnFile: false, commissionRecorded: false, amountVatBasis: 'INCLUDING_VAT',
      payments: [
        { id: 1, kind: 'DEPOSIT', amount: 40000, currency: 'THB', receivedAt: '2026-09-01T03:00:00Z', receiptRef: 'RC-001', note: null, depositNoticeId: 7, recordedByName: 'บัญชี ทดสอบ' },
      ],
    },
    documents: {
      acceptedQuotation: {
        id: 31, number: 'QT-2026-0031', status: 'ACCEPTED', totalAmount: 120000, vatBasis: 'EXCLUDING_VAT',
        currency: 'THB', issuedAt: '2026-08-20T03:00:00Z', acceptedAt: '2026-08-25T03:00:00Z',
        downloadPath: '/downloads/quotation-31.pdf',
      },
      depositNotices: [
        { id: 7, docNumber: 'DN-2026-0007', version: 2, status: 'ISSUED', issueDate: '2026-08-26', depositAmount: 40000, totalPayable: 42800, totalPayableVatBasis: 'INCLUDING_VAT', downloadPath: '/downloads/deposit-notice-7' },
      ],
      remainingInvoices: [
        { id: 12, docNumber: 'RI-2026-0012', version: 1, status: 'ISSUED', docDate: '2026-10-01', grandTotal: 85600, grandTotalVatBasis: 'INCLUDING_VAT', downloadPath: '/downloads/remaining-invoice-12' },
      ],
      taxInvoices: [],
      billingNotes: [],
      purchaseOrders: [
        { id: 61, fileName: 'PO-สุขุมวิท.pdf', attachType: 'PO', uploadedAt: '2026-08-27T03:00:00Z', fileSize: 20480, downloadPath: '/downloads/attachment-61' },
      ],
      contracts: [],
    },
    comments: [
      { id: 1, authorName: 'สมหญิง ขายดี', createdAt: '2026-09-05T03:00:00Z', message: 'ลูกค้าขอชำระเป็นงวด' },
    ],
    availableActions: [],
  };
  return { ...base, ...overrides };
}
