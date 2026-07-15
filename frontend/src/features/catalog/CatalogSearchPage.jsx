import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { ProductFormModal } from './ProductFormModal.jsx';

// ── helpers ───────────────────────────────────────────────────────────────────

function unitLabel(unit) {
  if (unit === 'per_sqm')      return 'ม²';
  if (unit === 'per_piece')    return 'แผ่น';
  if (unit === 'per_box')      return 'กล่อง';
  if (unit === 'per_linear_m') return 'ม.';
  return unit ?? '';
}

function priceDisplay(price, currency) {
  const n = Number(price);
  if (!n) return '-';
  return `${n.toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency ?? ''}`;
}

let _debounce = null;
function debounce(fn, ms) {
  clearTimeout(_debounce);
  _debounce = setTimeout(fn, ms);
}

// ── main page ─────────────────────────────────────────────────────────────────

export function CatalogSearchPage({ showToast }) {
  const [query, setQuery]         = useState('');
  const [factoryId, setFactoryId] = useState('');
  const [factories, setFactories] = useState([]);
  const [items, setItems]         = useState([]);
  const [loading, setLoading]     = useState(false);
  const [searched, setSearched]   = useState(false);

  const [editingProduct, setEditingProduct] = useState(null);

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

  async function handleProductSaved() {
    setEditingProduct(null);
    showToast?.('success', 'บันทึกสินค้าแล้ว');
    await doSearch(query, factoryId);
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
          <p className="text-xs text-muted mb-2">
            พบ {items.length} รายการ{items.length >= 50 ? ' (แสดงสูงสุด 50)' : ''}
          </p>
          <div className="overflow-x-auto">
            <table className="w-full text-sm border-collapse">
              <thead className="border-b-2 border-border">
                <tr>
                  <th className="text-left px-3 py-2 text-muted font-medium">โรงงาน</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">รหัส</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">Collection</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">ชื่อ</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">สี</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">ผิว</th>
                  <th className="text-left px-3 py-2 text-muted font-medium">ขนาด</th>
                  <th className="text-right px-3 py-2 text-muted font-medium">ราคา</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {items.map((p) => (
                  <tr
                    key={p.priceId}
                    className="border-b border-border hover:bg-surface-alt transition-colors"
                  >
                    <td className="px-3 py-2 font-medium">{p.factoryName}</td>
                    <td className="px-3 py-2 font-mono text-xs">{p.productCode || '—'}</td>
                    <td className="px-3 py-2">{p.collection || '—'}</td>
                    <td className="px-3 py-2">{p.productName || '—'}</td>
                    <td className="px-3 py-2">{p.color || '—'}</td>
                    <td className="px-3 py-2">{p.surface || '—'}</td>
                    <td className="px-3 py-2">{p.sizeRaw || '—'}</td>
                    <td className="px-3 py-2 text-right whitespace-nowrap font-bold text-primary">
                      {priceDisplay(p.price, p.currency)}
                      <span className="text-xs font-normal text-muted ml-1">
                        / {unitLabel(p.priceUnit)}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      <Button size="sm" variant="secondary" onClick={() => setEditingProduct(p)}>
                        แก้ไข
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {!searched && !loading && (
        <p className="text-sm text-muted text-center py-10">
          พิมพ์คำค้นหา หรือเลือกโรงงานเพื่อดูสินค้า
        </p>
      )}

      {editingProduct && (
        <ProductFormModal
          product={editingProduct}
          onClose={() => setEditingProduct(null)}
          onSaved={handleProductSaved}
        />
      )}
    </PageStack>
  );
}
