/* Tokens only. Forwarding cannot be taken back, so the warehouse confirms it first: a short bottom sheet (phone design
 * M15 pattern) with one primary. */
import { Button } from '../../../components/common/Button.jsx';
import { Modal } from '../../../components/common/Modal.jsx';

export function ForwardSheet({ round, pending, onConfirm, onClose }) {
  return (
    <Modal
      title="ส่งต่อให้ออกใบกำกับ"
      onClose={pending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="forward-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          <Button type="button" variant="primary" className="w-full" disabled={pending} data-testid="forward-confirm" onClick={onConfirm}>
            {pending ? 'กำลังส่งต่อ…' : 'ส่งต่อ'}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={pending} onClick={onClose}>ยกเลิก</Button>
        </div>
      )}
    >
      <p className="m-0 text-base">รอบส่งที่ {round.roundNo} จะไปที่ผู้ออกใบกำกับภาษี และย้อนกลับไม่ได้</p>
    </Modal>
  );
}
