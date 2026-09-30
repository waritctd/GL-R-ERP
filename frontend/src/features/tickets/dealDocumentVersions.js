/**
 * Pure helpers for DealDocumentRegister's "all versions of each document" view.
 * No React, no API — grouping and ordering only, so the rules are unit-testable.
 */

// Revisions are the base number plus a short `-<n>` suffix (QT-2026-0016-3). Only a 1-2 digit
// tail is treated as a revision suffix: a bare number such as QT-2026-0901 ends in a 4-digit
// running sequence, and stripping that would merge unrelated documents.
export function baseNumberOf(number) {
  if (!number) return null;
  return String(number).replace(/-\d{1,2}$/, '');
}

function timeOf(value) {
  const t = value ? Date.parse(value) : NaN;
  return Number.isFinite(t) ? t : 0;
}

/**
 * Group rows into documents. Versions inside a group are newest first (version desc, id desc as
 * the tie-break); groups are ordered by their newest version's date, then id, descending.
 *
 * @param rows      the rows to group
 * @param keyOf     row -> group key (rows with no usable key should return a unique one)
 * @param versionOf row -> numeric version (missing counts as 0)
 * @param dateOf    row -> date string used only to order groups
 */
export function groupVersions(rows, { keyOf, versionOf, dateOf }) {
  const byKey = new Map();
  for (const row of rows) {
    const key = keyOf(row);
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(row);
  }
  const newestFirst = (a, b) => (Number(versionOf(b) ?? 0) - Number(versionOf(a) ?? 0)) || (Number(b.id) - Number(a.id));
  const groups = [...byKey.entries()].map(([key, versions]) => {
    const sorted = [...versions].sort(newestFirst);
    const newest = sorted.reduce((m, v) => (timeOf(dateOf(v)) > timeOf(dateOf(m)) ? v : m), sorted[0]);
    return { key, versions: sorted, sortTime: timeOf(dateOf(newest)), sortId: Number(sorted[0].id) };
  });
  return sortGroupsNewestFirst(groups);
}

/** Order groups by their newest version's date, then id, descending. Works across families. */
export function sortGroupsNewestFirst(groups) {
  return [...groups].sort((a, b) => (b.sortTime - a.sortTime) || (b.sortId - a.sortId));
}

/** Legacy ticket rows that are ALSO in the customer-quotation chain (same id) render once, as the chain row. */
export function dropLegacyDuplicatedByChain(legacyRows, chainRows) {
  const chainIds = new Set(chainRows.map((r) => r.id));
  return legacyRows.filter((r) => !chainIds.has(r.id));
}

// Group keys are prefixed with the family so keys stay unique once families are merged.
// Chain: revisions of one pricing request are ONE document even when their numbers are unrelated
// (revisions before 2026-09-18 got fresh numbers, CustomerQuotationService).
export const groupChainQuotations = (rows) => groupVersions(rows, {
  keyOf: (r) => (r.pricingRequestId != null ? `chain:pr-${r.pricingRequestId}` : `chain:id-${r.id}`),
  versionOf: (r) => r.quotationRevisionNo,
  dateOf: (r) => r.issuedAt ?? r.createdAt,
});
// Legacy: every version gets a fresh bare number, but `quotationVersion` is counted per
// (ticket, recipientType) — so recipientType is what identifies the document.
export const groupLegacyQuotations = (rows) => groupVersions(rows, {
  keyOf: (r) => `legacy:${r.recipientType ?? 'UNSPECIFIED'}`,
  versionOf: (r) => r.quotationVersion,
  dateOf: (r) => r.issuedAt,
});
// Direct: revisions share `{base}-n`. Strip exactly `-${revisionNo}` when the number ends with it
// (mirrors QuotationNumbering.baseNumber); a number that does not end with it is returned
// unchanged. The short-suffix regex is only a guess for rows with no revisionNo at all.
function directBaseNumber(r) {
  if (!r.number) return null;
  if (r.revisionNo == null) return baseNumberOf(r.number);
  const suffix = `-${r.revisionNo}`;
  return String(r.number).endsWith(suffix) ? String(r.number).slice(0, -suffix.length) : String(r.number);
}
export const groupDirectQuotations = (rows) => groupVersions(rows, {
  keyOf: (r) => {
    const base = directBaseNumber(r);
    return base ? `direct:${base}` : `direct:id-${r.id}`;
  },
  versionOf: (r) => r.revisionNo,
  dateOf: (r) => r.approvedAt ?? r.createdAt,
});

/**
 * Stored remaining invoices, grouped by baseNumber. A DRAFT has no baseNumber yet. The backend
 * (RemainingInvoiceRepository#lockIssuedPredecessor) continues a base ONLY from an ISSUED row with
 * the same customer_quotation_id (null matches null), so a draft joins only a group holding such an
 * ISSUED row, as its newest (top) row. A SUPERSEDED-only group is never adopted; otherwise the
 * draft stands alone.
 */
export function groupRemainingInvoices(rows) {
  const numbered = rows.filter((r) => r.baseNumber);
  const drafts = rows.filter((r) => !r.baseNumber);
  const groups = groupVersions(numbered, {
    keyOf: (r) => `remaining:${r.baseNumber}`, versionOf: (r) => r.version, dateOf: (r) => r.issuedAt ?? r.createdAt,
  });
  const standalone = [];
  for (const d of drafts) {
    const home = groups.find((g) => g.versions.some(
      (v) => v.status === 'ISSUED' && (v.customerQuotationId ?? null) === (d.customerQuotationId ?? null),
    ));
    if (home) {
      home.versions.unshift(d);
      // The draft is now this group's newest version: refresh its sort key.
      home.sortTime = Math.max(home.sortTime, timeOf(d.createdAt));
      home.sortId = Math.max(home.sortId, Number(d.id));
    } else standalone.push(d);
  }
  const alone = groupVersions(standalone, {
    keyOf: (r) => `remaining:draft-${r.id}`, versionOf: (r) => r.version, dateOf: (r) => r.createdAt,
  });
  return sortGroupsNewestFirst([...groups, ...alone]);
}

// One deposit notice per deal, many versions -> a single group. The API returns newest first but
// the mock does not, so the order is imposed here.
export const groupDepositNotices = (rows) => groupVersions(rows, {
  keyOf: () => 'deposit', versionOf: (r) => r.version, dateOf: (r) => r.issueDate ?? r.createdAt,
});
