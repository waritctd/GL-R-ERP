import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EntryChannelFix } from './EntryChannelFix.jsx';

globalThis.React = React;

/**
 * The server's own advertisement (TicketService.addPolicyActions). Hand-built and passed DIRECTLY:
 * this component's whole contract is "render what the server advertised", so the fixture has to be
 * able to say either answer independently of the deal's channel. `requiredFields` is what
 * TicketService.entryChannelIsStated populates — `note` only when a STATED channel is being changed.
 */
const FIRST_CORRECTION = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value'] };
const STATED_CHANGE = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value', 'note'] };

function renderFix(props = {}) {
  const onSubmit = vi.fn();
  const utils = render(
    <EntryChannelFix entryChannel="UNSPECIFIED" action={FIRST_CORRECTION} onSubmit={onSubmit} {...props} />,
  );
  return { onSubmit, ...utils };
}

const toggle = () => screen.getByRole('button', { name: /แก้ช่องทางดีล/ });
const open = () => fireEvent.click(toggle());
const radios = () => screen.getAllByRole('radio');
const save = () => screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' });
const pick = (value) => fireEvent.click(radios().find((radio) => radio.value === value));

describe('EntryChannelFix — rendered only when the server advertises SET_ENTRY_CHANNEL', () => {
  it('renders nothing at all when the action is not advertised, even with a handler wired', () => {
    // Wrong-way-round: a caller who cannot correct the channel must not be handed a control that
    // would only 403 — and there must be no request path either.
    const { container, onSubmit } = renderFix({ action: undefined });
    expect(container.innerHTML).toBe('');
    expect(screen.queryByRole('button')).toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders nothing when the action is null', () => {
    const { container } = renderFix({ action: null });
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when no handler is wired, even if the server advertised the action', () => {
    const { container } = renderFix({ onSubmit: undefined });
    expect(container.innerHTML).toBe('');
  });

  it('ignores a differently-named advertised action', () => {
    const { container } = renderFix({ action: { action: 'SET_TENDER_REQUIREMENT', requiredFields: ['value'] } });
    expect(container.innerHTML).toBe('');
  });

  it('starts collapsed: a labelled disclosure button and no channel choices yet', () => {
    renderFix();
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
  });
});

describe('EntryChannelFix — the choices', () => {
  it.each(['UNSPECIFIED', 'DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'])(
    'offers exactly the three real channels and never UNSPECIFIED (deal currently %s)',
    (entryChannel) => {
      renderFix({ entryChannel });
      open();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(radios().map((radio) => radio.value)).toEqual(['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT']);
      // UNSPECIFIED is valid as STORED and 400s as INPUT: it may be READ, never CHOSEN.
      expect(radios().some((radio) => radio.value === 'UNSPECIFIED')).toBe(false);
      expect(screen.queryByRole('radio', { name: /ยังไม่ระบุช่องทาง/ })).toBeNull();
    },
  );

  it('says where the deal stands now, as text, when it is on the stored-only default', () => {
    renderFix({ entryChannel: 'UNSPECIFIED' });
    open();
    expect(screen.getByTestId('entry-channel-fix-current').textContent).toContain('ยังไม่ระบุช่องทาง');
  });

  it('cannot re-pick the channel the deal is already on (it would be a no-op the server 409s)', () => {
    renderFix({ entryChannel: 'OWNER_DIRECT' });
    open();
    const current = radios().find((radio) => radio.value === 'OWNER_DIRECT');
    expect(current.disabled).toBe(true);
    expect(radios().filter((radio) => radio.disabled)).toHaveLength(1);
    expect(screen.getByTestId('entry-channel-fix-current').textContent).toContain('เจ้าของติดต่อโดยตรง');
  });

  it('collapses again on ยกเลิก and forgets the draft', () => {
    renderFix({ action: STATED_CHANGE, entryChannel: 'OWNER_DIRECT' });
    open();
    pick('BUYER_DIRECT');
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'ลืมไว้' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    open();
    expect(radios().every((radio) => !radio.checked)).toBe(true);
    expect(screen.getByTestId('entry-channel-fix-reason').value).toBe('');
  });
});

describe('EntryChannelFix — the reason field follows the SERVER, not the channel value', () => {
  it('shows no reason field when the server did not ask for a note', () => {
    renderFix({ action: FIRST_CORRECTION, entryChannel: 'UNSPECIFIED' });
    open();
    expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
  });

  it('shows the reason field when the server did ask for a note', () => {
    renderFix({ action: STATED_CHANGE, entryChannel: 'OWNER_DIRECT' });
    open();
    expect(screen.getByTestId('entry-channel-fix-reason')).toBeTruthy();
  });

  it('driven by the advertisement, not by the value: a STATED channel with a note-free ad has no field', () => {
    // If the client computed "changing a stated channel needs a reason" itself, this would show one.
    renderFix({ action: FIRST_CORRECTION, entryChannel: 'BUYER_DIRECT' });
    open();
    expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
  });

  it('and the reverse: an UNSPECIFIED deal whose ad asks for a note still gets the field', () => {
    renderFix({ action: STATED_CHANGE, entryChannel: 'UNSPECIFIED' });
    open();
    expect(screen.getByTestId('entry-channel-fix-reason')).toBeTruthy();
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

  it('is blocked while a REQUIRED reason is empty, and the control says why', () => {
    const { onSubmit } = renderFix({ action: STATED_CHANGE, entryChannel: 'OWNER_DIRECT' });
    open();
    pick('DESIGNER_LED');
    expect(save().disabled).toBe(true);
    expect(screen.getByTestId('entry-channel-fix-hint').textContent).toMatch(/เหตุผล/);
    fireEvent.click(save());
    expect(onSubmit).not.toHaveBeenCalled();
    // Whitespace is not a reason.
    fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: '   ' } });
    expect(save().disabled).toBe(true);
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

  it('cannot be saved while a request is in flight (disabled), and says so', () => {
    const { onSubmit } = renderFix({ disabled: true });
    open();
    pick('OWNER_DIRECT');
    expect(save().disabled).toBe(true);
    fireEvent.click(save());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('collapses itself once the deal actually reports the new channel (so the refresh is visible)', () => {
    const { onSubmit, rerender } = renderFix({ entryChannel: 'UNSPECIFIED' });
    open();
    pick('OWNER_DIRECT');
    fireEvent.click(save());
    // Still open until the server answers: a failed save must not throw the draft away.
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    rerender(<EntryChannelFix entryChannel="OWNER_DIRECT" action={STATED_CHANGE} onSubmit={onSubmit} />);
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
  });
});

describe('EntryChannelFix — accessibility and layout hooks', () => {
  it('the toggle controls its panel and the panel is a labelled group', () => {
    renderFix();
    open();
    const panelId = toggle().getAttribute('aria-controls');
    expect(panelId).toBeTruthy();
    expect(document.getElementById(panelId)).toBe(screen.getByTestId('entry-channel-fix-panel'));
    expect(within(screen.getByTestId('entry-channel-fix-panel')).getByRole('radiogroup', { name: /เสนอแก่/ })).toBeTruthy();
  });

  it('carries the row it belongs to into the button name, so four identical buttons are distinguishable', () => {
    renderFix({ context: 'เสนอราคาผู้ออกแบบ' });
    expect(toggle().textContent).toContain('เสนอราคาผู้ออกแบบ');
  });

  it('is not a modal: opening it adds no dialog', () => {
    renderFix();
    open();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
