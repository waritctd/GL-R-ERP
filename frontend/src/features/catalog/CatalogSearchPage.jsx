import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { TicketCreateModal } from '../tickets/TicketCreateModal.jsx';

// ── helpers ───────────────────────────────────────────────────────────────────

function unitLabel(unit) {
  if (unit === 'per_sqm')     return 'ม²';
  if (unit === 'per_piece')   return 'แผ่น';
  if (unit === 'per_box')     return 'กล่อง';
  if (unit === 'per_linear_m') return 'ม.';
  return unit ?? '';
}

function priceDisplay(price, currency) {
  const n = Number(price);
  if (!n) return '-';
  return `${n.toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency ?? ''}`;
}

function productToTicketItem(p) {
  return {
    brand:       p.factoryName ?? '',
    model:       p.collection  ?? p.productName ?? '',
    color:       p.color       ?? '',
    texture:     p.surface     ?? '',
    size:        p.sizeRaw     ?? '',
    factory:     p.factoryName ?? '',
    unitBasis:   p.priceUnit === 'per_sqm' ? 'SQM' : 'PIECE',
    qty:         1,
    qtySqm:      '',
    sqmPerPiece: p.sqmPerPiece ?? null,
  };
}

let _debounce = null;
function debounce(fn, ms) {
  clearTimeout(_debounce);
  _debounce = setTimeout(fn, ms);
}

// ── result card ───────────────────────────────────────────────────────────────

function ProductCard({ product, onRequestQuote }) {
  return (
    <div className="bg-surface border border-border rounded-md p-4 flex flex-col gap-2 hover:border-primary transition-colors">
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <p className="font-semibold text-sm truncate">
            {product.collection || product.productName || product.productCode || '—'}
          </p>
          <p className="text-xs text-muted">{product.factoryName}</p>
        </div>
        {product.grade && (
          <span className="shrink-0 text-xs bg-blue-100 text-blue-700 px-2 py-0.5 rounded font-medium">
            {product.grade}
          </span>
        )}
      </div>

      <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
        {product.productCode && (
          <>
            <span className="text-muted">รหัส</span>
            <span className="font-mono">{product.productCode}</span>
          </>
        )}
        {product.color && (
          <>
            <span className="text-muted">สี</span>
            <span>{product.color}</span>
          </>
        )}
        {product.surface && (
          <>
            <span className="text-muted">ผิว</span>
            <span>{product.surface}</span>
          </>
        )}
        {product.sizeRaw && (
          <>
            <span className="text-muted">ขนาด</span>
            <span>{product.sizeRaw}</span>
          </>
        )}
      </div>

      <div className="flex items-center justify-between mt-1 pt-2 border-t border-border">
        <span className="text-sm font-bold text-primary">
          {priceDisplay(product.price, product.currency)}
          <span className="text-xs font-normal text-muted ml-1">/ {unitLabel(product.priceUnit)}</span>
        </span>
        <Button size="sm" variant="secondary" onClick={() => onRequestQuote(product)}>
          <Icon name="plus" />
          ขอราคา
        </Button>
      </div>
    </div>
  );
}

// ── main page ─────────────────────────────────────────────────────────────────

export function CatalogSearchPage({ user, showToast }) {
  const canCreateTicket = hasPermission(user?.role, 'canCreateTickets');

  const [query, setQuery]     = useState('');
  const [factoryId, setFactoryId] = useState('');
  const [factories, setFactories] = useState([]);
  const [items, setItems]     = useState([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  const [modalItems, setModalItems] = useState(null); // null = closed

  const inputRef = useRef(null);

  useEffect(() => {
    api.priceImport.factories().then(setFactories).catch(() => {});
    inputRef.current?.focus();
  }, []);

  const doSearch = useCallback(async (q, fid) => {
    setLoading(true);
    setSearched(true);
    try {
      const res = await api.catalog.prices(q || undefined, fid || undefined);
      setItems(res.items ?? []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  function handleQueryChange(e) {
    const q = e.target.value;
    setQuery(q);
    debounce(() => doSearch(q, factoryId), 300);
  }

  function handleFactoryChange(e) {
    const fid = e.target.value;
    setFactoryId(fid);
    doSearch(query, fid);
  }

  function handleSearch(e) {
    e.preventDefault();
    doSearch(query, factoryId);
  }

  function openQuoteModal(product) {
    setModalItems([productToTicketItem(product)]);
  }

  async function handleCreateTicket(payload) {
    try {
      await api.tickets.create(payload);
      setModalItems(null);
      showToast?.('success', 'สร้างใบขอราคาสำเร็จ');
    } catch (err) {
      showToast?.('error', err.message || 'สร้างไม่สำเร็จ');
    }
  }

  return (
    <PageStack>
      <PageHeader title="ค้นหาสินค้า" subtitle="ราคาจาก price list ที่ import ล่าสุด (ACTIVE)" />

      {/* Search bar */}
      <Panel>
        <form onSubmit={handleSearch} className="flex flex-wrap gap-3 items-end">
          <div className="flex-1 min-w-[200px]">
            <label className="block text-sm font-medium mb-1" htmlFor="catalog-q">
              ค้นหา (แบรนด์ / รุ่น / สี / รหัส)
            </label>
            <input
              id="catalog-q"
              ref={inputRef}
              type="text"
              className="input w-full"
              placeholder="เช่น Panaria, Stone, L-Trim..."
              value={query}
              onChange={handleQueryChange}
            />
          </div>
          <div className="min-w-[180px]">
            <label className="block text-sm font-medium mb-1" htmlFor="catalog-factory">
              โรงงาน
            </label>
            <select
              id="catalog-factory"
              className="input w-full"
              value={factoryId}
              onChange={handleFactoryChange}
            >
              <option value="">— ทุกโรงงาน —</option>
              {factories.map((f) => (
                <option key={f.factoryId} value={f.factoryId}>{f.name}</option>
              ))}
            </select>
          </div>
          <Button type="submit" variant="primary" disabled={loading}>
            <Icon name="search" />
            {loading ? 'กำลังค้นหา…' : 'ค้นหา'}
          </Button>
        </form>
      </Panel>

      {/* Results */}
      {loading && (
        <p className="text-sm text-muted text-center py-6">กำลังโหลด…</p>
      )}

      {!loading && searched && items.length === 0 && (
        <p className="text-sm text-muted text-center py-6">ไม่พบสินค้าที่ตรงกัน</p>
      )}

      {!loading && items.length > 0 && (
        <>
          <p className="text-xs text-muted">พบ {items.length} รายการ{items.length >= 50 ? ' (แสดงสูงสุด 50)' : ''}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((p) => (
              <ProductCard
                key={p.priceId}
                product={p}
                onRequestQuote={canCreateTicket ? openQuoteModal : undefined}
              />
            ))}
          </div>
        </>
      )}

      {!searched && !loading && (
        <p className="text-sm text-muted text-center py-10">
          พิมพ์คำค้นหา หรือเลือกโรงงานเพื่อดูสินค้า
        </p>
      )}

      {/* Ticket create modal — pre-filled from selected product */}
      {modalItems && (
        <TicketCreateModal
          initialItems={modalItems}
          onClose={() => setModalItems(null)}
          onSubmit={handleCreateTicket}
        />
      )}
    </PageStack>
  );
}
