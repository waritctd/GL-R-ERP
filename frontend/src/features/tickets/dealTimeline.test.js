import { describe, expect, it } from 'vitest';
import { latestPricedQuotation, TIMELINE_FILTERS, timelineRowMatches } from './dealTimeline.js';

const event = (kind) => ({ type: 'event', event: { id: 1, kind } });
const activity = (kind) => ({ type: 'activity', activity: { id: 1, kind } });

describe('TIMELINE_FILTERS', () => {
  it('is ทั้งหมด / เข้าพบ / เอกสาร, in the drawn order', () => {
    expect(TIMELINE_FILTERS.map((f) => [f.id, f.label])).toEqual([
      ['all', 'ทั้งหมด'],
      ['visits', 'เข้าพบ'],
      ['documents', 'เอกสาร'],
    ]);
  });
});

describe('timelineRowMatches', () => {
  it('ทั้งหมด keeps every row', () => {
    for (const row of [event('STAGE_CHANGED'), event('QUOTATION_ISSUED'), activity('CALL'), activity('SITE_VISIT')]) {
      expect(timelineRowMatches(row, 'all')).toBe(true);
    }
  });

  it('เข้าพบ keeps visits (a ticked visit is a SITE_VISIT activity) and meetings, nothing else', () => {
    expect(timelineRowMatches(activity('SITE_VISIT'), 'visits')).toBe(true);
    expect(timelineRowMatches(activity('MEETING'), 'visits')).toBe(true);
    // Wrong-way-round: a call, a document event and a stage move are not visits.
    expect(timelineRowMatches(activity('CALL'), 'visits')).toBe(false);
    expect(timelineRowMatches(activity('EMAIL'), 'visits')).toBe(false);
    expect(timelineRowMatches(event('QUOTATION_ISSUED'), 'visits')).toBe(false);
    expect(timelineRowMatches(event('STAGE_CHANGED'), 'visits')).toBe(false);
  });

  it.each([
    'QUOTATION_ISSUED', 'QUOTATION_SENT', 'QUOTATION_ACCEPTED', 'QUOTATION_REJECTED',
    'DEAL_QUOTATION_SUBMITTED', 'DEAL_QUOTATION_APPROVED', 'DEAL_QUOTATION_REJECTED',
    'DEAL_QUOTATION_REVISION_SUBMITTED', 'DEAL_QUOTATION_REORDERED', 'DEAL_QUOTATION_SUPERSEDED',
    'DOCUMENT_ISSUED', 'DEPOSIT_NOTICE_ISSUED', 'IR_ISSUED', 'IR_SENT', 'IMPORT_REQUEST_EMAIL_SENT',
  ])('เอกสาร keeps the document event %s', (kind) => {
    expect(timelineRowMatches(event(kind), 'documents')).toBe(true);
  });

  it('เอกสาร drops everything that is not a document', () => {
    for (const row of [
      event('STAGE_CHANGED'), event('COMMENTED'), event('PAYMENT_RECORDED'), event('DELIVERY_RECORDED'),
      activity('CALL'), activity('SITE_VISIT'), activity('QUOTATION_FOLLOWUP'),
    ]) {
      expect(timelineRowMatches(row, 'documents')).toBe(false);
    }
  });

  it('an unknown filter id keeps every row (never an empty timeline by accident)', () => {
    expect(timelineRowMatches(activity('CALL'), 'nope')).toBe(true);
  });
});

// "สินค้าในใบล่าสุด" (drawing a-deal-quote): the newest quotation that carries a price the customer was given.
describe('latestPricedQuotation', () => {
  it('picks the newest APPROVED/ISSUED/SENT/ACCEPTED row over a newer draft', () => {
    const rows = [
      { id: 10, docStatus: 'APPROVED' },
      { id: 12, docStatus: 'DRAFT' },
      { id: 11, docStatus: 'ISSUED' },
    ];
    expect(latestPricedQuotation(rows).id).toBe(11);
  });

  it('falls back to the newest live row when nothing is priced yet', () => {
    expect(latestPricedQuotation([{ id: 3, docStatus: 'DRAFT' }, { id: 4, docStatus: 'PENDING_APPROVAL' }]).id).toBe(4);
  });

  it('never picks a dead row (cancelled, superseded, expired, rejected)', () => {
    expect(latestPricedQuotation([
      { id: 5, docStatus: 'CANCELLED' }, { id: 6, docStatus: 'SUPERSEDED' },
      { id: 7, docStatus: 'EXPIRED' }, { id: 8, docStatus: 'REJECTED' },
    ])).toBeNull();
    expect(latestPricedQuotation([])).toBeNull();
    expect(latestPricedQuotation(undefined)).toBeNull();
  });
});
