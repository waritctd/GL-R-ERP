import { describe, expect, it } from 'vitest';
import * as importSteps from './importSteps.js';

// QA-22 (owner ruling 2026-10-29): the deal header's การนำเข้า chip follows the per-factory IR steps.
// Contract pinned here: importChipLabel(importRequests, fulfillmentStatus) -> string, or null when the
// caller should keep today's fulfilment label ("ยังไม่เริ่ม" / fulfilmentStatusLabel(fulfillmentStatus)).
// Looked up off the namespace so a missing export fails on the assertion, not on a TypeError.
const importChipLabel = importSteps.importChipLabel ?? (() => undefined);

const ir = (importStep, status = 'ISSUED') => ({ status, importStep });

describe('importChipLabel', () => {
  it('shows the slowest live tracked factory step, with arrivals when there are several factories', () => {
    expect(importChipLabel([ir('IN_TRANSIT'), ir('RECEIVED')], 'IR_ISSUED')).toBe('กำลังเดินทาง · ถึงโกดัง 1/2');
    expect(importChipLabel([ir('RECEIVED'), ir('CONTACTED'), ir('IN_TRANSIT')], 'IR_ISSUED'))
      .toBe('ส่ง IR ให้จัดซื้อ · ถึงโกดัง 1/3');
  });

  it('a revised factory counts once: v1 SUPERSEDED at ORDERED is ignored, v2 ISSUED at RECEIVED is the factory', () => {
    expect(importChipLabel([ir('ORDERED', 'SUPERSEDED'), ir('RECEIVED')], 'IR_ISSUED')).toBe('ถึงโกดัง');
  });

  it.each(['GOODS_RECEIVED', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED', 'FROM_STOCK'])(
    'returns null once the deal rollup is past import (%s)',
    (fulfillmentStatus) => {
      expect(importChipLabel([ir('RECEIVED'), ir('IN_TRANSIT')], fulfillmentStatus)).toBeNull();
    },
  );
});
