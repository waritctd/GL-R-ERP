import { describe, it, expect } from 'vitest';
import { api as hrApi } from './hrApi.js';
import { api as mockApi } from './mockApi.js';

// Guards the mock/real API contract at the level of *method names* — see #201. The direction that matters is
// hrApi → mockApi: every endpoint the app can call must exist in the mock, or `VITE_USE_MOCKS=true`
// verification hits "TypeError: api.x.y is not a function" on click.

const KNOWN_GAPS = {
  // (empty) The former `documents` exemption is gone: the orphaned DocumentPage.jsx, its hrApi namespace, and
  // the broken backend document/ module were deleted together — the deposit-notice stack is the single
  // implementation now.
};

function methodPaths(apiObject) {
  return Object.entries(apiObject).flatMap(([namespace, methods]) =>
    Object.keys(methods).map((method) => `${namespace}.${method}`)
  );
}

function isImplemented(apiObject, namespace, method) {
  return typeof apiObject[namespace]?.[method] === 'function';
}

describe('mockApi / hrApi method contract', () => {
  it('mockApi implements every method hrApi exposes, except documented gaps', () => {
    const missing = methodPaths(hrApi).filter((path) => {
      const [namespace, method] = path.split('.');
      if (KNOWN_GAPS[namespace]) return false;
      return !isImplemented(mockApi, namespace, method);
    });

    expect(missing).toEqual([]);
  });

  it('mockApi has no dead methods that hrApi does not expose', () => {
    const dead = methodPaths(mockApi).filter((path) => {
      const [namespace, method] = path.split('.');
      return !isImplemented(hrApi, namespace, method);
    });

    expect(dead).toEqual([]);
  });

  it('every KNOWN_GAPS entry is a real hrApi namespace with a written reason', () => {
    for (const [namespace, reason] of Object.entries(KNOWN_GAPS)) {
      expect(hrApi[namespace], `KNOWN_GAPS lists "${namespace}", which hrApi does not have`).toBeDefined();
      expect(reason.length, `KNOWN_GAPS["${namespace}"] needs a reason`).toBeGreaterThan(20);
    }
  });
});

// Argument arity — issue #434 ─────────────────────────────────────────────────────────────────────────────
// WHAT THIS CATCHES. WHAT THIS DOES NOT CATCH — do not read a green run here as more than it is: 1.

/** Parses a function's declared parameter list from its source text. Unlike `Function.prototype.length` this
 * counts defaulted (`params = {}`), destructured (`{ ifMatch } = {}`) and rest (`...args`) parameters. */
function declaredParameters(fn) {
  const src = Function.prototype.toString.call(fn);
  const open = src.indexOf('(');
  if (open === -1) {
    // `x => …` — a single un-parenthesised arrow parameter.
    const arrow = src.indexOf('=>');
    if (arrow === -1) return [];
    const name = src.slice(0, arrow).replace(/^async\s+/, '').trim();
    return name ? [name] : [];
  }

  let depth = 0;
  let close = -1;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '(') depth += 1;
    else if (src[i] === ')') {
      depth -= 1;
      if (depth === 0) { close = i; break; }
    }
  }
  if (close === -1) return [];

  const inner = src.slice(open + 1, close);
  const params = [];
  let current = '';
  let nest = 0;
  let quote = null;
  for (let i = 0; i < inner.length; i += 1) {
    const ch = inner[i];
    if (quote) {
      current += ch;
      if (ch === '\\') { current += inner[i + 1] ?? ''; i += 1; }
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; current += ch; continue; }
    if (ch === '(' || ch === '[' || ch === '{') nest += 1;
    else if (ch === ')' || ch === ']' || ch === '}') nest -= 1;
    if (ch === ',' && nest === 0) { params.push(current); current = ''; continue; }
    current += ch;
  }
  params.push(current);
  return params.map((p) => p.trim()).filter(Boolean);
}

