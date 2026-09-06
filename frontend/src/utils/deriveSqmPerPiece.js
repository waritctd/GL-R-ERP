// Extracted from features/tickets/TicketCreateModal.jsx (SPEC-PREFILL.md ladder B, 2026-09):
// PricingRequestDetailPage.jsx's "พื้นที่ต่อ 1 แผ่น (ตร.ม.)" prefill needs the SAME geometric
// derivation this file already had for the ticket-creation แผ่น↔ตร.ม. toggle — reusing it here
// rather than writing a second, subtly-different parser is the point of this extraction. Behaviour
// is byte-identical to the pre-extraction function; only its location moved.

/**
 * ตร.ม. per piece derived from a catalog `size_raw` string, for the ~350 active catalog rows that
 * carry no `sqm_per_piece` of their own (all of factory Bode, plus LEA's trim pieces). Without a
 * factor the แผ่น↔ตร.ม. toggle cannot cross-fill, which is what stranded a rep's entered quantity
 * in UAT.
 *
 * Only ever called when the catalog has no factor of its own, and deliberately only accepts a
 * MILLIMETRE reading — both dimensions >= 100. Catalog sizes are genuinely mixed-unit ("600x1200"
 * is mm, "30 x 60" and "120 x 278" are cm), and there is no reliable way to tell them apart from
 * the string alone. Every row that actually lacks a factor is millimetres; every centimetre-format
 * row already ships its own `sqm_per_piece` and so never reaches this function. Anything else —
 * centimetre-looking values, a third thickness dimension ("598X598X18"), junk ("15X1'5"), or no
 * size at all — returns null, and the caller then lets the rep enter both quantities by hand rather
 * than converting on a guess.
 */
export function deriveSqmPerPiece(sizeRaw) {
  const match = /^\s*(\d+(?:\.\d+)?)\s*[xX×]\s*(\d+(?:\.\d+)?)\s*$/.exec(sizeRaw ?? '');
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (!(width >= 100) || !(height >= 100)) return null;
  const sqm = (width / 1000) * (height / 1000);
  return sqm > 0 ? Number(sqm.toFixed(6)) : null;
}
