// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  employeeDetail: (id) => ['employeeDetail', id],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
  payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth ?? ''],
  // Garnishment shortfall ledger. employeeId is the only server-side filter the
  // ledger page uses; the rest of its narrowing is client-side over the returned rows.
  deductionShortfalls: (employeeId) => ['payroll', 'deductionShortfalls', employeeId ?? ''],
  // Written-consent register. Both server-side filters are in the key: unlike the
  // shortfall ledger above, `kind` is a real request parameter here, so two different
  // kinds must not share one cache entry.
  deductionConsents: (employeeId, kind) => ['payroll', 'deductionConsents', employeeId ?? '', kind ?? ''],
  // Tax-allowance declaration workflow. Mirrors TaxAllowanceDeclarationController's endpoints.
  taxAllowanceDeclarationsMe: (year) => ['taxAllowanceDeclarations', 'me', year ?? ''],
  taxAllowanceDeclarationsRegister: (filters = {}) =>
    ['taxAllowanceDeclarations', 'register', filters.year ?? '', filters.status ?? ''],
  taxAllowanceCaps: (year) => ['taxAllowanceCaps', year ?? ''],
  // Evidence attachments. Mirrors TaxAllowanceDeclarationController's nested .../{id}/attachments endpoint.
  taxAllowanceAttachments: (declarationId) => ['taxAllowanceAttachments', declarationId ?? ''],
  // Stored tax allowance (hr.employee_tax_allowance): "register shows what payroll actually
  // uses". GET /api/payroll/tax-allowances?year=, joined into TaxAllowanceReviewPage.jsx's
  // register by employeeId. Distinct from taxAllowanceDeclarationsRegister above: same UI
  // screen, two different backend tables.
  taxAllowances: (year) => ['taxAllowances', year ?? ''],
  notifications: () => ['notifications'],
  leaveRequests: (filters = {}) => ['leave', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  leaveBalances: (employeeId, year) => ['leave', 'balances', employeeId, year],
  // Manager team-quota summary -- TeamLeaveTab.jsx.
  leaveTeamBalances: (year) => ['leave', 'balances', 'team', year],
  leaveEmployees: () => ['leave', 'employees'],
  leaveTypes: () => ['leave', 'types'],
  leaveContactDefaults: (employeeId) => ['leave', 'contactDefaults', employeeId],
  // Leave-surface review summary: not yet constructed by any query; see routes.js's comment on
  // API_ROUTES.leave.reviewSummary.
  leaveReviewSummary: () => ['leave', 'reviewSummary'],
  // Leave-request composer: keyed on every field the dry-run gate chain actually branches on
  // (see LeaveService#preview's Javadoc) so distinct inputs never share a cache entry --
  // `depth` is included because a QUICK and FULL call for the identical employee/type/dates
  // can return different `coverageEvaluated`/`blocking`.
  //
  // `quotaPoolPreference` (pool choice): appended last so existing callers that never pass it
  // keep their exact key. Without it in the key, switching the carry-in/own-quota toggle would
  // reuse the cache entry of the old preference and the split shown would stay pinned.
  // startTime/endTime are part of the key: without them, two previews with the same
  // startDate/endDate but different times (ลาทั้งวัน off, or a narrowed timed span) would
  // collide on one entry and show a STALE totalDays.
  leavePreview: (params = {}) => ['leave', 'preview',
    params.employeeId ?? '', params.leaveTypeCode ?? '', params.startDate ?? '', params.endDate ?? '',
    params.startTime ?? '', params.endTime ?? '',
    params.purposeCode ?? '', params.requestedAsEmergency ?? false, params.hasAttachment ?? false,
    params.depth ?? 'FULL', params.quotaPoolPreference ?? 'CARRIED_IN_FIRST'],
  // Rules tab's policy-document link availability probe.
  leavePolicyDocumentAvailable: () => ['leave', 'policyDocumentAvailable'],
  // Leave-request composer: keyed on the exact { from, to }
  // window fetched -- see LeaveRequestPage.jsx's own comment on the lookahead-window choice.
  leaveCalendarContext: (from, to) => ['leave', 'calendarContext', from ?? '', to ?? ''],
  overtimeRequests: (filters = {}) => ['overtime', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  overtimeEmployees: () => ['overtime', 'employees'],
  attendanceCorrectionRequests: (filters = {}) =>
    ['attendanceCorrection', 'list', filters.status ?? '', filters.employeeId ?? ''],
  // CommissionController#list only ever takes payrollMonth (no status param —
  // see CommissionPage.jsx's imperative load()); kept here so CeoOverview's
  // unfiltered fetch shares one cache entry with anything else that reads
  // "every commission record" for the current payroll month.
  // GET /api/commissions/pending-approval: the caller's own approval queue (dashboard landings).
  // Always cached as the endpoint returns it: the response's `commissions` entries, unreshaped.
  commissionsPendingApproval: () => ['commissions', 'pending-approval'],
  commissionsList: (payrollMonth) => ['commissions', 'list', payrollMonth ?? ''],
  // Reserved for a future react-query migration of CommissionPage's payrollReady() read
  // (GET /api/commissions/payroll-ready, the hr-only summary view); nothing constructs this key yet.
  commissionsPayrollReady: (payrollMonth) => ['commissions', 'payrollReady', payrollMonth ?? ''],
  // Self-service landing (EmployeeSelfService): own attendance.daily() reads. `to` defaults to
  // "today" server-side when omitted, same as AttendancePage's self view.
  attendanceDaily: (from, to) => ['attendance', 'daily', from ?? '', to ?? ''],
  // AttendancePage's team/company view: scoped by employeeId/divisionId as well as the date
  // range, unlike attendanceDaily above, whose key has neither filter (always "my own"), so it
  // stays a distinct key rather than an overload.
  attendanceDailyScoped: (from, to, employeeId, divisionId) =>
    ['attendance', 'daily', 'scoped', from ?? '', to ?? '', employeeId ?? '', divisionId ?? ''],
  specialMoneyRequests: (filters = {}) => ['specialMoney', 'list', filters.from, filters.to, filters.status, filters.employeeId, filters.type],
  specialMoneyEmployees: () => ['specialMoney', 'employees'],
  specialMoneyTypes: () => ['specialMoney', 'types'],
  specialMoneyUsage: (employeeId, year) => ['specialMoney', 'usage', employeeId, year],
  // Attachment list: mirrors SpecialMoneyController's nested .../{id}/attachments endpoint,
  // same shape as taxAllowanceAttachments above. First caller: AttachmentList.jsx.
  specialMoneyAttachments: (id) => ['specialMoney', 'attachments', id ?? ''],
  // CEO approve dialog's ceiling preview (GET .../approval-preview) -- keyed on the request id so
  // opening a different row's dialog re-fetches rather than reusing a stale ceiling.
  specialMoneyApprovalPreview: (id) => ['specialMoney', 'approvalPreview', id ?? ''],
  // ticketDetail/ticketAttachments: used by TicketDetailPage.
  ticketList: (status) => ['tickets', 'list', status ?? ''],
  // Account role-scoped views: the CLOSED_PAID picker AccountOverview/
  // AccountFinancePage/CommissionPage's createFromDeal flow all use — same
  // `salesStage` query param, distinct key from the plain ticketList above.
  ticketListBySalesStage: (salesStage) => ['tickets', 'list', 'salesStage', salesStage ?? ''],
  // Coerced to Number: TicketDetailPage reaches this id via useParams() (a STRING), while
  // ImportFulfilmentPage/DealFulfilmentPanel pass the ticket's own numeric `id`/`ticketId`.
  // ['tickets','detail','7'] and ['tickets','detail',7] are DIFFERENT cache keys to react-query,
  // so an invalidation from one surface would miss the other's cached entry. Number(id) on an
  // empty/undefined id is left alone (NaN) rather than defaulted, like every other id-keyed
  // entry below that has no "no id yet" caller.
  ticketDetail: (id) => ['tickets', 'detail', id == null ? id : Number(id)],
  // Immutable server enumeration — fetched once, never invalidated. See stageCatalog.js.
  dealStageCatalog: () => ['meta', 'deal-stages'],
  // Same reasoning, for UnitBasis's four codes. See features/pricingRequests/unitBasisCatalog.js.
  unitBasisCatalog: () => ['meta', 'unit-bases'],
  // See ticketDetail's comment above for why Number() matters.
  ticketActions: (id) => ['tickets', 'actions', id == null ? id : Number(id)],
  // Finance-only read model of one deal (GET /api/finance/deals/{id}), account + ceo. Number()-coerced
  // for the same route-param-is-a-string reason as ticketDetail above.
  financeDeal: (id) => ['finance', 'deal', id == null ? id : Number(id)],
  ticketPayments: (id) => ['tickets', 'payments', id],
  ticketDeliveries: (id) => ['tickets', 'deliveries', id],
  // Which brands a deal needs a ใบขอซื้อ for — one F-SM-001 per brand.
  importRequestBrands: (id) => ['tickets', 'import-request-brands', id],
  // The STORED ใบขอซื้อ aggregate: one entry per deal's whole per-factory list, and
  // one for a single row by its own id (the edit/issue/advance-step screens load it first).
  factoryNames: () => ['factoryConfigs', 'names'],
  // See ticketDetail's comment above for why Number() matters here:
  // ImportFulfilmentPage's rowsQueries and DealFulfilmentPanel's own storedIrQuery must land on the
  // SAME cache entry for the same deal regardless of whether their ticketId is a string or a number.
  storedImportRequests: (ticketId) => ['importRequests', 'byTicket', ticketId == null ? ticketId : Number(ticketId)],
  // GET /api/tickets/{id}/procurement-source: where each line comes from, and whether the rep has decided.
  procurementSource: (ticketId) => ['tickets', 'procurement-source', ticketId == null ? ticketId : Number(ticketId)],
  storedImportRequestDetail: (id) => ['importRequests', 'detail', id],
  // The import-only per-deal view (GET /api/import/deals/{id}). Number() for the same route-param
  // string vs numeric-field reason as ticketDetail above.
  importDeal: (ticketId) => ['importDeal', Number(ticketId)],
  ticketAttachments: (id) => ['tickets', 'attachments', id],
  // Deal parties (deal-party-model.md). Number()-coerced like ticketDetail above: TicketDetailPage
  // reaches the id via useParams() (a string) while the quotation editor passes a number.
  ticketParties: (ticketId) => ['tickets', 'parties', ticketId == null ? ticketId : Number(ticketId)],
  // GET /api/tickets/open-for-project?projectName= — keyed on the normalised name the server
  // matches on (case/whitespace-insensitive), so "S26 Hotel" and "s26  hotel" share one entry.
  openDealsForProject: (name) => ['tickets', 'open-for-project', String(name ?? '').trim().replace(/\s+/g, ' ').toLowerCase()],
  ticketActivities: (id) => ['tickets', 'activities', id == null ? id : Number(id)],
  depositNotices: (ticketId) => ['depositNotices', ticketId],
  depositNoteTemplates: () => ['depositNotices', 'templates'],
  remainingInvoiceOptions: (ticketId, quotationId) => ['remainingInvoice', 'options', ticketId, quotationId ?? null],
  // The STORED remaining invoice aggregate: DRAFT/ISSUED/SUPERSEDED rows
  // for one deal. Separate from remainingInvoiceOptions above, which stays the stateless prefill
  // source a new draft snapshots from.
  storedRemainingInvoices: (ticketId) => ['remainingInvoice', 'stored', ticketId],
  customersSearch: (q) => ['customers', 'search', q ?? ''],
  // One customer MASTER row by id (quotation editor). There is no GET
  // /api/customers/{id}, so this is resolved through the name search and matched on id (see
  // QuotationEditorPage). Under the ['customers'] prefix so CustomerDetailsFields' post-save
  // invalidation refreshes it too.
  customerRecord: (id) => ['customers', 'record', id ?? ''],
  fxRates: () => ['fxRates'],
  priceCalcConfigs: () => ['priceCalcConfigs'],
  pricingFormulaConfig: () => ['pricingFormulaConfig'],
  // Thickness fallbacks. The gap list is derived from the catalogue, so it changes whenever a
  // price list is re-imported, not only when the CEO saves.
  catalogThicknessDefaults: () => ['catalogThicknessDefaults'],
  // The price_catalog.factories master list: the import factory picker on
  // PricingRequestDetailPage reads it (and invalidates it after adding a new factory in-flow).
  // No params: GET /api/price-import/factories takes none and returns the whole roster.
  priceImportFactories: () => ['priceImport', 'factories'],
  priceImportCountries: () => ['priceImport', 'countries'],
  pricingRequestsByTicket: (ticketId) => ['pricingRequests', 'byTicket', ticketId],
  pricingRequestQueue: (filters = {}) => ['pricingRequests', 'queue', filters.status ?? '', filters.assignedImportId ?? '', filters.activeOnly ?? true],
  pricingRequestDetail: (id) => ['pricingRequests', 'detail', id],
  pricingRequestFactoryQuotes: (id) => ['pricingRequests', 'factoryQuotes', id],
  pricingRequestCostings: (id) => ['pricingRequests', 'costings', id],
  pricingRequestAttachments: (id) => ['pricingRequests', 'attachments', id],
  // Lead-time change requests of one pricing request.
  pricingRequestLeadTimeChanges: (id) => ['pricingRequests', 'leadTimeChanges', id],
  pricingCostingDetail: (id) => ['pricingCostings', 'detail', id],
  // Step 3: CEO Selling Price Decision.
  pricingDecisions: (id) => ['pricingRequests', 'pricingDecisions', id],
  pricingDecisionSalesView: (id) => ['pricingRequests', 'pricingDecisionSalesView', id],
  pricingDecisionDetail: (id) => ['pricingDecisions', 'detail', id],
  // Step 4: Customer Quotation Generation and Issuance.
  customerQuotations: (pricingRequestId) => ['pricingRequests', 'customerQuotations', pricingRequestId],
  customerQuotationDetail: (id) => ['customerQuotations', 'detail', id],
  // The NEW engine's counterpart of customerQuotations above, for the SAME "ใบเสนอราคาลูกค้า" panel.
  dealQuotationForPricingRequest: (pricingRequestId) => ['pricingRequests', 'dealQuotationForPricingRequest', pricingRequestId],
  // CEO discount-approval workflow: per-line approval status for one quotation.
  discountApprovals: (quotationId) => ['customerQuotations', 'discountApprovals', quotationId],
  // Direct deal quotation. A sibling key space to
  // pricingRequests'/customerQuotations' above, never sharing an entry with them.
  dealQuotationsByTicket: (ticketId) => ['dealQuotations', 'byTicket', ticketId ?? ''],
  // The same list WITH `orderConfirmedQuotationId` (DealDocumentRegister): the full response, kept apart from the
  // items-only entry above so its four readers keep their array. A child of that key, so invalidating it refreshes both.
  dealQuotationsByTicketWithConfirmed: (ticketId) => ['dealQuotations', 'byTicket', ticketId ?? '', 'withConfirmed'],
  // `needsRework` is part of the key, not just `status`: the "แก้" tab is a DIFFERENT server-side
  // filter at the same empty `status`, so keying on status alone would serve ทั้งหมด's cached
  // rows to แก้ and vice versa.
  dealQuotationsList: (filters = {}) => ['dealQuotations', 'list', filters.status ?? '', filters.needsRework ? 'rework' : ''],
  dealQuotationCounts: (from) => (from ? ['dealQuotations', 'counts', from] : ['dealQuotations', 'counts']),
  dealQuotationDetail: (id) => ['dealQuotations', 'detail', id ?? ''],
  // The ผู้พิมพ์/พนักงานขาย print-name selector options.
  // No parameters: the scope is the caller's own session (sales/sales_manager or the grant).
  dealQuotationDisplayNameOptions: () => ['dealQuotations', 'displayNameOptions'],
  // One quotation's เอกสารแนบ, its own files and those it inherits from the documents it revises.
  dealQuotationAttachments: (quotationId) => ['dealQuotations', 'attachments', quotationId ?? ''],
  employeeSignature: (employeeId) => ['employeeSignature', employeeId ?? ''],
  // Step 7: Factory Purchase Order and Import Execution.
  // Attendance calendar admin. `holidays` is per year-range
  // (mirrors GET /api/holidays?from&to) since the tab's year selector re-queries per year; the
  // other two have no filters yet (workSchedules is the whole read-only catalogue,
  // workScheduleAssignments is the whole admin list).
  holidays: (from, to) => ['holidays', from ?? '', to ?? ''],
  workSchedules: () => ['workSchedules'],
  workScheduleAssignments: () => ['workScheduleAssignments'],
};
