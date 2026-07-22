// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  employeeDetail: (id) => ['employeeDetail', id],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
  payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth ?? ''],
  notifications: () => ['notifications'],
  leaveRequests: (filters = {}) => ['leave', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  leaveBalances: (employeeId, year) => ['leave', 'balances', employeeId, year],
  leaveEmployees: () => ['leave', 'employees'],
  leaveTypes: () => ['leave', 'types'],
  overtimeRequests: (filters = {}) => ['overtime', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  overtimeEmployees: () => ['overtime', 'employees'],
  // CommissionController#list only ever takes payrollMonth (no status param —
  // see CommissionPage.jsx's imperative load()); kept here so CeoOverview's
  // unfiltered fetch shares one cache entry with anything else that reads
  // "every commission record" for the current payroll month.
  commissionsList: (payrollMonth) => ['commissions', 'list', payrollMonth ?? ''],
  // Self-service landing (EmployeeSelfService): own attendance.daily() reads. `to` defaults to
  // "today" server-side when omitted, same as AttendancePage's self view.
  attendanceDaily: (from, to) => ['attendance', 'daily', from ?? '', to ?? ''],
  specialMoneyRequests: (filters = {}) => ['specialMoney', 'list', filters.from, filters.to, filters.status, filters.employeeId, filters.type],
  specialMoneyEmployees: () => ['specialMoney', 'employees'],
  specialMoneyTypes: () => ['specialMoney', 'types'],
  specialMoneyUsage: (employeeId, year) => ['specialMoney', 'usage', employeeId, year],
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
};
