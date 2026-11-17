import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { hasActivitySince, isReadyToAdvance, lastStageChangeAt } from './dealTrackingMeta.js';
import { ESTIMATE_ERROR, parseEstimatedValue } from './dealEstimate.js';
import { quarterLabel } from '../salesBoard/boardFormat.js';

function Row({ label, children }) {
  return (
    <div className="grid grid-cols-[8.5rem_minmax(0,1fr)] gap-3 border-b border-border-subtle py-2 text-sm last:border-b-0 mobile:grid-cols-[7rem_minmax(0,1fr)]">
      <dt className="text-text-muted">{label}</dt>
      <dd className="m-0 min-w-0 font-semibold text-text [overflow-wrap:anywhere]">{children || '—'}</dd>
    </div>
  );
}

/**
 * "ข้อมูลงาน" (drawing a-deal): the deal's who / when / how likely in one card. It merges what the old ดีล tab held
 * in three places — the วันสำคัญ and ผู้เกี่ยวข้อง blocks and the "การติดตามดีล" panel (DealTrackingPanel, whose edit
 * form moved here unchanged) — so every readout stays, the drawn six first.
 *
 *   - `trackingVisible` — the old panel's gate (canViewDealTracking: sales, sales_manager, ceo): the โอกาส row, the
 *     ready-to-advance badge and the edit form.
 *   - `canEdit` — the old panel's canEdit (owner/keyer, sales_manager, ceo; TicketService.requireDealOwnership).
 * `designers` / `owners`: the DESIGNER / OWNER deal parties' names, which win over the free-text names.
 * "คาดปิด" (expected quarter) is drawn but not served on the deal (TicketSummaryDto has no expectedQuarter), so it is
 * not shown.
 */
