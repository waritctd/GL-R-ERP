// Status-badge + "current declaration" selection for the ล.ย.01 workflow (issue #387). Mirrors
// TaxAllowanceDeclarationStatus (backend/.../declaration/TaxAllowanceDeclarationStatus.java)
// exactly: PENDING / APPROVED / REJECTED / SUPERSEDED / EXPIRED / WITHDRAWN. `appliedAt` is a FLAG
// on an APPROVED row, not a status of its own — see that enum's javadoc — so "applied" here is
// derived the same way the backend documents it (`appliedAt != null`), not a 7th status string.
//
// Same idiom as `requestStatus`/`leaveStatusLabel`/`overtimeStatusLabel` in utils/format.js
// ("Canonical source; do not re-add a page-local map elsewhere") — kept in this feature folder
// rather than format.js because the label additionally depends on `appliedAt`/`appliedEffectiveMonth`/
// `reviewerNote`, not just the bare status string those helpers key off.

// SUPERSEDED/WITHDRAWN are terminal, non-current rows (a newer declaration replaced them, or the
// employee pulled the submission back) — they never surface as "the" status, only PENDING/
// APPROVED/REJECTED/EXPIRED do. Mirrors how ProfilePage/EmployeeSelfService never show a
// cancelled/rejected request as if it were still live.
const NON_CURRENT_STATUSES = new Set(['SUPERSEDED', 'WITHDRAWN']);

/**
 * Picks the one declaration that represents "the employee's current standing" for a tax year, out
 * of every row `getMyTaxAllowanceDeclarations`/`getTaxAllowanceDeclarations` returns (which include
 * full history — old REJECTED rows, WITHDRAWN drafts, SUPERSEDED approvals). The most recently
 * submitted non-terminal row wins; `items` is expected pre-sorted newest-first (both hrApi and
 * mockApi return it that way), but this re-sorts defensively rather than trusting that ordering.
 */
export function selectCurrentDeclaration(items = []) {
  const candidates = items.filter((item) => !NON_CURRENT_STATUSES.has(item.status));
  if (candidates.length === 0) return null;
  return [...candidates].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

/**
 * Picks the declaration a "resume" flow should pre-fill a form from when there is no CURRENT
 * declaration (`selectCurrentDeclaration` returned null) -- specifically, the most recently
 * submitted WITHDRAWN row for this tax year, if one exists. Same defensive newest-first re-sort on
 * `submittedAt` as `selectCurrentDeclaration` above, for the same reason (trust the shape, not the
 * ordering).
 *
 * This is PREFILL ONLY and changes nothing about "current standing": `NON_CURRENT_STATUSES` and
 * `selectCurrentDeclaration` are untouched by this function, so `selectCurrentDeclaration` keeps
 * returning null after a withdrawal and `taxAllowanceStatusInfo(null)` keeps reporting the honest
 * `ยังไม่ได้ยื่น` badge -- a withdrawn declaration genuinely is not the employee's current standing.
 * The only caller that should ever read this is TaxAllowancePage's `defaultValues` memo (fixing the
 * defect where withdrawing a PENDING declaration wiped every value the employee had typed, directly
 * contradicting the PENDING banner's "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw dialog's "กลับมาแก้ไข"
 * promises) -- never `statusInfo`/`canStartEdit`/`evidenceMode`, which must keep reading `current`.
 *
 * Deliberately excludes SUPERSEDED: a superseded row was replaced by a newer APPROVED declaration,
 * so resuming from it would resurrect stale figures the employee no longer stands behind. Only
 * WITHDRAWN is a user-initiated "I want this back".
 */
export function selectResumableDeclaration(items = []) {
  const withdrawn = items.filter((item) => item.status === 'WITHDRAWN');
  if (withdrawn.length === 0) return null;
  return [...withdrawn].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

// Compact "chip" copy per derived status KEY — not the raw backend `status` column (there is no
// backend status called APPROVED_UNAPPLIED or APPLIED; those two are this module's own split of
// APPROVED by `appliedAt`, see taxAllowanceStatusInfo below). A filter chip stands for a whole
// bucket of declarations, not one row in hand, so it cannot reuse the long-form `label` below where
// that form is per-declaration text (APPLIED's `label` interpolates one specific effective month;
// a chip has no single month to show). This is the one place the short copy lives — callers that
// need a label for a status KEY with no declaration in hand (TaxAllowanceReviewPage's STATUS_CHIPS)
// read it via `taxAllowanceStatusShortLabel` instead of keeping their own literals, which is how
// APPROVED_UNAPPLIED and EXPIRED ended up reading differently on the register than on the badge.
const STATUS_SHORT_LABELS = {
  NONE: 'ยังไม่ได้ยื่น',
  PENDING: 'รอ HR ตรวจสอบ',
  REJECTED: 'ปฏิเสธ',
  EXPIRED: 'หมดอายุ',
  APPROVED_UNAPPLIED: 'ยังไม่ใช้กับเงินเดือน',
  APPLIED: 'ใช้กับเงินเดือนแล้ว',
};

/**
 * Short chip label for a status KEY, e.g. `taxAllowanceStatusShortLabel('EXPIRED')` — the one
 * place this short copy lives. A chip needs a label for every status a filter row can show BEFORE
 * any one declaration is in view — most obviously NONE, which `taxAllowanceStatusInfo` can only
 * ever produce by being handed `null`, not a key — so this stays a separate, declaration-free
 * lookup rather than a field tacked onto that function's return value (review-remediation: a
 * `.shortLabel` field lived there briefly and had zero callers — `taxAllowanceStatusInfo` is keyed
 * off a full declaration, which every chip-building caller lacks by construction, so nothing could
 * ever have read it). An unrecognised key falls back to itself so a status this map hasn't been
 * taught about yet renders as its own name instead of blank.
 */
export function taxAllowanceStatusShortLabel(key) {
  return STATUS_SHORT_LABELS[key] ?? key;
}

/** Six distinct states per issue #387 screen 1, each with its own Thai copy and StatusBadge tone. */
export function taxAllowanceStatusInfo(declaration) {
  if (!declaration) {
    return { label: 'ยังไม่ได้ยื่น', tone: 'neutral', key: 'NONE' };
  }
  const { status, appliedAt, appliedEffectiveMonth, reviewerNote } = declaration;
  if (status === 'PENDING') {
    return { label: 'รอ HR ตรวจสอบ', tone: 'warning', key: 'PENDING' };
  }
  if (status === 'REJECTED') {
    return { label: 'ปฏิเสธ', tone: 'danger', key: 'REJECTED', note: reviewerNote || null };
  }
  if (status === 'EXPIRED') {
    return { label: 'หมดอายุ — ต้องยืนยันใหม่', tone: 'danger', key: 'EXPIRED' };
  }
  if (status === 'APPROVED') {
    if (appliedAt) {
      return {
        label: `ใช้กับเงินเดือนแล้ว ตั้งแต่เดือน ${appliedEffectiveMonth ?? '-'}`,
        // Deliberately NOT the same tone as "approved, not yet applied" — issue #387: "the
        // approved-but-unapplied state must not read as done". `success` here is genuinely
        // terminal-good; the unapplied state below stays `info` so it never reads the same.
        tone: 'success',
        key: 'APPLIED',
      };
    }
    return {
      label: 'อนุมัติแล้ว — ยังไม่ใช้กับเงินเดือน',
      tone: 'info',
      key: 'APPROVED_UNAPPLIED',
    };
  }
  return { label: status || 'ไม่ทราบสถานะ', tone: 'neutral', key: status || 'UNKNOWN' };
}
