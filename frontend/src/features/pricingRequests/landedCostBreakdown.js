// The full landed-cost calculation for one costed line, step by step, rebuilt from the costing
// row the server stored (factory price, FX, quantities, and the calculation snapshot it wrote).
// Display only: every figure mirrors LandedCostCalculator / PricingFormulaEngine's own rounding
// (money4 at each step), so the lines add up to the cost the server actually froze.
//
//   C  = goods cost (factory price × FX, per แผ่น, × แผ่น)
//   i  = C × insurance value factor × insurance rate × insurance buffer
//   F  = this line's share of the freight group's container charge
//   S  = this line's share of the shipment's container clearance charge
//   TC = [(C + i + F) × (1 + duty) × cost buffer] + S
//   ทุน/ตร.ม. = TC ÷ ตร.ม.
//   ราคาพิเศษ = ทุน/ตร.ม. × (1 + กำไร) × selling buffer (VAT)

function money4(n) {
  return Math.round((Number(n) + Number.EPSILON) * 1e4) / 1e4;
}
function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}
function num(v) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function parseSnapshot(raw) {
  if (raw == null) return null;
  if (typeof raw === 'object') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/**
 * @returns null when the line has no V109 calculation to show (stock line, uncostable, or a
 *   costing written before the snapshot existed); otherwise every input and step of the formula.
 */
export function landedCostBreakdown({ costingItem, formulaConfig, marginFraction, sellingBuffer }) {
  if (!costingItem || costingItem.uncostableReason) return null;
  const snap = parseSnapshot(costingItem.calculationSnapshot);
  if (!snap || snap.formula !== 'V109') return null;

  const pieces = num(costingItem.normalizedQuantityPieces);
  const qtySqm = num(snap.itemQtySqm);
  const goodsPerPiece = num(costingItem.goodsCostThb);
  const freight = num(snap.freightShareThb);
  const clearance = num(snap.clearanceShareThb);
  const dutyPct = num(snap.dutyPct);
  const costBuffer = num(snap.costBuffer);
  if ([pieces, qtySqm, goodsPerPiece, freight, clearance, dutyPct, costBuffer].some((v) => v == null) || !(qtySqm > 0)) {
    return null;
  }

  const goods = num(snap.goodsCostTotalThb) ?? money4(goodsPerPiece * pieces);
  // The rates the cost was calculated with (in the snapshot since 2026-11), else today's config.
  const insFactor = num(snap.insuranceValueFactor) ?? num(formulaConfig?.insuranceValueFactor);
  const insRate = num(snap.insuranceRate) ?? num(formulaConfig?.insuranceRate);
  const insBuffer = num(snap.insuranceBuffer) ?? num(formulaConfig?.insuranceBuffer);
  const insurance = num(snap.insuranceThb)
    ?? (insFactor != null && insRate != null && insBuffer != null ? money4(goods * insFactor * insRate * insBuffer) : null);
  if (insurance == null) return null;

  const cif = money4(goods + insurance + freight);
  const duty = money4(cif * dutyPct);
  const beforeClearance = money4((cif + duty) * costBuffer);
  const totalCost = money4(beforeClearance + clearance);
  const costPerSqm = totalCost / qtySqm;

  const margin = num(marginFraction);
  const buffer = num(sellingBuffer);
  const specialPerSqm = margin != null && buffer > 0 ? round2(costPerSqm * (1 + margin) * buffer) : null;

  const fullOrNull = (v) => (v == null || v === '' ? null : Number(v));
  return {
    rawUnitPrice: num(costingItem.rawUnitPrice),
    rawCurrency: costingItem.rawCurrency,
    rawUnit: costingItem.rawUnit,
    fxRate: num(costingItem.fxRate),
    sqmPerPiece: num(costingItem.sqmPerUnit),
    goodsPerPiece,
    pieces,
    qtySqm,
    goods,
    insFactor,
    insRate,
    insBuffer,
    insurance,
    freight: {
      share: freight,
      groupAmount: num(snap.freightAmountThbForGroup),
      groupQtySqm: num(snap.freightGroupQtySqm),
      fullContainers: fullOrNull(snap.freightFullContainers),
      remainderSqm: num(snap.freightRemainderSqm),
    },
    clearance: {
      share: clearance,
      // Same shape as freight: the shipment is the clearance's "group".
      groupAmount: num(snap.clearanceAmountThbForShipment),
      groupQtySqm: num(snap.shipmentQtySqm),
      fullContainers: fullOrNull(snap.clearanceFullContainers),
      remainderSqm: num(snap.clearanceRemainderSqm),
    },
    containerSqm: num(snap.containerSqm),
    dutyPct,
    costBuffer,
    cif,
    duty,
    beforeClearance,
    totalCost,
    costPerSqm,
    margin,
    sellingBuffer: buffer,
    specialPerSqm,
  };
}
