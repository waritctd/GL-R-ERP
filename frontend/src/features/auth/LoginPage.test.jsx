import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

globalThis.React = React;

const featuresMock = vi.hoisted(() => ({ SALES_ENABLED: false, UAT_QUICK_LOGIN_ENABLED: false }));
vi.mock('../../app/features.js', () => featuresMock);

async function renderLogin({ quickLogin = false } = {}) {
  featuresMock.UAT_QUICK_LOGIN_ENABLED = quickLogin;
  vi.resetModules();
  const { LoginPage } = await import('./LoginPage.jsx');
  const onLogin = vi.fn();
  return { onLogin, ...render(<LoginPage onLogin={onLogin} loading={false} error="" />) };
}

afterEach(() => {
  featuresMock.UAT_QUICK_LOGIN_ENABLED = false;
});

describe('LoginPage UAT quick login', () => {
  it('is hidden unless the UAT flag is on, so a demo/prod build never exposes the personas', async () => {
    await renderLogin({ quickLogin: false });

    expect(screen.queryByText('UAT — เข้าสู่ระบบด่วน')).toBeNull();
    expect(screen.queryByRole('button', { name: /ceo/i })).toBeNull();
  });

  it('signs in with the seeded persona credentials, not a password-less role the backend rejects', async () => {
    const { onLogin } = await renderLogin({ quickLogin: true });

    expect(screen.getByText('UAT — เข้าสู่ระบบด่วน')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /CEO/ }));

    expect(onLogin).toHaveBeenCalledTimes(1);
    expect(onLogin).toHaveBeenCalledWith({ email: 'ceo@uat.glr', password: 'Uat@2026' });
  });

  it('offers every persona V900 seeds, each wired to its own account', async () => {
    const { onLogin } = await renderLogin({ quickLogin: true });

    const emails = ['ceo', 'hr', 'salesmgr', 'sales', 'import', 'divmgr', 'employee', 'nulldiv', 'admin']
      .map((name) => `${name}@uat.glr`);

    // The form's own submit button, then one button per persona in seed order. Matching by label
    // would be ambiguous — "Sales" is a prefix of "Sales Manager".
    const [, ...personaButtons] = screen.getAllByRole('button');
    expect(personaButtons).toHaveLength(emails.length);

    personaButtons.forEach((button) => fireEvent.click(button));

    expect(onLogin.mock.calls.map(([payload]) => payload.email)).toEqual(emails);
  });
});
