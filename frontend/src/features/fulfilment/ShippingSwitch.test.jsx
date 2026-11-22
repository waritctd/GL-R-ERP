import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ShippingSwitch } from './ShippingSwitch.jsx';

globalThis.React = React;

const show = (user, current) => render(<MemoryRouter><ShippingSwitch user={user} current={current} /></MemoryRouter>);

describe('ShippingSwitch', () => {
  it('a person who may open both screens gets the switch, with the current one marked', () => {
    show({ id: 1, role: 'ceo' }, 'orders');
    expect(screen.getByTestId('shipping-switch-orders').getAttribute('aria-current')).toBe('page');
    expect(screen.getByTestId('shipping-switch-rounds').getAttribute('href')).toBe('/fulfilment');
  });

  it('a rep, who has only the team list, and import, who has only the rounds, get no switch', () => {
    const rep = show({ id: 2, role: 'sales' }, 'orders');
    expect(screen.queryByTestId('shipping-switch')).toBeNull();
    rep.unmount();
    show({ id: 3, role: 'import' }, 'rounds');
    expect(screen.queryByTestId('shipping-switch')).toBeNull();
  });
});
