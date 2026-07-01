import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';

const emptyItem = () => ({ brand: '', model: '', color: '', texture: '', size: '', qty: 1 });

export function TicketCreateModal({ onClose, onSubmit }) {
  const [form, setForm] = useState({ customerName: '', note: '' });
  const [items, setItems] = useState([emptyItem()]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  function updateForm(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function updateItem(index, field, value) {
    setItems((current) => current.map((item, i) => (i === index ? { ...item, [field]: value } : item)));
  }

  function addItem() {
    setItems((current) => [...current, emptyItem()]);
  }

  function removeItem(index) {
    if (items.length === 1) return;
    setItems((current) => current.filter((_, i) => i !== index));
  }

  async function submit(event) {
    event.preventDefault();
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (!item.brand.trim() || !item.model.trim() || !item.color.trim() || !item.texture.trim() || !item.size.trim() || !item.qty) {
        setError(`กรุณากรอกข้อมูลสินค้าให้ครบทุกช่องในรายการที่ ${i + 1}`);
        return;
      }
    }
    setError('');
    setLoading(true);
    try {
      await onSubmit({
        title: form.customerName.trim(),
        customerName: form.customerName.trim(),
        note: form.note.trim() || null,
        items: items.map((item) => ({
          brand: item.brand.trim(),
          model: item.model.trim(),
          color: item.color.trim(),
          texture: item.texture.trim(),
          size: item.size.trim(),
          qty: Number(item.qty) || 1,
        })),
      });
    } catch (err) {
      setError(err.message || 'สร้างใบขอราคาไม่สำเร็จ');
      setLoading(false);
    }
  }

  return (
    <Modal
      title="สร้างใบขอราคาใหม่"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={loading}>ยกเลิก</button>
          <button type="submit" form="ticket-create-form" className="primary-button" disabled={loading}>
            <Icon name="fileText" />
            {loading ? 'กำลังสร้าง...' : 'สร้างใบขอราคา'}
          </button>
        </>
      )}
    >
      <form id="ticket-create-form" className="form-grid" onSubmit={submit}>
        <label className="span-2">
          บริษัท / โครงการ *
          <input
            value={form.customerName}
            onChange={(e) => updateForm('customerName', e.target.value)}
            placeholder="ชื่อลูกค้า บริษัท หรือโครงการ"
            required
          />
        </label>

        <div className="span-2">
          <p style={{ margin: '0 0 10px', fontWeight: 700, fontSize: 13 }}>รายการสินค้า *</p>
          {items.map((item, index) => (
            <div
              key={index}
              style={{
                border: '1px solid #e2e8f0',
                borderRadius: 8,
                padding: '12px 14px',
                marginBottom: 10,
                background: '#f8fafc',
                position: 'relative',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: '#64748b' }}>รายการที่ {index + 1}</span>
                {items.length > 1 && (
                  <button
                    type="button"
                    className="icon-button"
                    onClick={() => removeItem(index)}
                    title="ลบรายการ"
                    aria-label={`ลบรายการที่ ${index + 1}`}
                    style={{ color: '#ef4444' }}
                  >
                    <Icon name="close" size={14} />
                  </button>
                )}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>ชื่อยี่ห้อ *</span>
                  <input
                    value={item.brand}
                    onChange={(e) => updateItem(index, 'brand', e.target.value)}
                    placeholder="เช่น SCG, Cotto"
                    required
                  />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>ชื่อรุ่น *</span>
                  <input
                    value={item.model}
                    onChange={(e) => updateItem(index, 'model', e.target.value)}
                    placeholder="เช่น รุ่น Premium Series"
                    required
                  />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>สี *</span>
                  <input
                    value={item.color}
                    onChange={(e) => updateItem(index, 'color', e.target.value)}
                    placeholder="เช่น ขาว, เทา, ครีม"
                    required
                  />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>เนื้อผิว *</span>
                  <input
                    value={item.texture}
                    onChange={(e) => updateItem(index, 'texture', e.target.value)}
                    placeholder="เช่น ด้าน, มัน, หยาบ"
                    required
                  />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>ขนาด *</span>
                  <input
                    value={item.size}
                    onChange={(e) => updateItem(index, 'size', e.target.value)}
                    placeholder="เช่น 60x60, 30x60 ซม."
                    required
                  />
                </label>
                <label style={{ margin: 0 }}>
                  <span style={{ fontSize: 12 }}>จำนวน *</span>
                  <input
                    type="number"
                    value={item.qty}
                    min="1"
                    step="1"
                    onChange={(e) => updateItem(index, 'qty', e.target.value)}
                    placeholder="ระบุจำนวน"
                    required
                  />
                </label>
              </div>
            </div>
          ))}
          <button type="button" className="secondary-button" onClick={addItem} style={{ marginTop: 2 }}>
            <Icon name="plus" size={14} />
            เพิ่มรายการสินค้า
          </button>
        </div>

        <label className="span-2">
          หมายเหตุ
          <textarea
            value={form.note}
            onChange={(e) => updateForm('note', e.target.value)}
            rows={2}
            placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)"
          />
        </label>

        {error ? <div className="form-error span-2">{error}</div> : null}
      </form>
    </Modal>
  );
}