// Methods whose mock deliberately declares a different parameter count from hrApi's. Same spirit — and same
// enforcement — as KNOWN_GAPS above: every entry must name a real, still-divergent method and carry a written
// reason.
const ARITY_EXEMPTIONS = {
  'attendance.unmapped': 'Returns the static MOCK_UNMAPPED_BADGES fixture; the mock has no persisted attendance store for the params filter to narrow.',
  'attendance.recalculate': 'The mock generates attendance days on the fly, so there is no stored rollup to recalculate; it reports 0 rather than pretending work happened.',
  'attendance.markPresent': 'Deliberate "not supported in mock mode" stub — no persisted attendance_daily store to write into; a fake marked-count would look verified while proving nothing.',
  'attendance.backfillCards': 'Deliberate "not supported in mock mode" stub — the mock has no badge-to-employee card mapping table to rewrite.',
  'attendance.importDat': 'Deliberate "not supported in mock mode" stub — there is no scanner .dat parser or punch store in the mock.',
  'payroll.preview': 'Deliberate "not supported in mock mode" stub — real Thai tax/SSO math; fabricating figures here would be a mirrored computation, which is never independent evidence.',
  'payroll.process': 'Deliberate "not supported in mock mode" stub — processing writes real payroll lines and carries the full tax/SSO calculation.',
  'payroll.exportFile': 'Deliberate "not supported in mock mode" stub — the KBank/PND1/SSO statutory files are byte-pinned fixed-width formats, not worth fabricating.',
  'payroll.exportPreviewFile': 'Deliberate "not supported in mock mode" stub — same reasoning as exportFile: real tax/SSO figures in a binary xlsx.',
  'payroll.downloadPayslip': 'Deliberate "not supported in mock mode" stub — payslip rendering needs real computed payroll lines.',
  'payroll.downloadOwnPayslip': 'Deliberate "not supported in mock mode" stub — same reasoning as downloadPayslip.',
  'payroll.downloadPayslipsZip': 'Deliberate "not supported in mock mode" stub — bulk ZIP of payslips the mock cannot render individually.',
  'payroll.distributePayslips': 'Deliberate "not supported in mock mode" stub — the mock sends no email and has no payslips to attach.',
  'payroll.saveTaxAllowances': 'Deliberate "not supported in mock mode" stub — allowances change real withholding tax.',
  'payroll.estimateMyTaxAllowanceDeclaration': 'Deliberate "not supported in mock mode" stub — the estimate runs real Thai tax math through PayrollCalculator twice; reimplementing it here is exactly the mirrored-computation trap.',
  'payroll.reverifyTaxAllowanceDeclaration': 'Deliberate "not supported in mock mode" stub — re-verification rewrites a whole year of verification_status, real payroll-affecting state.',
  'payroll.getYtdSeed': 'Returns an empty single-year fixture; the mock stores no year-to-date seed rows for the year argument to select.',
  'payroll.saveYtdSeed': 'Deliberate "not supported in mock mode" stub — the YTD seed carries real year-to-date income and tax figures.',
  'payroll.getComponentTaxTreatments': 'Returns an empty single-year fixture; the mock stores no per-year tax classification matrix for the year argument to select.',
  'payroll.saveComponentTaxTreatments': 'Deliberate "not supported in mock mode" stub — the classification matrix drives real withholding tax.',
  'pricingRequests.deleteFactoryQuoteAttachment': 'The `reason` argument is an audit note the real service writes to a deletion audit column; the mock hard-deletes from an in-memory array with no audit row to record it on.',
  'leave.downloadMyReport': 'Deliberate "not supported in mock mode" stub — the quota block must read the real LeaveService#balanceFor numbers (#914); a mock render would only prove plumbing, never the day-math.',
  'leave.downloadTeamReport': 'Deliberate "not supported in mock mode" stub — same reasoning as downloadMyReport.',
};

function arityDivergence(namespace, method) {
  const real = hrApi[namespace]?.[method];
  const mock = mockApi[namespace]?.[method];
  if (typeof real !== 'function' || typeof mock !== 'function') return null;
  const realParams = declaredParameters(real);
  const mockParams = declaredParameters(mock);
  if (realParams.length === mockParams.length) return null;
  return `${namespace}.${method}: hrApi declares (${realParams.join(', ')}) `
    + `but mockApi declares (${mockParams.join(', ')})`;
}

describe('mockApi / hrApi argument arity contract (#434)', () => {
  it('mockApi declares the same parameter count as hrApi, except documented exemptions', () => {
    const divergent = methodPaths(hrApi)
      .filter((path) => !ARITY_EXEMPTIONS[path])
      .map((path) => arityDivergence(...path.split('.')))
      .filter(Boolean);

    expect(divergent).toEqual([]);
  });

  it('every ARITY_EXEMPTIONS entry is a real, still-divergent method with a written reason', () => {
    for (const [path, reason] of Object.entries(ARITY_EXEMPTIONS)) {
      const [namespace, method] = path.split('.');
      expect(
        typeof hrApi[namespace]?.[method],
        `ARITY_EXEMPTIONS lists "${path}", which hrApi does not have`,
      ).toBe('function');
      // Stale entries rot: once a mock is fixed to match, its exemption must go, or the next genuine divergence on
      // that method is silently pre-approved.
      expect(
        arityDivergence(namespace, method),
        `ARITY_EXEMPTIONS["${path}"] no longer diverges — delete the entry`,
      ).not.toBeNull();
      expect(reason.length, `ARITY_EXEMPTIONS["${path}"] needs a reason`).toBeGreaterThan(20);
    }
  });
});

// Fixture completeness. The instance on record: mocks returned no `etag`, so `hrApi.payroll.saveInputDraft`
// (which sends `If-Match` only when the caller threads one through) sent NO header at all, and a 993/993 green
// suite was exercising the headerless path end to end.
describe('mock fixtures populate fields callers branch on (#434)', () => {
  it('payroll.getInputDraft supplies the etag that saveInputDraft sends as If-Match', async () => {
    await mockApi.auth.login({ role: 'hr' });
    const draft = await mockApi.payroll.getInputDraft({ payrollMonth: '2026-08' });

    // Without this, saveInputDraft's `ifMatch ? {...} : {}` collapses to no header and the 428/409 conflict paths
    // become unreachable under VITE_USE_MOCKS=true.
    expect(typeof draft.etag, 'getInputDraft must return an etag').toBe('string');
    expect(draft.etag.length).toBeGreaterThan(0);
  });
});
