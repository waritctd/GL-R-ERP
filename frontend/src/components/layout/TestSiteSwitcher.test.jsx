import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TestSiteSwitcher } from './TestSiteSwitcher.jsx';

globalThis.React = React;

const { accounts, switchTo } = vi.hoisted(() => ({ accounts: vi.fn(), switchTo: vi.fn() }));

vi.mock('../../api/index.js', async (importOriginal) => ({
  ...(await importOriginal()),
  api: { testSite: { accounts, switchTo } },
}));

const user = { name: '[DEMO] ฝ่ายขาย', email: 'demo.sales@demo.invalid', role: 'sales' };
const list = {
  accounts: [
    { email: 'demo.sales@demo.invalid', name: '[DEMO] ฝ่ายขาย', role: 'sales' },
    { email: 'demo.ceo@demo.invalid', name: '[DEMO] ผู้บริหาร', role: 'ceo' },
  ],
};

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  accounts.mockReset();
  switchTo.mockReset();
});

describe('test-site role switcher', () => {
  it('renders nothing and calls nothing unless the build is a test site', () => {
    vi.stubEnv('VITE_TEST_SITE', '');
    const { container } = render(<TestSiteSwitcher user={user} />);
    expect(container.innerHTML).toBe('');
    expect(accounts).not.toHaveBeenCalled();
  });

  it('shows the test banner with the current account, and switching reloads as the chosen account', async () => {
    vi.stubEnv('VITE_TEST_SITE', 'true');
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    accounts.mockResolvedValue(list);
    switchTo.mockResolvedValue({});
    render(<TestSiteSwitcher user={user} />);

    expect(await screen.findByText(/โหมดทดสอบ · กำลังใช้งานเป็น \[DEMO\] ฝ่ายขาย/)).toBeTruthy();
    const select = await screen.findByRole('combobox');
    expect(select.value).toBe('demo.sales@demo.invalid');
    fireEvent.change(select, { target: { value: 'demo.ceo@demo.invalid' } });

    await waitFor(() => expect(assign).toHaveBeenCalledWith('/'));
    expect(switchTo).toHaveBeenCalledWith('demo.ceo@demo.invalid');
  });

  it('when the accounts call fails, shows a note and no select', async () => {
    vi.stubEnv('VITE_TEST_SITE', 'true');
    accounts.mockRejectedValue({ status: 403 });
    render(<TestSiteSwitcher user={user} />);

    expect(await screen.findByText('บัญชีนี้สลับบทบาทไม่ได้')).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
  });
});
