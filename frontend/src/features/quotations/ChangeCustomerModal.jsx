import { useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { DealCustomerCard } from './DealCustomerCard.jsx';

/**
 * Re-points a deal at another customer + project (owner, 2026-10-12: "ลูกค้า must also be editable
 * when still not sent for approval, in case of a typo"). Not the same as renaming the customer
 * record, which ชื่อลูกค้า on the summary already does. The picked customer comes back through
 * `onChanged` so the editor can show its details at once; the document itself only picks the new
 * customer up on its next draft save.
 */
export function ChangeCustomerModal({ ticketId, onClose, onChanged, showToast }) {
  const [form, setForm] = useState({ customer: null, project: null });
  const [saving, setSaving] = useState(false);

  async function confirm() {
    setSaving(true);
    try {
      await api.tickets.changeCustomer(ticketId, {
        customerId: form.customer.id, projectId: form.project.id, contactId: null,
      });
      await onChanged(form.customer, form.project);
      onClose();
    } catch (error) {
      showToast('error', error.message || 'เปลี่ยนลูกค้าไม่สำเร็จ');
      setSaving(false);
    }
  }

  return (
    <Modal
      title="เปลี่ยนลูกค้า"
      subtitle="เปลี่ยนได้จนกว่าจะส่งขออนุมัติ ข้อมูลในใบเสนอราคาจะเปลี่ยนเมื่อกดบันทึกร่าง"
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            variant="primary"
            loading={saving}
            disabled={!form.customer || !form.project || saving}
            onClick={confirm}
          >
            ยืนยันเปลี่ยนลูกค้า
          </Button>
        </>
      )}
    >
      <DealCustomerCard
        value={form}
        onChange={(patch) => setForm((prev) => ({ ...prev, ...patch }))}
        showToast={showToast}
        pickOnly
      />
    </Modal>
  );
}
