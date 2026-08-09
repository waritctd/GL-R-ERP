import { describe, it, expect } from 'vitest';
import { parseAcceptedChanges } from './accepted-changes.js';

// This parser decides whether a pixel diff is allowed through a REQUIRED check, so the
// cases below are written wrong-way-round: they assert what must NOT be accepted. A test
// that only proves "my own entry works" would pass just as happily if the PR scoping were
// deleted entirely.

const KNOWN = new Set(['desktop-hr-employee-requests', 'mobile-ceo-ticket-overview']);
const ENTRY = '615  desktop-hr-employee-requests  — OT panel gains the suggested-rate row';

describe('parseAcceptedChanges', () => {
  it('accepts an entry naming the current PR', () => {
    const got = parseAcceptedChanges(ENTRY, 615, KNOWN);
    expect(got.get('desktop-hr-employee-requests')).toBe('OT panel gains the suggested-rate row');
  });

  // ── the cases that matter: what must NOT be accepted ──────────────────────────────

  it('IGNORES an entry belonging to a different PR', () => {
    expect(parseAcceptedChanges(ENTRY, 616, KNOWN).size).toBe(0);
  });

  it('accepts NOTHING when no PR number is supplied', () => {
    for (const pr of [undefined, null, '', 0]) {
      expect(parseAcceptedChanges(ENTRY, pr, KNOWN).size).toBe(0);
    }
  });

  it('does not even parse the file when there is no PR number', () => {
    // The assertion above passes either way — the PR-scoping check alone already skips
    // every entry, so it does NOT prove the early return exists (mutation-checked: deleting
    // that line leaves it green). This is the case that pins it. Without the early return a
    // broken accepted-changes.txt would throw on every LOCAL run too, where acceptances are
    // switched off and the file is none of the run's business.
    expect(() => parseAcceptedChanges('this is not a valid entry', undefined, KNOWN)).not.toThrow();
    expect(() => parseAcceptedChanges('this is not a valid entry', 615, KNOWN)).toThrow(/PR number/);
  });

  it('does not let a numeric-string PR miss its own entry', () => {
    // The env var arrives as a string; the file holds text. A === between the two forms
    // would silently accept nothing, and the PR author would see a red they cannot explain.
    expect(parseAcceptedChanges(ENTRY, '615', KNOWN).size).toBe(1);
  });

  it('does not match a PR number by prefix or substring', () => {
    expect(parseAcceptedChanges('61  desktop-hr-employee-requests  — x', 615, KNOWN).size).toBe(0);
    expect(parseAcceptedChanges('6150 desktop-hr-employee-requests  — x', 615, KNOWN).size).toBe(0);
  });

  it('rejects an unknown snapshot name for THIS PR', () => {
    expect(() => parseAcceptedChanges('615  desktop-hr-typo  — x', 615, KNOWN)).toThrow(/not a snapshot/);
  });

  it('leaves an unknown snapshot for ANOTHER PR inert rather than throwing', () => {
    // A merged entry pointing at a since-deleted route must not break the suite for
    // everyone else — that is the difference between housekeeping and a landmine.
    expect(parseAcceptedChanges('600  route-since-deleted  — x', 615, KNOWN).size).toBe(0);
  });

  it('requires a reason', () => {
    expect(() => parseAcceptedChanges('615  desktop-hr-employee-requests', 615, KNOWN)).toThrow(/reason/);
    expect(() => parseAcceptedChanges('615  desktop-hr-employee-requests  —', 615, KNOWN)).toThrow(/reason/);
  });

  it('rejects a line whose first field is not a PR number', () => {
    expect(() => parseAcceptedChanges('desktop-hr-employee-requests  — x', 615, KNOWN)).toThrow(/PR number/);
    expect(() => parseAcceptedChanges('#615 desktop-hr-employee-requests', 615, KNOWN)).not.toThrow(); // comment
  });

  // ── format tolerance ──────────────────────────────────────────────────────────────

  it('ignores comments and blank lines', () => {
    const text = `# header\n\n   \n${ENTRY}\n# trailing note\n`;
    expect(parseAcceptedChanges(text, 615, KNOWN).size).toBe(1);
  });

  it('strips a .png suffix so either spelling works', () => {
    const got = parseAcceptedChanges('615  desktop-hr-employee-requests.png  — x', 615, KNOWN);
    expect(got.has('desktop-hr-employee-requests')).toBe(true);
  });

  it('accepts an en/em dash or a plain hyphen before the reason', () => {
    for (const sep of ['—', '–', '-']) {
      const got = parseAcceptedChanges(`615 desktop-hr-employee-requests ${sep} why`, 615, KNOWN);
      expect(got.get('desktop-hr-employee-requests')).toBe('why');
    }
  });

  it('collects several entries for the same PR and drops the others', () => {
    const text = [
      '614  mobile-ceo-ticket-overview        — someone else, must not leak',
      '615  desktop-hr-employee-requests      — a',
      '615  mobile-ceo-ticket-overview        — b',
    ].join('\n');
    const got = parseAcceptedChanges(text, 615, KNOWN);
    expect([...got.keys()].sort()).toEqual(['desktop-hr-employee-requests', 'mobile-ceo-ticket-overview']);
  });

  it('works without a knownSnapshots set (validation is optional, scoping is not)', () => {
    expect(parseAcceptedChanges(ENTRY, 615).size).toBe(1);
    expect(parseAcceptedChanges(ENTRY, 616).size).toBe(0);
  });
});
