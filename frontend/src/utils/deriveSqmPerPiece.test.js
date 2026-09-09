import { describe, expect, it } from 'vitest';
import { deriveSqmPerPiece } from './deriveSqmPerPiece.js';

// Extracted from TicketCreateModal.jsx (SPEC-PREFILL.md ladder B, 2026-09) with NO behaviour
// change — this is its first DIRECT unit test; previously it was only exercised indirectly through
// TicketCreateModal.test.jsx's rendering-based cases, which keep passing unmodified after the
// extraction.
describe('deriveSqmPerPiece', () => {
  it('derives ตร.ม. from a millimetre-format WIDTHxHEIGHT size', () => {
    expect(deriveSqmPerPiece('600x1200')).toBeCloseTo(0.72, 6);
    expect(deriveSqmPerPiece('600X1200')).toBeCloseTo(0.72, 6);
    expect(deriveSqmPerPiece('600×1200')).toBeCloseTo(0.72, 6);
  });

  it('refuses a centimetre-looking size — both dimensions must read as >= 100mm', () => {
    expect(deriveSqmPerPiece('30 x 60')).toBeNull();
    expect(deriveSqmPerPiece('60x60')).toBeNull();
  });

  it('refuses a three-dimension size (width x height x thickness) rather than misreading it', () => {
    expect(deriveSqmPerPiece('598X598X18')).toBeNull();
  });

  it("refuses apostrophe-decimal junk rather than misreading it", () => {
    expect(deriveSqmPerPiece("15X1'5")).toBeNull();
  });

  it('refuses no size at all', () => {
    expect(deriveSqmPerPiece(null)).toBeNull();
    expect(deriveSqmPerPiece(undefined)).toBeNull();
    expect(deriveSqmPerPiece('')).toBeNull();
  });

  it('refuses a non-numeric or malformed string', () => {
    expect(deriveSqmPerPiece('abc')).toBeNull();
    expect(deriveSqmPerPiece('600x')).toBeNull();
  });
});