export function DealInfoCard({
  summary, events = [], activities = [], designers = [], owners = [],
  latestFollowUp = null, quotationExpiry = null, assignedImport = null,
  trackingVisible = false, canEdit = false, onUpdateTracking, updating = false,
}) {
  const [draft, setDraft] = useState(null); // the edit form's values while it is open

  const effectiveWin = summary.effectiveWinProbability ?? 0;
  const hasOverride = summary.winProbabilityOverride != null;
  const ready = isReadyToAdvance(summary, hasActivitySince(activities, lastStageChangeAt(events, summary.createdAt)));

  const [estimateError, setEstimateError] = useState('');

  function openEdit() {
    setEstimateError('');
    setDraft({
      winProbability: summary.winProbabilityOverride != null ? String(summary.winProbabilityOverride) : '',
      designerName: summary.designerName ?? '',
      ownerName: summary.ownerName ?? '',
      nextFollowUpAt: summary.nextFollowUpAt ?? '',
      estimatedValue: summary.estimatedValue != null ? String(summary.estimatedValue) : '',
    });
  }

  async function submitTracking() {
    // มูลค่าโดยประมาณ (V220 estimated_value): set when typed, cleared when emptied (`clearEstimatedValue`, because the
    // endpoint KEEPS an absent estimate). Same bounds as the create form and the server.
    const estimate = parseEstimatedValue(draft.estimatedValue);
    if (Number.isNaN(estimate)) {
      setEstimateError(ESTIMATE_ERROR);
      return;
    }
    await onUpdateTracking({
      winProbability: draft.winProbability === '' ? null : Number(draft.winProbability),
      designerName: draft.designerName.trim() || null,
      ownerName: draft.ownerName.trim() || null,
      // Buyers are deal parties now (deal-party-model.md, "ผู้รับเหมาที่เสนอราคา"); the PUT is a full
      // replace, so the stored free-text value is sent back unchanged rather than cleared.
      buyerName: summary.buyerName ?? null,
      // PUT /tracking keeps the date when absent, so a stale form cannot overwrite one a call/visit set: send it only
      // when changed, and `clearNextFollowUpAt` when emptied.
      ...(draft.nextFollowUpAt === (summary.nextFollowUpAt ?? '') ? {}
        : draft.nextFollowUpAt ? { nextFollowUpAt: draft.nextFollowUpAt } : { clearNextFollowUpAt: true }),
      ...(estimate == null ? { clearEstimatedValue: true } : { estimatedValue: estimate }),
    });
    setDraft(null);
  }

  return (
    <Panel
      title="ข้อมูลงาน"
      actions={trackingVisible ? (
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge tone={ready ? 'success' : 'warning'}>
            <Icon name={ready ? 'check' : 'clock'} size={12} />
            {ready ? 'พร้อมเลื่อนสถานะ' : 'ยังไม่พร้อม'}
          </StatusBadge>
          {canEdit && !draft ? (
            <Button type="button" variant="secondary" size="sm" className="mobile:min-h-[44px]" onClick={openEdit}>
              <Icon name="pencil" size={14} />
              แก้ไข
            </Button>
          ) : null}
        </div>
      ) : null}
    >
      {draft ? (
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
              Win probability (%) — ว่างไว้เพื่อใช้ค่าเริ่มต้นตามขั้น
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
              <ThaiDateInput
                className="text-base"
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
              มูลค่าโดยประมาณ (บาท) — ว่างไว้เพื่อล้างค่า
              <input
                type="text" inputMode="decimal" className="text-base"
                value={draft.estimatedValue}
                aria-invalid={estimateError ? true : undefined}
                onChange={(e) => { setEstimateError(''); setDraft((d) => ({ ...d, estimatedValue: e.target.value })); }}
              />
              {estimateError ? <span role="alert" className="text-xs font-bold text-danger">{estimateError}</span> : null}
            </label>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="primary" disabled={updating} onClick={submitTracking}>บันทึก</Button>
            <Button type="button" variant="secondary" disabled={updating} onClick={() => setDraft(null)}>ยกเลิก</Button>
          </div>
        </div>
      ) : (
        <dl className="m-0">
          <Row label="ลูกค้า">{summary.customerName}</Row>
          <Row label="ผู้ออกแบบ">{designers.length ? designers.join(', ') : summary.designerName}</Row>
          <Row label="เจ้าของโครงการ">{owners.length ? owners.join(', ') : summary.ownerName}</Row>
          <Row label="เจ้าของงาน">{summary.createdByName}</Row>
          {/* Slice 6.3: the drawn คาดปิด row (TicketSummaryDto.expectedQuarter, V209), in the board's own style. */}
          <Row label="คาดปิด">{quarterLabel(summary.expectedQuarter)}</Row>
          {trackingVisible ? (
            <Row label="โอกาส">
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <span className="tabular-nums">{`${effectiveWin}%`}</span>
                {hasOverride
                  ? <StatusBadge tone="info">กำหนดเอง</StatusBadge>
                  : <span className="text-2xs font-normal text-text-muted">(ค่าเริ่มต้นตามขั้น)</span>}
              </span>
            </Row>
          ) : null}
          {summary.estimatedValue != null ? (
            <Row label="มูลค่าโดยประมาณ">{`฿${Number(summary.estimatedValue).toLocaleString('th-TH')}`}</Row>
          ) : null}
          <Row label="ติดตามครั้งถัดไป">{summary.nextFollowUpAt ? formatThaiDate(summary.nextFollowUpAt) : null}</Row>
          <Row label="ติดตามล่าสุด">{latestFollowUp ? formatThaiDate(latestFollowUp) : null}</Row>
          <Row label="ใบเสนอราคาหมดอายุ">{quotationExpiry ? formatThaiDate(quotationExpiry) : null}</Row>
          <Row label="ผู้รับเรื่องคำขอราคา">{assignedImport}</Row>
          {summary.assignedToName ? <Row label="เจ้าหน้าที่นำเข้าที่ดูแล">{summary.assignedToName}</Row> : null}
          <Row label="บัญชี">{summary.closeConfirmedByName || 'ยังไม่ระบุ'}</Row>
          <Row label="อัปเดตล่าสุด">{formatThaiDate(summary.updatedAt)}</Row>
        </dl>
      )}
    </Panel>
  );
}
