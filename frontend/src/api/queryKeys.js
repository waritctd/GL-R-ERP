// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  employeeDetail: (id) => ['employeeDetail', id],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
  payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth ?? ''],
  // Garnishment shortfall ledger (issue #376). employeeId is the only server-side filter the
  // ledger page uses; the rest of its narrowing is client-side over the returned rows.
  deductionShortfalls: (employeeId) => ['payroll', 'deductionShortfalls', employeeId ?? ''],
  // Written-consent register (issue #376). Both server-side filters are in the key: unlike the
  // shortfall ledger above, `kind` is a real request parameter here rather than client-side
  // narrowing, so two different kinds must not share one cache entry.
  deductionConsents: (employeeId, kind) => ['payroll', 'deductionConsents', employeeId ?? '', kind ?? ''],
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
  // C1 stored tax allowance (hr.employee_tax_allowance) -- "register shows what payroll actually
  // uses" (2026-08). GET /api/payroll/tax-allowances?year=, joined into TaxAllowanceReviewPage.jsx's
  // register by employeeId. Distinct key from taxAllowanceDeclarationsRegister above: same UI
  // screen, two different backend tables (see that page's own header comment).
  taxAllowances: (year) => ['taxAllowances', year ?? ''],
  notifications: () => ['notifications'],
  leaveRequests: (filters = {}) => ['leave', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  leaveBalances: (employeeId, year) => ['leave', 'balances', employeeId, year],
  // Manager team-quota summary (2026-09) -- TeamLeaveTab.jsx.
  leaveTeamBalances: (year) => ['leave', 'balances', 'team', year],
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
  //
  // `quotaPoolPreference` (V161, §5.3.5 pool choice): appended last so existing callers that never
  // pass it keep the exact key they always had, aside from this one new trailing entry. Without it
  // in the key, switching the composer's carry-in/own-quota toggle would refetch the SAME cache
  // entry the old preference already populated -- the split shown would silently stay pinned to
  // whichever pool order was previewed first, never updating to reflect the new choice.
  // Partial-day span (V166, 2026-09-10): startTime/endTime are now part of the key -- without
  // them, two previews sharing the same startDate/endDate but DIFFERENT times (e.g. toggling
  // ลาทั้งวัน off, or narrowing a timed span's hours) would collide on the same cache entry and
  // show a STALE totalDays.
  leavePreview: (params = {}) => ['leave', 'preview',
    params.employeeId ?? '', params.leaveTypeCode ?? '', params.startDate ?? '', params.endDate ?? '',
    params.startTime ?? '', params.endTime ?? '',
    params.purposeCode ?? '', params.requestedAsEmergency ?? false, params.hasAttachment ?? false,
    params.depth ?? 'FULL', params.quotaPoolPreference ?? 'CARRIED_IN_FIRST'],
  // Leave-surface IA rebuild, Phase A3: rules tab's policy-document link availability probe.
  leavePolicyDocumentAvailable: () => ['leave', 'policyDocumentAvailable'],
  // Leave-request composer, Phase C (#leave-calendar-context): keyed on the exact { from, to }
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
  // CEO approve dialog's ceiling preview (GET .../approval-preview) -- keyed on the request id so
  // opening a different row's dialog re-fetches rather than reusing a stale ceiling.
  specialMoneyApprovalPreview: (id) => ['specialMoney', 'approvalPreview', id ?? ''],
  // ticketDetail/ticketAttachments are for slice B (TicketDetailPage) — defined
  // now so the key module is stable across both slices; only ticketList is used here.
  ticketList: (status) => ['tickets', 'list', status ?? ''],
  // Account role-scoped views: the CLOSED_PAID picker AccountOverview/
  // AccountFinancePage/CommissionPage's createFromDeal flow all use — same
  // `salesStage` query param, distinct key from the plain ticketList above.
  ticketListBySalesStage: (salesStage) => ['tickets', 'list', 'salesStage', salesStage ?? ''],
  // PR-B REVIEW ROUND 1, S6: coerced to Number — TicketDetailPage reaches this id via
  // useParams() (a STRING from the URL), while ImportFulfilmentPage/DealFulfilmentPanel pass the
  // ticket's own `id`/`ticketId` field (a NUMBER from the API). ['tickets','detail','7'] and
  // ['tickets','detail',7] are DIFFERENT cache keys to react-query, so an invalidation fired by
  // one surface silently missed the other's cached entry — the two surfaces could disagree about
  // a deal's own fulfilment state after an advance. Number(id) on an already-empty/undefined id
  // is intentionally left alone (NaN) rather than defaulted, matching every other id-keyed entry
  // below that has no "no id yet" caller.
  ticketDetail: (id) => ['tickets', 'detail', id == null ? id : Number(id)],
  // Immutable server enumeration — fetched once, never invalidated. See stageCatalog.js.
  dealStageCatalog: () => ['meta', 'deal-stages'],
  // Same reasoning, for UnitBasis's four codes. See features/pricingRequests/unitBasisCatalog.js.
  unitBasisCatalog: () => ['meta', 'unit-bases'],
  // PR-B REVIEW ROUND 1, S6 — see ticketDetail's own comment just above.
  ticketActions: (id) => ['tickets', 'actions', id == null ? id : Number(id)],
  ticketPayments: (id) => ['tickets', 'payments', id],
  ticketDeliveries: (id) => ['tickets', 'deliveries', id],
  // Which brands a deal needs a ใบขอซื้อ for — one F-SM-001 per brand.
  importRequestBrands: (id) => ['tickets', 'import-request-brands', id],
  // The STORED ใบขอซื้อ aggregate (V184, PR-B) — one entry per deal's whole per-factory list, and
  // one for a single row by its own id (the edit/issue/advance-step screens load this before
  // acting on a specific version).
  // PR-B REVIEW ROUND 1, S6 — see ticketDetail's own comment above for why Number() matters here:
  // ImportFulfilmentPage's rowsQueries and DealFulfilmentPanel's own storedIrQuery must land on the
  // SAME cache entry for the same deal regardless of whether their ticketId arrived as a route
  // param (string) or an already-numeric field.
  storedImportRequests: (ticketId) => ['importRequests', 'byTicket', ticketId == null ? ticketId : Number(ticketId)],
  storedImportRequestDetail: (id) => ['importRequests', 'detail', id],
  ticketAttachments: (id) => ['tickets', 'attachments', id],
  // Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103).
  ticketActivities: (id) => ['tickets', 'activities', id],
  // slice C (DepositNoticePage/CeoSettingsPage/NotificationBell)
  depositNotices: (ticketId) => ['depositNotices', ticketId],
  depositNoteTemplates: () => ['depositNotices', 'templates'],
  remainingInvoiceOptions: (ticketId, quotationId) => ['remainingInvoice', 'options', ticketId, quotationId ?? null],
  // The STORED remaining invoice aggregate (V188, GLA-99 step 2) — DRAFT/ISSUED/SUPERSEDED rows
  // for one deal. Separate from remainingInvoiceOptions above, which stays the stateless prefill
  // source a new draft snapshots from.
  storedRemainingInvoices: (ticketId) => ['remainingInvoice', 'stored', ticketId],
  // The STORED billing note aggregate (V189, GLA-99 step 3), customer-scoped — GLA-129's money-tab
  // document pipeline joins this against a deal's own remaining invoice to resolve the ใบวางบิล
  // step's status.
  billingNotesForCustomer: (customerId) => ['billingNotes', 'customer', customerId ?? ''],
  customersSearch: (q) => ['customers', 'search', q ?? ''],
  // One customer MASTER row by id (quotation editor, owner 2026-09-11). There is no GET
  // /api/customers/{id}, so this is resolved through the name search and matched on id — see
  // QuotationEditorPage. Under the ['customers'] prefix so CustomerDetailsFields' post-save
  // invalidation refreshes it too.
  customerRecord: (id) => ['customers', 'record', id ?? ''],
  fxRates: () => ['fxRates'],
  priceCalcConfigs: () => ['priceCalcConfigs'],
  // BRANCH 1 of the sales pricing-formula redesign (config storage + CEO editing UI only).
  pricingFormulaConfig: () => ['pricingFormulaConfig'],
  // V153 thickness fallbacks. The gap list is derived from the catalogue, so it changes whenever a
  // price list is re-imported — not only when the CEO saves.
  catalogThicknessDefaults: () => ['catalogThicknessDefaults'],
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
  // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20) — the NEW engine's counterpart of
  // customerQuotations above, for the SAME "ใบเสนอราคาลูกค้า" panel.
  dealQuotationForPricingRequest: (pricingRequestId) => ['pricingRequests', 'dealQuotationForPricingRequest', pricingRequestId],
  // CEO discount-approval workflow, Phase 2 (V155): per-line approval status for one quotation.
  discountApprovals: (quotationId) => ['customerQuotations', 'discountApprovals', quotationId],
  // Quotation v2 — direct deal quotation (QUOTATION-V2-PLAN.md). A sibling key space to
  // pricingRequests'/customerQuotations' above, never sharing an entry with them.
  dealQuotationsByTicket: (ticketId) => ['dealQuotations', 'byTicket', ticketId ?? ''],
  // `needsRework` is part of the key, not just `status` — the "แก้" tab (owner feedback F5,
  // 2026-09-10) is a DIFFERENT server-side filter at the same empty `status`, so keying on status
  // alone would serve ทั้งหมด's cached rows to แก้ and vice versa.
  dealQuotationsList: (filters = {}) => ['dealQuotations', 'list', filters.status ?? '', filters.needsRework ? 'rework' : ''],
  dealQuotationCounts: () => ['dealQuotations', 'counts'],
  dealQuotationDetail: (id) => ['dealQuotations', 'detail', id ?? ''],
  // V179 (owner feedback #4, 2026-09-14) — the ผู้พิมพ์/พนักงานขาย print-name selector options.
  // No parameters: the scope is the caller's own session (sales/sales_manager or the grant), same
  // as dealQuotationCounts above.
  dealQuotationDisplayNameOptions: () => ['dealQuotations', 'displayNameOptions'],
  employeeSignature: (employeeId) => ['employeeSignature', employeeId ?? ''],
  // Step 7: Factory Purchase Order and Import Execution.
  // Attendance calendar admin (PR #480's API, this branch's UI). `holidays` is per year-range
  // (mirrors GET /api/holidays?from&to) since the tab's year selector re-queries per year; the
  // other two have no filters yet (workSchedules is the whole read-only catalogue,
  // workScheduleAssignments is the whole admin list).
  holidays: (from, to) => ['holidays', from ?? '', to ?? ''],
  workSchedules: () => ['workSchedules'],
  workScheduleAssignments: () => ['workScheduleAssignments'],
};
