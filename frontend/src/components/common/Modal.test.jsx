import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Modal } from './Modal.jsx';

globalThis.React = React;

// The header's ✕ is a 44×44 icon button. Beside a long title at 320px it used to SHRINK (flex item without
// shrink-0, measured 37px wide in the document chain prototype): it must keep its size, and the title must be the
// one that gives way.
describe('Modal header', () => {
  it('the close button never shrinks; the title column does', () => {
    render(
      <Modal title="ออกใบแจ้งยอดเงินรับมัดจำ — ชื่อยาวมากที่ต้องตัดบรรทัดบนจอแคบ" subtitle="ฉบับร่าง" onClose={vi.fn()}>
        body
      </Modal>,
    );
    expect(screen.getByRole('button', { name: 'ปิด' }).className).toContain('shrink-0');
    expect(screen.getByRole('heading', { name: /ออกใบแจ้งยอดเงินรับมัดจำ/ }).parentElement.className).toContain('min-w-0');
  });
});
