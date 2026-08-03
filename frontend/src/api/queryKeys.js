// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  employeeDetail: (id) => ['employeeDetail', id],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
  payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth ?? ''],
  // Tax-allowance declaration workflow (PR A, 2026-08-01). Mirrors
  // TaxAllowanceDeclarationController's endpoints -- no UI in this PR, but the frontend agent
  // building the declaration screen (PR B) needs these to already exist.
  taxAllowanceDeclarationsMe: (year) => ['taxAllowanceDeclarations', 'me', year ?? ''],
  taxAllowanceDeclarationsRegister: (filters = {}) =>
    ['taxAllowanceDeclarations', 'register', filters.year ?? '', filters.status ?? ''],
  taxAllowanceCaps: (year) => ['taxAllowanceCaps', year ?? ''],
  // Evidence attachments (decision #5, 2026-08-01). Mirrors TaxAllowanceDeclarationController's
  // nested .../{id}/attachments endpoint.
  taxAllowanceAttachments: (declarationId) => ['taxAllowanceAttachments', declarationId ?? ''],
  notifications: () => ['notifications'],
  leaveRequests: (filters = {}) => ['leave', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  leaveBalances: (employeeId, year) => ['leave', 'balances', employeeId, year],
  leaveEmployees: () => ['leave', 'employees'],
  leaveTypes: () => ['leave', 'types'],
  leaveContactDefaults: (employeeId) => ['leave', 'contactDefaults', employeeId],
  // Leave-surface IA rebuild, Phase A0 (not yet landed) — see routes.js's own comment on
  // API_ROUTES.leave.reviewSummary. Defined now for the same reason: keep the module stable ahead
  // of A0, even though no query in this phase constructs it yet.
  leaveReviewSummary: () => ['leave', 'reviewSummary'],
  // Leave-request composer (Phase A2, #485): keyed on every field the dry-run gate chain
  // actually branches on (see LeaveService#preview's Javadoc) so distinct inputs never share a
  // cache entry -- `depth` is included because a QUICK and FULL call for the identical
  // employee/type/dates can legitimately return different `coverageEvaluated`/`blocking`.
  leavePreview: (params = {}) => ['leave', 'preview',
    params.employeeId ?? '', params.leaveTypeCode ?? '', params.startDate ?? '', params.endDate ?? '',
    params.purposeCode ?? '', params.requestedAsEmergency ?? false, params.hasAttachment ?? false,
    params.depth ?? 'FULL'],
  // Leave-surface IA rebuild, Phase A3: rules tab's policy-document link availability probe.
  leavePolicyDocumentAvailable: () => ['leave', 'policyDocumentAvailable'],
  // Leave-request composer, Phase C (#leave-calendar-context): keyed on the exact { from, to }
  // window fetched -- see LeaveRequestPage.jsx's own comment on the lookahead-window choice.
  leaveCalendarContext: (from, to) => ['leave', 'calendarContext', from ?? '', to ?? ''],
  overtimeRequests: (filters = {}) => ['overtime', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  overtimeEmployees: () => ['overtime', 'employees'],
  // CommissionController#list only ever takes payrollMonth (no status param —
  // see CommissionPage.jsx's imperative load()); kept here so CeoOverview's
  // unfiltered fetch shares one cache entry with anything else that reads
  // "every commission record" for the current payroll month.
  commissionsList: (payrollMonth) => ['commissions', 'list', payrollMonth ?? ''],
  // Issue #422 B5: reserved for a future react-query migration of CommissionPage's payrollReady()
  // read (GET /api/commissions/payroll-ready, the hr-only summary view) -- CommissionPage itself
  // stays imperative in this fix (out of scope; invalidating the ['payroll'] prefix from its
  // mutations is sufficient now that PayrollPage is a query), so nothing constructs this key yet.
  commissionsPayrollReady: (payrollMonth) => ['commissions', 'payrollReady', payrollMonth ?? ''],
  // Self-service landing (EmployeeSelfService): own attendance.daily() reads. `to` defaults to
  // "today" server-side when omitted, same as AttendancePage's self view.
  attendanceDaily: (from, to) => ['attendance', 'daily', from ?? '', to ?? ''],
  // AttendancePage's team/company view (issue #422 B2): scoped by employeeId/divisionId as well
  // as the date range, unlike attendanceDaily above -- EmployeeSelfService's key has neither
  // filter (it is always "my own" attendance), so it stays untouched rather than widened, and
  // this is a distinct key rather than an overload of it.
  attendanceDailyScoped: (from, to, employeeId, divisionId) =>
    ['attendance', 'daily', 'scoped', from ?? '', to ?? '', employeeId ?? '', divisionId ?? ''],
  specialMoneyRequests: (filters = {}) => ['specialMoney', 'list', filters.from, filters.to, filters.status, filters.employeeId, filters.type],
  specialMoneyEmployees: () => ['specialMoney', 'employees'],
  specialMoneyTypes: () => ['specialMoney', 'types'],
  specialMoneyUsage: (employeeId, year) => ['specialMoney', 'usage', employeeId, year],
  // Attachment list (welfare page IA redesign, 2026-08) — mirrors SpecialMoneyController's nested
  // .../{id}/attachments endpoint, same shape as taxAllowanceAttachments above. None existed
  // before this: AttachmentList.jsx is the first caller of api.specialMoney.attachments().
  specialMoneyAttachments: (id) => ['specialMoney', 'attachments', id ?? ''],
  // ticketDetail/ticketAttachments are for slice B (TicketDetailPage) — defined
  // now so the key module is stable across both slices; only ticketList is used here.
  ticketList: (status) => ['tickets', 'list', status ?? ''],
  // Account role-scoped views: the CLOSED_PAID picker AccountOverview/
  // AccountFinancePage/CommissionPage's createFromDeal flow all use — same
  // `salesStage` query param, distinct key from the plain ticketList above.
  ticketListBySalesStage: (salesStage) => ['tickets', 'list', 'salesStage', salesStage ?? ''],
  ticketDetail: (id) => ['tickets', 'detail', id],
  ticketActions: (id) => ['tickets', 'actions', id],
  ticketPayments: (id) => ['tickets', 'payments', id],
  ticketDeliveries: (id) => ['tickets', 'deliveries', id],
  ticketAttachments: (id) => ['tickets', 'attachments', id],
  // Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103).
  ticketActivities: (id) => ['tickets', 'activities', id],
  // slice C (DepositNoticePage/CeoSettingsPage/NotificationBell)
  depositNotices: (ticketId) => ['depositNotices', ticketId],
  depositNoteTemplates: () => ['depositNotices', 'templates'],
  customersSearch: (q) => ['customers', 'search', q ?? ''],
  fxRates: () => ['fxRates'],
  priceCalcConfigs: () => ['priceCalcConfigs'],
  // BRANCH 1 of the sales pricing-formula redesign (config storage + CEO editing UI only).
  pricingFormulaConfig: () => ['pricingFormulaConfig'],
  dealEstimateMarkup: () => ['dealEstimateMarkup'],
  // Commit 6 (pricing-request-foundation)
  pricingRequestsByTicket: (ticketId) => ['pricingRequests', 'byTicket', ticketId],
  pricingRequestQueue: (filters = {}) => ['pricingRequests', 'queue', filters.status ?? '', filters.assignedImportId ?? '', filters.activeOnly ?? true],
  pricingRequestDetail: (id) => ['pricingRequests', 'detail', id],
  pricingRequestFactoryQuotes: (id) => ['pricingRequests', 'factoryQuotes', id],
  pricingRequestCostings: (id) => ['pricingRequests', 'costings', id],
  pricingRequestAttachments: (id) => ['pricingRequests', 'attachments', id],
  pricingCostingDetail: (id) => ['pricingCostings', 'detail', id],
  // Step 3: CEO Selling Price Decision.
  pricingDecisions: (id) => ['pricingRequests', 'pricingDecisions', id],
  pricingDecisionSalesView: (id) => ['pricingRequests', 'pricingDecisionSalesView', id],
  pricingDecisionDetail: (id) => ['pricingDecisions', 'detail', id],
  // Step 4: Customer Quotation Generation and Issuance.
  customerQuotations: (pricingRequestId) => ['pricingRequests', 'customerQuotations', pricingRequestId],
  customerQuotationDetail: (id) => ['customerQuotations', 'detail', id],
  // Step 7: Factory Purchase Order and Import Execution.
  factoryPurchaseOrderList: (status) => ['factoryPurchaseOrders', 'list', status ?? ''],
  factoryPurchaseOrdersForPricingRequest: (pricingRequestId) => ['pricingRequests', 'factoryPurchaseOrders', pricingRequestId],
  factoryPurchaseOrderDetail: (id) => ['factoryPurchaseOrders', 'detail', id],
  // Attendance calendar admin (PR #480's API, this branch's UI). `holidays` is per year-range
  // (mirrors GET /api/holidays?from&to) since the tab's year selector re-queries per year; the
  // other two have no filters yet (workSchedules is the whole read-only catalogue,
  // workScheduleAssignments is the whole admin list).
  holidays: (from, to) => ['holidays', from ?? '', to ?? ''],
  workSchedules: () => ['workSchedules'],
  workScheduleAssignments: () => ['workScheduleAssignments'],
};
