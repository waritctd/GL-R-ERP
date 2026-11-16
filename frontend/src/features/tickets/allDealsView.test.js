import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  TABLE_ONLY_PARAMS, boardDealMatches, closedCounts, readStoredView, resolveDealsView, sortByQuiet, viewStorageKey,
  writeStoredView,
} from './allDealsView.js';
import { ownerShortName } from '../salesBoard/boardFormat.js';

const params = (query) => new URLSearchParams(query);

// This repo's Node ships a broken global `localStorage` that shadows jsdom's (see quotationPrefs.test.js), so the
// storage is stubbed outright.
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => { map.set(k, String(v)); },
    removeItem: (k) => { map.delete(k); },
    clear: () => map.clear(),
  };
}

function throwingStorage() {
  const boom = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom };
}

describe('resolveDealsView', () => {
  it('opens the board by default', () => {
    expect(resolveDealsView(params(''), null)).toBe('board');
  });

  it('opens the table when the user last chose it', () => {
    expect(resolveDealsView(params(''), 'table')).toBe('table');
  });

  it('ignores a stored value that is neither board nor table', () => {
    expect(resolveDealsView(params(''), 'grid')).toBe('board');
  });

  it('an explicit ?view= wins over what was stored', () => {
    expect(resolveDealsView(params('view=board'), 'table')).toBe('board');
    expect(resolveDealsView(params('view=table'), 'board')).toBe('table');
  });

  it.each(TABLE_ONLY_PARAMS)('a deep link carrying the table-only filter %s opens the table', (key) => {
    expect(resolveDealsView(params(`${key}=1`), 'board')).toBe('table');
  });

  it('a table-only filter still yields to an explicit ?view=board', () => {
    expect(resolveDealsView(params('owner=7&view=board'), null)).toBe('board');
  });

  it('a search alone does not force the table — both views search', () => {
    expect(resolveDealsView(params('q=Aira'), null)).toBe('board');
  });

  it('covers the deep links the app already emits (owner, life, flag, phase, week, from/to, inbox)', () => {
    expect(TABLE_ONLY_PARAMS).toEqual(expect.arrayContaining(['owner', 'life', 'flag', 'phase', 'week', 'from', 'to', 'inbox']));
  });
});

describe('stored view (per user, never throws)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('round-trips per user', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    writeStoredView({ employeeId: 1 }, 'table');
    expect(readStoredView({ employeeId: 1 })).toBe('table');
    expect(readStoredView({ employeeId: 2 })).toBeNull();
    expect(viewStorageKey({ employeeId: 1 })).not.toBe(viewStorageKey({ employeeId: 2 }));
  });

  it('a storage that throws reads as nothing and writes silently', () => {
    vi.stubGlobal('localStorage', throwingStorage());

    expect(readStoredView({ employeeId: 1 })).toBeNull();
    expect(() => writeStoredView({ employeeId: 1 }, 'table')).not.toThrow();
  });

  it('no localStorage at all reads as nothing and writes silently', () => {
    vi.stubGlobal('localStorage', undefined);

    expect(readStoredView({ employeeId: 1 })).toBeNull();
    expect(() => writeStoredView({ employeeId: 1 }, 'table')).not.toThrow();
  });
});

const deal = (over) => ({
  ticketId: 1, title: 'Aira Two Hotel Silom', customerName: 'ไอรา จำกัด', projectName: 'โครงการไอรา',
  designerName: 'PIA', ownerName: 'สมหมาย ขายดี', daysQuiet: 0, quiet: false, ...over,
});

describe('boardDealMatches', () => {
  it.each([
    ['project', 'โครงการไอ'],
    ['customer', 'ไอรา จำกัด'],
    ['designer', 'pia'],
    ['title', 'two hotel'],
  ])('finds a deal by its %s, case-insensitively', (_field, query) => {
    expect(boardDealMatches(deal(), query)).toBe(true);
  });

  it('does not match an unrelated query, and an empty query matches everything', () => {
    expect(boardDealMatches(deal(), 'Landmark')).toBe(false);
    expect(boardDealMatches(deal(), '  ')).toBe(true);
  });
});

describe('sortByQuiet', () => {
  it('puts the longest-quiet deal first, ticket id breaking ties, without mutating the input', () => {
    const input = [deal({ ticketId: 3, daysQuiet: 5 }), deal({ ticketId: 2, daysQuiet: 30 }), deal({ ticketId: 1, daysQuiet: 5 })];

    expect(sortByQuiet(input).map((d) => d.ticketId)).toEqual([2, 1, 3]);
    expect(input.map((d) => d.ticketId)).toEqual([3, 2, 1]);
  });
});

describe('closedCounts', () => {
  it('counts paused, long-paused, lost and cancelled deals from the ticket list', () => {
    const tickets = [
      { lifecycle: 'ON_HOLD' }, { lifecycle: 'ON_HOLD' }, { lifecycle: 'DORMANT' },
      { lifecycle: 'CLOSED_LOST' }, { lifecycle: 'CANCELLED' }, { lifecycle: 'ACTIVE' }, { lifecycle: 'COMPLETED' },
    ];

    expect(closedCounts(tickets)).toEqual({ ON_HOLD: 2, DORMANT: 1, CLOSED_LOST: 1, CANCELLED: 1 });
    expect(closedCounts(undefined)).toEqual({ ON_HOLD: 0, DORMANT: 0, CLOSED_LOST: 0, CANCELLED: 0 });
  });
});

describe('ownerShortName', () => {
  it('shows the first name, as the review draws the owner tag', () => {
    expect(ownerShortName('สมหมาย ขายดี')).toBe('สมหมาย');
    expect(ownerShortName('  นภา  ')).toBe('นภา');
    expect(ownerShortName(null)).toBe('');
  });
});
