import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';

const emptyItem = () => ({ productName: '', qty: 1, unitPrice: '' });

export function TicketCreateModal({ onClose, onSubmit }) {
  const [form, setForm] = useState({ title: '', customerName: '', priority: 'medium', notes: '' });
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
    const validItems = items.filter((item) => item.productName.trim());
    if (validItems.length === 0) {
      setError('กรุณาระบุสินค้าอย่างน้อย 1 รายการ');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await onSubmit({
        title: form.title.trim(),
        customerName: form.customerName.trim() || null,
        priority: form.priority,
        notes: form.notes.trim() || null,
        items: validItems.map((item) => ({
          productName: item.productName.trim(),
          qty: Number(item.qty) || 1,
          unitPrice: item.unitPrice !== '' ? Number(item.unitPrice) : null,
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
          หัวข้อ *
          <input
            value={form.title}
            onChange={(e) => updateForm('title', e.target.value)}
            placeholder="ระบุหัวข้อใบขอราคา"
            required
          />
        </label>

        <label>
          ลูกค้า
          <input
            value={form.customerName}
            onChange={(e) => updateForm('customerName', e.target.value)}
            placeholder="ชื่อลูกค้า"
          />
        </label>

        <label>
          ความสำคัญ
          <select value={form.priority} onChange={(e) => updateForm('priority', e.target.value)}>
            <option value="low">ต่ำ</option>
            <option value="medium">กลาง</option>
            <option value="high">สูง</option>
            <option value="urgent">เร่งด่วน</option>
          </select>
        </label>

        <div className="span-2">
          <p style={{ margin: '0 0 8px', fontWeight: 700, fontSize: 13 }}>รายการสินค้า *</p>
          {items.map((item, index) => (
            <div key={index} style={{ display: 'grid', gridTemplateColumns: '2fr 80px 120px 36px', gap: 8, marginBottom: 6, alignItems: 'center' }}>
              <input
                value={item.productName}
                onChange={(e) => updateItem(index, 'productName', e.target.value)}
                placeholder="ชื่อสินค้า / รายการ"
              />
              <input
                type="number"
                value={item.qty}
                min="1"
                onChange={(e) => updateItem(index, 'qty', e.target.value)}
                placeholder="จำนวน"
              />
              <input
                type="number"
                value={item.unitPrice}
                min="0"
                step="0.01"
                onChange={(e) => updateItem(index, 'unitPrice', e.target.value)}
                placeholder="ราคา/หน่วย"
              />
              <button
                type="button"
                className="icon-button"
                onClick={() => removeItem(index)}
                disabled={items.length === 1}
                title="ลบรายการ"
              >
                <Icon name="close" size={14} />
              </button>
            </div>
          ))}
          <button type="button" className="secondary-button" onClick={addItem} style={{ marginTop: 4 }}>
            <Icon name="plus" size={14} />
            เพิ่มรายการ
          </button>
        </div>

        <label className="span-2">
          หมายเหตุ
          <textarea
            value={form.notes}
            onChange={(e) => updateForm('notes', e.target.value)}
            rows={2}
            placeholder="ข้อมูลเพิ่มเติม (ถ้ามี)"
          />
        </label>

        {error ? <div className="form-error span-2">{error}</div> : null}
      </form>
    </Modal>
  );
}
