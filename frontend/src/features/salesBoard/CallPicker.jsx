import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { dealName, dealPickGroups } from './dealPickGroups.js';

/**
 * โทรแล้ว needs a deal: ควรตามต่อ first (the reason to call), then the rest of the rep's open deals. Rendered by MyWork
 * with its other dialogs.
 */
export function CallPicker({ followUp, board, onPick, onClose }) {
  const groups = dealPickGroups(followUp, board);
  return (
    <Modal
      title="โทรหางานไหน"
      subtitle="เลือกงาน แล้วบันทึกผลการโทร"
      placement="sheet-on-phone"
      onClose={onClose}
      footer={<Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>}
    >
      <div className="grid gap-4">
        {groups.map((group) => (
          <section key={group.label} className="grid gap-1.5">
            <h3 className="m-0 text-sm font-bold text-text-muted">{group.label}</h3>
            <ul className="m-0 grid list-none gap-1.5 p-0">
              {group.deals.map((deal) => (
                <li key={deal.ticketId}>
                  <button
                    type="button"
                    onClick={() => onPick(deal)}
                    className="flex min-h-11 w-full items-center justify-between gap-3 rounded-md border border-border-input bg-surface px-3 py-2 text-left text-text hover:bg-surface-hover"
                  >
                    <span className="min-w-0 break-words text-sm font-bold">{dealName(deal)}</span>
                    {deal.quiet ? (
                      <span className="shrink-0 rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                        {`เงียบ ${deal.daysQuiet} วัน`}
                      </span>
                    ) : null}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </Modal>
  );
}
