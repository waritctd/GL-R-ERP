import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { ProductFormModal } from './ProductFormModal.jsx';

// Default quoting unit for a factory's RFQ (price_catalog.factories.unit, V163). The column is a
// free VARCHAR with no server-side CHECK constraint, but its own migration comment names exactly
// these three values as the intended vocabulary — a constrained select is a better fit for that
// than a free-text field pretending the domain is open-ended.
const FACTORY_UNIT_OPTIONS = [
  { value: 'piece', label: 'ชิ้น' },
  { value: 'sqm', label: 'ตร.ม.' },
  { value: 'box', label: 'กล่อง' },
];
const FACTORY_CURRENCY_OPTIONS = ['EUR', 'USD', 'THB', 'GBP'];

// ── StepLabel ─────────────────────────────────────────────────────────────────
// This upload flow is a genuine ordered sequence (select factory → upload →
// commit — there is no separate preview/validate step, see handleUpload),
// which is exactly the case where a step number carries real information
// rather than acting as decoration. Kept to plain bold/muted text, no
// circles or color blocks, per DESIGN.md's overline treatment.
function StepLabel({ n, children }) {
  return (
    <p className="mb-1.5 text-xs font-bold text-text-muted">
      ขั้นตอนที่ {n} · {children}
    </p>
  );
}

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

function statusLabel(status) {
  if (status === 'ACTIVE')   return 'ใช้งานอยู่';
  if (status === 'DRAFT')    return 'ร่าง';
  if (status === 'ARCHIVED') return 'เก็บเป็นประวัติ';
  return status || 'ไม่ทราบสถานะ';
}

// factory.unit ('piece'/'sqm'/'box', V163) is a DIFFERENT vocabulary from product.priceUnit
// ('per_sqm'/'per_piece'/...) above — do not reuse unitLabel() for it.
function factoryUnitLabel(unit) {
  return FACTORY_UNIT_OPTIONS.find((opt) => opt.value === unit)?.label ?? (unit || '—');
}

// `factory.country` is a price_catalog.country CODE (e.g. "IT") since V163's factory editor
// replaced the old free-text input — resolves it to a Thai display name via the same roster the
// picker itself offers, so the list reads "อิตาลี (IT)" instead of a bare code.
function countryLabel(countries, code) {
  const match = countries.find((c) => c.countryCode === code);
  return match ? `${match.nameTh} (${match.countryCode})` : (code || '—');
}

