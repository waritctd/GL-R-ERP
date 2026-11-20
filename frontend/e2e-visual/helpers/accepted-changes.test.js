import { describe, it, expect } from 'vitest';
import { parseAcceptedChanges } from './accepted-changes.js';

// This parser decides whether a pixel diff is allowed through a REQUIRED check, so the cases below are written
// wrong-way-round: they assert what must NOT be accepted. A test that only proves "my own entry works" would
// pass just as happily if the PR scoping were deleted entirely.

const KNOWN = new Set(['desktop-hr-employee-requests', 'mobile-ceo-ticket-overview']);
const ENTRY = '615  desktop-hr-employee-requests  — OT panel gains the suggested-rate row';

describe('parseAcceptedChanges', () => {
  it('accepts an entry naming the current PR', () => {
    const got = parseAcceptedChanges(ENTRY, 615, KNOWN);
    expect(got.get('desktop-hr-employee-requests')).toBe('OT panel gains the suggested-rate row');
  });

  it('IGNORES an entry belonging to a different PR', () => {
    expect(parseAcceptedChanges(ENTRY, 616, KNOWN).size).toBe(0);
  });

  it('accepts NOTHING when no PR number is supplied', () => {
    for (const pr of [undefined, null, '', 0]) {
      expect(parseAcceptedChanges(ENTRY, pr, KNOWN).size).toBe(0);
    }
  });

  it('does not match a PR number by prefix or substring', () => {
    expect(parseAcceptedChanges('61  desktop-hr-employee-requests  — x', 615, KNOWN).size).toBe(0);
    expect(parseAcceptedChanges('6150 desktop-hr-employee-requests  — x', 615, KNOWN).size).toBe(0);
  });

  it('rejects an unknown snapshot name for THIS PR', () => {
    expect(() => parseAcceptedChanges('615  desktop-hr-typo  — x', 615, KNOWN)).toThrow(/not a snapshot/);
  });
});
