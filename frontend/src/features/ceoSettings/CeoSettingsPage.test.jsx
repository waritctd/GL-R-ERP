import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CeoSettingsPage } from './CeoSettingsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    fxRates: {
      list: vi.fn(),
      upsert: vi.fn(),
    },
    priceCalcConfigs: {
      list: vi.fn(),
      update: vi.fn(),
    },
  },
}));

function renderCeoSettingsPage(showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <CeoSettingsPage showToast={showToast} />
    </QueryClientProvider>,
  );
}

describe('CeoSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.fxRates.list.mockResolvedValue({
      fxRates: [
        { currency: 'THB', rateToThb: 1, effectiveDate: '2026-07-16', source: 'MANUAL' },
        { currency: 'USD', rateToThb: 36.5, effectiveDate: '2026-07-16', source: 'BOT' },
      ],
    });
    api.fxRates.upsert.mockResolvedValue({ fxRate: { currency: 'USD', rateToThb: 37 } });
    api.priceCalcConfigs.list.mockResolvedValue({
      configs: [{
        configId: 1, country: 'CN', version: 1,
        freightPerSqm: 10, insurancePerSqm: 2,
        inlandFactoryToPortPerSqm: 3, inlandPortToWarehousePerSqm: 4,
        importDutyPct: 0.1, marginPct: 0.2,
      }],
    });
  });

  it('renders fx rates from a mocked api.fxRates.list', async () => {
    renderCeoSettingsPage();

    expect(await screen.findByText('USD')).not.toBeNull();
    expect(screen.getByText('36.50')).not.toBeNull();
    expect(api.fxRates.list).toHaveBeenCalledTimes(1);
  });

  it('invalidates and refetches fx rates after saving an override', async () => {
    const showToast = vi.fn();
    renderCeoSettingsPage(showToast);

    await screen.findByText('USD');
    expect(api.fxRates.list).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Override' }));
    const input = screen.getByDisplayValue('36.5');
    fireEvent.change(input, { target: { value: '37' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.fxRates.upsert).toHaveBeenCalledWith('USD', { rateToThb: 37 }));
    await waitFor(() => expect(api.fxRates.list).toHaveBeenCalledTimes(2));
    expect(showToast).toHaveBeenCalledWith('success', 'อัปเดตอัตรา USD แล้ว');
  });
});
