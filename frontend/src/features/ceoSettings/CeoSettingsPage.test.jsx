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
      fetchNow: vi.fn(),
    },
    priceCalcConfigs: {
      list: vi.fn(),
      update: vi.fn(),
    },
    pricingFormulaConfig: {
      get: vi.fn(),
      update: vi.fn(),
      addFreightRate: vi.fn(),
      deleteFreightRate: vi.fn(),
    },
  },
}));

// BRANCH 1 sample config, small enough to be readable: 1 country x 1 thickness band x 1 qty band, so tests can
// pin exact matrix cell text without a 39-row fixture.
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
    // Deliberately NOT 0.2 -- the pre-existing priceCalcConfigs fixture below also has marginPct: 0.2, which
    // renders via the same pctDisplay helper ("20.00%"); a distinct value here keeps this section's assertions
    // from colliding with that pre-existing table's text.
    defaultMarginPct: 0.24,
    sellingPriceRoundUpTo: 10,
    freightRates: [
      { freightRateId: 1, originCountryCode: 'CN', originCountryName: 'จีน', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 60000 },
    ],
    dutyRates: [
      { dutyRateId: 1, productType: 'TILE', productLabel: 'กระเบื้อง', dutyPct: 0.3 },
    ],
    clearanceFees: [
      { clearanceFeeId: 1, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 8000 },
    ],
    // V151: the freight editor's country <select> is populated from this, not from the countries already present
    // in freightRates -- otherwise a new supplier country could never be added. 'ES' is here but absent from
    // freightRates above, which is exactly the case that matters.
    availableCountries: [
      { countryCode: 'CN', nameEn: 'China', nameTh: 'จีน' },
      { countryCode: 'ES', nameEn: 'Spain', nameTh: 'สเปน' },
      { countryCode: 'IT', nameEn: 'Italy', nameTh: 'อิตาลี' },
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
    api.pricingFormulaConfig.addFreightRate.mockResolvedValue({ formulaConfig: { ...sampleFormulaConfig(), version: 2 } });
    api.pricingFormulaConfig.deleteFreightRate.mockResolvedValue({
      formulaConfig: { ...sampleFormulaConfig(), version: 2, freightRates: [] },
    });
  });

  // UX-03: an invalid FX rate override must be marked inline on that currency's own input (aria-invalid +
  // aria-describedby -> role="alert"), and must never reach api.fxRates.upsert.
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

  // P0 support fix (2026-09): FxResolver no longer requires source === 'BOT', so a non-THB rate's ONLY remaining
  // refusal-by-age path is staleness (effectiveDate more than FxResolver.MAX_RATE_AGE_DAYS days old). This flags
  // that here, before it ever blocks costing on a pricing request.
  describe('FX rate staleness badge (P0 support fix)', () => {
    // Deliberately PURE UTC day arithmetic, matching exactly how isFxRateStale itself computes "today" (`new
    // Date.toISOString.slice`).
    function isoDaysAgo(n) {
      const date = new Date();
      date.setUTCDate(date.getUTCDate() - n);
      return date.toISOString().slice(0, 10);
    }

    // Both sides asserted on the SAME render/fixture (CLAUDE.md's own testing rule) — a badge that always renders,
    // or one that never does, fails this either way.
    it('flags a non-THB row older than 7 days as stale and leaves a fresher one, and THB itself, unflagged', async () => {
      api.fxRates.list.mockResolvedValue({
        fxRates: [
          // THB is exempt from the check entirely (FxResolver.resolve short-circuits it before ever looking at
          // effectiveDate) — deliberately given a very old date to prove that, not just one that happens to be fresh.
          { currency: 'THB', rateToThb: 1, effectiveDate: isoDaysAgo(400), source: 'MANUAL' },
          { currency: 'USD', rateToThb: 36.5, effectiveDate: isoDaysAgo(8), source: 'MANUAL' },
          { currency: 'EUR', rateToThb: 39.2, effectiveDate: isoDaysAgo(3), source: 'BOT' },
        ],
      });
      renderCeoSettingsPage();

      await screen.findByText('USD');
      expect(screen.getByTestId('fx-rate-stale-USD')).not.toBeNull();
      expect(screen.queryByTestId('fx-rate-stale-EUR')).toBeNull();
      expect(screen.queryByTestId('fx-rate-stale-THB')).toBeNull();
    });

    // Mirrors FxResolver.resolve()'s own `isBefore` (strict) rather than `isBefore`-or-equal — a rate dated
    // EXACTLY on the 7-day boundary is still accepted, so it must not be flagged.
    it('does not flag a row exactly on the 7-day boundary', async () => {
      api.fxRates.list.mockResolvedValue({
        fxRates: [
          { currency: 'THB', rateToThb: 1, effectiveDate: isoDaysAgo(0), source: 'MANUAL' },
          { currency: 'USD', rateToThb: 36.5, effectiveDate: isoDaysAgo(7), source: 'MANUAL' },
        ],
      });
      renderCeoSettingsPage();

      await screen.findByText('USD');
      expect(screen.queryByTestId('fx-rate-stale-USD')).toBeNull();
    });
  });

  // Pricing round-trip guard: importDutyPct/marginPct are stored as fractions but edited as percents.
  // openConfigEdit multiplies by 100 for display; saveConfig must divide back by 100 on save.
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
    it('saves with the exact payload, including the multiplier passthrough and the percent round-trip', async () => {
      renderCeoSettingsPage();

      await screen.findByText('สูตรคำนวณราคาขายของงาน');
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
          // Code only: originCountryName is display data resolved server-side, never sent back.
          { originCountryCode: 'CN', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 60000 },
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

  // Issue #436, commit 4: freight-row DELETE. Same read-only-matrix-only placement as add.
  describe('freight-row delete (issue #436)', () => {
    async function openDeleteConfirm() {
      const deleteButton = await screen.findByRole('button', { name: /^ลบค่าขนส่ง จีน/ });
      fireEvent.click(deleteButton);
      return screen.findByRole('dialog', { name: 'ยืนยันการลบค่าขนส่ง' });
    }

    it("confirming calls deleteFreightRate with that row's freightRateId exactly once", async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขายของงาน');

      const dialog = await openDeleteConfirm();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ลบค่าขนส่ง' }));

      await waitFor(() => expect(api.pricingFormulaConfig.deleteFreightRate).toHaveBeenCalledWith(1));
      expect(api.pricingFormulaConfig.deleteFreightRate).toHaveBeenCalledTimes(1);
    });
  });

  // The "ตัวคูณราคาตั้งประมาณการ" panel and its five tests were removed on 2026-08-10 along with the ราคาตั้ง
  // (ประมาณการ) estimate the multiplier fed. `api.dealEstimateMarkup` no longer exists on either hrApi or
  // mockApi, so there is nothing left to mock or assert here.
});
