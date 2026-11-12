import { useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';

const QUICK = [
  { key: 'import', label: 'สั่งนำเข้า' },
  { key: 'stock', label: 'จากสต็อก' },
  { key: 'split', label: 'แยกรายการ' },
];

function modeOf(sources) {
  if (sources.length === 0) return null;
  if (sources.every((s) => !s)) return 'import';
  if (sources.every(Boolean)) return 'stock';
  return 'split';
}

/**
 * "จัดซื้อสินค้า" — where each deal line comes from (stock vs import). A NEW deal's source follows
 * its quotation: read-only, only ยืนยัน. An OLD (legacy, undecided) deal lets the rep choose once.
 * The server owns every rule; this only renders `procurementSource` and POSTs every line once.
 */
export function ProcurementSourceDialog({ open, ticketId, items = [], procurementSource, onClose, onDecided }) {
  const editable = Boolean(procurementSource?.editable);
  // Seeded once from the server's current coverage (the parent mounts the dialog per open).
  const [fromStock, setFromStock] = useState(() => {
    const byId = new Map((procurementSource?.lines ?? []).map((l) => [l.itemId, Boolean(l.fromStock)]));
    return Object.fromEntries(items.map((it) => [it.id, byId.get(it.id) ?? false]));
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  if (!open) return null;

  const sources = items.map((it) => Boolean(fromStock[it.id]));
  const mode = modeOf(sources);
  const setAll = (value) => setFromStock(Object.fromEntries(items.map((it) => [it.id, value])));

  async function confirm() {
    setSubmitting(true);
    setError(null);
    try {
      const lines = items.map((it) => ({ itemId: it.id, fromStock: Boolean(fromStock[it.id]) }));
      const { ticket } = await api.tickets.decideProcurementSource(ticketId, { lines });
      onDecided?.(ticket, { anyImport: lines.some((l) => !l.fromStock) });
    } catch (e) {
      setError(e?.message || 'บันทึกที่มาสินค้าไม่สำเร็จ');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title="จัดซื้อสินค้า"
      onClose={onClose}
      testId="procurement-source-dialog"
      footer={(
        <Button onClick={confirm} disabled={items.length === 0 || submitting} loading={submitting}>ยืนยัน</Button>
      )}
    >
      <div className="grid grid-cols-3 gap-2 mb-3">
        {QUICK.map((q) => (
          <Button
            key={q.key}
            className="whitespace-nowrap mobile:!px-1 mobile:!text-xs"
            variant={mode === q.key ? 'primary' : 'secondary'}
            aria-pressed={mode === q.key}
            disabled={!editable || submitting}
            onClick={() => {
              if (q.key === 'import') setAll(false);
              else if (q.key === 'stock') setAll(true);
            }}
          >
            {q.label}
          </Button>
        ))}
      </div>

      {items.length === 0 ? (
        <p className="m-0 text-text-muted">ยังไม่มีรายการสินค้า</p>
      ) : (
        <ul className="m-0 p-0 list-none border border-border rounded-md divide-y divide-border">
          {items.map((it) => {
            const stock = Boolean(fromStock[it.id]);
            return (
              <li key={it.id} data-testid="procurement-line" className="flex items-center gap-3 px-3 py-2">
                <div className="flex-1 min-w-0 font-semibold">
                  {[it.brand, it.model, it.size].filter(Boolean).join(' ')}
                  <small className="block font-normal text-text-muted">{it.qty} {it.unit}</small>
                </div>
                {editable ? (
                  <Button
                    role="switch"
                    aria-checked={stock}
                    aria-label={`${[it.brand, it.model].filter(Boolean).join(' ')} ที่มา`}
                    variant={stock ? 'primary' : 'secondary'}
                    disabled={submitting}
                    onClick={() => setFromStock((prev) => ({ ...prev, [it.id]: !prev[it.id] }))}
                  >
                    {stock ? 'สต็อก' : 'นำเข้า'}
                  </Button>
                ) : (
                  <span className="rounded-full bg-surface-muted px-[10px] py-1 text-[length:var(--text-sm)] font-semibold">
                    {stock ? 'สต็อก' : 'นำเข้า'}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {!editable && procurementSource?.quotationNumber ? (
        <p className="mt-[10px] mb-0 text-text-muted text-[length:var(--text-sm)]">
          ตามใบเสนอราคา {procurementSource.quotationNumber}
        </p>
      ) : null}
      {error ? <p role="alert" className="mt-[10px] mb-0 text-danger">{error}</p> : null}
    </Modal>
  );
}
