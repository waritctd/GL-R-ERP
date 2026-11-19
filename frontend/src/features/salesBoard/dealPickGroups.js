// The งาน a phone quick action is about, as the rep sees them: ควรตามต่อ first (the reason to act), then the rest of
// the rep's open งาน, each once. Shared by โทรแล้ว's picker (TodayOnPhone's CallPicker) and ส่งตัวอย่าง's sheet
// (SampleSentModal), so the two quick actions offer the same งาน in the same order.

/** A folded project card shows the root's identity with its live add-on's state: every quick ACTION targets that lead order. */
export const actionTicketId = (d) => d.leadTicketId ?? d.ticketId;

export const dealName = (d) => d.title || d.customerName || `#${d.ticketId}`;

/** `[{ label, deals }]`, empty groups left out. `followUp` and `board` are GET /api/sales/my-work's lists. */
export function dealPickGroups(followUp = [], board = []) {
  const quietIds = new Set(followUp.map((d) => d.ticketId));
  return [
    { label: 'ควรตามต่อ', deals: followUp },
    { label: 'งานอื่นของฉัน', deals: board.filter((d) => !quietIds.has(d.ticketId)) },
  ].filter((g) => g.deals.length);
}
