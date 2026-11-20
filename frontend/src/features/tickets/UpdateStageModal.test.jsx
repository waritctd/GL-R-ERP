import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UpdateStageModal } from './UpdateStageModal.jsx';

globalThis.React = React;

const deal = { salesStage: 'NEGOTIATION' };

/** Decisions as the backend ships them (TicketService.stageDecisions). Hand-built on purpose: this component's
 * whole contract is now "render what the server said", so the test must be able to say arbitrary things and
 * watch them appear. */
function decision(stage, no, overrides = {}) {
  return { stage, no, allowed: true, requiresReason: false, blockedReason: null, ...overrides };
}

const decisions = [
  decision('SPEC_APPROVED', 3),
  decision('QUOTE_OWNER', 5),
  decision('NEGOTIATION', 9, { allowed: false, blockedReason: 'งานนี้อยู่ในขั้นตอน NEGOTIATION อยู่แล้ว' }),
  decision('ORDER_RECEIVED', 10, {
    allowed: false,
    blockedReason: 'เลื่อนไปขั้นตอน ORDER_RECEIVED ไม่ได้: ยังไม่ได้ยืนยันคำสั่งซื้อของลูกค้า',
  }),
  decision('DEPOSIT_RECEIVED', 11, { allowed: false, blockedReason: 'ไม่มีสิทธิ์เข้าถึงรายการนี้' }),
  decision('DELIVERY_SCHEDULING', 13, { requiresReason: true }),
];

function renderModal(props = {}) {
  return render(
    <UpdateStageModal
      deal={deal}
      stageDecisions={decisions}
      onClose={() => {}}
      onSubmit={() => {}}
      {...props}
    />,
  );
}

describe('UpdateStageModal renders the backend decision', () => {
  it('offers exactly the allowed stages — never one the server marked blocked', () => {
    renderModal();
    const options = screen.getAllByRole('option').map((option) => option.value);
    expect(options).toEqual(['SPEC_APPROVED', 'QUOTE_OWNER', 'DELIVERY_SCHEDULING']);
    // The current stage, the fact-gated stage and the forbidden stage are all absent from the select — the server
    // said no to each, for three different reasons.
    expect(options).not.toContain('NEGOTIATION');
    expect(options).not.toContain('ORDER_RECEIVED');
    expect(options).not.toContain('DEPOSIT_RECEIVED');
  });

  it('demands a note when the server says requiresReason, and not otherwise', () => {
    const onSubmit = vi.fn();
    renderModal({ onSubmit });
    const select = screen.getByRole('combobox');
    const save = screen.getByRole('button', { name: 'บันทึก' });

    // SPEC_APPROVED is BACKWARD from NEGOTIATION and requiresReason is false — this is the exact route the deleted
    // frontend copy taxed with a mandatory justification and the backend does not.
    fireEvent.change(select, { target: { value: 'SPEC_APPROVED' } });
    expect(screen.queryByText(/ต้องระบุเหตุผล/)).toBeNull();
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    expect(onSubmit).toHaveBeenCalledWith({ stage: 'SPEC_APPROVED', note: undefined });

    // DELIVERY_SCHEDULING steps over a MANDATORY stage, so the server does require a reason.
    fireEvent.change(select, { target: { value: 'DELIVERY_SCHEDULING' } });
    expect(screen.getByText(/กำลังข้ามขั้นตอนสำคัญ/)).toBeTruthy();
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByPlaceholderText('ระบุรายละเอียด…'), {
      target: { value: 'ลูกค้าเร่งส่งของ' },
    });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    expect(onSubmit).toHaveBeenCalledWith({ stage: 'DELIVERY_SCHEDULING', note: 'ลูกค้าเร่งส่งของ' });
  });
});

// F5b: leaving PROCUREMENT for a later stage by hand warns that the goods are not all in.
describe('UpdateStageModal goods-not-arrived warning', () => {
  const WARNING = /สินค้าจากโรงงานยังมาไม่ครบ/;
  const procurementDecisions = [
    decision('SPEC_APPROVED', 3),
    decision('PROCUREMENT', 12, { allowed: false, blockedReason: 'อยู่ในขั้นตอนนี้อยู่แล้ว' }),
    decision('DELIVERY_SCHEDULING', 13),
  ];

  it('shows the warning when the target is later than PROCUREMENT and the goods have not arrived', () => {
    renderModal({ deal: { salesStage: 'PROCUREMENT', fulfillmentStatus: 'SHIPPING' }, stageDecisions: procurementDecisions });
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'DELIVERY_SCHEDULING' } });
    expect(screen.getByText(WARNING)).not.toBeNull();
  });
});
