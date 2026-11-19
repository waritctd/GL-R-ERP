import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EntryChannelFix } from './EntryChannelFix.jsx';

globalThis.React = React;

/** The server's own advertisement (TicketService.addPolicyActions). `requiredFields` is what
 * TicketService.entryChannelIsStated populates — `note` only when a STATED channel is being changed. */
const FIRST_CORRECTION = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value'] };
const STATED_CHANGE = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value', 'note'] };

function renderFix(props = {}) {
  const onSubmit = vi.fn();
  const utils = render(
    <EntryChannelFix entryChannel="UNSPECIFIED" action={FIRST_CORRECTION} onSubmit={onSubmit} {...props} />,
  );
  return { onSubmit, ...utils };
}

const toggle = () => screen.getByRole('button', { name: /แก้ช่องทางรับงาน/ });
const open = () => fireEvent.click(toggle());
const radios = () => screen.getAllByRole('radio');
const save = () => screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' });
const pick = (value) => fireEvent.click(radios().find((radio) => radio.value === value));

describe('EntryChannelFix — rendered only when the server advertises SET_ENTRY_CHANNEL', () => {
  it('renders nothing at all when the action is not advertised, even with a handler wired', () => {
    // Wrong-way-round: a caller who cannot correct the channel must not be handed a control that would only 403 —
    // and there must be no request path either.
    const { container, onSubmit } = renderFix({ action: undefined });
    expect(container.innerHTML).toBe('');
    expect(screen.queryByRole('button')).toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('EntryChannelFix — saving', () => {
  it('is blocked until a channel is chosen, and says so', () => {
    const { onSubmit } = renderFix();
    open();
    expect(save().disabled).toBe(true);
    expect(screen.getByTestId('entry-channel-fix-hint').textContent).toMatch(/เลือกช่องทาง/);
    fireEvent.click(save());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('a first correction (no note asked) sends the channel and a null note', () => {
    const { onSubmit } = renderFix({ action: FIRST_CORRECTION, entryChannel: 'UNSPECIFIED' });
    open();
    pick('OWNER_DIRECT');
    expect(save().disabled).toBe(false);
    fireEvent.click(save());
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ value: 'OWNER_DIRECT', note: null });
  });

  it('with a required reason typed, sends the channel and the trimmed note', () => {
    const { onSubmit } = renderFix({ action: STATED_CHANGE, entryChannel: 'OWNER_DIRECT' });
    open();
    pick('DESIGNER_LED');
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: '  มีผู้ออกแบบเข้ามาร่วมจริง  ' } });
    expect(save().disabled).toBe(false);
    fireEvent.click(save());
    expect(onSubmit).toHaveBeenCalledWith({ value: 'DESIGNER_LED', note: 'มีผู้ออกแบบเข้ามาร่วมจริง' });
  });

  it('never sends a note the server did not ask for, even if one was typed under an earlier ad', () => {
    const { onSubmit, rerender } = renderFix({ action: STATED_CHANGE, entryChannel: 'OWNER_DIRECT' });
    open();
    pick('BUYER_DIRECT');
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'ร่างไว้' } });
    rerender(<EntryChannelFix entryChannel="OWNER_DIRECT" action={FIRST_CORRECTION} onSubmit={onSubmit} />);
    fireEvent.click(save());
    expect(onSubmit).toHaveBeenCalledWith({ value: 'BUYER_DIRECT', note: null });
  });
});
