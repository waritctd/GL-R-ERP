import React from 'react';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { LoginPage } from './LoginPage.jsx';

globalThis.React = React;

// LoginPage now calls useNavigate() (the ลืมรหัสผ่าน? link), so every render needs a Router context - same
// MemoryRouter pattern used elsewhere in this suite (e.g. CommissionPage's tests).
function renderLoginPage(props) {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage {...props} />} />
        <Route path="/forgot-password" element={<div>forgot-password-page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

// The demo row only renders under VITE_USE_MOCKS — it is a mock-mode affordance, never shipped to a real
// login. Without this stub every case below fails on a missing button rather than on the behaviour it is
// testing.
beforeEach(() => vi.stubEnv('VITE_USE_MOCKS', 'true'));
afterEach(() => vi.unstubAllEnvs());

// The demo quick-login row is the only way most of these personas are ever reached in mock mode, so what each
// button SENDS is behaviour, not decoration. Why this file exists at all: a division manager is not a role.
describe('LoginPage demo quick-login', () => {
  it('sends a role for the personas that ARE a role', () => {
    const onLogin = vi.fn();
    renderLoginPage({ onLogin, loading: false, error: null });

    fireEvent.click(screen.getByTestId('login-role-employee'));

    expect(onLogin).toHaveBeenCalledWith({ role: 'employee' });
  });

  it('sends real credentials for the division manager, which no role can select', () => {
    const onLogin = vi.fn();
    renderLoginPage({ onLogin, loading: false, error: null });

    fireEvent.click(screen.getByTestId('login-role-division_manager'));

    // Email+password, not `{ role: 'division_manager' }` — no such role exists. This is an ordinary credential
    // login, which is also why it behaves the same against the real backend.
    expect(onLogin).toHaveBeenCalledWith({
      email: 'warehouse.manager@glr.co.th',
      password: 'demo1234',
    });
  });
});

// THE TYPED LOGIN, NOT THE DEMO ROW ──────────────────────────────────────── Case has never decided a login:
// EmployeeAuthRepository#findByEmail compares LOWER(btrim(e.email)) = LOWER(:email). Client-side trimming is
// what makes the fix real before that image bump.
describe('LoginPage credential submit', () => {
  it('trims surrounding whitespace off the email before submitting', () => {
    const onLogin = vi.fn();
    renderLoginPage({ onLogin, loading: false, error: null });

    fireEvent.change(screen.getByTestId('login-email'), {
      target: { value: '  Suneesllim.1977@gmail.com  ' },
    });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    // Trimmed, but NOT lowercased here: the server folds case, and rewriting what the user typed would make the
    // field disagree with the value on screen for no benefit.
    expect(onLogin).toHaveBeenCalledWith({
      email: 'Suneesllim.1977@gmail.com',
      password: 'pw',
    });
  });

  it('leaves the password exactly as typed, spaces included', () => {
    const onLogin = vi.fn();
    renderLoginPage({ onLogin, loading: false, error: null });

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'a@b.co' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: '  spaced pw  ' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    // Spaces are legitimate password characters; trimming them would lock out whoever uses one.
    expect(onLogin).toHaveBeenCalledWith({ email: 'a@b.co', password: '  spaced pw  ' });
  });
});

