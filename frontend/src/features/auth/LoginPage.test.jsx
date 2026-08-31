import React from 'react';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LoginPage } from './LoginPage.jsx';

globalThis.React = React;

// The demo row only renders under VITE_USE_MOCKS — it is a mock-mode affordance, never shipped to
// a real login. Without this stub every case below fails on a missing button rather than on the
// behaviour it is testing.
beforeEach(() => vi.stubEnv('VITE_USE_MOCKS', 'true'));
afterEach(() => vi.unstubAllEnvs());

// The demo quick-login row is the only way most of these personas are ever reached in mock mode,
// so what each button SENDS is behaviour, not decoration.
//
// Why this file exists at all: a division manager is not a role. It is `role: 'employee'` plus
// direct reports (permissions.js `isDivisionManager`, driven by the org chart). The mock resolves
// `{ role }` with `db.users.find(u => u.role === r && u.active)` — the FIRST match — so
// `{ role: 'employee' }` always lands on the plain employee and `warehouse.manager@glr.co.th`
// was unreachable. That left the entire "ทีมของฉัน" nav group and every team-scoped self-service
// view with no way to be opened, which is how a 238px unreachable column on the OT table survived
// in the manager view: no persona could render it.
describe('LoginPage demo quick-login', () => {
  it('sends a role for the personas that ARE a role', () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} loading={false} error={null} />);

    fireEvent.click(screen.getByTestId('login-role-employee'));

    expect(onLogin).toHaveBeenCalledWith({ role: 'employee' });
  });

  it('sends real credentials for the division manager, which no role can select', () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} loading={false} error={null} />);

    fireEvent.click(screen.getByTestId('login-role-division_manager'));

    // Email+password, not `{ role: 'division_manager' }` — no such role exists. This is an
    // ordinary credential login, which is also why it behaves the same against the real backend.
    expect(onLogin).toHaveBeenCalledWith({
      email: 'warehouse.manager@glr.co.th',
      password: 'demo1234',
    });
  });

  it('does not collapse the division manager onto the plain employee account', () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} loading={false} error={null} />);

    fireEvent.click(screen.getByTestId('login-role-division_manager'));

    // Wrong-way-round on purpose: the failure mode is the manager button quietly logging in as
    // the plain employee, which looks like a working button and shows the wrong person's data.
    expect(onLogin).not.toHaveBeenCalledWith({ role: 'employee' });
    expect(onLogin.mock.calls[0][0].role).toBeUndefined();
  });
});

// ── THE TYPED LOGIN, NOT THE DEMO ROW ────────────────────────────────────────
// Case has never decided a login: EmployeeAuthRepository#findByEmail compares
// LOWER(btrim(e.email)) = LOWER(:email). Whitespace is the half that actually failed —
// AuthController#login is @Valid, and LoginRequest's @Email rejects a leading or trailing
// space, so a pasted or phone-autocorrected address 400'd before the lookup ever ran.
//
// This is trimmed on the client as WELL as in LoginRequest's constructor because the two
// halves ship on different clocks: merging to `main` deploys this bundle immediately, while
// the backend runs a pinned pre-built image that changes only when someone builds and deploys
// a new tag. Client-side trimming is what makes the fix real before that image bump.
describe('LoginPage credential submit', () => {
  it('trims surrounding whitespace off the email before submitting', () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} loading={false} error={null} />);

    fireEvent.change(screen.getByTestId('login-email'), {
      target: { value: '  Suneesllim.1977@gmail.com  ' },
    });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: 'pw' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    // Trimmed, but NOT lowercased here: the server folds case, and rewriting what the user
    // typed would make the field disagree with the value on screen for no benefit.
    expect(onLogin).toHaveBeenCalledWith({
      email: 'Suneesllim.1977@gmail.com',
      password: 'pw',
    });
  });

  it('leaves the password exactly as typed, spaces included', () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} loading={false} error={null} />);

    fireEvent.change(screen.getByTestId('login-email'), { target: { value: 'a@b.co' } });
    fireEvent.change(screen.getByTestId('login-password'), { target: { value: '  spaced pw  ' } });
    fireEvent.click(screen.getByTestId('login-submit'));

    // Spaces are legitimate password characters; trimming them would lock out whoever uses one.
    expect(onLogin).toHaveBeenCalledWith({ email: 'a@b.co', password: '  spaced pw  ' });
  });

  it('stops the phone keyboard editing the address behind the user', () => {
    render(<LoginPage onLogin={vi.fn()} loading={false} error={null} />);
    const input = screen.getByTestId('login-email');

    // Without these a mobile keyboard capitalises the first letter and autocorrects the
    // address — and an accepted correction is what appends the trailing space above.
    // getAttribute, not toHaveAttribute — this project does not wire up jest-dom's matchers.
    expect(input.getAttribute('autocapitalize')).toBe('none');
    expect(input.getAttribute('autocorrect')).toBe('off');
    expect(input.getAttribute('spellcheck')).toBe('false');
  });
});
