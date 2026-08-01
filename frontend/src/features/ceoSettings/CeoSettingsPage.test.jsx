import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
    pricingFormulaConfig: {
      get: vi.fn(),
      update: vi.fn(),
    },
  },
}));

// BRANCH 1 sample config, small enough to be readable: 1 country x 1 thickness band x 1 qty
// band, so tests can pin exact matrix cell text without a 39-row fixture.
function sampleFormulaConfig() {
  return {
    formulaConfigId: 1,
    version: 1,
    effectiveFrom: '2026-01-01',
    insuranceValueFactor: 1.15,
    insuranceRate: 0.0045,
    insuranceBuffer: 1.07,
    costBuffer: 1.07,
    sellingBuffer: 1.07,
    // Deliberately NOT 0.2 -- the pre-existing priceCalcConfigs fixture below also has
    // marginPct: 0.2, which renders via the same pctDisplay helper ("20.00%"); a distinct value
    // here keeps this section's assertions from colliding with that pre-existing table's text.
    defaultMarginPct: 0.24,
    sellingPriceRoundUpTo: 10,
    freightRates: [
      { freightRateId: 1, originCountry: 'China', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 60000 },
    ],
    dutyRates: [
      { dutyRateId: 1, productType: 'TILE', productLabel: 'กระเบื้อง', dutyPct: 0.3 },
    ],
    clearanceFees: [
      { clearanceFeeId: 1, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 8000 },
    ],
  };
}

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
    api.pricingFormulaConfig.get.mockResolvedValue({ formulaConfig: sampleFormulaConfig() });
    api.pricingFormulaConfig.update.mockResolvedValue({ formulaConfig: { ...sampleFormulaConfig(), version: 2 } });
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

    fireEvent.click(screen.getByRole('button', { name: 'แก้ไขเอง' }));
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
    fireEvent.click(screen.getByRole('button', { name: 'แก้ไขเอง' }));

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
    expect(screen.getByText('แก้ไขสูตรราคา — CN')).not.toBeNull();

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

  // BRANCH 1: sales.pricing_formula_config config storage + CEO editing UI.
  describe('pricing formula config (สูตรคำนวณราคาขาย)', () => {
    it('renders the section with version/effective date and displays multipliers RAW (not scaled) alongside percent fields scaled', async () => {
      renderCeoSettingsPage();

      const heading = await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');
      // Scoped to this new section -- "เวอร์ชัน 1" / "20.00%"-shaped text also appears in the
      // pre-existing price-calc-config table above it, on the same page.
      const section = within(heading.closest('section'));

      expect(section.getByText('เวอร์ชัน 1')).not.toBeNull();
      expect(section.getByText('• มีผลตั้งแต่ 2026-01-01')).not.toBeNull();

      // Raw multipliers must render as-is -- 1.15 must NOT become "115" or "115%".
      expect(section.getByText('1.15')).not.toBeNull();
      expect(section.getByText('0.0045')).not.toBeNull();
      // insuranceBuffer/costBuffer/sellingBuffer all default to 1.07 -- appears 3x.
      expect(section.getAllByText('1.07')).toHaveLength(3);

      // defaultMarginPct (0.24) and dutyPct (0.3) ARE percentages -- displayed scaled.
      expect(section.getByText('24.00%')).not.toBeNull();
      expect(section.getByText('30.00%')).not.toBeNull();

      // Freight matrix cell and clearance fee amount.
      expect(section.getByText('60,000.00')).not.toBeNull();
      expect(section.getByText('8,000.00 บาท')).not.toBeNull();
    });

    it('opens the formula editor as a real dialog with multiplier defaults unscaled and percent defaults scaled', async () => {
      renderCeoSettingsPage();

      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');
      fireEvent.click(screen.getByRole('button', { name: 'แก้ไขสูตรคำนวณราคาขาย' }));

      const dialog = await screen.findByRole('dialog');
      expect(dialog.getAttribute('aria-modal')).toBe('true');

      // NON-NEGOTIABLE round-trip guard: the multiplier input must show "1.15", never "115".
      expect(screen.getByLabelText('ตัวคูณมูลค่าสินค้าเพื่อประกันภัย').value).toBe('1.15');
      expect(screen.getByLabelText('อัตราค่าประกันภัย').value).toBe('0.0045');
      // The percent field must show the SCALED value ("24"), matching the existing page's
      // fraction->percent convention exactly.
      expect(screen.getByLabelText('อัตรากำไรเริ่มต้น').value).toBe('24');
    });

    it('rejects a blank multiplier field inline and never calls pricingFormulaConfig.update', async () => {
      renderCeoSettingsPage();

      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');
      fireEvent.click(screen.getByRole('button', { name: 'แก้ไขสูตรคำนวณราคาขาย' }));
      await screen.findByRole('dialog');

      const factorInput = screen.getByLabelText('ตัวคูณมูลค่าสินค้าเพื่อประกันภัย');
      fireEvent.change(factorInput, { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกเวอร์ชันใหม่' }));

      expect(await screen.findByText('กรุณากรอกตัวเลขที่ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)')).not.toBeNull();
      expect(factorInput.getAttribute('aria-invalid')).toBe('true');
      expect(api.pricingFormulaConfig.update).not.toHaveBeenCalled();
    });

    it('saves with the exact payload, including the multiplier passthrough and the percent round-trip', async () => {
      renderCeoSettingsPage();

      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');
      fireEvent.click(screen.getByRole('button', { name: 'แก้ไขสูตรคำนวณราคาขาย' }));
      await screen.findByRole('dialog');

      fireEvent.click(screen.getByRole('button', { name: 'บันทึกเวอร์ชันใหม่' }));

      await waitFor(() => expect(api.pricingFormulaConfig.update).toHaveBeenCalledTimes(1));
      expect(api.pricingFormulaConfig.update).toHaveBeenCalledWith({
        insuranceValueFactor: 1.15,
        insuranceRate: 0.0045,
        insuranceBuffer: 1.07,
        costBuffer: 1.07,
        sellingBuffer: 1.07,
        defaultMarginPct: 0.24,
        sellingPriceRoundUpTo: 10,
        freightRates: [
          { originCountry: 'China', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 60000 },
        ],
        dutyRates: [
          { productType: 'TILE', productLabel: 'กระเบื้อง', dutyPct: 0.3 },
        ],
        clearanceFees: [
          { qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 8000 },
        ],
      });
    });
  });
});
