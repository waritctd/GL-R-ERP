const thaiMonths = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];

export function formatThaiDate(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return `${date.getDate()} ${thaiMonths[date.getMonth()]} ${date.getFullYear() + 543}`;
}

// Buddhist-era month/year, for a native `<input type="month">` value ("YYYY-MM") — display only,
// never a value that gets submitted anywhere. Deliberately parses the "YYYY-MM" string directly
// instead of going through `new Date(value)`: a bare-month ISO string is parsed as UTC midnight,
// which can roll to the wrong local month/year near a timezone boundary. Reuses the same
// thaiMonths table and +543 offset as formatThaiDate above rather than a second lookup.
//
// Named `...FromMonthInputValue` (not the shorter `formatThaiMonthYear`) because
// `features/specialmoney/specialMoneyRules.js` already exports a *different* `formatThaiMonthYear`
// that takes a `Date` and returns a 2-digit BE year — same name, incompatible signature. Autofilling
// an import from this file into a specialmoney file would pass a `Date` here, the regex below
// wouldn't match, and it would silently return '-' with no crash and no test. Do not rename this
// back to `formatThaiMonthYear` without also resolving that collision (ideally consolidating both
// into one thaiMonths table — this file, `specialMoneyRules.js`, and `formatThaiDate` above all keep
// their own copy today).
export function formatThaiMonthYearFromMonthInputValue(value) {
  if (!value) return '-';
  const match = /^(\d{4})-(\d{2})$/.exec(value);
  if (!match) return '-';
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex > 11) return '-';
  return `${thaiMonths[monthIndex]} ${Number(match[1]) + 543}`;
}

