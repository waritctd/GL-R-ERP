import { render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

globalThis.React = React;

// features.js reads import.meta.env once at module load, so each case stubs the
// env and imports a fresh module (same pattern as App.test.jsx's lockdown suite).
async function renderGate(flag) {
  if (flag !== undefined) vi.stubEnv('VITE_MAINTENANCE', flag);
  vi.resetModules();
  const { MaintenanceGate } = await import('./MaintenanceGate.jsx');
  return render(
    <MaintenanceGate>
      <div>real app</div>
    </MaintenanceGate>,
  );
}

describe('MaintenanceGate', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('shows the maintenance page and NOT the app when VITE_MAINTENANCE=true', async () => {
    await renderGate('true');
    expect(screen.getByRole('heading', { name: /ปรับปรุงระบบ/ })).toBeTruthy();
    expect(screen.queryByText('real app')).toBeNull();
  });

  it('renders the app untouched when the flag is unset', async () => {
    await renderGate();
    expect(screen.getByText('real app')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: /ปรับปรุงระบบ/ })).toBeNull();
  });

  it('renders the app for any value other than "true"', async () => {
    await renderGate('false');
    expect(screen.getByText('real app')).toBeTruthy();
  });
});
