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
      addFreightRate: vi.fn(),
      deleteFreightRate: vi.fn(),
    },
    catalogThicknessDefaults: {
      list: vi.fn(),
      save: vi.fn(),
    },
  },
}));

// V153 thickness gaps. Deliberately NOT in descending row order, so a panel that re-sorted or
// re-ordered them would show ALTEA first and fail the ordering test below.
function sampleThicknessGaps() {
  return {
    gaps: [
      { factoryId: 8, factoryName: 'Vives', collection: 'ALTEA', rowsMissingThickness: 312, currentDefaultMm: null, hasSizeLevelOverride: false },
      { factoryId: 7, factoryName: 'Equipe', collection: 'KENZAI', rowsMissingThickness: 96, currentDefaultMm: 8.5, hasSizeLevelOverride: false },
    ],
    rowsStillMissingThickness: 312,
  };
}

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
      { freightRateId: 1, originCountryCode: 'CN', originCountryName: 'จีน', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 60000 },
    ],
    dutyRates: [
      { dutyRateId: 1, productType: 'TILE', productLabel: 'กระเบื้อง', dutyPct: 0.3 },
    ],
    clearanceFees: [
      { clearanceFeeId: 1, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 8000 },
    ],
    // V151: the freight editor's country <select> is populated from this, not from the countries
    // already present in freightRates -- otherwise a new supplier country could never be added.
    // 'ES' is here but absent from freightRates above, which is exactly the case that matters.
    availableCountries: [
      { countryCode: 'CN', nameEn: 'China', nameTh: 'จีน' },
      { countryCode: 'ES', nameEn: 'Spain', nameTh: 'สเปน' },
      { countryCode: 'IT', nameEn: 'Italy', nameTh: 'อิตาลี' },
    ],
  };
}

