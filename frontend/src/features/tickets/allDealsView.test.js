import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  TABLE_ONLY_PARAMS, readStoredView, resolveDealsView, 
  writeStoredView,
} from './allDealsView.js';

const params = (query) => new URLSearchParams(query);

function throwingStorage() {
  const boom = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom };
}

describe('resolveDealsView', () => {
  it('opens the board by default', () => {
    expect(resolveDealsView(params(''), null)).toBe('board');
  });

  it.each(TABLE_ONLY_PARAMS)('a deep link carrying the table-only filter %s opens the table', (key) => {
    expect(resolveDealsView(params(`${key}=1`), 'board')).toBe('table');
  });
});

describe('stored view (per user, never throws)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('a storage that throws reads as nothing and writes silently', () => {
    vi.stubGlobal('localStorage', throwingStorage());

    expect(readStoredView({ employeeId: 1 })).toBeNull();
    expect(() => writeStoredView({ employeeId: 1 }, 'table')).not.toThrow();
  });
});
