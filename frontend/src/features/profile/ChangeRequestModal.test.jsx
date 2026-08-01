import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChangeRequestModal } from './ChangeRequestModal.jsx';

globalThis.React = React;

const requestField = {
  fieldKey: 'phone',
  fieldLabel: 'เบอร์โทรศัพท์',
  oldValue: '081-234-5678',
  icon: 'phone',
};

function renderModal({ onClose = vi.fn(), onSubmit = vi.fn() } = {}) {
  return {
    onClose,
    onSubmit,
    ...render(
      <ChangeRequestModal requestField={requestField} onClose={onClose} onSubmit={onSubmit} />,
    ),
  };
}

describe('ChangeRequestModal form validation', () => {
  it('separates the current value from the requested value field', () => {
    renderModal();

    expect(screen.getByText('ข้อมูลปัจจุบัน')).not.toBeNull();
    expect(screen.getByText('081-234-5678')).not.toBeNull();
    expect(screen.getByLabelText(/ค่าใหม่ของเบอร์โทรศัพท์/)).not.toBeNull();
    expect(screen.queryByLabelText(/เหตุผล/)).toBeNull();
  });

  it('blocks submit when the new value is empty', async () => {
    const { onSubmit } = renderModal();

    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });
    fireEvent.click(submitButton);

    expect(await screen.findByText('กรุณาระบุค่าใหม่')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the existing change-request payload shape for a valid submit', async () => {
    const { onSubmit } = renderModal();

    fireEvent.change(screen.getByLabelText(/ค่าใหม่ของเบอร์โทรศัพท์/), { target: { value: '089-999-9999' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      fieldKey: 'phone',
      fieldLabel: 'เบอร์โทรศัพท์',
      oldValue: '081-234-5678',
      icon: 'phone',
      newValue: '089-999-9999',
    });
    expect(onSubmit.mock.calls[0][0]).not.toHaveProperty('reason');
  });
});