// Issue #436: extends sampleFormulaConfig with two more rows that leave ONE cell genuinely blank
// (China thickness [7,12) x qty [100, null)) -- mirrors V109's own "trailing blank, never an
// interior hole" seed shape. A DISTINCT fixture, not a mutation of sampleFormulaConfig's shared
// default, so the existing "saves with the exact payload" test's exact 1-row freightRates
// assertion is untouched by this file.
function sampleFormulaConfigWithBlankCell() {
  const base = sampleFormulaConfig();
  return {
    ...base,
    freightRates: [
      ...base.freightRates,
      { freightRateId: 2, originCountryCode: 'CN', originCountryName: 'จีน', thicknessMinMm: 3, thicknessMaxMm: 7, qtyMinSqm: 100, qtyMaxSqm: null, amountThb: 70000 },
      { freightRateId: 3, originCountryCode: 'CN', originCountryName: 'จีน', thicknessMinMm: 7, thicknessMaxMm: 12, qtyMinSqm: 1, qtyMaxSqm: 100, amountThb: 65000 },
      // China [7,12) x [100, null) is DELIBERATELY missing -- the blank cell under test.
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
    api.catalogThicknessDefaults.list.mockResolvedValue(sampleThicknessGaps());
    api.catalogThicknessDefaults.save.mockResolvedValue({ saved: 1, ...sampleThicknessGaps() });
  });

  // V153 thickness defaults. These pin the two behaviours that would silently MIS-PRICE if wrong:
  // a blank must clear rather than save 0 (a stored 0 selects the lowest freight band instead of
  // refusing to price), and rows must stay in biggest-impact-first order.
  describe('thickness defaults panel (V153)', () => {
    async function findThicknessInput(name) {
      return screen.findByLabelText(new RegExp(`ความหนา .*${name}`));
    }

    it('lists gaps biggest-impact-first and shows how many rows are still unpriceable', async () => {
      renderCeoSettingsPage();

      const altea = await screen.findByText('ALTEA');
      const kenzai = screen.getByText('KENZAI');
      // ALTEA (312 rows) must render before KENZAI (96) — the CEO works top-down.
      expect(altea.compareDocumentPosition(kenzai) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(screen.getByText(/เหลือ 312 รายการ/)).not.toBeNull();
    });

    it('prefills an already-set default and leaves an unset one blank', async () => {
      renderCeoSettingsPage();

      expect((await findThicknessInput('KENZAI')).value).toBe('8.5');
      expect((await findThicknessInput('ALTEA')).value).toBe('');
    });

    it('sends a typed thickness as a number, and only the edited row', async () => {
      renderCeoSettingsPage();

      fireEvent.change(await findThicknessInput('ALTEA'), { target: { value: '9' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึก 1 รายการ/ }));

      await waitFor(() => expect(api.catalogThicknessDefaults.save).toHaveBeenCalledWith({
        entries: [{ factoryId: 8, collection: 'ALTEA', thicknessMm: 9 }],
      }));
    });

    // The one that matters most: a stored 0 would silently pick the LOWEST freight band rather
    // than refusing to price, so a cleared field must reach the API as null — never Number('') = 0.
    it('sends a cleared thickness as null, never as zero', async () => {
      renderCeoSettingsPage();

      fireEvent.change(await findThicknessInput('KENZAI'), { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึก 1 รายการ/ }));

      await waitFor(() => expect(api.catalogThicknessDefaults.save).toHaveBeenCalledWith({
        entries: [{ factoryId: 7, collection: 'KENZAI', thicknessMm: null }],
      }));
    });

    it('offers no save control until something is edited', async () => {
      renderCeoSettingsPage();
      await screen.findByText('ALTEA');

      expect(screen.queryByRole('button', { name: /บันทึก \d+ รายการ/ })).toBeNull();
      expect(api.catalogThicknessDefaults.save).not.toHaveBeenCalled();
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

  // P0 support fix (2026-09): FxResolver no longer requires source === 'BOT', so a non-THB rate's
  // ONLY remaining refusal-by-age path is staleness (effectiveDate more than
  // FxResolver.MAX_RATE_AGE_DAYS days old). This flags that here, before it ever blocks costing on
  // a pricing request.
  describe('FX rate staleness badge (P0 support fix)', () => {
    // Deliberately PURE UTC day arithmetic (setUTCDate + toISOString), matching exactly how
    // isFxRateStale() itself computes "today" (`new Date().toISOString().slice(0, 10)`) — so this
    // helper's "n days ago" and the component's own age-in-days computation can never disagree by
    // a day depending on the test runner's TZ, unlike a local-calendar-day helper would (see this
    // repo's own Bangkok-timezone test-flake history).
    function isoDaysAgo(n) {
      const date = new Date();
      date.setUTCDate(date.getUTCDate() - n);
      return date.toISOString().slice(0, 10);
    }

    // Both sides asserted on the SAME render/fixture (CLAUDE.md's own testing rule) — a badge
    // that always renders, or one that never does, fails this either way.
    it('flags a non-THB row older than 7 days as stale and leaves a fresher one, and THB itself, unflagged', async () => {
      api.fxRates.list.mockResolvedValue({
        fxRates: [
          // THB is exempt from the check entirely (FxResolver.resolve short-circuits it before
          // ever looking at effectiveDate) — deliberately given a very old date to prove that,
          // not just one that happens to be fresh.
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

    // Mirrors FxResolver.resolve()'s own `isBefore` (strict) rather than `isBefore`-or-equal — a
    // rate dated EXACTLY on the 7-day boundary is still accepted, so it must not be flagged.
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

  // P0 support fix (2026-09): this used to read "...ติดต่อผู้ดูแลระบบหากยังไม่เปิดใช้งานการดึง
  // อัตราอัตโนมัติ", implying the CEO had to wait on an admin before costing would work — no
  // longer true now that a MANUAL rate is fully usable on its own.
  it('rewords the FX panel subheading so a manually entered rate reads as fully usable, not gated on an admin', async () => {
    renderCeoSettingsPage();
    await screen.findByText('USD');

    expect(screen.queryByText(/ติดต่อผู้ดูแลระบบหากยังไม่เปิดใช้งาน/)).toBeNull();
    expect(screen.getByText(/ใช้คำนวณต้นทุนได้ตามปกติทันที/)).not.toBeNull();
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

  // Issue #436, commit 3: freight-row ADD. Lives on the READ-ONLY matrix panel — see
  // AddFreightRateModal's own doc comment for why it is never inside FormulaConfigEditModal.
  describe('freight-row add (issue #436)', () => {
    it('renders an add control on a blank matrix cell; clicking it opens the modal prefilled with that cell\'s coordinates', async () => {
      api.pricingFormulaConfig.get.mockResolvedValue({ formulaConfig: sampleFormulaConfigWithBlankCell() });
      renderCeoSettingsPage();

      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');
      const addCellButton = await screen.findByRole(
        'button',
        { name: 'เพิ่มค่าขนส่ง จีน หนา 7 – <12 มม. ช่วง ≥100 ตร.ม.' },
      );
      fireEvent.click(addCellButton);

      const dialog = await screen.findByRole('dialog', { name: 'เพิ่มค่าขนส่ง' });
      expect(within(dialog).getByLabelText('ประเทศต้นทาง').value).toBe('CN');
      expect(within(dialog).getByLabelText('ความหนาตั้งแต่ (มม.)').value).toBe('7');
      expect(within(dialog).getByLabelText('ถึง (<) (มม.)').value).toBe('12');
      expect(within(dialog).getByLabelText('จำนวนตั้งแต่ (ตร.ม.)').value).toBe('100');
      // qtyMaxSqm is null on the target cell (open-ended top band) -- prefilled BLANK, not '0'
      // and not the string 'null'.
      expect(within(dialog).getByLabelText('ถึง (<) (ตร.ม.)').value).toBe('');
    });

    it('submits with the right types, sending qtyMaxSqm: null when the field is left blank', async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      fireEvent.click(screen.getByRole('button', { name: '+ เพิ่มค่าขนส่ง' }));
      const dialog = await screen.findByRole('dialog', { name: 'เพิ่มค่าขนส่ง' });

      fireEvent.change(within(dialog).getByLabelText('ประเทศต้นทาง'), { target: { value: 'ES' } });
      fireEvent.change(within(dialog).getByLabelText('ความหนาตั้งแต่ (มม.)'), { target: { value: '21' } });
      fireEvent.change(within(dialog).getByLabelText('ถึง (<) (มม.)'), { target: { value: '25' } });
      fireEvent.change(within(dialog).getByLabelText('จำนวนตั้งแต่ (ตร.ม.)'), { target: { value: '1' } });
      // qtyMax deliberately left blank.
      fireEvent.change(within(dialog).getByLabelText('ค่าขนส่ง (บาท)'), { target: { value: '90000' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'เพิ่มค่าขนส่ง' }));

      await waitFor(() => expect(api.pricingFormulaConfig.addFreightRate).toHaveBeenCalledWith({
        originCountryCode: 'ES',
        thicknessMinMm: 21,
        thicknessMaxMm: 25,
        qtyMinSqm: 1,
        qtyMaxSqm: null,
        amountThb: 90000,
      }));
    });

    it('rejects a blank required field inline and never calls addFreightRate', async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      fireEvent.click(screen.getByRole('button', { name: '+ เพิ่มค่าขนส่ง' }));
      const dialog = await screen.findByRole('dialog', { name: 'เพิ่มค่าขนส่ง' });
      fireEvent.click(within(dialog).getByRole('button', { name: 'เพิ่มค่าขนส่ง' }));

      expect(await within(dialog).findByText('กรุณาเลือกประเทศต้นทาง')).not.toBeNull();
      expect(api.pricingFormulaConfig.addFreightRate).not.toHaveBeenCalled();
    });

    it('surfaces a server 400 (overlap) through showToast', async () => {
      const showToast = vi.fn();
      api.pricingFormulaConfig.addFreightRate.mockRejectedValue(
        new Error('ช่วงความหนาและช่วงจำนวน (ตร.ม.) ซ้อนทับกัน: China หนา 3-7 มม. (1-100 ตร.ม.) กับ 3-7 มม. (1-100 ตร.ม.)'),
      );
      renderCeoSettingsPage(showToast);
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      fireEvent.click(screen.getByRole('button', { name: '+ เพิ่มค่าขนส่ง' }));
      const dialog = await screen.findByRole('dialog', { name: 'เพิ่มค่าขนส่ง' });
      fireEvent.change(within(dialog).getByLabelText('ประเทศต้นทาง'), { target: { value: 'CN' } });
      fireEvent.change(within(dialog).getByLabelText('ความหนาตั้งแต่ (มม.)'), { target: { value: '3' } });
      fireEvent.change(within(dialog).getByLabelText('ถึง (<) (มม.)'), { target: { value: '7' } });
      fireEvent.change(within(dialog).getByLabelText('จำนวนตั้งแต่ (ตร.ม.)'), { target: { value: '1' } });
      fireEvent.change(within(dialog).getByLabelText('ค่าขนส่ง (บาท)'), { target: { value: '60000' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'เพิ่มค่าขนส่ง' }));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ซ้อนทับกัน')));
    });
  });

  // Issue #436, commit 4: freight-row DELETE. Same read-only-matrix-only placement as add.
  // sampleFormulaConfig's single populated cell (China, freightRateId 1) is the row under test in
  // every case below -- its delete control's accessible name is matched by PREFIX regex rather
  // than the literal en-dash-bearing band labels, so a transcription slip in a dash character
  // cannot make these tests pass or fail for the wrong reason.
  describe('freight-row delete (issue #436)', () => {
    async function openDeleteConfirm() {
      const deleteButton = await screen.findByRole('button', { name: /^ลบค่าขนส่ง จีน/ });
      fireEvent.click(deleteButton);
      return screen.findByRole('dialog', { name: 'ยืนยันการลบค่าขนส่ง' });
    }

    it('renders a delete control on a populated cell; clicking it opens a confirmation naming that row', async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      const dialog = await openDeleteConfirm();
      expect(within(dialog).getByText(/จีน/)).not.toBeNull();
      expect(within(dialog).getByText(/60,000\.00 บาท/)).not.toBeNull();
    });

    it("confirming calls deleteFreightRate with that row's freightRateId exactly once", async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      const dialog = await openDeleteConfirm();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ลบค่าขนส่ง' }));

      await waitFor(() => expect(api.pricingFormulaConfig.deleteFreightRate).toHaveBeenCalledWith(1));
      expect(api.pricingFormulaConfig.deleteFreightRate).toHaveBeenCalledTimes(1);
    });

    it('cancelling the confirmation calls nothing', async () => {
      renderCeoSettingsPage();
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      const dialog = await openDeleteConfirm();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันการลบค่าขนส่ง' })).toBeNull());
      expect(api.pricingFormulaConfig.deleteFreightRate).not.toHaveBeenCalled();
    });

    it('surfaces a server 400 (interior gap) through showToast', async () => {
      const showToast = vi.fn();
      api.pricingFormulaConfig.deleteFreightRate.mockRejectedValue(new Error(
        'ลบไม่ได้: จะทำให้ช่วงจำนวน (ตร.ม.) ขาดตอนตรงกลาง — China หนา 3-7 มม. ช่วง 1-100 ตร.ม. ลบได้เฉพาะช่วงบนสุดหรือล่างสุด',
      ));
      renderCeoSettingsPage(showToast);
      await screen.findByText('สูตรคำนวณราคาขาย (ดีล)');

      const dialog = await openDeleteConfirm();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ลบค่าขนส่ง' }));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ขาดตอนตรงกลาง')));
    });
  });

  // The "ตัวคูณราคาตั้งประมาณการ" panel and its five tests were removed on 2026-08-10 along with
  // the ราคาตั้ง (ประมาณการ) estimate the multiplier fed. `api.dealEstimateMarkup` no longer
  // exists on either hrApi or mockApi, so there is nothing left to mock or assert here.
});