export function formatShortDate(value) {
  if (!value) return '-';
  // A bare "YYYY-MM-DD" (no time component — e.g. a LocalDate field, or an `<input type="date">`
  // value) is parsed directly rather than via `new Date(value)`: that constructor reads a date-only
  // ISO string as UTC midnight, so `.getDate()`/`.getMonth()` below then read it back in the local
  // zone, which rolls to the previous day west of UTC (America/New_York, Pacific/Honolulu, ...).
  // A full ISO datetime (a real instant, e.g. an attendance punch timestamp with a time/offset) is
  // NOT matched here and falls through to `new Date()` below, same as before — reading an instant
  // back in local time is the correct behaviour, not the bug this guards against.
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (dateOnly) {
    const [, year, month, day] = dateOnly;
    const monthIndex = Number(month) - 1;
    if (monthIndex < 0 || monthIndex > 11) return '-';
    return `${day}/${month}/${Number(year) + 543}`;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return `${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}/${date.getFullYear() + 543}`;
}

/**
 * Canonical address formatter; do not re-add a page-local version.
 *
 * `currentAddress` is four separate fields (line1/district/province/postalCode),
 * and rendering only `line1` silently dropped the district, province and postcode.
 * Empty parts are filtered out because a newly created employee gets '' for
 * everything but `line1` (mockApi) — joining blindly would leave stray spaces.
 */
export function formatAddress(address) {
  if (!address) return '-';
  const parts = [address.line1, address.district, address.province, address.postalCode]
    .map((part) => (typeof part === 'string' ? part.trim() : part))
    .filter(Boolean);
  return parts.length > 0 ? parts.join(' ') : '-';
}

export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '-';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '-';
  return `฿${amount.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

// Thai honorifics a stored name may already carry — either the employee
// master's `nameTh` (title_id-derived, e.g. "นายสมชาย ใจดี") or a free-text
// `user.name` seeded with one baked in (e.g. "คุณสมหมาย ขายดี"). Order doesn't
// affect matching (`startsWith` is checked per-entry), but 'นางสาว' is listed
// so it reads clearly alongside its own prefix 'นาง'.
const THAI_HONORIFICS = ['นางสาว', 'นาง', 'นาย', 'ดร.', 'คุณ'];

// Prepends the "คุณ" salutation used by dashboard greetings, but only when
// `name` doesn't already start with a Thai honorific. Every greeting used to
// prepend "คุณ" unconditionally, which doubled up whenever the resolved name
// already carried one — see issue #395 ("สวัสดี คุณคุณสมหมาย ขายดี"). The
// doubling traces to inconsistent name data (some `user.name` rows are
// seeded with "คุณ" already in them; `employee.nameTh` separately bakes in a
// นาย/นาง/นางสาว title), not to this helper's own logic — detecting an
// existing honorific here is the fix, not stripping/rewriting the stored
// name (that's a data concern, out of scope for this helper).
export function greetingName(name) {
  const trimmed = (name ?? '').trim();
  if (!trimmed) return '';
  return THAI_HONORIFICS.some((title) => trimmed.startsWith(title)) ? trimmed : `คุณ${trimmed}`;
}

export function initialsFromName(name = '') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase() || 'GL';
}

// Login-topbar role badge (AppShell.jsx). Canonical source; do not re-add a page-local map for it
// elsewhere.
//
// Mirrors DivisionAccessPolicy.roleFor (backend auth/DivisionAccessPolicy.java) — that class
// derives the role from inline branch logic over division+position data, not a declared constants
// field, so its digest group (DivisionAccessPolicyRole, in utils/statusCatalog.test.js) is
// generated by StatusCatalogContractTest actually CALLING roleFor against one synthetic
// EmployeeLoginRecord per branch, rather than by reflecting on a field the way the other groups do
// — see that test's own Javadoc.
//
// ROLE_LABEL_KEYS backs the same digest guard as the other *_LABEL_KEYS exports in this file.
// Unlike those, roleLabel returns a plain string, not a { label, tone } pair — there is no tone to
// a role badge — so the guard's isUnlabelled helper takes an explicit hasKey predicate for this one
// spec instead of probing the return shape: several entries below (hr -> 'HR', qc -> 'QC', ceo ->
// 'CEO', ...) coincidentally equal what the .toUpperCase() fallback would produce anyway, so
// output-comparison alone cannot tell a real entry from a miss here the way it can for the Thai-text
// maps elsewhere in this file.
const ROLE_LABELS = {
  hr: 'HR',
  employee: 'EMPLOYEE',
  sales: 'SALES',
  sales_manager: 'SALES MANAGER',
  import: 'IMPORT',
  warehouse: 'WAREHOUSE',
  qc: 'QC',
  ceo: 'CEO',
  // AC-ฝ่ายบัญชี division (DivisionAccessPolicy.roleFor). Reuses the exact wording
  // LoginPage.jsx's quick-login button already uses for this role (also matched by demoData.js's
  // own comment and referenceData.js's division table) rather than inventing a new English
  // abbreviation to match this map's other entries.
  account: 'ฝ่ายบัญชี',
  // No `admin` entry: confirmed genuinely underivable (2026-08-16) — DivisionAccessPolicy.roleFor
  // has no admin branch, ApplicationRoles.priority() does not list it, no division in
  // referenceData.js codes to it, and no login path (mock or real) ever assigns it. It was dead
  // copy, the READY_FOR_COSTING fossil shape in the other direction: a frontend key with no
  // backend source, rather than a backend status with no frontend label.
};
export const ROLE_LABEL_KEYS = Object.keys(ROLE_LABELS);

export function roleLabel(role) {
  return ROLE_LABELS[role] ?? role?.toUpperCase() ?? '-';
}

// Mirrors TicketStatus.VALUES (backend ticket/TicketStatus.java). Key coverage against the
// backend is guarded both ways by utils/statusCatalog.test.js; TICKET_STATUS_LABEL_KEYS exists
// only so that guard can enumerate this map's keys without a second hand-typed copy of them.
const TICKET_STATUS_LABELS = {
  draft:            { label: 'แบบร่าง',          tone: 'neutral' },
  submitted:        { label: 'รอฝ่ายนำเข้ารับเรื่อง', tone: 'warning' },
  in_review:        { label: 'กำลังดำเนินการ',    tone: 'info' },
  price_proposed:   { label: 'รอการอนุมัติ',      tone: 'warning' },
  approved:         { label: 'อนุมัติแล้ว',       tone: 'success' },
  rejected:         { label: 'ตีกลับ',            tone: 'danger' },
  quotation_issued: { label: 'ออกใบเสนอราคาแล้ว', tone: 'success' },
  document_issued:  { label: 'ออกใบแจ้งยอดแล้ว',  tone: 'success' },
  closed:           { label: 'ปิดแล้ว',           tone: 'neutral' },
  cancelled:        { label: 'ยกเลิกแล้ว',        tone: 'danger' },
};
export const TICKET_STATUS_LABEL_KEYS = Object.keys(TICKET_STATUS_LABELS);

export function ticketStatusLabel(status) {
  return TICKET_STATUS_LABELS[status] ?? { label: status, tone: 'neutral' };
}

// PricingRequest status -> StatusBadge tone (commit 6). Canonical source; do
// not re-add a page-local map for pricing-request status elsewhere. Status
// order/gates live in features/pricingRequests/pricingRequestMeta.js.
// PRICING_REQUEST_STATUS_LABEL_KEYS is this map's own key set (Object.keys, not a hand-typed
// second copy) — utils/statusCatalog.test.js reads it to guard coverage against
// PricingRequestStatus.VALUES. Not to be confused with pricingRequestMeta.js's
// ALLOWED_TRANSITIONS, a SEPARATE hand-mirror of the same eleven statuses for transition rules
// rather than labels, guarded independently by pricingRequestMeta.test.js; that file's own comment
// warns against adding a bare status array that nothing consumes, which is why this one is named
// for what it feeds rather than reusing that name.
const PRICING_REQUEST_STATUS_LABELS = {
    DRAFT: { label: 'แบบร่าง', tone: 'neutral' },
    SUBMITTED: { label: 'รอฝ่ายนำเข้ารับเรื่อง', tone: 'warning' },
    // Import's workflow was simplified to three meaningful stages (owner ruling,
    // 2026-08-11): รับเรื่อง -> เจรจาราคากับโรงงาน -> รอ CEO อนุมัติราคา.
    IMPORT_REVIEWING: { label: 'รับเรื่อง', tone: 'info' },
    // AWAITING_FACTORY_RESPONSE now covers costing too: V140 merged COSTING_IN_PROGRESS
    // into it and dropped it from the DB constraint. COSTING_IN_PROGRESS is kept here
    // ON PURPOSE, mapped to the SAME label and tone, for two reasons: rows still sit in
    // it during the window between deploying this code and V140 running, and historical
    // pricing_request_event rows keep the old value forever (V140 deliberately does not
    // rewrite history). Do not re-split them, and do not delete this entry — without it
    // such a row falls through to the raw enum string below.
    AWAITING_FACTORY_RESPONSE: { label: 'เจรจาราคากับโรงงาน', tone: 'info' },
    COSTING_IN_PROGRESS: { label: 'เจรจาราคากับโรงงาน', tone: 'info' },
    // Wording is now "waiting on the CEO", not "Import has sent it" — tone follows
    // SUBMITTED's (the queue's other waiting-on-someone stage) rather than staying
    // 'success', which would paint a pending approval green.
    READY_FOR_CEO_REVIEW: { label: 'รอ CEO อนุมัติราคา', tone: 'warning' },
    // Step 3 (CEO Selling Price Decision).
    CEO_REVIEWING: { label: 'CEO กำลังพิจารณาราคาขาย', tone: 'info' },
    APPROVED_FOR_QUOTATION: { label: 'อนุมัติราคาขายแล้ว', tone: 'success' },
    // V141 (db/migration/V141__ceo_owns_pricing_costing.sql) retired this status outright: the
    // CEO owns costing now, and PricingDecisionService.returnToImport (:438-460) sends the
    // request straight to AWAITING_FACTORY_RESPONSE, never to a "revise the costing" state.
    // Kept here for the SAME deploy-before-migration reason as the two neighbours above, and
    // ONLY that reason: the frontend deploys separately from the backend, so a build can go
    // live against a backend that still writes this status before V141 has run on the database
    // it talks to. Delete the entry and such a row falls through to the raw enum string below.
    //
    // The OTHER reason one might assume — historical pricing_request_event rows, which V141 §5
    // deliberately preserves — does NOT currently reach this map: every caller passes a pricing
    // REQUEST's own status, never an event's from/to status. Verified 2026-08-14:
    // DealStateHeader.jsx:136, DealQuotationPanel.jsx:299,
    // PricingRequestQueuePage.jsx:53/98/123, PricingRequestPanel.jsx:134,
    // PricingRequestDetailPage.jsx:547. If a history view ever renders event statuses through
    // this function, this entry becomes load-bearing for that too.
    //
    // Nothing in mock mode can reach the label either any more: issue #741 stopped mockApi.js's
    // returnPricingDecisionToImport writing it, and demoSales.js:615 seeds
    // AWAITING_FACTORY_RESPONSE instead, with a comment explaining why.
    COSTING_REVISION_REQUIRED: { label: 'CEO ตีกลับให้แก้ไขต้นทุน', tone: 'danger' },
    // Step 4/5 (these two were missing — a pre-existing gap fixed alongside adding
    // QUOTATION_ACCEPTED; QUOTATION_ISSUED fell back to the raw status string before).
    QUOTATION_ISSUED: { label: 'ออกใบเสนอราคาลูกค้าแล้ว', tone: 'success' },
    QUOTATION_ACCEPTED: { label: 'ลูกค้ายอมรับใบเสนอราคาแล้ว', tone: 'success' },
    // Retired by V140 along with the ขอข้อมูลเพิ่มเติม feature. Kept for the same two
    // reasons as COSTING_IN_PROGRESS above — the deploy-before-migration window, and
    // historical event rows — not because anything can still reach this status.
    MORE_INFO_REQUIRED: { label: 'รอข้อมูลเพิ่มเติม', tone: 'warning' },
    SUPERSEDED: { label: 'ถูกแทนที่แล้ว', tone: 'neutral' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'danger' },
};
export const PRICING_REQUEST_STATUS_LABEL_KEYS = Object.keys(PRICING_REQUEST_STATUS_LABELS);

export function pricingRequestStatusLabel(status) {
  return PRICING_REQUEST_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Mirrors FactoryQuoteStatus.VALUES (backend factoryquote/FactoryQuoteStatus.java). Key coverage
// guarded both ways by utils/statusCatalog.test.js.
const FACTORY_QUOTE_STATUS_LABELS = {
    DRAFT: { label: 'ร่างอีเมลถึงโรงงาน', tone: 'neutral' },
    REQUESTED: { label: 'ส่งขอราคาแล้ว', tone: 'warning' },
    RESPONSE_RECEIVED: { label: 'ได้รับราคาโรงงานแล้ว', tone: 'info' },
    NEGOTIATING: { label: 'กำลังเจรจา', tone: 'warning' },
    // Relabelled again 2026-08-16 (owner ruling, factory-price-import-ui redesign): "รอ CEO
    // อนุมัติราคา", not "ส่งให้ CEO แล้ว". The constant's NAME is still a fossil for the same reason
    // as before — V141 severed the costing aggregate (PricingCostingService's writes all throw 409
    // COSTING_MOVED_TO_CEO) and the cost is now computed by the CEO at startReview, so there is no
    // costing step for a quote to be "ready for" any more.
    //
    // The PREVIOUS label here ("ส่งให้ CEO แล้ว") was itself a deliberate 2026-08-15 correction of
    // an even older fossil ("พร้อมคำนวณต้นทุน") and reasoned explicitly against "รอ CEO อนุมัติราคา":
    // this badge sits on ONE FACTORY QUOTE, not on the request, and a request does not advance to
    // READY_FOR_CEO_REVIEW until EVERY current quote is marked ready (FactoryQuoteService#
    // markReadyForCosting, guarded by landedCosts.isFullyResolvable) — so with two factories, one
    // quote can sit here while another is still being negotiated, and "waiting for the CEO" would be
    // false for the REQUEST in that window even though it is true for THIS quote either way.
    //
    // The owner has now ruled the other way, explicitly requesting exactly one wording for this
    // state across the app (previously it read "ส่งให้ CEO แล้ว" on this badge but "รอ CEO อนุมัติราคา"
    // nowhere — there was no actual second wording to unify, just a badge whose copy didn't match
    // the mental model Import and the CEO both already used talking about it out loud). The
    // multi-factory caveat above is still true of the mechanism; it is no longer true of the label
    // the owner wants shown. See docs/api/status-catalog.json / StatusCatalogContractTest — that
    // guard pins the backend's STATUS KEYS only, never label TEXT (see statusCatalog.test.js's own
    // header comment), so this text-only rename needed no digest regeneration.
    READY_FOR_COSTING: { label: 'รอ CEO อนุมัติราคา', tone: 'success' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'danger' },
    // Both added 2026-08-16, found by statusCatalog.test.js on its first run — the guard's own
    // KNOWN_UNLABELLED list named them, and that list is self-cleaning, so adding these here is
    // what removes the exemption.
    //
    // SUPERSEDED was rendering the literal English string on a Thai screen, and not rarely:
    // FactoryQuoteService calls quotes.supersede() on EVERY revised quote, and
    // PricingRequestDetailPage renders quote history through this function unfiltered. So the
    // moment Import saved a second round of prices, the first one showed as "SUPERSEDED" beside
    // its own ครั้งที่ 1 badge. neutral, not danger: a superseded round is ordinary history, not
    // something that went wrong.
    SUPERSEDED: { label: 'รอบเก่า', tone: 'neutral' },
    // danger, matching CANCELLED: for THIS factory the line is a dead end and Import has to source
    // it elsewhere. Names the factory rather than the item ("ไม่มีสินค้า" alone) because several
    // factories are usually asked and only one declines.
    NOT_AVAILABLE: { label: 'โรงงานไม่มีสินค้า', tone: 'danger' },
};
export const FACTORY_QUOTE_STATUS_LABEL_KEYS = Object.keys(FACTORY_QUOTE_STATUS_LABELS);

export function factoryQuoteStatusLabel(status) {
  return FACTORY_QUOTE_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Status only. There is no header-level staleness to fold in any more: V141 dropped
// pricing_costing.stale/stale_reason (a cost computed at CEO-review time is current by
// construction), so the second `options` parameter this took had nothing left to read. The
// surviving per-LINE staleness is PricingCostingItemDto.overrideStale, rendered elsewhere.
export function pricingCostingStatusLabel(status) {
  const map = {
    DRAFT: { label: 'ร่างต้นทุน', tone: 'neutral' },
    CALCULATED: { label: 'คำนวณแล้ว', tone: 'warning' },
    SUBMITTED: { label: 'ส่งให้ CEO แล้ว', tone: 'success' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// Mirrors PricingDecisionStatus (backend pricingdecision/PricingDecisionStatus.java) — that class
// declares no aggregating VALUES/VALID field, so the guard reflects every public static final
// String it declares directly (all four genuinely are members; see StatusCatalogContractTest).
const PRICING_DECISION_STATUS_LABELS = {
  DRAFT: { label: 'รอพิจารณา', tone: 'warning' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  RETURNED: { label: 'ตีกลับให้แก้ไข', tone: 'danger' },
  SUPERSEDED: { label: 'ถูกแทนที่แล้ว', tone: 'neutral' },
};
export const PRICING_DECISION_STATUS_LABEL_KEYS = Object.keys(PRICING_DECISION_STATUS_LABELS);

export function pricingDecisionStatusLabel(status) {
  return PRICING_DECISION_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Mirrors Priority.VALID (backend ticket/Priority.java) — that class names its canonical set
// VALID rather than VALUES, and separately declares a DEFAULT alias (= NORMAL) with no vocabulary
// of its own; the backend digest reads VALID specifically to sidestep it.
const TICKET_PRIORITY_LABELS = {
  LOW:    { label: 'ต่ำ',   tone: 'neutral' },
  NORMAL: { label: 'กลาง',  tone: 'warning' },
  HIGH:   { label: 'สูง',   tone: 'danger' },
};
export const TICKET_PRIORITY_LABEL_KEYS = Object.keys(TICKET_PRIORITY_LABELS);

export function ticketPriorityLabel(priority) {
  return TICKET_PRIORITY_LABELS[priority] ?? { label: priority, tone: 'neutral' };
}

// "Who this is waiting on" -- rendered BESIDE a รออนุมัติ/pending status badge (leave/overtime/
// special-money), never baked into leaveStatusLabel/overtimeStatusLabel/specialMoneyStatusLabel
// themselves (those stay shared, domain-status -> {label, tone} only). Consumes the backend's
// read-only `pendingApproverRole`/`pendingApproverName` fields (LeaveRequestDto/OvertimeRequestDto/
// SpecialMoneyRequestDto -- see PendingApproverSql on the backend). `name` reuses greetingName's
// existing "คุณ"-prefix rule so "เอ็ม" renders "คุณเอ็ม", matching the pattern this file already
// uses everywhere else a person's name is greeted. Returns null (render nothing) when there is no
// role to show -- e.g. a non-pending row, where the backend never sets these fields.
const PENDING_APPROVER_ROLE_LABELS = {
  hr: 'ฝ่ายบุคคล',
  manager: 'ผู้จัดการ',
  ceo: 'CEO',
};

export function pendingApproverText(role, name) {
  if (!role) return null;
  const roleLabel = PENDING_APPROVER_ROLE_LABELS[role] || role.toUpperCase();
  // No name: either the backend never resolved one (e.g. more than one active person holds a
  // generic role -- see LeaveRepository/OvertimeRepository/SpecialMoneyRepository's
  // resolvePendingApproverName ambiguity handling) or this role never carries one. Either way,
  // showing the role alone is the honest fallback -- never a placeholder or a guessed name.
  return name ? `${roleLabel} (${greetingName(name)})` : roleLabel;
}

export function requestStatus(status) {
  const map = {
    pending: { label: 'รออนุมัติ', tone: 'warning' },
    approved: { label: 'อนุมัติแล้ว', tone: 'success' },
    rejected: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}

// Commission approval status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for commission status elsewhere.
//
// Mirrors CommissionStatus (backend commission/CommissionStatus.java) — no aggregating field
// there either, so the guard reflects every public static final String directly (all five,
// including VOID, are genuine members — VOID is unreachable BY DESIGN, per that class's own
// Javadoc, not unlabelled; it still gets a real Thai entry below in case that ever changes).
const COMMISSION_STATUS_LABELS = {
  SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
  MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  VOID: { label: 'ยกเลิก', tone: 'danger' },
};
export const COMMISSION_STATUS_LABEL_KEYS = Object.keys(COMMISSION_STATUS_LABELS);

export function commissionStatusLabel(status) {
  return COMMISSION_STATUS_LABELS[status] ?? { label: status, tone: 'neutral' };
}

// Overtime approval status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for overtime status elsewhere.
//
// `pendingApproverRole` is an OPTIONAL second argument, so every existing 1-arg call site keeps
// working. Every SUBMITTED row used to read 'รอผู้จัดการ', even the ones OvertimeService routes
// straight to the CEO (a manager-less ฝ่าย, or the requester IS a ผู้จัดการ) -- the row then also
// printed a `ผู้จัดการ: -` audit line that could never fill on that route, so the badge and the
// audit line named two different, contradictory approvers on the same request.
// specialMoneyStatusLabel below fixed this identical mislabel for welfare with a single STATIC
// string, because every welfare request is CEO-only in one stage -- overtime has two real routes
// PER REQUEST (see OvertimeRepository#resolvePendingApproverRole), so the caller must say which
// route THIS row took rather than assuming one shape for the whole domain.
// Mirrors OvertimeStatus (backend overtime/OvertimeStatus.java) — a Java ENUM, not a
// String-constant class: zero `public static final String` fields, so the backend digest reads
// it via Class#getEnumConstants() rather than field reflection. See StatusCatalogContractTest.
const OVERTIME_STATUS_LABELS = {
  SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
  MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
};
export const OVERTIME_STATUS_LABEL_KEYS = Object.keys(OVERTIME_STATUS_LABELS);

export function overtimeStatusLabel(status, pendingApproverRole) {
  if (status === 'SUBMITTED' && pendingApproverRole === 'ceo') {
    // Same label/tone the MANAGER_APPROVED entry below already uses -- from the employee's point
    // of view both states mean the same thing: the CEO holds this now.
    return { label: 'รอ CEO', tone: 'info' };
  }
  return OVERTIME_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Attendance-correction request status -> StatusBadge tone. Single CEO-only stage, same shape as
// special-money's status set (no MANAGER_APPROVED — there is no manager stage at all here).
//
// Mirrors AttendanceCorrectionStatus (backend attendance/correction/AttendanceCorrectionStatus.java)
// — also a Java enum; see OVERTIME_STATUS_LABELS above for why that changes the extraction strategy.
const ATTENDANCE_CORRECTION_STATUS_LABELS = {
  SUBMITTED: { label: 'รอ CEO', tone: 'warning' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
};
export const ATTENDANCE_CORRECTION_STATUS_LABEL_KEYS = Object.keys(ATTENDANCE_CORRECTION_STATUS_LABELS);

export function attendanceCorrectionStatusLabel(status) {
  return ATTENDANCE_CORRECTION_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Special-money (welfare) request status -> StatusBadge tone. Canonical source;
// do not re-add a page-local `statusInfo`/map elsewhere.
//
// Welfare does NOT share overtime's two-stage shape. Since #482 every welfare
// request goes straight to the CEO in a single stage, so `SUBMITTED` *is*
// "waiting for the CEO" — it was previously mislabelled 'รอผู้จัดการ', which
// told every employee the wrong next holder.
//
// `MANAGER_APPROVED` is retained because `SpecialMoneyStatus` and the
// `chk_smr_status` check constraint still carry it, and `SpecialMoneyService`
// still clears rows written before the manager stage was removed. It is not
// reachable for new requests. It reads the same to the employee — still the
// CEO's queue — so it is labelled the same, qualified rather than made to look
// like a distinct step.
// Mirrors SpecialMoneyStatus (backend specialmoney/SpecialMoneyStatus.java), a Java enum — see
// OVERTIME_STATUS_LABELS's comment above for why that changes the extraction strategy.
//
// SPECIAL_MONEY_STATUSES now DERIVES from the same labels object below (Object.keys) rather than
// being a second hand-typed literal of the same five codes. It used to be declared independently
// of specialMoneyStatusLabel's own map — the two happened to still agree, but nothing enforced
// that, which is the identical unguarded-mirror shape this whole guard exists to close; fixing it
// here costs nothing (same value, same order) while this function was already being touched for
// the digest guard.
const SPECIAL_MONEY_STATUS_LABELS = {
  SUBMITTED: { label: 'รอ CEO อนุมัติ', tone: 'warning' },
  MANAGER_APPROVED: { label: 'รอ CEO อนุมัติ (คำขอเดิม)', tone: 'warning' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  REJECTED: { label: 'ปฏิเสธ', tone: 'danger' },
  CANCELLED: { label: 'ยกเลิก', tone: 'neutral' },
};
export const SPECIAL_MONEY_STATUSES = Object.keys(SPECIAL_MONEY_STATUS_LABELS);

export function specialMoneyStatusLabel(status) {
  return SPECIAL_MONEY_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Leave request status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for leave status elsewhere.
//
// Mirrors LeaveStatus (backend leave/LeaveStatus.java), a Java enum — see
// OVERTIME_STATUS_LABELS's comment above for why that changes the extraction strategy.
const LEAVE_STATUS_LABELS = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
    AUTO_REJECTED: { label: 'โควตาไม่พอ', tone: 'danger' },
};
export const LEAVE_STATUS_LABEL_KEYS = Object.keys(LEAVE_STATUS_LABELS);

export function leaveStatusLabel(status) {
  return LEAVE_STATUS_LABELS[status] ?? { label: status || '-', tone: 'neutral' };
}

// Project sales-pipeline stage -> StatusBadge tone (V50, widened by V143).
//
// Canonical source for the WORDING; do not re-add a page-local map elsewhere. Stage order, phases,
// gates and the auto-advance flag are NOT here and are not the frontend's to declare — they come
// from GET /api/meta/deal-stages (see features/tickets/stageCatalog.js).
//
// Completeness against the backend's stage list is enforced twice, because a missing label is how
// the QUOTE_OWNER regression stayed invisible: at runtime by stageMeta.js's
// assertStageLabelsComplete (throws in dev/test, console.error in prod), and at build time by
// features/tickets/stageCatalog.test.js, which reads DealStage.java out of the backend source tree
// and fails if this map does not cover every constant in DealStage.ORDER.
const DEAL_STAGE_LABELS = {
  LEAD_APPROACH:       { label: 'เข้าถึงเจ้าของ/ผู้ออกแบบโครงการ', tone: 'neutral' },
  PRESENTATION:        { label: 'นำเสนอสินค้า', tone: 'info' },
  SPEC_APPROVED:       { label: 'ผู้ออกแบบอนุมัติสเปค', tone: 'info' },
  // V143 narrowed S4 to the DESIGNER only and gave the owner their own S5 below. The wording
  // follows: this stage no longer covers "เจ้าของ".
  QUOTE_DESIGN_SIDE:   { label: 'เสนอราคาผู้ออกแบบ', tone: 'info' },
  QUOTE_OWNER:         { label: 'เสนอราคาเจ้าของโครงการ', tone: 'info' },
  OWNER_SIGNOFF:       { label: 'เจ้าของอนุมัติสเปค', tone: 'success' },
  AWAITING_BUYER:      { label: 'รอผลประมูล / รอผู้ซื้อ', tone: 'warning' },
  QUOTE_BUYER:         { label: 'เสนอราคาผู้ซื้อ/ผู้รับเหมา', tone: 'info' },
  NEGOTIATION:         { label: 'เจรจา ติดตามใบสั่งซื้อ/มัดจำ', tone: 'warning' },
  ORDER_RECEIVED:      { label: 'ได้รับใบสั่งซื้อ', tone: 'success' },
  DEPOSIT_RECEIVED:    { label: 'ได้รับเงินมัดจำ', tone: 'success' },
  PROCUREMENT:         { label: 'จัดซื้อและนำเข้าสินค้า', tone: 'info' },
  DELIVERY_SCHEDULING: { label: 'นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ', tone: 'warning' },
  DELIVERED:           { label: 'ส่งมอบสินค้าครบถ้วน', tone: 'success' },
  CLOSED_PAID:         { label: 'ปิดงาน — รับเงินครบถ้วน', tone: 'success' },
};

/** Does this stage code have Thai display copy? The predicate behind the label guard. */
export function hasDealStageLabel(stage) {
  return Object.prototype.hasOwnProperty.call(DEAL_STAGE_LABELS, stage);
}

export function dealStageLabel(stage) {
  // The fallback renders the raw code rather than throwing: a shipped build must still draw the
  // deal. It is deliberately ugly, and the guard above is what makes sure nobody has to rely on
  // seeing it — assertStageLabelsComplete has already thrown in dev/test by this point.
  return DEAL_STAGE_LABELS[stage] ?? { label: stage || '-', tone: 'neutral' };
}

// Project lost reason -> StatusBadge tone (V50). Canonical source.
export function dealLostReasonLabel(reason) {
  const map = {
    PRODUCT_FIT:       'ไม่มีสินค้าที่ลูกค้าต้องการ',
    PRICE:             'ราคา',
    LEAD_TIME:         'ระยะเวลาส่งมอบ',
    PAYMENT_TERMS:     'เงื่อนไขการชำระเงิน',
    RELATIONSHIP:      'ความสัมพันธ์ / connection',
    PROJECT_ON_HOLD:   'โครงการหยุดก่อสร้างชั่วคราว',
    PROJECT_CANCELLED: 'โครงการหยุดก่อสร้างถาวร',
    ALREADY_PURCHASED: 'ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว',
  };
  return { label: map[reason] ?? reason ?? '-', tone: 'danger' };
}

export function dealLifecycleLabel(value) {
  const map = {
    ACTIVE:      { label: 'กำลังดำเนินการ', tone: 'success' },
    ON_HOLD:     { label: 'พักไว้ชั่วคราว', tone: 'warning' },
    DORMANT:     { label: 'พักยาว (dormant)', tone: 'neutral' },
    CLOSED_LOST: { label: 'เสียงาน', tone: 'danger' },
    CANCELLED:   { label: 'ยกเลิก', tone: 'danger' },
    COMPLETED:   { label: 'เสร็จสมบูรณ์', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

export function tenderRequirementLabel(value) {
  const map = {
    REQUIRED: 'ต้องประมูล',
    NOT_REQUIRED: 'ไม่ต้องประมูล',
    UNKNOWN: 'ยังไม่ทราบ',
  };
  return { label: map[value] ?? value ?? '-', tone: value === 'REQUIRED' ? 'warning' : 'neutral' };
}

export function depositPolicyLabel(value) {
  const map = {
    REQUIRED: 'ต้องเก็บมัดจำ',
    NOT_REQUIRED: 'ไม่เก็บมัดจำ',
    WAIVED: 'ยกเว้นมัดจำ',
    CREDIT_CUSTOMER: 'ลูกค้าเครดิต',
  };
  const tone = value === 'REQUIRED' ? 'neutral' : value === 'CREDIT_CUSTOMER' ? 'info' : 'warning';
  return { label: map[value] ?? value ?? '-', tone };
}

export function paymentStageLabel(value) {
  const map = {
    NOT_REQUIRED: { label: 'ไม่ต้องชำระ', tone: 'neutral' },
    DEPOSIT_PENDING: { label: 'รอมัดจำ', tone: 'warning' },
    DEPOSIT_RECEIVED: { label: 'รับมัดจำแล้ว', tone: 'success' },
    PARTIALLY_PAID: { label: 'ชำระบางส่วน', tone: 'warning' },
    BALANCE_PENDING: { label: 'รอชำระส่วนที่เหลือ', tone: 'warning' },
    FULLY_PAID: { label: 'ชำระครบแล้ว', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

export function overdueBadgeLabel(overdue) {
  return overdue
    ? { label: 'เกินกำหนด', tone: 'danger' }
    : { label: 'ยังไม่เกินกำหนด', tone: 'neutral' };
}

/**
 * Labels for sales.ticket.fulfillment_status — exactly FulfilmentStatus.java's seven codes.
 *
 * `PICKED_UP` is deliberately absent, and is NOT the same thing as the `PICKED_UP` in
 * DealHistoryPanel's event-kind map ('รับมอบหมาย' — Import took the ticket). That one is a
 * TicketEventKind and is live; this axis is fulfillment_status, which never held it. Both
 * `PICKED_UP` and `CUSTOMS_CLEARANCE` were deleted from FulfilmentStatus.java by 7991b9f1 as
 * dead constants. Do not re-add either here on the strength of seeing the name elsewhere.
 */
export function fulfilmentStatusLabel(value) {
  const map = {
    IR_ISSUED: { label: 'ออกคำขอนำเข้าแล้ว', tone: 'info' },
    IR_SENT: { label: 'ส่งคำขอนำเข้าแล้ว', tone: 'info' },
    SHIPPING: { label: 'สินค้าเดินทาง', tone: 'info' },
    GOODS_RECEIVED: { label: 'สินค้าถึงโกดังแล้ว', tone: 'success' },
    FROM_STOCK: { label: 'สินค้าจากสต็อก', tone: 'success' },
    PARTIALLY_DELIVERED: { label: 'ส่งมอบบางส่วน', tone: 'warning' },
    FULLY_DELIVERED: { label: 'ส่งมอบครบแล้ว', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

// Step 7: Factory Purchase Order and Import Execution — mirrors

export function entryChannelLabel(value) {
  const map = {
    DESIGNER_LED: 'ผู้ออกแบบนำดีล',
    OWNER_DIRECT: 'เจ้าของติดต่อโดยตรง',
    BUYER_DIRECT: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง',
    // Stored-only (V144 default): no channel was ever stated. Never offered as a choice.
    UNSPECIFIED: 'ยังไม่ระบุช่องทาง',
  };
  return { label: map[value] ?? value ?? '-', tone: 'neutral' };
}

export function quotationRecipientLabel(value) {
  const map = {
    DESIGNER: 'ผู้ออกแบบ',
    OWNER: 'เจ้าของ',
    BUYER: 'ผู้ซื้อ-ผู้รับเหมา',
    UNSPECIFIED: 'ไม่ระบุ',
  };
  return { label: map[value] ?? value ?? '-', tone: 'neutral' };
}

export function quotationStatusLabel(value) {
  const map = {
    DRAFT: { label: 'แบบร่าง', tone: 'neutral' },
    READY_TO_ISSUE: { label: 'พร้อมออกใบเสนอราคา', tone: 'warning' },
    ISSUED: { label: 'ออกแล้ว', tone: 'success' },
    SENT: { label: 'ส่งแล้ว', tone: 'info' },
    ACCEPTED: { label: 'ลูกค้ายอมรับ', tone: 'success' },
    REJECTED: { label: 'ลูกค้าปฏิเสธ', tone: 'danger' },
    REVISION_REQUESTED: { label: 'ลูกค้าขอแก้ไข', tone: 'warning' },
    EXPIRED: { label: 'หมดอายุ', tone: 'warning' },
    CANCELLED: { label: 'ยกเลิก', tone: 'danger' },
    SUPERSEDED: { label: 'ถูกแทนที่', tone: 'neutral' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

// CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155). Mirrors
// sales.quotation_item_discount_approval.status.
export function discountApprovalStatusLabel(value) {
  const map = {
    PENDING: { label: 'รอ CEO อนุมัติส่วนลด', tone: 'warning' },
    APPROVED: { label: 'CEO อนุมัติส่วนลดแล้ว', tone: 'success' },
    REJECTED: { label: 'CEO ปฏิเสธส่วนลด', tone: 'danger' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

// Payroll run status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for payroll status elsewhere.
export function payrollStatusLabel(status) {
  const map = {
    PREVIEW: { label: 'ตัวอย่าง', tone: 'info' },
    OPEN: { label: 'เปิดรอบ', tone: 'warning' },
    PROCESSED: { label: 'ประมวลผลแล้ว', tone: 'success' },
    CLOSED: { label: 'ปิดรอบ', tone: 'neutral' },
    VOID: { label: 'ยกเลิก', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// PayrollDeductionKind -> Thai label. Canonical source; do not re-add a page-local map.
//
// NOTE this is NOT the same enum as DeductionObligationKind (STUDENT_LOAN / LEGAL_EXECUTION),
// which labels an obligation record. PayrollDeductionKind labels a LINE on the payslip, has eight
// values, and spells garnishment as LEGAL_EXECUTION_GARNISHMENT. Wording follows the enum's own
// javadoc so the UI reads the same as the backend's source of truth.
export function payrollDeductionKindLabel(kind) {
  const map = {
    WITHHOLDING_TAX: 'ภาษีหัก ณ ที่จ่าย',
    SOCIAL_SECURITY: 'ประกันสังคม',
    STUDENT_LOAN: 'กยศ.',
    LEGAL_EXECUTION_GARNISHMENT: 'อายัดเงินเดือนตามหมายบังคับคดี',
    WARNING_LETTER: 'หักตามใบเตือน',
    CUSTOMER_RETURN: 'ลูกค้าคืนสินค้า',
    OTHER_PRETAX: 'หักอื่นๆ ก่อนภาษี',
    OTHER_POST_TAX: 'หักอื่นๆ หลังภาษี',
  };
  return map[kind] ?? kind ?? '-';
}

// The four PayrollDeductionKind values the written-consent register may record (issue #376,
// exposed for #744) — the deduction kinds where "did the employee sign a consent letter?" is even
// a question. Mirrors DeductionWrittenConsentService.CONSENT_APPLICABLE_KINDS, which is itself
// V107's `CHECK (deduction_kind IN (...))` constraint on hr.deduction_written_consent.
//
// The other four are excluded by the backend, which 400s on them, so offering them would build a
// picker whose options the server rejects:
//   WITHHOLDING_TAX / SOCIAL_SECURITY / STUDENT_LOAN — ม.76 item (1); deductible without consent.
//   LEGAL_EXECUTION_GARNISHMENT                      — a court order; consent is irrelevant to it.
//
// mockApi.js keeps its own mirror of this list (it mirrors the Java service independently, by
// design). The two are pinned to each other by mockApi.deductionConsents.test.js, because a value
// mirrored into mockApi.js otherwise has NO guard — contract.test.js compares names and parameter
// counts, never values.
export const CONSENT_APPLICABLE_DEDUCTION_KINDS = [
  'WARNING_LETTER',
  'CUSTOMER_RETURN',
  'OTHER_PRETAX',
  'OTHER_POST_TAX',
];

// Bangkok is the business zone for every attendance/leave/overtime date. Deriving "today" from
// the browser's zone instead makes a late-evening session disagree with the server about which
// day it is — which matters most on the attendance page, whose primary control is a date stepper.
export function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Bangkok',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    })
      .formatToParts(date)
      .map((part) => [part.type, part.value]),
  );
}

export function bangkokTodayIso(date = new Date()) {
  const parts = bangkokDateParts(date);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

// `monthsBack` walks calendar months on the Bangkok wall-clock date, not milliseconds -- a
// millisecond subtraction is the wrong tool for month arithmetic (months aren't a fixed length),
// and doing it on the Intl-derived {year, month} parts (rather than a naive Date field mutation)
// keeps this consistent with bangkokTodayIso's pattern above. `Math.floor`/modulo on a flat
// "months since epoch" count handles the year rollover for free (e.g. Feb minus 3 months lands on
// the previous November) without any special-casing.
export function bangkokMonthStartIso(date = new Date(), monthsBack = 0) {
  const parts = bangkokDateParts(date);
  const totalMonths = Number(parts.year) * 12 + (Number(parts.month) - 1) - monthsBack;
  const targetYear = Math.floor(totalMonths / 12);
  const targetMonth = (totalMonths % 12) + 1;
  return `${targetYear}-${String(targetMonth).padStart(2, '0')}-01`;
}

// Pure calendar-day arithmetic on a YYYY-MM-DD string. Deliberately does NOT round-trip through
// `Date#toISOString()`: parsing "YYYY-MM-DDT00:00:00+07:00" and reading the date back off
// `toISOString()` reads the UTC calendar day off a Bangkok-midnight instant, which is always one
// day behind the Bangkok date that was intended (Bangkok midnight is 17:00 UTC the PREVIOUS day) --
// that mismatch is what made the attendance date stepper net a 2-day back-step and a stuck
// forward-step. `Date.UTC` here is used purely as neutral day-count arithmetic on the calendar
// fields (year/month/day), never as a timezone conversion -- there is no timezone attached to a
// bare YYYY-MM-DD, so none should be introduced by parsing it as one.
export function addDaysIso(iso, deltaDays) {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + deltaDays);
  const y = String(date.getUTCFullYear()).padStart(4, '0');
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

// Clock time only, pinned to Bangkok. Used by the attendance day table, where the date already
// labels the row and repeating it in every cell is noise.
export function formatBangkokTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

// Minutes -> "8:45". Hours are the unit people reason about for a working day; raw minutes
// ("525") force arithmetic at every glance.
export function formatDuration(minutes) {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return '-';
  const safe = Math.max(0, Math.round(minutes));
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`;
}

// Attendance day status -> StatusBadge tone. Canonical source; do not re-add a page-local map.
//
// NOTE: late/early-leave are REPORTING ONLY. Thai Labour Protection Act §76 forbids deducting
// wages as a penalty for lateness or absence, so these badges must never gain a "deduction"
// affordance. Tones are informational (warning), never punitive (danger).
export function attendanceStatusLabel(status) {
  const map = {
    PRESENT: { label: 'ปกติ', tone: 'success' },
    LATE: { label: 'มาสาย', tone: 'warning' },
    // Marked present with no punches (CEO/HR stand-up roster or WFH) — distinct tone from both
    // PRESENT (success/green) and LATE (warning/orange) so it reads as its own category, not a
    // variant of either.
    WFH: { label: 'WFH', tone: 'info' },
    MISSING_CHECK_IN: { label: 'ขาดสแกนเข้า', tone: 'warning' },
    MISSING_CHECK_OUT: { label: 'ขาดสแกนออก', tone: 'warning' },
    NON_WORKDAY: { label: 'วันหยุด', tone: 'neutral' },
    // Distinct from NON_WORKDAY: a company holiday (นักขัตฤกษ์) carries a different pay meaning
    // for a rostered ฝ่ายขาย shift (OT-eligible) than an ordinary Saturday does (not) — see
    // AttendanceDayFlag#HOLIDAY on the backend. Same neutral tone as NON_WORKDAY: neither is a
    // problem to flag, just a different reason there is nothing to report.
    HOLIDAY: { label: 'วันหยุดนักขัตฤกษ์', tone: 'neutral' },
    NO_RECORD: { label: '-', tone: 'neutral' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// Per-day flags -> badges. A day can carry several (late in AND early out), so this returns a
// list. Minutes are baked into the label because "มาสาย" alone prompts "by how much?".
export function attendanceFlagLabels(day) {
  if (!day) return [];
  const labels = [];
  if (day.late_minutes > 0) {
    labels.push({ key: 'LATE', label: `สาย ${day.late_minutes} นาที`, tone: 'warning' });
  }
  if (day.early_leave_minutes > 0) {
    labels.push({
      key: 'EARLY_LEAVE',
      label: `ออกก่อน ${day.early_leave_minutes} นาที`,
      tone: 'warning',
    });
  }
  const flags = day.flags ?? [];
  if (flags.includes('WFH')) {
    labels.push({ key: 'WFH', label: 'WFH', tone: 'info' });
  }
  if (flags.includes('MISSING_CHECK_IN')) {
    labels.push({ key: 'MISSING_CHECK_IN', label: 'ขาดสแกนเข้า', tone: 'warning' });
  }
  if (flags.includes('MISSING_CHECK_OUT')) {
    labels.push({ key: 'MISSING_CHECK_OUT', label: 'ขาดสแกนออก', tone: 'warning' });
  }
  if (day.overtime_minutes > 0) {
    labels.push({
      key: 'OVERTIME_APPROVED',
      label: `โอที ${formatDuration(day.overtime_minutes)}`,
      tone: 'info',
    });
  } else if (flags.includes('WORKED_LATE_UNAPPROVED')) {
    // Deliberately NOT called overtime: overtime pay requires approval, and promising it here
    // would set an expectation payroll will not meet.
    labels.push({ key: 'WORKED_LATE_UNAPPROVED', label: 'ออกช้า', tone: 'neutral' });
  }
  if (flags.includes('HOLIDAY')) {
    labels.push({ key: 'HOLIDAY', label: 'วันหยุดนักขัตฤกษ์', tone: 'neutral' });
  } else if (flags.includes('NON_WORKDAY')) {
    labels.push({ key: 'NON_WORKDAY', label: 'วันหยุด', tone: 'neutral' });
  }
  return labels;
}

// Where a day's attendance came from, derived from the daily row's site_code. Punched days carry
// the check-in punch's site (SHOWROOM / WAREHOUSE); a CEO/HR "marked present" day with no scans is
// written with site_code 'WFH'. Returns null for days with no record so the caller can render "-".
export function attendanceSourceLabel(day) {
  const map = {
    SHOWROOM: 'Showroom',
    WAREHOUSE: 'Warehouse',
    WFH: 'WFH',
  };
  const site = day?.site_code;
  if (!site) return null;
  return map[site] ?? site;
}
