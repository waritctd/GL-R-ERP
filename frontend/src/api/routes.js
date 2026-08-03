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
    markPresent: '/api/attendance/daily/mark-present',
    cardsBackfill: '/api/attendance/cards/backfill',
    importDat: '/api/attendance/imports/dat',
    devices: '/api/attendance/devices',
  },
  holidays: {
    list: '/api/holidays',
    create: '/api/holidays',
    fetch: '/api/holidays/fetch',
    detail: (date) => `/api/holidays/${date}`,
  },
  workSchedules: {
    list: '/api/work-schedules',
  },
  workScheduleAssignments: {
    list: '/api/work-schedule-assignments',
    create: '/api/work-schedule-assignments',
    end: (assignmentId) => `/api/work-schedule-assignments/${assignmentId}/end`,
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
    attachments: (id) => `/api/special-money/${id}/attachments`,
    attachmentDownload: (attachmentId) => `/api/special-money/attachments/${attachmentId}`,
  },
  leave: {
    list: '/api/leave',
    create: '/api/leave',
    employees: '/api/leave/employees',
    types: '/api/leave/types',
    balances: '/api/leave/balances',
    contactDefaults: '/api/leave/contact-defaults',
    approve: (id) => `/api/leave/${id}/approve`,
    reject: (id) => `/api/leave/${id}/reject`,
    cancel: (id) => `/api/leave/${id}/cancel`,
    // Leave-surface IA rebuild, Phase A0 (not yet landed): per-manager summary of requests
    // awaiting THIS user's decision, backing the "รอพิจารณา" tab. Wired into routes/hrApi/mockApi/
    // queryKeys now (Phase A1) only so contract.test.js's method-surface parity check stays green
    // ahead of A0 -- no UI in this phase calls it yet. See mockApi.js's own comment on this route.
    reviewSummary: '/api/leave/review-summary',
    // Leave-request composer (Phase A2, #485): dry-run gate-chain preview, writes nothing --
    // see LeaveService#preview's Javadoc for the FULL vs QUICK depth and nullable-dates contract.
    preview: '/api/leave/preview',
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
    // quotationStatus (mark-sent/accepted/rejected) is retired along with ticket-native
    // quotation generation — see hrApi.js's tickets block. quotationFile (read/download)
    // stays: both legacy and PCR-issued quotations render from it.
    quotationFile: (id, quotationId, fmt) => `/api/tickets/${id}/quotations/${quotationId}/file?format=${fmt ?? 'xlsx'}`,
    // Deal tracking + activity (V83, Slice B1/B2 "kill the weekly report" — handoff 103).
    activities: (id) => `/api/tickets/${id}/activities`,
    tracking: (id) => `/api/tickets/${id}/tracking`,
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
  // BRANCH 1 of the sales pricing-formula redesign (config storage + CEO editing UI only).
  // Mirrors PricingFormulaConfigController -- a NEW endpoint, distinct from priceCalcConfigs
  // above (which keeps serving the separate catalog price calculator, untouched).
  pricingFormulaConfig: {
    get: '/api/pricing-formula-config',
    update: '/api/pricing-formula-config',
  },
  // V112 — deal-create modal's ราคาตั้ง (ประมาณการ) display multiplier. NOT priceCalcConfigs:
  // see DealEstimateMarkupController's javadoc for why this is a separate, openly-readable table.
  dealEstimateMarkup: {
    get: '/api/deal-estimate-markup',
    update: '/api/deal-estimate-markup',
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
    // Slice A2: the accountant's auto-create trigger at deal close. Mirrors
    // CommissionController's POST /api/commissions/from-deal (ACCOUNT-only).
    createFromDeal: '/api/commissions/from-deal',
    deductions: (id) => `/api/commissions/${id}/deductions`,
    approve: (id) => `/api/commissions/${id}/approve`,
    reject: (id) => `/api/commissions/${id}/reject`,
    clawback: (id) => `/api/commissions/${id}/clawback`,
    // Manual commission entries (feat/commission-manual-adjustments). Mirrors
    // CommissionController's POST /api/commissions/manual.
    manual: '/api/commissions/manual',
    simulator: '/api/commissions/simulator',
    payrollReady: '/api/commissions/payroll-ready',
  },
  payroll: {
    current: '/api/payroll',
    // Special-pay carry-forward (2026-07-23): read-only pre-fill suggestions for a brand-new
    // monthly run, sourced from each employee's most-recent prior processed payroll_line. Mirrors
    // PayrollController#suggestedInputs.
    suggestedInputs: '/api/payroll/suggested-inputs',
    preview: '/api/payroll/preview',
    process: '/api/payroll/process',
    export: (periodId, kind, effectiveDate) =>
      `/api/payroll/${periodId}/export/${kind}${effectiveDate ? `?effectiveDate=${effectiveDate}` : ''}`,
    // Preview-time detail xlsx export (2026-07-30): HR must be able to review a month's full detail
    // BEFORE processing it (e.g. a live-but-unprocessed month) -- there is no periodId yet, so this
    // POSTs the same payload /preview does. Only payroll-detail supports this. Mirrors
    // PayrollController#exportPreview.
    exportPreview: (kind, effectiveDate) =>
      `/api/payroll/preview/export/${kind}${effectiveDate ? `?effectiveDate=${effectiveDate}` : ''}`,
    payslip: (periodId, lineId) => `/api/payroll/${periodId}/lines/${lineId}/payslip.pdf`,
    ownPayslip: (periodId) => `/api/payroll/${periodId}/payslip/me`,
    // Bulk payslip ZIP (2026-07-30): every payslip for a processed period, so HR can review the
    // whole batch before distribute() emails it to everyone. Mirrors PayrollController#bulkPayslipZip.
    payslipsZip: (periodId) => `/api/payroll/${periodId}/payslips.zip`,
    distribute: (periodId) => `/api/payroll/${periodId}/distribute`,
    taxAllowances: '/api/payroll/tax-allowances',
    // Tax-allowance DECLARATION workflow (PR A, 2026-08-01): staged separately from the legacy
    // bulk editor above -- a declaration never touches hr.employee_tax_allowance until HR applies
    // it. Mirrors TaxAllowanceDeclarationController.
    taxAllowanceDeclarations: {
      me: '/api/payroll/tax-allowances/declarations/me',
      // Tax-effect estimate (decision #4, 2026-08-01). Reuses the SAME body shape as `me` (POST) --
      // no employeeId field anywhere, same self-scoping idiom.
      estimate: '/api/payroll/tax-allowances/declarations/me/estimate',
      withdraw: (id) => `/api/payroll/tax-allowances/declarations/${id}`,
      register: '/api/payroll/tax-allowances/declarations',
      onBehalf: '/api/payroll/tax-allowances/declarations/on-behalf',
      approve: (id) => `/api/payroll/tax-allowances/declarations/${id}/approve`,
      reject: (id) => `/api/payroll/tax-allowances/declarations/${id}/reject`,
      apply: (id) => `/api/payroll/tax-allowances/declarations/${id}/apply`,
      // Yearly expiry (decision #10, 2026-08-01): the mirror of the scheduled expiry sweep.
      reverify: (id) => `/api/payroll/tax-allowances/declarations/${id}/reverify`,
      // Evidence attachments (decision #5, 2026-08-01). Nested POST/GET, mirrors
      // PricingRequestController's own `.../{id}/attachments` shape.
      attachments: (id) => `/api/payroll/tax-allowances/declarations/${id}/attachments`,
    },
    taxAllowanceCaps: '/api/payroll/tax-allowances/caps',
    // Flat evidence-file routes, sibling of taxAllowances above -- mirrors
    // PricingRequestController's flat `/pricing-request-attachments/{id}/file` + DELETE shape.
    // TaxAllowanceAttachmentController.
    taxAllowanceAttachmentFile: (attachmentId) => `/api/payroll/tax-allowance-attachments/${attachmentId}/file`,
    taxAllowanceAttachment: (attachmentId) => `/api/payroll/tax-allowance-attachments/${attachmentId}`,
    ytdSeed: '/api/payroll/ytd-seed',
    // P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix. Mirrors
    // PayrollController's component-tax-treatments mapping.
    componentTaxTreatments: '/api/payroll/component-tax-treatments',
    // Payroll input draft (2026-07-30): HR's in-progress, not-yet-processed inputs, persisted so a
    // browser reload restores exactly what was typed. Mirrors PayrollController's input-draft mapping.
    inputDraft: '/api/payroll/input-draft',
    // Deduction obligation tracking (issue #373): กยศ / กรมบังคับคดี obligation record + remittance
    // ledger. Mirrors DeductionObligationController.
    deductionObligations: {
      me: '/api/payroll/deduction-obligations/me',
      list: '/api/payroll/deduction-obligations',
      create: '/api/payroll/deduction-obligations',
      update: (id) => `/api/payroll/deduction-obligations/${id}`,
      progress: (id) => `/api/payroll/deduction-obligations/${id}/progress`,
      stop: (id) => `/api/payroll/deduction-obligations/${id}/stop`,
      acknowledgeCompletion: (id) => `/api/payroll/deduction-obligations/${id}/acknowledge-completion`,
      overrideContinue: (id) => `/api/payroll/deduction-obligations/${id}/override-continue`,
      clearOverride: (id) => `/api/payroll/deduction-obligations/${id}/clear-override`,
    },
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
  // warehouse (WH-คลังสินค้า) and qc (QC&ISO) are new roles that, for now, get the same
  // self-service experience as a plain employee — no งานขาย / sales access yet. Their scoped
  // warehouse/QC involvement is a separate, later change (see DivisionAccessPolicy).
  canUseEmployeeExperience: ['employee', 'warehouse', 'qc'],
  canSubmitProfileRequests: ['employee', 'warehouse', 'qc'],
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
  // CEO/HR "mark present" (stand-up / WFH roster). Mirrors AttendanceController's
  // POST /api/attendance/daily/mark-present requireAnyRole(user, "ceo", "hr") exactly.
  canMarkAttendance: ['hr', 'ceo'],
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
  // Role-scoped views (docs/role-scoped-views.md): the deal PIPELINE BROWSER
  // (list `/tickets`, `/ticket-overview`, the รายการดีล nav item, SalesTabs
  // pipeline tabs) is narrower than ticket-detail read (canViewTickets above,
  // unchanged) — only roles whose job IS the pipeline get it. Import and
  // Account are deliberately excluded — their jobs are procurement→delivery
  // and money-confirmation respectively, not browsing the whole deal list
  // (see features/dashboard/ImportOverview.jsx / AccountOverview.jsx).
  // Frontend-only presentation split; never narrows what
  // TicketService.VIEWER_ROLES actually allows a read of.
  canViewDealPipeline: ['sales', 'sales_manager', 'ceo'],
  // Sales/CRM tool — which roles get the catalog SCREENS, scoped to the same
  // audience as canViewTickets.
  //
  // This is a FRONTEND UX CHOICE, deliberately narrower than the API, and it is
  // NOT a security boundary — do not read it as one (#388 did, and filed the
  // divergence as a vulnerability). GET /api/catalog and GET /api/catalog/prices
  // are open to any authenticated user on the real backend, by product decision:
  // #205 (product owner, 2026-07-16) for browsing, and the owner's 2026-08-01
  // ruling closing #388 confirming that "browsable by any logged-in user" covers
  // the supplier purchase price too. Narrowing this list hides a screen; it does
  // not protect the data, and nothing about the data is meant to be protected.
  canViewCatalog: ['sales', 'import', 'ceo', 'account', 'sales_manager'],
  // Money-receipt confirmations (รับยอดมัดจำ / รับชำระเต็มจำนวน) belong to
  // ฝ่ายบัญชี, with CEO as fallback. Mirrors TicketService.ACCOUNT_ROLES.
  canConfirmPayments: ['account', 'ceo'],
  // Who may READ a deal's documents (the เอกสาร panel) without being one of its participants.
  // Mirrors TicketAccessPolicy.canViewDocuments' role half — deliberately NARROWER than
  // canViewTickets: `sales` and `import` reach a deal's documents only on deals they participate
  // in. AttachType spans {PO, SIGNED_QUOTATION, INVOICE, OTHER} and the list endpoint applies no
  // type filter, so a document read hands over the countersigned quotation and the ใบกำกับภาษี —
  // the approved customer price, which salesViewScope already hides from import and which the
  // backend refuses it on the quotation file, the payment ledger and deposit notices.
  canViewTicketDocuments: ['ceo', 'account', 'sales_manager'],
  // Who may ATTACH or REMOVE a deal's documents without being one of its participants
  // (the deal's own rep or whoever picked it up keep the ability regardless of role).
  // Mirrors TicketAccessPolicy.DOCUMENT_WRITER_ROLES — strictly narrower than reading a
  // document, which now follows canViewTickets exactly (issue #389).
  //
  // account is deliberately absent even though it READS every deal document: the closing tax
  // invoice has exactly one entry point (POST /api/commissions/from-deal), which dual-writes
  // the INVOICE attachment AND the rep's commission. A second upload path would satisfy the
  // close gate's invoiceOnFile check while the rep silently loses their commission.
  canManageTicketDocuments: ['sales_manager', 'ceo'],
  canCreateTickets: ['sales'],
  canPickupTickets: ['import'],
  canProposePrices: ['import'],
  canApproveReject: ['ceo'],
  canGenerateQuotation: ['sales'],
  // Slice A3 (commission redesign): sales is read-only on commissions now — it may reach the
  // /commissions route to see its own records + calc detail, but has no list()/submit access on
  // the real backend beyond that read. account is included here purely so the route guard lets
  // it in for the createFromDeal ("record invoice") flow; account has NO route to
  // GET /api/commissions (list()) on the real backend — see canListCommissionRecords below,
  // which intentionally excludes it.
  canViewCommissions: ['sales', 'sales_manager', 'ceo', 'hr', 'account'],
  // Mirrors CommissionController's GET /api/commissions PreAuthorize exactly (SALES,
  // SALES_MANAGER, CEO only — NOT account, NOT hr). hr reads via canViewPayrollCommissions
  // (payroll-ready) instead; account has no list access at all, only createFromDeal.
  canListCommissionRecords: ['sales', 'sales_manager', 'ceo'],
  // Slice A2 (AUTHZ CHANGE): sales removed — commission creation is now the accountant's
  // auto-create trigger (POST /api/commissions/from-deal) at deal close. Mirrors
  // CommissionService.CREATE_FROM_DEAL_ROLES exactly.
  canCreateCommissionFromDeal: ['account'],
  canApproveCommissions: ['sales_manager', 'ceo'],
  // Manual commission entries (feat/commission-manual-adjustments): a hand-typed ADJUSTMENT/
  // MANAGER/STOCK_BONUS/INCENTIVE amount, no invoice/tier-calc involved. Mirrors
  // CommissionController's POST /api/commissions/manual PreAuthorize + CommissionService
  // .MANUAL_CREATE_ROLES exactly — same role set as canApproveCommissions today, but kept as
  // its own key since the two gates are defined independently server-side.
  canCreateManualCommission: ['sales_manager', 'ceo'],
  canViewPayrollCommissions: ['hr'],
  // Split (issue #390): the SPA used to conflate view and manage under one key, which is why the
  // CEO's deliberate backend read grant (PayrollController: every GET, plus the non-persisting
  // POST /preview and /preview/export/{kind}, is hasAnyRole('HR','CEO')) was unreachable -- the
  // route and every mutating control gated on the single `canManagePayroll` key, which is
  // HR-only. canViewPayroll now gates the /payroll route and the read affordances (period
  // summary, lines, exports, payslip downloads, preview); canManagePayroll stays HR-only and
  // gates every write (process, input-draft PUT, tax-allowances PUT, ytd-seed PUT,
  // component-tax-treatments PUT, distribute). See PayrollPage.jsx's `canManage`.
  canViewPayroll: ['hr', 'ceo'],
  canManagePayroll: ['hr'],
  // Tax-allowance declaration (ล.ย.01) HR review screen (issue #387). Mirrors
  // TaxAllowanceDeclarationService: the register read (GET /declarations) is
  // hasAnyRole('HR','CEO'), while every mutation (approve/reject/apply/reverify/
  // on-behalf) is hasRole('HR') only — CEO gets read-only visibility into who has
  // declared what, never the reviewer actions.
  canViewTaxAllowanceRegister: ['hr', 'ceo'],
  canReviewTaxAllowances: ['hr'],
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
  // Attendance calendar admin UI (/settings/attendance-calendar — PR #480 shipped the write API
  // with no UI at all). Mirrors HolidayController / WorkScheduleController /
  // WorkScheduleAssignmentController's requireAnyRole(user, "hr", "ceo") exactly. FRONTEND GATING
  // ONLY: the backend already enforces this role check independently on every endpoint — this key
  // decides who sees the SCREEN, it grants nothing itself. See CLAUDE.md's "Mock API contract" on
  // why a frontend permission key is never itself evidence of a backend authorization change.
  canManageAttendanceCalendar: ['hr', 'ceo'],
};
