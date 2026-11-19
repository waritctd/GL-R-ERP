import { describe, expect, it } from 'vitest';
import { latestPricedQuotation, timelineRowMatches } from './dealTimeline.js';

const event = (kind) => ({ type: 'event', event: { id: 1, kind } });
const activity = (kind) => ({ type: 'activity', activity: { id: 1, kind } });

describe('timelineRowMatches', () => {
  it('เข้าพบ keeps visits (a ticked visit is a SITE_VISIT activity) and meetings, nothing else', () => {
    expect(timelineRowMatches(activity('SITE_VISIT'), 'visits')).toBe(true);
    expect(timelineRowMatches(activity('MEETING'), 'visits')).toBe(true);
    // Wrong-way-round: a call, a document event and a stage move are not visits.
    expect(timelineRowMatches(activity('CALL'), 'visits')).toBe(false);
    expect(timelineRowMatches(activity('EMAIL'), 'visits')).toBe(false);
    expect(timelineRowMatches(event('QUOTATION_ISSUED'), 'visits')).toBe(false);
    expect(timelineRowMatches(event('STAGE_CHANGED'), 'visits')).toBe(false);
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
});
