import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FactoryContactDialog } from './FactoryContactDialog.jsx';

globalThis.React = React;

// Design m-contacted (spec §7.11 item 4): ติดต่อโรงงานแล้ว opens on TODAY in Asia/Bangkok, says so, and still lets
// import pick an earlier day.
describe('FactoryContactDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 2026-11-13 18:30 UTC is already 14 Nov in Bangkok (UTC+7): the default must follow Bangkok, not UTC.
    vi.setSystemTime(new Date('2026-11-13T18:30:00Z'));
  });
  afterEach(() => { vi.useRealTimers(); cleanup(); });

  it('defaults the contact date to today in Asia/Bangkok and says it can be changed', () => {
    const onConfirm = vi.fn();
    render(<FactoryContactDialog factoryName="Mutina (อิตาลี)" pending={false} onCancel={() => {}} onConfirm={onConfirm} />);
    const dialog = screen.getByTestId('factory-contact-dialog');
    expect(within(dialog).getByText('ค่าเริ่มต้นเป็นวันนี้ เปลี่ยนได้')).not.toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));
    expect(onConfirm).toHaveBeenCalledWith({ contactedOn: '2026-11-14' });
  });
});
