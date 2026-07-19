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
    api.priceCalcConfigs.update.mockResolvedValue({});
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

  // UX-03: an invalid FX rate override must be marked inline on that
  // currency's own input (aria-invalid + aria-describedby -> role="alert"),
  // and must never reach api.fxRates.upsert.
  it('marks an invalid FX rate inline on that currency row and does not call the FX save', async () => {
    renderCeoSettingsPage();

    await screen.findByText('USD');
    fireEvent.click(screen.getByRole('button', { name: 'Override' }));

    const input = screen.getByDisplayValue('36.5');
    fireEvent.change(input, { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    const error = await screen.findByText('กรุณากรอกอัตราแลกเปลี่ยนที่ถูกต้อง');
    expect(error.getAttribute('role')).toBe('alert');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.fxRates.upsert).not.toHaveBeenCalled();
  });

  // UX-08: the config editor is now built on the shared Modal — real dialog
  // semantics, and Escape closes it (was: hand-rolled overlay with none of
  // this).
  it('opens the config editor as a real dialog and closes it on Escape', async () => {
    renderCeoSettingsPage();

    await screen.findByText('CN');
    fireEvent.click(screen.getByRole('button', { name: 'แก้ไข' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(screen.getByText('แก้ไข config — CN')).not.toBeNull();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  // UX-03: this is the silent-zero regression guard. Before this fix,
  // Number('') === 0, so clearing a pricing field and saving silently
  // persisted 0 for that pricing input (e.g. zeroing freight/margin would
  // under-price every deal for that country). It must now be rejected
  // inline instead, and the save must never fire.
  it('rejects a blank pricing field inline and does not call priceCalcConfigs.update', async () => {
    renderCeoSettingsPage();

    await screen.findByText('CN');
    fireEvent.click(screen.getByRole('button', { name: 'แก้ไข' }));
    await screen.findByRole('dialog');

    const freightInput = screen.getByLabelText('ค่าขนส่งทางเรือ (THB/ตร.ม.)');
    fireEvent.change(freightInput, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเวอร์ชันใหม่' }));

    expect(await screen.findByText('กรุณากรอกตัวเลขที่ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)')).not.toBeNull();
    expect(freightInput.getAttribute('aria-invalid')).toBe('true');
    expect(api.priceCalcConfigs.update).not.toHaveBeenCalled();
  });

  // Pricing round-trip guard: importDutyPct/marginPct are stored as
  // fractions but edited as percents. openConfigEdit multiplies by 100 for
  // display; saveConfig must divide back by 100 on save. This asserts the
  // exact outbound payload, including that round-trip, so a future edit
  // can't silently change the scaling or the payload shape.
  it('saves a valid config with the exact payload, including the percent round-trip', async () => {
    renderCeoSettingsPage();

    await screen.findByText('CN');
    fireEvent.click(screen.getByRole('button', { name: 'แก้ไข' }));
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกเวอร์ชันใหม่' }));

    await waitFor(() => expect(api.priceCalcConfigs.update).toHaveBeenCalledTimes(1));
    expect(api.priceCalcConfigs.update).toHaveBeenCalledWith({
      country: 'CN',
      freightPerSqm: 10,
      insurancePerSqm: 2,
      inlandFactoryToPortPerSqm: 3,
      inlandPortToWarehousePerSqm: 4,
      importDutyPct: 0.1,
      marginPct: 0.2,
    });
  });
});
