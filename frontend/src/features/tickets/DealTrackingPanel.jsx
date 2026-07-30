import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import {
  effectiveWinProbability, hasActivitySince, isReadyToAdvance, lastStageChangeAt,
} from './dealTrackingMeta.js';

/**
 * "การติดตามดีล" (Slice B2, handoff 103) — the weekly-report replacement: win%
 * (effective value + rep override), designer/owner/buyer names, and next
 * follow-up date. Read-only for any viewer sections.dealTracking lets in;
 * `canEdit` narrows further to the deal owner / sales_manager / ceo,
 * mirroring TicketService.requireDealOwnership (see
 * DealTrackingAndActivityIntegrationTest, backend Slice B1 — the real
 * enforcement; this component's canEdit is a UI convenience, not authoritative).
 *
 * The "พร้อมเลื่อนสถานะ / ยังไม่พร้อม" badge below is a compact status readout
 * only — ticket-detail IA rebuild Phase 1 moved the actual explanatory gate
 * sentence (what to do about it) to sit next to DealStagePanel's "เลื่อนไป"
 * button instead of living here, in a panel the button it blocks isn't even
 * in (see workState.js / TicketDetailPage / DealStagePanel's own doc
 * comment — this was the Phase-1 audit's "y=870" duplicate-panel finding).
 *
 * Ticket-detail IA rebuild Phase 2: this panel used to ALSO render its own
 * "ประวัติการติดตาม (Activity log)" list + add-activity form below the
 * tracking fields — a second, separate history from the ticket's own
 * "ประวัติการดำเนินการ" events panel elsewhere on the page. The spec's
 * กิจกรรม tab (docs/ui-repair/02-information-architecture/
 * TICKET_INFORMATION_ARCHITECTURE.md, regions 17+18) merges both into one
 * chronological stream — see DealHistoryPanel.jsx, rendered alongside this
 * (trimmed) panel in that tab now. `activities` stays as a prop ONLY to feed
 * the ready/not-ready badge's `hasActivitySince` check below; this component
 * no longer renders the activities list or the add-activity form itself
 * (DealHistoryPanel owns both).
 */
export function DealTrackingPanel({
  summary, events, activities = [], canEdit,
  onUpdateTracking, updating,
}) {
  const [editOpen, setEditOpen] = useState(false);
  const [draft, setDraft] = useState(null); // set on edit open

  const effectiveWin = effectiveWinProbability(summary.winProbabilityOverride, summary.salesStage);
  const hasOverride = summary.winProbabilityOverride != null;

  const sinceIso = lastStageChangeAt(events, summary.createdAt);
  const hasRecentActivity = hasActivitySince(activities, sinceIso);
  const ready = isReadyToAdvance(summary, hasRecentActivity);

  function openEdit() {
    setDraft({
      winProbability: summary.winProbabilityOverride != null ? String(summary.winProbabilityOverride) : '',
      designerName: summary.designerName ?? '',
      ownerName: summary.ownerName ?? '',
      buyerName: summary.buyerName ?? '',
      nextFollowUpAt: summary.nextFollowUpAt ?? '',
    });
    setEditOpen(true);
  }

  async function submitTracking() {
    await onUpdateTracking({
      winProbability: draft.winProbability === '' ? null : Number(draft.winProbability),
      designerName: draft.designerName.trim() || null,
      ownerName: draft.ownerName.trim() || null,
      buyerName: draft.buyerName.trim() || null,
      nextFollowUpAt: draft.nextFollowUpAt || null,
    });
    setEditOpen(false);
  }

  return (
    <section className="panel">
      <div className="panel-header" style={{ alignItems: 'center' }}>
        <h2>การติดตามดีล</h2>
        <StatusBadge tone={ready ? 'success' : 'warning'}>
          <Icon name={ready ? 'check' : 'clock'} size={12} />
          {ready ? 'พร้อมเลื่อนสถานะ' : 'ยังไม่พร้อม'}
        </StatusBadge>
      </div>

      <div className="flex flex-col gap-4 px-4 py-4 sm:px-5">
        {editOpen ? (
          <div className="flex flex-col gap-3">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                Win probability (%) — ว่างไว้เพื่อใช้ค่าเริ่มต้นตามขั้นดีล
                <input
                  type="number" min="0" max="100" inputMode="numeric"
                  className="text-base"
                  value={draft.winProbability}
                  placeholder={String(effectiveWin)}
                  onChange={(e) => setDraft((d) => ({ ...d, winProbability: e.target.value }))}
                />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                วันติดตามครั้งถัดไป
                <input
                  type="date" className="text-base"
                  value={draft.nextFollowUpAt}
                  onChange={(e) => setDraft((d) => ({ ...d, nextFollowUpAt: e.target.value }))}
                />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                ชื่อดีไซเนอร์
                <input
                  type="text" className="text-base"
                  value={draft.designerName}
                  onChange={(e) => setDraft((d) => ({ ...d, designerName: e.target.value }))}
                />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                ชื่อเจ้าของโครงการ
                <input
                  type="text" className="text-base"
                  value={draft.ownerName}
                  onChange={(e) => setDraft((d) => ({ ...d, ownerName: e.target.value }))}
                />
              </label>
              <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                ชื่อผู้ซื้อ
                <input
                  type="text" className="text-base"
                  value={draft.buyerName}
                  onChange={(e) => setDraft((d) => ({ ...d, buyerName: e.target.value }))}
                />
              </label>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <button type="button" className="primary-button" disabled={updating} onClick={submitTracking}>
                บันทึก
              </button>
              <button type="button" className="secondary-button" disabled={updating} onClick={() => setEditOpen(false)}>
                ยกเลิก
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            <div className="grid grid-cols-1 gap-x-6 gap-y-1.5 sm:grid-cols-2">
              <div className="flex items-center justify-between gap-2 border-b border-border-subtle py-1.5 text-sm">
                <span className="text-text-muted">Win probability</span>
                <span className="flex items-center gap-1.5 font-bold text-text">
                  {effectiveWin}%
                  {hasOverride ? <StatusBadge tone="info">override</StatusBadge> : <span className="text-2xs font-normal text-text-muted">(ค่าเริ่มต้นตามขั้นดีล)</span>}
                </span>
              </div>
              <div className="flex items-center justify-between gap-2 border-b border-border-subtle py-1.5 text-sm">
                <span className="text-text-muted">วันติดตามครั้งถัดไป</span>
                <span className="font-bold text-text">
                  {summary.nextFollowUpAt ? formatThaiDate(summary.nextFollowUpAt) : '—'}
                </span>
              </div>
              <div className="flex items-center justify-between gap-2 border-b border-border-subtle py-1.5 text-sm">
                <span className="text-text-muted">ดีไซเนอร์</span>
                <span className="font-bold text-text">{summary.designerName || '—'}</span>
              </div>
              <div className="flex items-center justify-between gap-2 border-b border-border-subtle py-1.5 text-sm">
                <span className="text-text-muted">เจ้าของโครงการ</span>
                <span className="font-bold text-text">{summary.ownerName || '—'}</span>
              </div>
              <div className="flex items-center justify-between gap-2 border-b border-border-subtle py-1.5 text-sm">
                <span className="text-text-muted">ผู้ซื้อ</span>
                <span className="font-bold text-text">{summary.buyerName || '—'}</span>
              </div>
            </div>
            {canEdit ? (
              <button type="button" className="secondary-button self-start" onClick={openEdit}>
                <Icon name="pencil" size={14} />
                แก้ไขข้อมูลติดตาม
              </button>
            ) : null}
          </div>
        )}
      </div>
    </section>
  );
}
