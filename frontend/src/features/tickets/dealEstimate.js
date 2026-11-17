// มูลค่าโดยประมาณ (V220 sales.ticket.estimated_value): the bounds the server enforces on CreateTicketRequest and
// TrackingUpdateRequest — > 0 and NUMERIC(14,2), the precision of imported_value (V215) — in one place for the create
// form and the ข้อมูลงาน edit form.

export const ESTIMATE_MAX = 999999999999.99;
export const ESTIMATE_ERROR = 'มูลค่าต้องมากกว่า 0 และไม่เกิน 999,999,999,999.99';

/** '' → null; a number > 0 with at most two decimals and within NUMERIC(14,2) (thousands separators allowed) → that
 * number; anything else → NaN. */
export function parseEstimatedValue(text) {
  const cleaned = String(text ?? '').replace(/[,\s฿]/g, '');
  if (cleaned === '') return null;
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return Number.NaN;
  const value = Number(cleaned);
  return value > 0 && value <= ESTIMATE_MAX ? value : Number.NaN;
}
