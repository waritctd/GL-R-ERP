import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { ProductFormModal } from './ProductFormModal.jsx';

// ── helpers ───────────────────────────────────────────────────────────────────

function num(n) {
  return n != null ? Number(n).toLocaleString('th-TH') : '0';
}

function unitLabel(unit) {
  if (unit === 'per_sqm')      return 'ม²';
  if (unit === 'per_piece')    return 'แผ่น';
  if (unit === 'per_box')      return 'กล่อง';
  if (unit === 'per_linear_m') return 'ม.';
  return unit ?? '';
}

function priceDisplay(price, currency) {
  const n = Number(price);
  if (!n) return '—';
  return `${n.toLocaleString('th-TH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency ?? ''}`;
}

function statusTone(status) {
  if (status === 'ACTIVE')   return 'green';
  if (status === 'DRAFT')    return 'blue';
  if (status === 'ARCHIVED') return 'neutral';
  return 'neutral';
}

// ── AddFactoryModal ───────────────────────────────────────────────────────────

function AddFactoryModal({ onClose, onCreated }) {
  const [name, setName]         = useState('');
  const [country, setCountry]   = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [saving, setSaving]     = useState(false);
  const [error, setError]       = useState('');

  async function handleSubmit(e) {
    e.preventDefault();
    if (!name.trim()) { setError('กรุณาใส่ชื่อโรงงาน'); return; }
    setSaving(true);
    setError('');
    try {
      const factory = await api.priceImport.createFactory(
        name.trim(), country.trim() || undefined, currency
      );
      onCreated(factory);
    } catch (err) {
      setError(err.message || 'เพิ่มโรงงานไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title="เพิ่มโรงงานใหม่"
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="add-factory-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      }
    >
      <form id="add-factory-form" onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="af-name" className="block text-sm font-medium mb-1">ชื่อโรงงาน *</label>
          <input
            id="af-name"
            type="text"
            className="input w-full"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="เช่น Rex Ceramics"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="af-country" className="block text-sm font-medium mb-1">
              ประเทศ (2 อักษร)
            </label>
            <input
              id="af-country"
              type="text"
              className="input w-full"
              value={country}
              onChange={(e) => setCountry(e.target.value.toUpperCase())}
              maxLength={2}
              placeholder="เช่น IT, ES"
            />
          </div>
          <div>
            <label htmlFor="af-currency" className="block text-sm font-medium mb-1">
              สกุลเงินหลัก
            </label>
            <select
              id="af-currency"
              className="input w-full"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
            >
              <option>EUR</option>
              <option>USD</option>
              <option>THB</option>
              <option>GBP</option>
            </select>
          </div>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
      </form>
    </Modal>
  );
}

// ── UploadResultCard ──────────────────────────────────────────────────────────

function UploadResultCard({ result }) {
  return (
    <Panel title="อัปโหลดและ Commit สำเร็จ">
      <div className="flex flex-wrap gap-4 mb-4">
        <div className="flex flex-col items-center p-3 bg-green-50 rounded-md min-w-[90px]">
          <span className="text-2xl font-bold text-green-700">{num(result.committedRows)}</span>
          <span className="text-xs text-muted">แถวที่บันทึก</span>
        </div>
        <div className="flex flex-col items-center p-3 bg-surface border border-border rounded-md min-w-[90px]">
          <span className="text-2xl font-bold">{num(result.parsedRows)}</span>
          <span className="text-xs text-muted">แถวที่อ่านได้</span>
        </div>
        {result.retainedRows > 0 && (
          <div className="flex flex-col items-center p-3 bg-blue-50 rounded-md min-w-[90px]">
            <span className="text-2xl font-bold text-blue-700">{num(result.retainedRows)}</span>
            <span className="text-xs text-muted">คงราคาเดิมไว้</span>
          </div>
        )}
        {result.errorCount > 0 && (
          <div className="flex flex-col items-center p-3 bg-yellow-50 rounded-md min-w-[90px]">
            <span className="text-2xl font-bold text-yellow-700">{num(result.errorCount)}</span>
            <span className="text-xs text-muted">แถวถูกข้าม</span>
          </div>
        )}
      </div>
      {result.errors?.length > 0 && (
        <details>
          <summary className="cursor-pointer text-sm text-yellow-700 font-medium">
            ดูรายละเอียด ({result.errors.length} รายการ)
          </summary>
          <ul className="mt-2 space-y-1 max-h-40 overflow-y-auto">
            {result.errors.map((e, i) => (
              <li key={i} className="text-xs text-yellow-800 bg-yellow-50 px-3 py-1 rounded font-mono">
                {e}
              </li>
            ))}
          </ul>
        </details>
      )}
    </Panel>
  );
}

// ── ProductCard (mobile) ──────────────────────────────────────────────────────
// Same 8-column desktop table as CatalogSearchPage, minus the factory column
// (already filtered to one factory here) — reflowed by hand since this is a
// plain `<table>`, not DataTable.
function ProductCard({ product, onEdit, onDelete }) {
  return (
    <div className="mt-2.5 flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 first:mt-0">
      {/* This list is already scoped to one selected factory, so collection is the
          identity anchor here (it leads the desktop table); productName is often
          empty in real price-list rows and would collapse the line to a dash. */}
      <div className="flex min-w-0 items-start justify-between gap-3">
        <strong className="min-w-0 truncate text-md font-extrabold text-text">
          {product.collection || product.productName || '—'}
        </strong>
        <code className="shrink-0 text-2xs text-text-muted">{product.productCode}</code>
      </div>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[product.productName, product.color, product.surface, product.sizeRaw]
          .filter(Boolean)
          .join(' · ')}
      </span>

      <div className="flex items-center justify-between gap-3">
        <span className="text-md font-extrabold text-primary">
          {priceDisplay(product.price, product.currency)}
          <span className="ml-1 text-xs font-normal text-muted">/ {unitLabel(product.priceUnit)}</span>
        </span>
        <div className="flex gap-1">
          <Button size="sm" variant="secondary" onClick={() => onEdit(product)}>
            แก้ไข
          </Button>
          <Button size="sm" variant="danger" onClick={() => onDelete(product)}>
            ลบ
          </Button>
        </div>
      </div>
    </div>
  );
}

// ── VersionsPanel ─────────────────────────────────────────────────────────────

function VersionsPanel({ versions, currentVersionId }) {
  if (!versions.length) return null;
  return (
    <Panel title="ประวัติการนำเข้า">
      <ul className="space-y-2 max-h-48 overflow-y-auto">
        {versions.map((v) => (
          <li
            key={v.versionId}
            className={`flex items-center justify-between gap-2 px-3 py-2 rounded border ${
              v.versionId === currentVersionId
                ? 'border-blue-400 bg-blue-50'
                : 'border-border bg-surface'
            }`}
          >
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{v.label || `Version ${v.versionId}`}</p>
              <p className="text-xs text-muted">
                {v.uploadedAt ? new Date(v.uploadedAt).toLocaleString('th-TH') : ''}
              </p>
            </div>
            <StatusBadge tone={statusTone(v.status)}>{v.status}</StatusBadge>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

// ── main page ─────────────────────────────────────────────────────────────────

export function PriceImportPage({ showToast }) {
  const isMobile = useIsMobile();
  const [factories, setFactories]             = useState([]);
  const [factoryId, setFactoryId]             = useState('');
  const [versions, setVersions]               = useState([]);
  const [products, setProducts]               = useState([]);
  const [loadingProducts, setLoadingProducts] = useState(false);

  const [file, setFile]                         = useState(null);
  const [label, setLabel]                       = useState('');
  const [uploading, setUploading]               = useState(false);
  const [uploadResult, setUploadResult]         = useState(null);
  const [currentVersionId, setCurrentVersionId] = useState(null);
  const [error, setError]                       = useState('');

  const [showFactoryModal, setShowFactoryModal] = useState(false);
  const [editingProduct, setEditingProduct]     = useState(null);

  const fileRef = useRef(null);

  useEffect(() => {
    api.priceImport.factories().then(setFactories).catch(() => {});
  }, []);

  const loadVersions = useCallback(async (fid) => {
    if (!fid) return;
    try {
      const data = await api.priceImport.versions(fid);
      setVersions(Array.isArray(data) ? data : []);
    } catch {
      setVersions([]);
    }
  }, []);

  const loadProducts = useCallback(async (fid) => {
    if (!fid) return;
    setLoadingProducts(true);
    try {
      const res = await api.catalog.prices(undefined, Number(fid), 200);
      setProducts(res.items ?? []);
    } catch {
      setProducts([]);
    } finally {
      setLoadingProducts(false);
    }
  }, []);

  function handleFactoryChange(e) {
    const fid = e.target.value;
    setFactoryId(fid);
    setUploadResult(null);
    setCurrentVersionId(null);
    setFile(null);
    setLabel('');
    setError('');
    if (fileRef.current) fileRef.current.value = '';
    if (fid) {
      loadVersions(fid);
      loadProducts(fid);
    } else {
      setVersions([]);
      setProducts([]);
    }
  }

  function handleFileChange(e) {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    if (f && !label) setLabel(f.name.replace(/\.[^.]+$/, ''));
    setUploadResult(null);
    setCurrentVersionId(null);
    setError('');
  }

  async function handleUpload() {
    if (!factoryId || !file) return;
    setUploading(true);
    setError('');
    try {
      const result = await api.priceImport.uploadAndCommit(
        Number(factoryId), file, label || undefined
      );
      setUploadResult(result);
      setCurrentVersionId(result.versionId);
      setFile(null);
      setLabel('');
      if (fileRef.current) fileRef.current.value = '';
      await loadVersions(factoryId);
      await loadProducts(factoryId);
      showToast?.('success', `Commit สำเร็จ ${result.committedRows} รายการ`);
    } catch (err) {
      setError(err.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploading(false);
    }
  }

  function handleFactoryCreated(factory) {
    setFactories((prev) =>
      [...prev, factory].sort((a, b) => a.name.localeCompare(b.name, 'th'))
    );
    setShowFactoryModal(false);
    setFactoryId(String(factory.factoryId));
    setVersions([]);
    setProducts([]);
  }

  async function handleDeleteProduct(product) {
    const displayName =
      product.collection || product.productName || product.productCode || 'รายการนี้';
    if (!window.confirm(`ยืนยันลบ "${displayName}"?`)) return;
    try {
      await api.catalog.deleteProduct(product.priceId);
      showToast?.('success', 'ลบสินค้าแล้ว');
      loadProducts(factoryId);
    } catch (err) {
      showToast?.('error', err.message || 'ลบไม่สำเร็จ');
    }
  }

  async function handleProductSaved() {
    setEditingProduct(null);
    showToast?.('success', 'บันทึกสินค้าแล้ว');
    await loadProducts(factoryId);
  }

  return (
    <PageStack>
      <PageHeader
        title="จัดการราคาสินค้า"
        subtitle="อัปโหลด price list หรือแก้ไขรายสินค้าด้วยตนเอง"
      />

      {/* Factory selection */}
      <Panel title="เลือกโรงงาน">
        <div className="flex gap-3 items-end flex-wrap">
          <div className="flex-1 min-w-[200px]">
            <label htmlFor="factory-select" className="block text-sm font-medium mb-1">
              โรงงาน
            </label>
            <select
              id="factory-select"
              className="input w-full"
              value={factoryId}
              onChange={handleFactoryChange}
            >
              <option value="">— เลือกโรงงาน —</option>
              {factories.map((f) => (
                <option key={f.factoryId} value={f.factoryId}>{f.name}</option>
              ))}
            </select>
          </div>
          <Button variant="secondary" onClick={() => setShowFactoryModal(true)}>
            <Icon name="plus" />
            เพิ่มโรงงาน
          </Button>
        </div>
      </Panel>

      {/* Upload panel */}
      {factoryId && (
        <Panel title="อัปโหลด Price List">
          <div className="mb-3">
            <label htmlFor="version-label" className="block text-sm font-medium mb-1">
              Label (ชื่อ version)
            </label>
            <input
              id="version-label"
              type="text"
              className="input w-full max-w-md"
              placeholder="เช่น Price List 2026 Q3"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </div>
          <div className="flex items-center gap-3 flex-wrap">
            <input
              ref={fileRef}
              type="file"
              accept=".xlsx,.xls"
              className="text-sm"
              onChange={handleFileChange}
            />
            <Button variant="primary" onClick={handleUpload} disabled={!file || uploading}>
              <Icon name="upload" />
              {uploading ? 'กำลังอัปโหลด…' : 'อัปโหลดและ Commit'}
            </Button>
          </div>
        </Panel>
      )}

      {error && (
        <div className="bg-red-50 border border-red-300 text-red-700 px-4 py-3 rounded-md text-sm">
          {error}
        </div>
      )}

      {uploadResult && <UploadResultCard result={uploadResult} />}

      {/* Products table */}
      {factoryId && (
        <Panel title="รายการสินค้า (ACTIVE)">
          <div className="flex justify-between items-center mb-3">
            <p className="text-sm text-muted">{products.length} รายการ</p>
            <Button variant="primary" size="sm" onClick={() => setEditingProduct({})}>
              <Icon name="plus" />
              เพิ่มสินค้า
            </Button>
          </div>

          {loadingProducts ? (
            <p className="text-sm text-muted py-6 text-center">กำลังโหลด…</p>
          ) : products.length === 0 ? (
            <p className="text-sm text-muted py-6 text-center">
              ยังไม่มีสินค้าสำหรับโรงงานนี้ — อัปโหลดไฟล์หรือเพิ่มสินค้าด้วยตนเอง
            </p>
          ) : isMobile ? (
            <div className="flex flex-col">
              {products.map((p) => (
                <ProductCard
                  key={p.priceId}
                  product={p}
                  onEdit={setEditingProduct}
                  onDelete={handleDeleteProduct}
                />
              ))}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm border-collapse">
                <thead className="border-b-2 border-border">
                  <tr>
                    <th className="text-left px-2 py-2 text-muted font-medium">Collection</th>
                    <th className="text-left px-2 py-2 text-muted font-medium">ชื่อ</th>
                    <th className="text-left px-2 py-2 text-muted font-medium">รหัส</th>
                    <th className="text-left px-2 py-2 text-muted font-medium">สี</th>
                    <th className="text-left px-2 py-2 text-muted font-medium">ผิว</th>
                    <th className="text-left px-2 py-2 text-muted font-medium">ขนาด</th>
                    <th className="text-right px-2 py-2 text-muted font-medium">ราคา</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {products.map((p) => (
                    <tr
                      key={p.priceId}
                      className="border-b border-border hover:bg-surface-alt transition-colors"
                    >
                      <td className="px-2 py-2">{p.collection || '—'}</td>
                      <td className="px-2 py-2">{p.productName || '—'}</td>
                      <td className="px-2 py-2 font-mono text-xs">{p.productCode || '—'}</td>
                      <td className="px-2 py-2">{p.color || '—'}</td>
                      <td className="px-2 py-2">{p.surface || '—'}</td>
                      <td className="px-2 py-2">{p.sizeRaw || '—'}</td>
                      <td className="px-2 py-2 text-right whitespace-nowrap font-medium">
                        {priceDisplay(p.price, p.currency)}
                        <span className="text-xs text-muted ml-1">/ {unitLabel(p.priceUnit)}</span>
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex gap-1 justify-end">
                          <Button size="sm" variant="secondary" onClick={() => setEditingProduct(p)}>
                            แก้ไข
                          </Button>
                          <Button size="sm" variant="danger" onClick={() => handleDeleteProduct(p)}>
                            ลบ
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      )}

      <VersionsPanel versions={versions} currentVersionId={currentVersionId} />

      {/* Modals */}
      {showFactoryModal && (
        <AddFactoryModal
          onClose={() => setShowFactoryModal(false)}
          onCreated={handleFactoryCreated}
        />
      )}

      {editingProduct !== null && (
        <ProductFormModal
          product={editingProduct?.priceId ? editingProduct : null}
          factoryId={Number(factoryId)}
          onClose={() => setEditingProduct(null)}
          onSaved={handleProductSaved}
        />
      )}
    </PageStack>
  );
}
