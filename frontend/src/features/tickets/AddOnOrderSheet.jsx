/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app
 * Tokens only. "เปิดออเดอร์เพิ่ม" (phone design M07): the confirm sheet before สั่งเพิ่ม opens order 2, 3, … under the
 * same project. One primary button; the server's own refusal text shows inline.
 */
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { nextAddOnCode, rootCode } from './orderChain.js';

function Row({ label, value, code = false }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1.5">
      <span className="text-sm text-text-muted">{label}</span>
      <b className={code ? 'text-sm tabular-nums text-text' : 'text-sm text-text'}>{value}</b>
    </div>
  );
}

/** `summary`: the deal the rep is on (any order of the chain). On success it opens the new order's quotation editor. */
export function AddOnOrderSheet({ summary, onClose }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState(null);
  const root = rootCode(summary);
  // The root is order 1 even when the rep is standing on an add-on of it.
  const firstOrder = `${root}-1`;
  const project = summary.projectName || summary.title || summary.customerName;

  const mutation = useMutation({
    mutationFn: () => api.tickets.createAddOnOrder(summary.id),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      navigate(`/quotations/new?ticket=${res.addOnOrder.ticketId}`);
    },
    onError: (e) => setError(e?.message || 'เปิดออเดอร์เพิ่มไม่สำเร็จ'),
  });

  return (
    <Modal
      title="เปิดออเดอร์เพิ่ม"
      onClose={mutation.isPending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="add-on-order-sheet"
      footer={(
        <Button
          variant="primary"
          className="w-full sm:w-auto"
          disabled={mutation.isPending}
          onClick={() => { setError(null); mutation.mutate(); }}
          data-testid="add-on-order-confirm"
        >
          {mutation.isPending ? 'กำลังเปิดออเดอร์…' : 'เปิดออเดอร์เพิ่ม'}
        </Button>
      )}
    >
      <div className="grid gap-4">
        <div>
          <p className="m-0 text-xl font-bold leading-snug text-text">เปิดออเดอร์เพิ่มในงานนี้?</p>
          <p className="m-0 mt-1 text-sm text-text-muted">
            {project ? `${project} · ` : ''}<code className="tabular-nums">{root}</code>
          </p>
        </div>
        <div className="divide-y divide-border rounded-md border border-border px-3.5 py-1">
          <Row label="เลขที่ออเดอร์ใหม่" value={nextAddOnCode(summary)} code />
          <Row label="ลูกค้า / ผู้รับเหมา" value="เดิม" />
          <Row label="ใบเสนอราคา เงิน นำเข้า" value="แยกของตัวเอง" />
        </div>
        <div className="flex items-start gap-2 rounded-md bg-surface-subtle px-3.5 py-3 text-sm text-text-secondary">
          <Icon name="lock" size={16} className="mt-0.5 shrink-0" />
          <span>ออเดอร์เดิม (<code className="tabular-nums">{firstOrder}</code>) ไม่ถูกแก้ไข</span>
        </div>
        {error ? <p role="alert" className="m-0 text-sm font-bold text-danger">{error}</p> : null}
      </div>
    </Modal>
  );
}
