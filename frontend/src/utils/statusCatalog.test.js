import { describe, expect, it } from 'vitest';
import { statusGroups } from './statusCatalog.js';
import {
  ATTENDANCE_CORRECTION_STATUS_LABEL_KEYS,
  COMMISSION_STATUS_LABEL_KEYS,
  FACTORY_QUOTE_STATUS_LABEL_KEYS,
  LEAVE_STATUS_LABEL_KEYS,
  OVERTIME_STATUS_LABEL_KEYS,
  PRICING_DECISION_STATUS_LABEL_KEYS,
  PRICING_REQUEST_STATUS_LABEL_KEYS,
  ROLE_LABEL_KEYS,
  SPECIAL_MONEY_STATUSES,
  TICKET_PRIORITY_LABEL_KEYS,
  TICKET_STATUS_LABEL_KEYS,
  attendanceCorrectionStatusLabel,
  commissionStatusLabel,
  factoryQuoteStatusLabel,
  leaveStatusLabel,
  overtimeStatusLabel,
  pricingDecisionStatusLabel,
  pricingRequestStatusLabel,
  roleLabel,
  specialMoneyStatusLabel,
  ticketPriorityLabel,
  ticketStatusLabel,
} from './format.js';

/** THE GUARD THIS FILE EXISTS TO ADD. Every backend status has a frontend label (`KNOWN_UNLABELLED` is the
 * exemption list) — a new or renamed status must not silently render as its raw code. THE DIGEST. 2. */

const GROUPS = statusGroups();

/** `roleLabel`'s own presence check: does `ROLE_LABELS` (format.js's private backing map) declare this code? */
const hasRoleLabelKey = (code) => ROLE_LABEL_KEYS.includes(code);

/** Every map this digest covers, paired with its backend group name and the frontend function/key list that
 * back it. `keys` always comes from format.js's own `Object.keys(...)` export — never a second hand-typed list
 * — so there is nothing here for `keys` itself to drift from. */
const MAP_SPECS = [
  { name: 'ticketStatusLabel', labelFn: ticketStatusLabel, keys: TICKET_STATUS_LABEL_KEYS, group: 'TicketStatus' },
  { name: 'pricingRequestStatusLabel', labelFn: pricingRequestStatusLabel, keys: PRICING_REQUEST_STATUS_LABEL_KEYS, group: 'PricingRequestStatus' },
  { name: 'factoryQuoteStatusLabel', labelFn: factoryQuoteStatusLabel, keys: FACTORY_QUOTE_STATUS_LABEL_KEYS, group: 'FactoryQuoteStatus' },
  { name: 'pricingDecisionStatusLabel', labelFn: pricingDecisionStatusLabel, keys: PRICING_DECISION_STATUS_LABEL_KEYS, group: 'PricingDecisionStatus' },
  { name: 'commissionStatusLabel', labelFn: commissionStatusLabel, keys: COMMISSION_STATUS_LABEL_KEYS, group: 'CommissionStatus' },
  { name: 'ticketPriorityLabel', labelFn: ticketPriorityLabel, keys: TICKET_PRIORITY_LABEL_KEYS, group: 'Priority' },
  { name: 'overtimeStatusLabel', labelFn: overtimeStatusLabel, keys: OVERTIME_STATUS_LABEL_KEYS, group: 'OvertimeStatus' },
  { name: 'attendanceCorrectionStatusLabel', labelFn: attendanceCorrectionStatusLabel, keys: ATTENDANCE_CORRECTION_STATUS_LABEL_KEYS, group: 'AttendanceCorrectionStatus' },
  { name: 'specialMoneyStatusLabel', labelFn: specialMoneyStatusLabel, keys: SPECIAL_MONEY_STATUSES, group: 'SpecialMoneyStatus' },
  { name: 'leaveStatusLabel', labelFn: leaveStatusLabel, keys: LEAVE_STATUS_LABEL_KEYS, group: 'LeaveStatus' },
  { name: 'roleLabel', labelFn: roleLabel, keys: ROLE_LABEL_KEYS, group: 'DivisionAccessPolicyRole', hasKey: hasRoleLabelKey },
];

const specFor = (name) => MAP_SPECS.find((spec) => spec.name === name);

/** A value no real backend status will ever equal, used to learn each function's fallback shape. */
const UNKNOWN_SENTINEL = '__STATUS_CATALOG_UNKNOWN_PROBE__';

/** True when `labelFn(code)` fell through to the generic "no entry for this key" branch, rather than an
 * intentional map entry. `roleLabel` cannot use the same echo-match: it returns a plain string built by
 * `ROLE_LABELS[role] ?? */
function isUnlabelled(labelFn, code, hasKey) {
  if (hasKey) {
    return !hasKey(code);
  }
  const probe = labelFn(UNKNOWN_SENTINEL);
  const actual = labelFn(code);
  return actual.label === code && actual.tone === probe.tone;
}

// Direction 1 exemptions — backend statuses with NO frontend label today
// ───────────────────────────────────────────────────────────────────────────── EMPTY, and that is the
// assertion — not an oversight.
const KNOWN_UNLABELLED = {};

