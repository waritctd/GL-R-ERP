export const API_ROUTES = {
  auth: {
    login: '/api/auth/login',
    logout: '/api/auth/logout',
    me: '/api/auth/me',
    changePassword: '/api/auth/change-password',
  },
  employees: {
    list: '/api/employees',
    create: '/api/employees',
    detail: (id) => `/api/employees/${id}`,
  },
  profileRequests: {
    list: '/api/profile-requests',
    create: '/api/profile-requests',
    detail: (id) => `/api/profile-requests/${id}`,
  },
  attendance: {
    daily: '/api/attendance/daily',
    // Still served, but no longer a top-level view: it backs the per-day drill-down.
    punches: '/api/attendance/punches',
    unmapped: '/api/attendance/unmapped',
    employees: '/api/attendance/employees',
    recalculate: '/api/attendance/daily/recalculate',
    cardsBackfill: '/api/attendance/cards/backfill',
    importDat: '/api/attendance/imports/dat',
    devices: '/api/attendance/devices',
  },
  overtime: {
    list: '/api/overtime',
    create: '/api/overtime',
    employees: '/api/overtime/employees',
    approve: (id) => `/api/overtime/${id}/approve`,
    reject: (id) => `/api/overtime/${id}/reject`,
    cancel: (id) => `/api/overtime/${id}/cancel`,
  },
  specialMoney: {
    list: '/api/special-money',
    create: '/api/special-money',
    employees: '/api/special-money/employees',
    usage: '/api/special-money/usage',
    types: '/api/special-money/types',
    approve: (id) => `/api/special-money/${id}/approve`,
    reject: (id) => `/api/special-money/${id}/reject`,
    cancel: (id) => `/api/special-money/${id}/cancel`,
  },
  leave: {
    list: '/api/leave',
    create: '/api/leave',
    employees: '/api/leave/employees',
    types: '/api/leave/types',
    balances: '/api/leave/balances',
    approve: (id) => `/api/leave/${id}/approve`,
    reject: (id) => `/api/leave/${id}/reject`,
    cancel: (id) => `/api/leave/${id}/cancel`,
  },
  tickets: {
    list: '/api/tickets',
    create: '/api/tickets',
    detail: (id) => `/api/tickets/${id}`,
    payments: (id) => `/api/tickets/${id}/payments`,
    billing: (id) => `/api/tickets/${id}/billing`,
    deliveries: (id) => `/api/tickets/${id}/deliveries`,
    reserveStock: (id) => `/api/tickets/${id}/reserve-stock`,
    completeDelivery: (id) => `/api/tickets/${id}/deliveries/complete`,
    action: (id, action) => `/api/tickets/${id}/${action}`,
    editItems: (id) => `/api/tickets/${id}/items`,
    createDocDraft: (id) => `/api/tickets/${id}/deposit-notice/draft`,
    listDocs: (id) => `/api/tickets/${id}/deposit-notices`,
    revision: (id) => `/api/tickets/${id}/revision`,
    quotationStatus: (id, quotationId, action) => `/api/tickets/${id}/quotations/${quotationId}/${action}`,
    quotationFile: (id, quotationId, fmt) => `/api/tickets/${id}/quotations/${quotationId}/file?format=${fmt ?? 'xlsx'}`,
  },
  depositNotices: {
    get: (id) => `/api/deposit-notices/${id}`,
    update: (id) => `/api/deposit-notices/${id}`,
    preview: (id) => `/api/deposit-notices/${id}/preview`,
    issue: (id) => `/api/deposit-notices/${id}/issue`,
    file: (id, fmt) => `/api/deposit-notices/${id}/file?format=${fmt}`,
    noteTemplates: '/api/document-note-templates',
    remainingInvoiceFile: (ticketId) => `/api/tickets/${ticketId}/remaining-invoice/file`,
  },
  catalog: {
    search: (q) => `/api/catalog${q ? `?q=${encodeURIComponent(q)}` : ''}`,
    prices: (q, factoryId, limit) => {
      const p = new URLSearchParams();
      if (q) p.set('q', q);
      if (factoryId) p.set('factoryId', factoryId);
      if (limit) p.set('limit', limit);
      return `/api/catalog/prices${p.toString() ? `?${p}` : ''}`;
    },
    pricesBase: '/api/catalog/prices',
    price: (priceId) => `/api/catalog/prices/${priceId}`,
  },
  factoryConfigs: {
    list: '/api/factory-configs',
    sendEmail: (ticketId) => `/api/tickets/${ticketId}/factory-emails/send`,
  },
  customers: {
    create: '/api/customers',
    search: (q) => `/api/customers${q ? `?search=${encodeURIComponent(q)}` : ''}`,
    contacts: (customerId) => `/api/customers/${customerId}/contacts`,
    createContact: (customerId) => `/api/customers/${customerId}/contacts`,
    projects: (customerId) => `/api/customers/${customerId}/projects`,
    createProject: (customerId) => `/api/customers/${customerId}/projects`,
  },
  dashboard: {
    summary: '/api/dashboard/summary',
  },
  notifications: {
    list: '/api/notifications',
    markRead: (id) => `/api/notifications/${id}/read`,
  },
  fxRates: {
    list: '/api/fx-rates',
    upsert: (currency) => `/api/fx-rates/${currency}`,
  },
  priceCalcConfigs: {
    list: '/api/price-calc-configs',
    update: '/api/price-calc-configs',
  },
  attachments: {
    list: (ticketId) => `/api/tickets/${ticketId}/attachments`,
    upload: (ticketId) => `/api/tickets/${ticketId}/attachments`,
    file: (id) => `/api/attachments/${id}/file`,
    delete: (id) => `/api/attachments/${id}`,
  },
  commissions: {
    list: '/api/commissions',
    create: '/api/commissions',
    deductions: (id) => `/api/commissions/${id}/deductions`,
    approve: (id) => `/api/commissions/${id}/approve`,
    reject: (id) => `/api/commissions/${id}/reject`,
    clawback: (id) => `/api/commissions/${id}/clawback`,
    simulator: '/api/commissions/simulator',
    payrollReady: '/api/commissions/payroll-ready',
  },
  payroll: {
    current: '/api/payroll',
    preview: '/api/payroll/preview',
    process: '/api/payroll/process',
    bankExport: (periodId) => `/api/payroll/${periodId}/bank-export`,
    payslip: (periodId, lineId) => `/api/payroll/${periodId}/lines/${lineId}/payslip.pdf`,
    ownPayslip: (periodId) => `/api/payroll/${periodId}/payslip/me`,
    distribute: (periodId) => `/api/payroll/${periodId}/distribute`,
    taxAllowances: '/api/payroll/tax-allowances',
    ytdSeed: '/api/payroll/ytd-seed',
  },
  priceImport: {
    factories: '/api/price-import/factories',
    versions: (factoryId) => `/api/price-import/versions?factoryId=${factoryId}`,
    upload: '/api/price-import/upload',
    uploadCommit: '/api/price-import/upload-commit',
    validate: (versionId) => `/api/price-import/validate/${versionId}`,
    staging: (versionId) => `/api/price-import/staging/${versionId}`,
    commit: (versionId) => `/api/price-import/commit/${versionId}`,
    profile: (factoryId) => `/api/price-import/profile/${factoryId}`,
  },
  // Mirrors PricingRequestController: ticket-scoped create/list live under
  // /api/tickets/{ticketId}/..., everything else is its own /api/pricing-requests/... resource.
  pricingRequests: {
    listForTicket: (ticketId) => `/api/tickets/${ticketId}/pricing-requests`,
    create: (ticketId) => `/api/tickets/${ticketId}/pricing-requests`,
    queue: (params = {}) => {
      const p = new URLSearchParams();
      if (params.status) p.set('status', params.status);
      if (params.assignedImportId) p.set('assignedImportId', params.assignedImportId);
      if (params.activeOnly !== undefined) p.set('activeOnly', String(params.activeOnly));
      return `/api/pricing-requests${p.toString() ? `?${p}` : ''}`;
    },
    detail: (id) => `/api/pricing-requests/${id}`,
    factoryEmailDrafts: (id) => `/api/pricing-requests/${id}/factory-email-drafts`,
    factoryQuotes: (id) => `/api/pricing-requests/${id}/factory-quotes`,
    factoryQuote: (id) => `/api/factory-quotes/${id}`,
    factoryQuoteSend: (id) => `/api/factory-quotes/${id}/send`,
    factoryQuoteReceive: (id) => `/api/factory-quotes/${id}/receive`,
    factoryQuoteStartNegotiation: (id) => `/api/factory-quotes/${id}/start-negotiation`,
    factoryQuoteReady: (id) => `/api/factory-quotes/${id}/mark-ready-for-costing`,
    factoryQuoteNotAvailable: (id) => `/api/factory-quotes/${id}/not-available`,
    factoryQuoteAttachments: (id) => `/api/factory-quotes/${id}/attachments`,
    factoryQuoteAttachmentFile: (id) => `/api/factory-quote-attachments/${id}/file`,
    factoryQuoteAttachment: (id) => `/api/factory-quote-attachments/${id}`,
    costings: (id) => `/api/pricing-requests/${id}/costings`,
    costing: (id) => `/api/pricing-costings/${id}`,
    costingRecalculate: (id) => `/api/pricing-costings/${id}/recalculate`,
    costingSubmit: (id) => `/api/pricing-costings/${id}/submit`,
    // Step 3: CEO Selling Price Decision. Mirrors PricingDecisionController.
    pricingDecisions: (id) => `/api/pricing-requests/${id}/pricing-decisions`,
    pricingDecisionSalesView: (id) => `/api/pricing-requests/${id}/pricing-decision/sales-view`,
    pricingDecision: (id) => `/api/pricing-decisions/${id}`,
    pricingDecisionRecalculate: (id) => `/api/pricing-decisions/${id}/recalculate`,
    pricingDecisionApprove: (id) => `/api/pricing-decisions/${id}/approve`,
    pricingDecisionReturnToImport: (id) => `/api/pricing-decisions/${id}/return-to-import`,
    // Step 4: Customer Quotation Generation and Issuance. Mirrors CustomerQuotationController.
    customerQuotations: (id) => `/api/pricing-requests/${id}/quotations`,
    customerQuotation: (id) => `/api/customer-quotations/${id}`,
    customerQuotationPreview: (id) => `/api/customer-quotations/${id}/preview`,
    customerQuotationIssue: (id) => `/api/customer-quotations/${id}/issue`,
    customerQuotationCancel: (id) => `/api/customer-quotations/${id}/cancel`,
    customerQuotationRevisions: (id) => `/api/customer-quotations/${id}/revisions`,
    customerQuotationFile: (id, format) => `/api/customer-quotations/${id}/file?format=${format}`,
    // Step 5: Customer Decision and Commercial Revisions.
    customerQuotationOutcome: (id) => `/api/customer-quotations/${id}/outcome`,
    // Step 6: Deposit, Payment, and Order Confirmation. Mirrors OrderConfirmationController.
    confirmOrder: (id) => `/api/pricing-requests/${id}/confirm-order`,
    depositNoticeFromQuotation: (id) => `/api/pricing-requests/${id}/deposit-notice`,
    submit: (id) => `/api/pricing-requests/${id}/submit`,
    pickup: (id) => `/api/pricing-requests/${id}/pickup`,
    requestInformation: (id) => `/api/pricing-requests/${id}/request-information`,
    respondInformation: (id) => `/api/pricing-requests/${id}/respond-information`,
    customerChangeRevision: (id) => `/api/pricing-requests/${id}/customer-change-revision`,
    cancel: (id) => `/api/pricing-requests/${id}/cancel`,
    // Sales-level supporting attachments on the Pricing Request itself (V69, review remediation
    // COMMIT 4) — distinct from the raw factory-quote attachments above.
    attachments: (id) => `/api/pricing-requests/${id}/attachments`,
    attachmentFile: (id) => `/api/pricing-request-attachments/${id}/file`,
    attachment: (id) => `/api/pricing-request-attachments/${id}`,
    attachmentIncludeInFactoryEmail: (id) => `/api/pricing-request-attachments/${id}/include-in-factory-email`,
  },
  // Step 7: Factory Purchase Order and Import Execution. Mirrors ProcurementController.
  procurement: {
    create: (pricingRequestId) => `/api/pricing-requests/${pricingRequestId}/factory-purchase-orders`,
    listForPricingRequest: (pricingRequestId) => `/api/pricing-requests/${pricingRequestId}/factory-purchase-orders`,
    list: (status) => `/api/factory-purchase-orders${status ? `?status=${encodeURIComponent(status)}` : ''}`,
    detail: (id) => `/api/factory-purchase-orders/${id}`,
    proforma: (id) => `/api/factory-purchase-orders/${id}/proforma`,
    shipping: (id) => `/api/factory-purchase-orders/${id}/shipping`,
    goodsReceived: (id) => `/api/factory-purchase-orders/${id}/goods-received`,
    cancel: (id) => `/api/factory-purchase-orders/${id}/cancel`,
  },
};

