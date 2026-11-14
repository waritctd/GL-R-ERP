// Shares stage probabilities, activity kinds, and advancement readiness across deal screens and mocks.

/** Mirrors WinProbabilityDefaults.java's stage → % map exactly — in pipeline order (DealStage.ORDER), so the
 * two files read side by side. 0`, so every deal at S5 showed 0% and added nothing to TicketListPage's
 * win-weighted forecast, with the whole suite green. */
export const WIN_PROBABILITY_DEFAULTS = {
  LEAD_APPROACH: 10,
  PRESENTATION: 20,
  SPEC_APPROVED: 30,
  QUOTE_DESIGN_SIDE: 40,
  QUOTE_OWNER: 40,
  OWNER_SIGNOFF: 50,
  AWAITING_BUYER: 50,
  QUOTE_BUYER: 60,
  NEGOTIATION: 70,
  ORDER_RECEIVED: 90,
  DEPOSIT_RECEIVED: 100,
  PROCUREMENT: 100,
  DELIVERY_SCHEDULING: 100,
  DELIVERED: 100,
  CLOSED_PAID: 100,
};

export function winProbabilityDefault(salesStage) {
  return WIN_PROBABILITY_DEFAULTS[salesStage] ?? 0;
}

/** Mirrors TicketSummaryDto.effectiveWinProbability() / WinProbabilityDefaults.effective — the rep's override
 * when set, else the stage default. ⚠️ **NOT for UI code. Do not reintroduce a UI caller. */
export function effectiveWinProbability(winProbabilityOverride, salesStage) {
  return winProbabilityOverride != null ? Number(winProbabilityOverride) : winProbabilityDefault(salesStage);
}

// Mirrors DealActivityKind.java's VALID set exactly.
export const ACTIVITY_KINDS = [
  { code: 'CALL', label: 'โทรศัพท์' },
  { code: 'MEETING', label: 'นัดพบ / ประชุม' },
  { code: 'SITE_VISIT', label: 'เข้าหน้างาน' },
  { code: 'EMAIL', label: 'อีเมล' },
  { code: 'MESSAGE', label: 'ข้อความ (แชท/ไลน์)' },
  { code: 'QUOTATION_FOLLOWUP', label: 'ติดตามใบเสนอราคา' },
  { code: 'OTHER', label: 'อื่น ๆ' },
];

// Ticket-attachment types (INVOICE is written by commission creation, PO/OTHER by uploads).
const ATTACH_TYPE_LABEL = { PO: 'ใบสั่งซื้อ (PO)', INVOICE: 'ใบกำกับภาษี', SIGNED_QUOTATION: 'ใบเสนอราคาที่ลูกค้าเซ็น', OTHER: 'อื่น ๆ' };

export function attachTypeLabel(code) {
  return ATTACH_TYPE_LABEL[code] ?? code ?? '-';
}

export function activityKindLabel(code) {
  return ACTIVITY_KINDS.find((k) => k.code === code)?.label ?? code ?? '-';
}

export function isValidActivityKind(code) {
  return ACTIVITY_KINDS.some((k) => k.code === code);
}

/** The `created_at` timestamp the stage-advance gate and staleness both compare activity against: the most
 * recent STAGE_CHANGED event's createdAt, or (if the deal has never had one) the ticket's own createdAt.
 * Mirrors TicketRepository.hasActivitySinceLastStageChange's SQL exactly. */
export function lastStageChangeAt(events, ticketCreatedAt) {
  const stageEvents = (events ?? []).filter((e) => e.kind === 'STAGE_CHANGED');
  if (stageEvents.length === 0) return ticketCreatedAt ?? null;
  return stageEvents.reduce(
    (latest, e) => (!latest || new Date(e.createdAt) > new Date(latest) ? e.createdAt : latest),
    null,
  );
}

/** Whether any activity in `activities` was logged (created) at/after `sinceIso`. */
export function hasActivitySince(activities, sinceIso) {
  if (!sinceIso) return false;
  const since = new Date(sinceIso).getTime();
  return (activities ?? []).some((a) => new Date(a.createdAt).getTime() >= since);
}

/** The manual forward stage-advance gate's two conditions, as a single UI-only readiness check — mirrors
 * TicketService.updateStage's `forward` branch. */
export function isReadyToAdvance(deal, hasRecentActivity) {
  return Boolean(deal?.nextFollowUpAt) && Boolean(hasRecentActivity);
}

/** Exact Thai 400 message TicketService.updateStage throws (and the mock mirrors) — what a failed advance
 * attempt surfaces via the reactive error toast. */
export const STAGE_ADVANCE_GATE_MESSAGE =
  'เลื่อนสถานะไม่ได้: ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการหลังเปลี่ยนสถานะล่าสุด';

/** Pre-emptive UI hint shown before the user attempts an advance (same two conditions, phrased forward-looking
 * rather than as a rejection). */
export const STAGE_ADVANCE_GATE_HINT =
  'ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการก่อนเลื่อนสถานะ';

// Staleness window (V83): mirrors TicketRepository#isStale's 7-day interval.
const STALE_ACTIVITY_DAYS = 7;

/** Mirrors TicketRepository.enrichSummary's `stale` computation. */
export function computeStale(lifecycle, activities) {
  if (lifecycle !== 'ACTIVE') return false;
  const sinceIso = new Date(Date.now() - STALE_ACTIVITY_DAYS * 86400000).toISOString();
  return !hasActivitySince(activities, sinceIso);
}
