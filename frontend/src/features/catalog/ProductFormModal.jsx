import { useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';

export function ProductFormModal({ product, factoryId, onClose, onSaved }) {
  const isEdit = !!product?.priceId;
  const [form, setForm] = useState({
    productCode: product?.productCode ?? '',
    grade:       product?.grade       ?? '',
    collection:  product?.collection  ?? '',
    productName: product?.productName ?? '',
    color:       product?.color       ?? '',
    surface:     product?.surface     ?? '',
    sizeRaw:     product?.sizeRaw     ?? '',
    price:       product?.price != null ? String(product.price) : '',
    currency:    product?.currency    ?? 'EUR',
    priceUnit:   product?.priceUnit   ?? 'per_sqm',
  });
  const [saving, setSaving] = useState(false);
  const [error, setError]   = useState('');

  const set = (field) => (e) => setForm((prev) => ({ ...prev, [field]: e.target.value }));

  async function handleSubmit(e) {
    e.preventDefault();
    if (!form.price || Number(form.price) <= 0) { setError('กรุณาใส่ราคา'); return; }
    setSaving(true);
    setError('');
    try {
      const payload = {
        productCode: form.productCode || null,
        grade:       form.grade       || null,
        collection:  form.collection  || null,
        productName: form.productName || null,
        color:       form.color       || null,
        surface:     form.surface     || null,
        sizeRaw:     form.sizeRaw     || null,
        price:       Number(form.price),
        currency:    form.currency,
        priceUnit:   form.priceUnit,
      };
      if (isEdit) {
        await api.catalog.updateProduct(product.priceId, payload);
      } else {
        await api.catalog.addProduct({ ...payload, factoryId });
      }
      onSaved();
    } catch (err) {
      setError(err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขสินค้า' : 'เพิ่มสินค้า'}
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="product-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      }
    >
      <form id="product-form" onSubmit={handleSubmit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="pf-code" className="block text-sm font-medium mb-1">รหัสสินค้า</label>
            <input id="pf-code" type="text" className="input w-full" value={form.productCode} onChange={set('productCode')} />
          </div>
          <div>
            <label htmlFor="pf-grade" className="block text-sm font-medium mb-1">Grade</label>
            <input id="pf-grade" type="text" className="input w-full" value={form.grade} onChange={set('grade')} />
          </div>
        </div>
        <div>
          <label htmlFor="pf-collection" className="block text-sm font-medium mb-1">Collection</label>
          <input id="pf-collection" type="text" className="input w-full" value={form.collection} onChange={set('collection')} />
        </div>
        <div>
          <label htmlFor="pf-name" className="block text-sm font-medium mb-1">ชื่อสินค้า</label>
          <input id="pf-name" type="text" className="input w-full" value={form.productName} onChange={set('productName')} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="pf-color" className="block text-sm font-medium mb-1">สี</label>
            <input id="pf-color" type="text" className="input w-full" value={form.color} onChange={set('color')} />
          </div>
          <div>
            <label htmlFor="pf-surface" className="block text-sm font-medium mb-1">ผิว</label>
            <input id="pf-surface" type="text" className="input w-full" value={form.surface} onChange={set('surface')} />
          </div>
        </div>
        <div>
          <label htmlFor="pf-size" className="block text-sm font-medium mb-1">ขนาด</label>
          <input
            id="pf-size"
            type="text"
            className="input w-full"
            value={form.sizeRaw}
            onChange={set('sizeRaw')}
            placeholder="เช่น 60x60, 30x90"
          />
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label htmlFor="pf-price" className="block text-sm font-medium mb-1">ราคา *</label>
            <input
              id="pf-price"
              type="number"
              step="0.01"
              min="0"
              className="input w-full"
              value={form.price}
              onChange={set('price')}
              required
            />
          </div>
          <div>
            <label htmlFor="pf-currency" className="block text-sm font-medium mb-1">สกุลเงิน</label>
            <select id="pf-currency" className="input w-full" value={form.currency} onChange={set('currency')}>
              <option>EUR</option>
              <option>USD</option>
              <option>THB</option>
              <option>GBP</option>
            </select>
          </div>
          <div>
            <label htmlFor="pf-unit" className="block text-sm font-medium mb-1">หน่วย</label>
            <select id="pf-unit" className="input w-full" value={form.priceUnit} onChange={set('priceUnit')}>
              <option value="per_sqm">ม²</option>
              <option value="per_piece">แผ่น</option>
              <option value="per_box">กล่อง</option>
              <option value="per_linear_m">ม.</option>
            </select>
          </div>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
      </form>
    </Modal>
  );
}