// ── FactoryFormModal ─────────────────────────────────────────────────────────
// Add AND edit share this one modal rather than a second, drifting copy of the same five fields —
// `factory` is null for "เพิ่มโรงงานใหม่", or the row being edited.
//
// Country used to be a free-text 2-letter input — the direct cause of "cannot add a factory": a
// typo or an unseeded code reached price_catalog.factories' NOT NULL + FK column and 500'd
// (PriceImportService.createFactory's own javadoc). It is now a required <select> sourced from
// GET /api/price-import/countries, so an invalid value cannot be typed in the first place.
//
// email/unit are the two RFQ fields V163 folded onto price_catalog.factories from the dropped
// sales.factory_config — email is this change's whole point (see PriceImportPage's own comment
// on the factory list below), so it is deliberately NOT required: a factory may sit here with no
// contact email until จัดซื้อ has one to enter, same as every real factory does today.
function FactoryFormModal({ factory, countries, onClose, onSaved }) {
  const isEdit = Boolean(factory);
  const [name, setName]         = useState(factory?.name ?? '');
  const [country, setCountry]   = useState(factory?.country ?? '');
  const [currency, setCurrency] = useState(factory?.defaultCurrency ?? 'EUR');
  const [email, setEmail]       = useState(factory?.email ?? '');
  const [unit, setUnit]         = useState(factory?.unit ?? 'piece');
  const [saving, setSaving]     = useState(false);
  const [error, setError]       = useState('');

  async function handleSubmit(e) {
    e.preventDefault();
    if (!name.trim()) { setError('กรุณาใส่ชื่อโรงงาน'); return; }
    if (!country) { setError('กรุณาเลือกประเทศ'); return; }
    setSaving(true);
    setError('');
    try {
      const saved = isEdit
        ? await api.priceImport.updateFactory(factory.factoryId, name.trim(), country, currency, email.trim(), unit)
        : await api.priceImport.createFactory(name.trim(), country, currency, email.trim(), unit);
      onSaved(saved);
    } catch (err) {
      // Surfaces the backend's own Thai 400/409 message (bad/blank country, duplicate name)
      // rather than a generic failure — see PriceImportService.createFactory/updateFactory.
      setError(err.message || (isEdit ? 'บันทึกโรงงานไม่สำเร็จ' : 'เพิ่มโรงงานไม่สำเร็จ'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขข้อมูลโรงงาน' : 'เพิ่มโรงงานใหม่'}
      subtitle={isEdit ? factory.name : undefined}
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="factory-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      }
    >
      <SafeForm id="factory-form" onSubmit={handleSubmit} className="grid gap-4">
        <FormField label="ชื่อโรงงาน" htmlFor="factory-name" required>
          <input
            id="factory-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="เช่น Rex Ceramics"
          />
        </FormField>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="ประเทศ" htmlFor="factory-country" required>
            <select
              id="factory-country"
              value={country}
              onChange={(e) => setCountry(e.target.value)}
            >
              <option value="">— เลือกประเทศ —</option>
              {countries.map((c) => (
                <option key={c.countryCode} value={c.countryCode}>{c.nameTh} ({c.countryCode})</option>
              ))}
            </select>
          </FormField>
          <FormField label="สกุลเงินหลัก" htmlFor="factory-currency">
            <select
              id="factory-currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
            >
              {FACTORY_CURRENCY_OPTIONS.map((code) => (
                <option key={code} value={code}>{code}</option>
              ))}
            </select>
          </FormField>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="อีเมลขอราคา (ถ้ามี)" htmlFor="factory-email" hint="ที่อยู่อีเมลติดต่อโรงงานนี้เวลาขอราคา — ไม่บังคับ">
            <input
              id="factory-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="buyer@factory.example"
            />
          </FormField>
          <FormField label="หน่วยขอราคา" htmlFor="factory-unit">
            <select
              id="factory-unit"
              value={unit}
              onChange={(e) => setUnit(e.target.value)}
            >
              {FACTORY_UNIT_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </FormField>
        </div>
        {error && <p className="m-0 text-sm font-bold text-danger">{error}</p>}
      </SafeForm>
    </Modal>
  );
}

// ── UploadResultCard ──────────────────────────────────────────────────────────

function UploadResultCard({ result }) {
  return (
    <Panel title="อัปโหลดและบันทึกราคาใช้งานสำเร็จ">
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
            <Icon name="pencil" size={14} />
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

// ── FactoryCard (mobile) ─────────────────────────────────────────────────────
// Same five columns as the desktop factory table below, reflowed by hand — same pattern as
// ProductCard above for the products table.
function FactoryCard({ factory, countries, onEdit }) {
  return (
    <div className="mt-2.5 flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 first:mt-0">
      <div className="flex min-w-0 items-start justify-between gap-3">
        <strong className="min-w-0 truncate text-md font-extrabold text-text">{factory.name}</strong>
        <Button size="sm" variant="secondary" onClick={() => onEdit(factory)}>
          <Icon name="pencil" size={14} />
          แก้ไข
        </Button>
      </div>
      <span className="text-xs text-text-muted">
        {countryLabel(countries, factory.country)} · {factory.defaultCurrency} · {factoryUnitLabel(factory.unit)}
      </span>
      {factory.email ? (
        <span className="min-w-0 truncate text-xs text-text-muted">{factory.email}</span>
      ) : (
        <span className="text-xs font-bold text-warning-dark">ยังไม่ระบุอีเมลขอราคา</span>
      )}
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
              <p className="text-sm font-medium truncate">{v.label || `เวอร์ชัน ${v.versionId}`}</p>
              <p className="text-xs text-muted">
                {v.uploadedAt ? new Date(v.uploadedAt).toLocaleString('th-TH') : ''}
              </p>
            </div>
            <StatusBadge tone={statusTone(v.status)}>{statusLabel(v.status)}</StatusBadge>
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
  const [countries, setCountries]             = useState([]);
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
  // The factory master-data row จัดซื้อ is currently editing — null closes FactoryFormModal in
  // edit mode; `showFactoryModal` above is the separate "add" trigger. Both render the same modal
  // (see FactoryFormModal), just with `factory` set or not.
  const [editingFactory, setEditingFactory]     = useState(null);
  const [editingProduct, setEditingProduct]     = useState(null);
  const [confirmUpload, setConfirmUpload]       = useState(false);
  const [deleteTarget, setDeleteTarget]         = useState(null);
  const [deletingProduct, setDeletingProduct]   = useState(false);

  const fileRef = useRef(null);

  useEffect(() => {
    api.priceImport.factories().then(setFactories).catch(() => {});
    api.priceImport.countries().then(setCountries).catch(() => {});
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

  // uploadAndCommit (src/api/hrApi.js) hits POST /api/price-import/upload-commit,
  // which parses the file then calls PriceImportService.uploadAndCommit —
  // parse → stage → validate → commit, in one transaction, with no separate
  // preview step. commit() sets this version ACTIVE, archives the factory's
  // previous ACTIVE version (kept for history, not deleted), and carries
  // forward any existing product not matched by a row in the new file. This
  // same call/args are unchanged; the confirm step below only gates *when*
  // it fires, not what it does.
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
      showToast?.('success', `บันทึกราคาใช้งานสำเร็จ ${result.committedRows} รายการ`);
    } catch (err) {
      setError(err.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploading(false);
      setConfirmUpload(false);
    }
  }

  // Shared save handler for FactoryFormModal in BOTH its modes. An edit replaces the row in
  // place and leaves the current price-list selection alone (its versions/products are
  // untouched — only master data changed); an add appends the new row and switches Step 2's
  // selection onto it, same as this page has always done right after creating a factory.
  function handleFactorySaved(factory) {
    setFactories((prev) => {
      const isEdit = prev.some((f) => f.factoryId === factory.factoryId);
      const next = isEdit
        ? prev.map((f) => (f.factoryId === factory.factoryId ? factory : f))
        : [...prev, factory];
      return next.sort((a, b) => a.name.localeCompare(b.name, 'th'));
    });
    if (editingFactory) {
      setEditingFactory(null);
      showToast?.('success', 'บันทึกข้อมูลโรงงานแล้ว');
      return;
    }
    setShowFactoryModal(false);
    setFactoryId(String(factory.factoryId));
    setVersions([]);
    setProducts([]);
    showToast?.('success', 'เพิ่มโรงงานแล้ว');
  }

  function handleDeleteProduct(product) {
    setDeleteTarget(product);
  }

  async function confirmDeleteProduct() {
    if (!deleteTarget) return;
    setDeletingProduct(true);
    try {
      await api.catalog.deleteProduct(deleteTarget.priceId);
      showToast?.('success', 'ลบสินค้าแล้ว');
      await loadProducts(factoryId);
    } catch (err) {
      showToast?.('error', err.message || 'ลบไม่สำเร็จ');
    } finally {
      setDeletingProduct(false);
      setDeleteTarget(null);
    }
  }

  async function handleProductSaved() {
    setEditingProduct(null);
    showToast?.('success', 'บันทึกสินค้าแล้ว');
    await loadProducts(factoryId);
  }

  // The RFQ-email gap this whole change exists to close (see V163's migration header: the old
  // email directory matched 0% of the real factories) — surfaced as a count here rather than only
  // discoverable one row at a time.
  const factoriesMissingEmail = factories.filter((f) => !f.email).length;

  return (
    <PageStack>
      <PageHeader
        title="จัดการราคาสินค้า"
        subtitle="อัปโหลดรายการราคาหรือแก้ไขรายสินค้าด้วยตนเอง"
      />

      {/* Factory selection */}
      <Panel>
        <StepLabel n={1}>เลือกโรงงาน</StepLabel>
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

      {/* Factory master data — name/country/currency/RFQ email/quoting unit for every factory,
          each with its own แก้ไข action (PUT /api/price-import/factories/{factoryId}). Deliberately
          NOT folded into the เลือกโรงงาน select above or gated behind a disclosure: setting a real
          RFQ email here is this change's whole point (see V163's migration header — the old email
          directory matched 0% of the real factories, so no factory has ever had a usable one), and
          that only gets fixed if the gap is visible, not tucked away. Always rendered once
          factories have loaded, independent of Step 1's price-list selection below. */}
      <Panel flush title="ข้อมูลโรงงาน">
        {factoriesMissingEmail > 0 ? (
          <p className="m-0 border-b border-warning-border bg-warning-bg-soft px-5 py-2.5 text-xs text-warning-dark">
            {`${factoriesMissingEmail} จาก ${factories.length} โรงงานยังไม่มีอีเมลขอราคา — กด "แก้ไข" เพื่อเพิ่ม`}
          </p>
        ) : null}
        {factories.length === 0 ? (
          <p className="p-4 text-sm text-muted">ยังไม่มีโรงงาน</p>
        ) : isMobile ? (
          <div className="flex flex-col px-4 pb-4">
            {factories.map((f) => (
              <FactoryCard key={f.factoryId} factory={f} countries={countries} onEdit={setEditingFactory} />
            ))}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm border-collapse">
              <thead className="border-b-2 border-border">
                <tr>
                  <th className="text-left px-5 py-2 text-muted font-medium">ชื่อโรงงาน</th>
                  <th className="text-left px-2 py-2 text-muted font-medium">ประเทศ</th>
                  <th className="text-left px-2 py-2 text-muted font-medium">สกุลเงิน</th>
                  <th className="text-left px-2 py-2 text-muted font-medium">อีเมลขอราคา</th>
                  <th className="text-left px-2 py-2 text-muted font-medium">หน่วยขอราคา</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {factories.map((f) => (
                  <tr key={f.factoryId} className="border-b border-border hover:bg-surface-hover transition-colors">
                    <td className="px-5 py-2 font-medium text-text">{f.name}</td>
                    <td className="px-2 py-2 text-text-muted">{countryLabel(countries, f.country)}</td>
                    <td className="px-2 py-2 text-text-muted">{f.defaultCurrency}</td>
                    <td className="px-2 py-2">
                      {f.email ? (
                        <span className="text-text-muted">{f.email}</span>
                      ) : (
                        <span className="font-bold text-warning-dark">ยังไม่ระบุ</span>
                      )}
                    </td>
                    <td className="px-2 py-2 text-text-muted">{factoryUnitLabel(f.unit)}</td>
                    <td className="px-2 py-2 text-right">
                      <Button size="sm" variant="secondary" onClick={() => setEditingFactory(f)}>
                        <Icon name="pencil" size={14} />
                        แก้ไข
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {/* Upload panel */}
      {factoryId && (
        <Panel>
          <StepLabel n={2}>อัปโหลดรายการราคา</StepLabel>
          <div className="mb-3">
            <label htmlFor="version-label" className="block text-sm font-medium mb-1">
              ชื่อเวอร์ชันราคา
            </label>
            <input
              id="version-label"
              type="text"
              className="input w-full max-w-md"
              placeholder="เช่น รายการราคา Q3 2026"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </div>
          <FileUploadField
            ref={fileRef}
            accept=".xlsx,.xls"
            onChange={handleFileChange}
            helperText="Excel (.xlsx, .xls)"
            className="max-w-md mb-4"
          />

          {/* No preview/validation step exists for this upload path — it parses,
              stages, validates, and commits in one call (see handleUpload above).
              Per the plan: "If preview does not exist, add a clear warning/note
              before commit" instead of fabricating one. */}
          <div className="max-w-md rounded-md border border-border-input bg-surface-muted p-3 mb-4">
            <StepLabel n={3}>ไม่มีขั้นตอนแสดงตัวอย่างก่อนบันทึก</StepLabel>
            <p className="text-sm text-text-secondary">
              ระบบนี้จะอ่านไฟล์และตั้งเป็นราคาที่ใช้งานจริงทันทีในขั้นตอนเดียว
              โดยไม่มีหน้าตรวจสอบก่อน เวอร์ชันราคาเดิมของโรงงานนี้จะถูกเก็บเป็นประวัติ
              ไม่ถูกลบ และสินค้าที่ไม่มีในไฟล์ใหม่จะยังคงอยู่ในเวอร์ชันใหม่
            </p>
          </div>

          <StepLabel n={4}>อัปโหลดและบันทึกราคาใช้งาน</StepLabel>
          <Button
            variant="primary"
            onClick={() => setConfirmUpload(true)}
            disabled={!file || uploading}
            className="mobile:min-h-11 mobile:w-full"
          >
            <Icon name="upload" />
            {uploading ? 'กำลังอัปโหลด…' : 'อัปโหลดและบันทึกราคาใช้งาน'}
          </Button>
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
        <Panel title="รายการสินค้าที่ใช้งานอยู่">
          <div className="flex justify-between items-center mb-3">
            <p className="text-sm text-muted">{products.length} รายการ</p>
            <Button variant="primary" size="sm" onClick={() => setEditingProduct({})}>
              <Icon name="plus" />
              เพิ่มสินค้า
            </Button>
          </div>

          {loadingProducts ? (
            <p className="text-sm text-muted py-6 text-center">กำลังโหลดรายการสินค้าของโรงงาน…</p>
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
                      className="border-b border-border hover:bg-surface-hover transition-colors"
                    >
                      {/* Collection (primary) + product code (secondary, muted
                          mono) stacked — this list is already scoped to one
                          factory, so collection is the identity anchor, same
                          as the mobile ProductCard above. */}
                      <td className="px-2 py-2">
                        <span className="flex min-w-0 flex-col gap-0.5">
                          <strong className="block truncate font-medium text-text">{p.collection || p.productName || '—'}</strong>
                          <code className="block truncate text-2xs text-text-muted">{p.productCode || '—'}</code>
                        </span>
                      </td>
                      <td className="px-2 py-2">{p.productName || '—'}</td>
                      <td className="px-2 py-2">{p.color || '—'}</td>
                      <td className="px-2 py-2">{p.surface || '—'}</td>
                      <td className="px-2 py-2">{p.sizeRaw || '—'}</td>
                      <td className="px-2 py-2 text-right whitespace-nowrap font-medium">
                        <span className="font-mono">{priceDisplay(p.price, p.currency)}</span>
                        <span className="text-xs text-muted ml-1">/ {unitLabel(p.priceUnit)}</span>
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex gap-1 justify-end">
                          <Button size="sm" variant="secondary" onClick={() => setEditingProduct(p)}>
                            <Icon name="pencil" size={14} />
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
        <FactoryFormModal
          factory={null}
          countries={countries}
          onClose={() => setShowFactoryModal(false)}
          onSaved={handleFactorySaved}
        />
      )}

      {editingFactory && (
        <FactoryFormModal
          factory={editingFactory}
          countries={countries}
          onClose={() => setEditingFactory(null)}
          onSaved={handleFactorySaved}
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

      {/* tone stays default (not danger): commit archives the previous version
          and carries forward unmatched products — it does not delete data. */}
      <ConfirmDialog
        open={confirmUpload}
        title="ยืนยันอัปโหลดและบันทึกราคาใช้งาน"
        message="ราคาที่บันทึกจะมีผลใช้งานจริงทันที เวอร์ชันเดิมของโรงงานนี้จะถูกเก็บเป็นประวัติ ไม่สามารถย้อนกลับจากหน้านี้ได้"
        confirmLabel="อัปโหลดและบันทึกราคาใช้งาน"
        busy={uploading}
        onConfirm={handleUpload}
        onCancel={() => setConfirmUpload(false)}
      />

      <ConfirmDialog
        open={!!deleteTarget}
        tone="danger"
        title="ลบสินค้า"
        message={`ยืนยันลบ "${deleteTarget?.collection || deleteTarget?.productName || deleteTarget?.productCode || 'รายการนี้'}"?`}
        confirmLabel="ลบสินค้า"
        busy={deletingProduct}
        onConfirm={confirmDeleteProduct}
        onCancel={() => setDeleteTarget(null)}
      />
    </PageStack>
  );
}
