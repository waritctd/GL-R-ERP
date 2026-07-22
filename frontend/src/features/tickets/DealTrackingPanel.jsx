import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import {
  ACTIVITY_KINDS, activityKindLabel, effectiveWinProbability,
  hasActivitySince, isReadyToAdvance, lastStageChangeAt, STAGE_ADVANCE_GATE_HINT,
} from './dealTrackingMeta.js';

function today() {
  return new Date().toISOString().slice(0, 10);
}

const EMPTY_ACTIVITY_DRAFT = { activityDate: today(), kind: 'CALL', note: '' };

/**
 * "การติดตามดีล" (Slice B2, handoff 103) — the weekly-report replacement: win%
 * (effective value + rep override), designer/owner/buyer names, next follow-up
 * date, and the activity log that backs the stage-advance gate. Read-only for
 * any viewer sections.dealTracking lets in; `canEdit` narrows further to the
 * deal owner / sales_manager / ceo, mirroring TicketService.requireDealOwnership
 * (see DealTrackingAndActivityIntegrationTest, backend Slice B1 — the real
 * enforcement; this component's canEdit is a UI convenience, not authoritative).
 */
export function DealTrackingPanel({
  summary, events, activities = [], activitiesLoading, canEdit,
  onUpdateTracking, onAddActivity, updating, addingActivity,
}) {
  const [editOpen, setEditOpen] = useState(false);
  const [draft, setDraft] = useState(null); // set on edit open
  const [activityDraft, setActivityDraft] = useState(EMPTY_ACTIVITY_DRAFT);

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

  async function submitActivity() {
    if (!activityDraft.activityDate) return;
    await onAddActivity({
      activityDate: activityDraft.activityDate,
      kind: activityDraft.kind,
      note: activityDraft.note.trim() || null,
    });
    setActivityDraft(EMPTY_ACTIVITY_DRAFT);
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
        {!ready ? (
          <div className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2.5 text-xs text-warning-dark">
            <Icon name="clock" size={14} className="mt-0.5 shrink-0" />
            <span>{STAGE_ADVANCE_GATE_HINT}</span>
          </div>
        ) : null}

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

        <div className="flex flex-col gap-2 border-t border-border pt-3">
          <h3 className="m-0 text-sm font-extrabold text-text">ประวัติการติดตาม (Activity log)</h3>
          {activitiesLoading ? (
            <span className="text-xs text-text-muted">กำลังโหลด...</span>
          ) : activities.length === 0 ? (
            <span className="text-xs text-text-muted">ยังไม่มีการบันทึกกิจกรรม</span>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {[...activities].reverse().map((activity) => (
                <li key={activity.id} className="flex flex-col gap-0.5 rounded-lg border border-border-subtle bg-surface-subtle px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge tone="neutral">{activityKindLabel(activity.kind)}</StatusBadge>
                    <span className="text-xs font-bold text-text-muted">{formatThaiDate(activity.activityDate)}</span>
                    <span className="text-2xs text-text-muted">· {activity.createdByName}</span>
                  </div>
                  {activity.note ? <p className="m-0 text-xs text-text-secondary">{activity.note}</p> : null}
                </li>
              ))}
            </ul>
          )}

          {canEdit ? (
            <div className="mt-1 flex flex-col gap-2 rounded-lg border border-dashed border-border p-3">
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                  วันที่
                  <input
                    type="date" className="text-base"
                    value={activityDraft.activityDate}
                    onChange={(e) => setActivityDraft((d) => ({ ...d, activityDate: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                  ประเภท
                  <select
                    className="text-base"
                    value={activityDraft.kind}
                    onChange={(e) => setActivityDraft((d) => ({ ...d, kind: e.target.value }))}
                  >
                    {ACTIVITY_KINDS.map((k) => (
                      <option key={k.code} value={k.code}>{k.label}</option>
                    ))}
                  </select>
                </label>
              </div>
              <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
                บันทึก (ถ้ามี)
                <textarea
                  rows={2}
                  value={activityDraft.note}
                  onChange={(e) => setActivityDraft((d) => ({ ...d, note: e.target.value }))}
                  placeholder="รายละเอียดการติดตอบ / ผลที่ได้"
                />
              </label>
              <button
                type="button" className="secondary-button self-end"
                disabled={addingActivity || !activityDraft.activityDate}
                onClick={submitActivity}
              >
                <Icon name="plus" size={14} />
                บันทึกกิจกรรม
              </button>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}