export const ROLE_PERMISSIONS = {
  canUseEmployeeExperience: ['employee'],
  canSubmitProfileRequests: ['employee'],
  canViewEmployees: ['hr'],
  canManageEmployees: ['hr'],
  // Salary, salary history, and the "ข้อมูลอ่อนไหว" (sensitive) tab on the
  // employee detail page. Mirrors EmployeeController's
  // requireAnyRole(user, "hr") gate on every employee endpoint — this key
  // does not grant anyone access they don't already have server-side.
  canViewSensitiveEmployeeData: ['hr'],
  canReviewProfileRequests: ['hr'],
  canViewAllAttendance: ['hr', 'ceo'],
  canImportAttendance: ['hr', 'ceo'],
  canViewAllOvertime: ['hr', 'ceo'],
  // Mirrors SpecialMoneyService.VIEW_ALL_ROLES — hr/ceo see every welfare/special-money
  // request; everyone else is scoped to self + managed employees, same shape as overtime.
  canViewAllSpecialMoney: ['hr', 'ceo'],
  canViewAllLeave: ['hr', 'ceo'],
  canReviewLeave: ['hr'],
  // Sales module
  // sales_manager is read+comment oversight ONLY (a project-manager-style
  // follow-up role for the sales team) — it must never be added to
  // canCreateTickets/canPickupTickets/canProposePrices/canApproveReject/
  // canGenerateQuotation/canConfirmPayments. Mirrors TicketService.VIEWER_ROLES.
  canViewTickets: ['sales', 'import', 'ceo', 'account', 'sales_manager'],
  // Money-receipt confirmations (รับยอดมัดจำ / รับชำระเต็มจำนวน) belong to
  // ฝ่ายบัญชี, with CEO as fallback. Mirrors TicketService.ACCOUNT_ROLES.
  canConfirmPayments: ['account', 'ceo'],
  canCreateTickets: ['sales'],
  canPickupTickets: ['import'],
  canProposePrices: ['import'],
  canApproveReject: ['ceo'],
  canGenerateQuotation: ['sales'],
  canViewCommissions: ['sales', 'sales_manager', 'ceo', 'hr'],
  canSubmitCommissions: ['sales', 'sales_manager', 'ceo'],
  canApproveCommissions: ['sales_manager', 'ceo'],
  canViewPayrollCommissions: ['hr'],
  canManagePayroll: ['hr'],
  canManagePriceImport: ['ceo', 'import'],
  canManageCatalogProducts: ['ceo', 'import'],
  // Import's PricingRequest queue (/pricing-requests). Mirrors
  // PricingRequestService.VIEWER_ROLES minus 'sales'/'account' — this key is
  // about who sees the cross-deal QUEUE page, not who may view/act on a single
  // pricing request (sales still sees its own deal's requests via
  // PricingRequestPanel, gated by ticket ownership, not this key).
  canViewPricingRequestQueue: ['import', 'ceo', 'sales_manager'],
  // Step 7: Factory Purchase Order and Import Execution — Import/CEO only, mirrors
  // ProcurementService.RAW_PO_ROLES. Raw supplier PO detail is never shown to sales.
  canManageProcurement: ['import', 'ceo'],
};