// Direction 2 exemptions.
const FRONTEND_ONLY_KEYS = {
  pricingRequestStatusLabel: {
    COSTING_IN_PROGRESS:
      'Retired by V140 (merged into AWAITING_FACTORY_RESPONSE, dropped from the DB constraint) — '
      + 'no Java constant of this name exists any more, so reflection correctly never produces it. '
      + 'Kept in the frontend map per its own inline comment: rows can sit in it during the '
      + 'deploy-before-migration window, and historical pricing_request_event rows keep the old '
      + 'value forever. Deliberate and permanent, not drift.',
    COSTING_REVISION_REQUIRED:
      'Retired by V141 (the CEO owns costing now; PricingDecisionService.returnToImport routes '
      + 'straight to AWAITING_FACTORY_RESPONSE). Kept for the identical '
      + 'deploy-before-migration-window and historical-event-row reasons as COSTING_IN_PROGRESS.',
    MORE_INFO_REQUIRED:
      'Retired by V140 along with the ขอข้อมูลเพิ่มเติม feature entirely. Kept for the same '
      + 'deploy-before-migration-window and historical-event-row reasons as its two neighbours.',
  },
};

describe('status catalog digest — harness sanity', () => {
  // These run first so a digest that failed to load, or a format.js export that came back undefined, fails
  // LOUDLY instead of letting every assertion below pass vacuously.
  it('read a plausible digest', () => {
    expect(Object.keys(GROUPS).length).toBeGreaterThanOrEqual(10);
    const total = Object.values(GROUPS).reduce((sum, list) => sum + list.length, 0);
    expect(total).toBeGreaterThan(50);
  });
});

describe('direction 1 — every backend status has a frontend label', () => {
  it('no backend status renders as its own raw code, undocumented', () => {
    const missing = [];
    for (const spec of MAP_SPECS) {
      const exemptions = KNOWN_UNLABELLED[spec.name] ?? {};
      for (const code of GROUPS[spec.group]) {
        if (exemptions[code]) continue;
        if (isUnlabelled(spec.labelFn, code, spec.hasKey)) {
          missing.push(`${spec.name}: ${spec.group}.${code}`);
        }
      }
    }
    expect(
      missing,
      'backend statuses with no frontend label and no KNOWN_UNLABELLED entry — a new or renamed '
      + 'status renders as its raw code to the user. Add a Thai label in format.js, or if it is '
      + 'genuinely not reachable from any screen yet, document it in KNOWN_UNLABELLED with a reason.',
    ).toEqual([]);
  });
});

describe('direction 2 — every frontend label key still exists in the backend', () => {
  it('no frontend label key is dead copy for a retired/renamed status, undocumented', () => {
    const stale = [];
    for (const spec of MAP_SPECS) {
      const backendCodes = new Set(GROUPS[spec.group]);
      const exemptions = FRONTEND_ONLY_KEYS[spec.name] ?? {};
      for (const key of spec.keys) {
        if (backendCodes.has(key)) continue;
        if (exemptions[key]) continue;
        stale.push(`${spec.name}: ${key}`);
      }
    }
    expect(
      stale,
      'frontend label keys with no backend counterpart and no FRONTEND_ONLY_KEYS entry — a '
      + 'retired status lingering as dead copy (the READY_FOR_COSTING shape, on a KEY instead of '
      + 'text). Delete the entry from format.js, or if it is a deliberate historical/fallback key, '
      + 'document it in FRONTEND_ONLY_KEYS with a reason.',
    ).toEqual([]);
  });
});

describe('exemption lists stay honest', () => {
  it('every KNOWN_UNLABELLED entry names a real map, a real backend status, and is still unlabelled', () => {
    const stale = [];
    for (const [specName, entries] of Object.entries(KNOWN_UNLABELLED)) {
      const spec = specFor(specName);
      expect(spec, `KNOWN_UNLABELLED names "${specName}", which is not a covered map`).toBeTruthy();
      const backendCodes = new Set(GROUPS[spec.group]);
      for (const [code, reason] of Object.entries(entries)) {
        expect(reason.length, `KNOWN_UNLABELLED["${specName}"]["${code}"] needs a reason`).toBeGreaterThan(20);
        if (!backendCodes.has(code)) {
          stale.push(`${specName}.${code} — no longer a backend status; delete the entry`);
        } else if (!isUnlabelled(spec.labelFn, code, spec.hasKey)) {
          stale.push(`${specName}.${code} — now has a real label; delete the entry`);
        }
      }
    }
    expect(stale).toEqual([]);
  });

  it('every FRONTEND_ONLY_KEYS entry names a real map, a real map key, and is still absent from the backend', () => {
    const stale = [];
    for (const [specName, entries] of Object.entries(FRONTEND_ONLY_KEYS)) {
      const spec = specFor(specName);
      expect(spec, `FRONTEND_ONLY_KEYS names "${specName}", which is not a covered map`).toBeTruthy();
      const backendCodes = new Set(GROUPS[spec.group]);
      for (const [key, reason] of Object.entries(entries)) {
        expect(reason.length, `FRONTEND_ONLY_KEYS["${specName}"]["${key}"] needs a reason`).toBeGreaterThan(20);
        if (!spec.keys.includes(key)) {
          stale.push(`${specName}.${key} — no longer a label key; delete the entry`);
        } else if (backendCodes.has(key)) {
          stale.push(`${specName}.${key} — the backend defines it again; delete the entry`);
        }
      }
    }
    expect(stale).toEqual([]);
  });
});
