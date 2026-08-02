// DealLegacyQuotations — extracted verbatim from TicketDetailPage.jsx in the
// ticket-workspace IA rebuild (ia-extract Slice C1, "extract two panels out
// of TicketDetailPage"). This is a pure move: the JSX, its comments, and the
// row markup are unchanged from the "quotations" tab's ใบเสนอราคา (เอกสารเดิม)
// section — only the plumbing (props in, formatting helpers imported
// directly like the parent already did, `docStatusColors` moved alongside
// its only caller) changed so this block could live in its own file. It
// holds NO state of its own: `quotationGroups` is computed by the parent,
// and the download click still calls back into the parent's
// `downloadingQuotationKey`/`handleDownloadQuotation` state and mutation.
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, quotationStatusLabel } from '../../utils/format.js';

// Quotation revision docStatus (DRAFT / ISSUED / SUPERSEDED) mapped onto the same
// success/info/neutral tokens StatusBadge uses, instead of one-off hex per state.
// Previously SUPERSEDED text used Ink Faint (#94a3b8), below the DESIGN.md Ink Muted
// contrast floor on a light background — this switches it to --color-icon-muted.
function docStatusColors(docStatus) {
  if (docStatus === 'SUPERSEDED') {
    return { background: 'var(--color-surface-subtle)', color: 'var(--color-icon-muted)' };
  }
  if (docStatus === 'ISSUED') {
    return { background: 'var(--color-success-bg)', color: 'var(--color-success-dark)' };
  }
  return { background: 'var(--color-info-bg)', color: 'var(--color-info)' };
}

export function DealLegacyQuotations({ quotationGroups, downloadingQuotationKey, handleDownloadQuotation }) {
  if (quotationGroups.length === 0) return null;
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>ใบเสนอราคา (เอกสารเดิม)</h2>
      </div>
      {/* Ticket-native quotation generate/mark-sent/accepted/rejected is retired
          (Phase 2 Slice S1/S2 — see docs/agent-handoffs/104): these rows predate the
          PricingRequest/CustomerQuotation redesign (pricing_request_id IS NULL) and
          stay visible read-only/download-only so the 3 legacy deals' history isn't
          stranded. New quotations live in DealQuotationPanel above. */}
      {quotationGroups.map((group) => (
        <div key={group.recipientType} style={{ borderTop: '1px solid var(--color-surface-subtle)' }}>
          <div style={{ padding: '12px 18px 6px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
            <h3 style={{ margin: 0, fontSize: 14 }}>{group.label}</h3>
          </div>
          {group.quotations.map((q) => {
            const status = quotationStatusLabel(q.docStatus);
            return (
              <div key={q.id} style={{ padding: '10px 18px', borderTop: '1px solid var(--color-surface-subtle)', display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                <div style={{ flexShrink: 0, marginTop: 2 }}>
                  <span style={{
                    fontSize: 11, fontWeight: 700, borderRadius: 4, padding: '2px 7px',
                    ...docStatusColors(q.docStatus),
                  }}>ครั้งที่ {q.quotationVersion}</span>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    <span style={{ fontWeight: 600, fontSize: 13 }}>{q.number}</span>
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                    {q.recipientLabel && <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{q.recipientLabel}</span>}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--color-icon-muted)', marginTop: 2 }}>
                    ยอดรวม {formatMoney(q.totalAmount)} · ออกโดย {q.issuedByName} · ออก {formatThaiDate(q.issuedAt)}
                    {q.sentAt ? ` · ส่ง ${formatThaiDate(q.sentAt)}` : ''}
                    {q.acceptedAt ? ` · รับ ${formatThaiDate(q.acceptedAt)}` : ''}
                    {q.validityDate ? ` · ใช้ได้ถึง ${formatThaiDate(q.validityDate)}` : ''}
                  </div>
                  {(q.paymentTerms || q.leadTime || q.deliveryTerms) && (
                    <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 4 }}>
                      {[q.paymentTerms && `ชำระเงิน: ${q.paymentTerms}`, q.leadTime && `ระยะเวลาส่งมอบ: ${q.leadTime}`, q.deliveryTerms && `ส่งมอบ: ${q.deliveryTerms}`].filter(Boolean).join(' · ')}
                    </div>
                  )}
                </div>
                <div style={{ display: 'flex', gap: 6, flexShrink: 0, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                  <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                    disabled={downloadingQuotationKey === `${q.id}-xlsx`}
                    onClick={() => handleDownloadQuotation(q.id, q.number, 'xlsx')}>
                    <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-xlsx` ? 'กำลังดาวน์โหลด…' : 'Excel'}
                  </button>
                  <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                    disabled={downloadingQuotationKey === `${q.id}-pdf`}
                    onClick={() => handleDownloadQuotation(q.id, q.number, 'pdf')}>
                    <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-pdf` ? 'กำลังดาวน์โหลด…' : 'PDF'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      ))}
    </section>
  );
}
