import { describe, expect, it } from 'vitest';
import { landedCostBreakdown } from './landedCostBreakdown.js';

// DL-2026-0325 row 1 (TRIBECA, Equipe, 3,910.03 ตร.ม. of a 7,820.04 ตร.ม. shipment = 10 containers).
const costingItem = {
  rawUnitPrice: 14.283, rawCurrency: 'EUR', rawUnit: 'ตร.ม.', fxRate: 37.9944, sqmPerUnit: 0.014706,
  goodsCostThb: 7.9806, normalizedQuantityPieces: 265880, uncostableReason: null,
  calculationSnapshot: JSON.stringify({
    formula: 'V109', itemQtySqm: '3910.0312800000', dutyPct: '0.300000', costBuffer: '1.070000',
    freightAmountThbForGroup: '1000000.0000', freightGroupQtySqm: '7820.0362800000', freightShareThb: '500001.6803',
    clearanceAmountThbForShipment: '200000.0000', shipmentQtySqm: '7820.0362800000', clearanceShareThb: '100000.3361',
    containerSqm: '800', freightFullContainers: '10', freightRemainderSqm: '0', clearanceFullContainers: '10', clearanceRemainderSqm: '0',
  }),
};
const formulaConfig = { insuranceValueFactor: 1.15, insuranceRate: 0.0045, insuranceBuffer: 1.07 };

describe('landedCostBreakdown', () => {
  it('rebuilds every step of the formula to the satang (row 1: ทุน 962.49, ราคาพิเศษ 1,338.83)', () => {
    const b = landedCostBreakdown({ costingItem, formulaConfig, marginFraction: 0.3, sellingBuffer: 1.07 });
    expect(b.goods).toBe(2121881.928);
    expect(b.insurance).toBe(11749.3907);
    expect(b.cif).toBe(2633632.999);
    expect(b.totalCost).toBe(3763383.8377);
    expect(b.costPerSqm).toBeCloseTo(962.4946, 4);
    expect(b.specialPerSqm).toBe(1338.83);
    expect(b.freight.fullContainers).toBe(10);
  });

  it('prefers the insurance rates frozen in the snapshot over today\'s config', () => {
    const snap = { ...JSON.parse(costingItem.calculationSnapshot), insuranceRate: '0' };
    const b = landedCostBreakdown({ costingItem: { ...costingItem, calculationSnapshot: JSON.stringify(snap) }, formulaConfig });
    expect(b.insurance).toBe(0);
  });

  it('returns null for a line with nothing to break down', () => {
    expect(landedCostBreakdown({ costingItem: null })).toBeNull();
    expect(landedCostBreakdown({ costingItem: { ...costingItem, uncostableReason: 'x' } })).toBeNull();
    expect(landedCostBreakdown({ costingItem: { ...costingItem, calculationSnapshot: '{}' } })).toBeNull();
  });
});
