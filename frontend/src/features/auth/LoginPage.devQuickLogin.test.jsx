import React from 'react';
import {
  afterEach, describe, expect, it, vi,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { LoginPage } from './LoginPage.jsx';

globalThis.React = React;

function renderLoginPage(props) {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage {...props} />} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => vi.unstubAllEnvs());

// The real-backend quick-login row: one-click sign-in as each account the backend's `demo`
// profile seeds (db/migration-demo V21 + V139, shared password Demo@2026). It exists so a dev
// running `npm run dev` against a local backend can switch persona without typing credentials.
//
// It must be a DEV-SERVER-ONLY affordance. `import.meta.env.DEV` is false in `vite build`, so the
// gate is what keeps a committed showcase password out of every deployed bundle.
const DEMO_EMAILS = [
  'demo.employee@demo.invalid',
  'demo.hr@demo.invalid',
  'demo.sales@demo.invalid',
  'demo.salesmanager@demo.invalid',
  'demo.import@demo.invalid',
  'demo.account@demo.invalid',
  'demo.warehouse@demo.invalid',
  'demo.qc@demo.invalid',
  'demo.ceo@demo.invalid',
];

describe('LoginPage dev quick-login (real backend)', () => {
  it('renders one button per seeded demo persona on the dev server with mocks off', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'false');
    renderLoginPage({ onLogin: vi.fn(), loading: false, error: null });

    expect(screen.getAllByTestId(/^login-dev-/)).toHaveLength(DEMO_EMAILS.length);
  });

  it.each(DEMO_EMAILS)('sends real credentials for %s, not a mock { role }', (email) => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'false');
    const onLogin = vi.fn();
    renderLoginPage({ onLogin, loading: false, error: null });

    fireEvent.click(screen.getByTestId(`login-dev-${email}`));

    expect(onLogin).toHaveBeenCalledWith({ email, password: 'Demo@2026' });
    expect(onLogin.mock.calls[0][0].role).toBeUndefined();
  });

  it('is absent from a production build, so the demo password never ships', () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_USE_MOCKS', 'false');
    renderLoginPage({ onLogin: vi.fn(), loading: false, error: null });

    expect(screen.queryAllByTestId(/^login-dev-/)).toHaveLength(0);
    expect(screen.queryByText('Demo@2026')).toBeNull();
  });

  it('is absent in mock mode, where the mock role row is the quick-login', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'true');
    renderLoginPage({ onLogin: vi.fn(), loading: false, error: null });

    expect(screen.queryAllByTestId(/^login-dev-/)).toHaveLength(0);
    expect(screen.getByTestId('login-role-hr')).toBeTruthy();
  });

  it('disables the buttons while a login is in flight', () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USE_MOCKS', 'false');
    renderLoginPage({ onLogin: vi.fn(), loading: true, error: null });

    screen.getAllByTestId(/^login-dev-/).forEach((button) => expect(button.disabled).toBe(true));
  });
});
