import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useFieldArray } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';

function pctDisplay(val) {
  return val != null ? `${(Number(val) * 100).toFixed(2)}%` : '-';
}

function moneyDisplay(val) {
  return val != null ? Number(val).toLocaleString('th-TH', { minimumFractionDigits: 2 }) : '-';
}

// BRANCH 1 pricing-formula-config helpers. multiplierDisplay is for the RAW MULTIPLIER fields
// (insuranceValueFactor/insuranceRate/the three buffers) -- these are NOT percentages, so unlike
// pctDisplay above they must never be scaled by 100. Kept as a visually distinct helper on
// purpose, so a future edit can't accidentally reuse pctDisplay on a multiplier field.
function multiplierDisplay(val) {
  return val != null ? Number(val).toString() : '-';
}

// Bands are HALF-OPEN [min, max) -- min inclusive, max EXCLUSIVE, null max = +infinity (see
// V109__pricing_formula_config.sql's BAND CONVENTION note). "min-max" would read as max being
// included, which is wrong once max is exclusive -- e.g. a [101,451) band must not look like it
// stops at 451 sqm. "<" makes the exclusive boundary explicit; "≥" labels the open-ended top band.
function qtyBandLabel(min, max) {
  return max == null ? `≥${min} ตร.ม.` : `${min} – <${max} ตร.ม.`;
}

function thicknessBandLabel(min, max) {
  return `${min} – <${max} มม.`;
}

// UX-03 slice 3: every pricing input must be a real, non-negative number.
// Before this, an empty field silently became Number('') === 0 on save —
// a blank freight/margin field would zero out that input and under-price
// every deal for the country, with no warning. Do not weaken this to allow
// blank values; do not change the >= 0 bound without an explicit pricing
// decision.
const numberFieldSchema = z
  .string()
  .refine((value) => value !== '' && !Number.isNaN(Number(value)) && Number(value) >= 0, 'กรุณากรอกตัวเลขที่ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)');

// BRANCH 1: a percent-displayed field (defaultMarginPct, dutyPct) additionally cannot exceed
// 100% -- the backend stores these as a 0..1 fraction, so an edited display value above 100 can
// never round-trip back into a valid fraction.
const percentFieldSchema = numberFieldSchema.refine((value) => Number(value) <= 100, 'กรอกได้ไม่เกิน 100%');

// sellingPriceRoundUpTo is the RoundUp() step -- must be strictly positive, or every selling
// price calculation downstream (branches 3-5) divides by zero.
const positiveNumberFieldSchema = numberFieldSchema.refine((value) => Number(value) > 0, 'ต้องมากกว่า 0');

const configFormSchema = z.object({
  freightPerSqm: numberFieldSchema,
  insurancePerSqm: numberFieldSchema,
  inlandFactoryToPortPerSqm: numberFieldSchema,
  inlandPortToWarehousePerSqm: numberFieldSchema,
  importDutyPct: numberFieldSchema,
  marginPct: numberFieldSchema,
});

const CONFIG_FIELDS = [
  { key: 'freightPerSqm', label: 'ค่าขนส่งทางเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
  { key: 'insurancePerSqm', label: 'ค่าประกันภัย (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
  { key: 'inlandFactoryToPortPerSqm', label: 'ขนส่ง โรงงาน→ท่าเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
  { key: 'inlandPortToWarehousePerSqm', label: 'ขนส่ง ท่าเรือ→โกดัง (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
  { key: 'importDutyPct', label: 'อัตราภาษีนำเข้า (%)', suffix: '%' },
  { key: 'marginPct', label: 'อัตรากำไร (%)', suffix: '%' },
];

// UX-08: real dialog (role="dialog", aria-modal, focus trap, Escape,
// focus-restore) via the shared Modal, instead of the old hand-rolled
// position:fixed overlay. Form state now lives here (react-hook-form +
// zod), separate from CeoSettingsPage so the parent only owns the mutation.
function ConfigEditModal({ config, saving, onClose, onSubmit }) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(configFormSchema),
    defaultValues: {
      freightPerSqm: String(config.freightPerSqm),
      insurancePerSqm: String(config.insurancePerSqm),
      inlandFactoryToPortPerSqm: String(config.inlandFactoryToPortPerSqm),
      inlandPortToWarehousePerSqm: String(config.inlandPortToWarehousePerSqm),
      // NON-NEGOTIABLE (pricing round-trip): stored fraction -> displayed
      // percent. Do not change this scaling.
      importDutyPct: String(Number(config.importDutyPct) * 100),
      marginPct: String(Number(config.marginPct) * 100),
    },
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  function submit(values) {
    const payload = {
      country: config.country,
      freightPerSqm: Number(values.freightPerSqm),
      insurancePerSqm: Number(values.insurancePerSqm),
      inlandFactoryToPortPerSqm: Number(values.inlandFactoryToPortPerSqm),
      inlandPortToWarehousePerSqm: Number(values.inlandPortToWarehousePerSqm),
      // NON-NEGOTIABLE (pricing round-trip): displayed percent -> stored
      // fraction. Do not change this scaling, the payload key names, or
      // api.priceCalcConfigs.update's shape.
      importDutyPct: Number(values.importDutyPct) / 100,
      marginPct: Number(values.marginPct) / 100,
    };
    onSubmit(payload);
  }

  return (
    <Modal
      title={`แก้ไขสูตรราคา — ${config.country}`}
      subtitle="(จะบันทึกเป็นเวอร์ชันใหม่)"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button type="submit" form="config-edit-form" className="primary-button" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึกเวอร์ชันใหม่'}
          </button>
        </>
      }
    >
      <form id="config-edit-form" onSubmit={handleSubmit(submit)} noValidate>
        <div style={{ display: 'grid', gap: 12 }}>
          {CONFIG_FIELDS.map(({ key, label, suffix }) => (
            <FormField key={key} label={label} htmlFor={`cfg-${key}`} error={errors[key]?.message}>
              {/*
                FormField's automatic aria-invalid/aria-describedby wiring
                only augments a DIRECT child whose id matches htmlFor — the
                input here is nested inside a wrapper div (to sit next to
                the unit suffix), so it's wired by hand instead, same as
                FormField's other manual-wiring callers.
              */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  id={`cfg-${key}`}
                  type="number" step="0.01" min="0"
                  style={{ flex: 1 }}
                  aria-invalid={errors[key] ? true : undefined}
                  aria-describedby={errors[key] ? fieldErrorId(`cfg-${key}`) : undefined}
                  {...register(key)}
                />
                <span style={{ color: 'var(--color-text-muted)', fontSize: 12, minWidth: 70 }}>{suffix}</span>
              </div>
            </FormField>
          ))}
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// BRANCH 1 of the sales pricing-formula redesign: config storage + CEO editing UI only.
// This section edits sales.pricing_formula_config + its 3 child tables (freight/duty/clearance)
// via /api/pricing-formula-config. It does NOT calculate a selling price -- that is later
// branches, built on top of this config. See V109__pricing_formula_config.sql for the formula.
// ---------------------------------------------------------------------------

// Scalar fields on the parent config row. `isPercent: true` is the ONLY thing that triggers the
// *100 (display) / /100 (save) round-trip -- every other field here is a RAW MULTIPLIER or a THB
// amount and must be edited/displayed as-is. Get this wrong and every deal is mispriced by 100x.
const FORMULA_SCALAR_FIELDS = [
  { key: 'insuranceValueFactor', label: 'ตัวคูณมูลค่าสินค้าเพื่อประกันภัย', hint: 'ตัวคูณดิบ (ค่าเริ่มต้น 1.15) — ห้ามกรอกเป็น %', schema: numberFieldSchema },
  { key: 'insuranceRate', label: 'อัตราค่าประกันภัย', hint: 'ตัวคูณดิบ (ค่าเริ่มต้น 0.0045) — ห้ามกรอกเป็น %', schema: numberFieldSchema },
  { key: 'insuranceBuffer', label: 'บัฟเฟอร์ต้นทุนประกันภัย (B1)', hint: 'ตัวคูณดิบ (ค่าเริ่มต้น 1.07) — ไม่ใช่ VAT, ห้ามกรอกเป็น %', schema: numberFieldSchema },
  { key: 'costBuffer', label: 'บัฟเฟอร์ต้นทุนรวม (B2)', hint: 'ตัวคูณดิบ (ค่าเริ่มต้น 1.07) — ไม่ใช่ VAT, ห้ามกรอกเป็น %', schema: numberFieldSchema },
  { key: 'sellingBuffer', label: 'บัฟเฟอร์ราคาขาย (B3)', hint: 'ตัวคูณดิบ (ค่าเริ่มต้น 1.07) — ไม่ใช่ VAT, ห้ามกรอกเป็น %', schema: numberFieldSchema },
  { key: 'defaultMarginPct', label: 'อัตรากำไรเริ่มต้น', hint: '% (เก็บในระบบเป็นเศษส่วน)', schema: percentFieldSchema, isPercent: true },
  { key: 'sellingPriceRoundUpTo', label: 'ปัดราคาขายขึ้นเป็นทวีคูณของ (บาท)', hint: 'บาท — ต้องมากกว่า 0', schema: positiveNumberFieldSchema },
];

const dutyRateRowSchema = z.object({
  productType: z.string().trim().min(1, 'กรุณากรอกรหัสประเภทสินค้า'),
  productLabel: z.string().trim().min(1, 'กรุณากรอกชื่อประเภทสินค้า'),
  dutyPct: percentFieldSchema,
});

const clearanceFeeRowSchema = z.object({
  qtyMinSqm: numberFieldSchema,
  // Blank = open-ended top band (qty_max_sqm IS NULL), NOT zero -- distinct from numberFieldSchema
  // which forbids blank, because here blank is a legitimate, meaningful value.
  qtyMaxSqm: z.string().refine((value) => value === '' || !Number.isNaN(Number(value)), 'กรอกตัวเลข หรือเว้นว่างไว้สำหรับไม่จำกัด'),
  amountThb: numberFieldSchema,
});

function buildFormulaFormSchema(config) {
  const shape = {};
  FORMULA_SCALAR_FIELDS.forEach((field) => { shape[field.key] = field.schema; });
  config.freightRates.forEach((rate) => { shape[`freight_${rate.freightRateId}`] = numberFieldSchema; });
  shape.dutyRates = z.array(dutyRateRowSchema).min(1, 'ต้องมีอัตราภาษีอย่างน้อย 1 รายการ');
  shape.clearanceFees = z.array(clearanceFeeRowSchema).min(1, 'ต้องมีค่าธรรมเนียมพิธีการอย่างน้อย 1 รายการ');
  return z.object(shape);
}

function buildFormulaDefaultValues(config) {
  const values = {};
  FORMULA_SCALAR_FIELDS.forEach((field) => {
    const raw = Number(config[field.key]);
    values[field.key] = String(field.isPercent ? raw * 100 : raw);
  });
  config.freightRates.forEach((rate) => {
    values[`freight_${rate.freightRateId}`] = String(rate.amountThb);
  });
  values.dutyRates = config.dutyRates.map((duty) => ({
    productType: duty.productType,
    productLabel: duty.productLabel,
    dutyPct: String(Number(duty.dutyPct) * 100),
  }));
  values.clearanceFees = config.clearanceFees.map((fee) => ({
    qtyMinSqm: String(fee.qtyMinSqm),
    qtyMaxSqm: fee.qtyMaxSqm == null ? '' : String(fee.qtyMaxSqm),
    amountThb: String(fee.amountThb),
  }));
  return values;
}

// Freight is stored as a flat list of (origin_country, thickness band, qty band) rows; this
// derives the matrix shape (countries x thickness bands x qty bands) from whichever rows the
// current config actually has, and a lookup from (country, thickness band, qty band) -> row. A
// combination with no row is a deliberately blank cell (see V109's seed comment) -- it renders
// as a non-editable placeholder, never a zero-amount input, so it can never be silently saved as 0.
function buildFreightMatrixDims(freightRates) {
  const bandKey = (min, max) => `${min}-${max ?? 'inf'}`;
  const countries = [...new Set(freightRates.map((rate) => rate.originCountry))].sort();
  const thicknessBands = [...new Map(freightRates.map((rate) =>
    [bandKey(rate.thicknessMinMm, rate.thicknessMaxMm), { min: rate.thicknessMinMm, max: rate.thicknessMaxMm }])).values()]
    .sort((a, b) => a.min - b.min);
  const qtyBands = [...new Map(freightRates.map((rate) =>
    [bandKey(rate.qtyMinSqm, rate.qtyMaxSqm), { min: rate.qtyMinSqm, max: rate.qtyMaxSqm }])).values()]
    .sort((a, b) => a.min - b.min);
  const byCell = new Map(freightRates.map((rate) =>
    [`${rate.originCountry}|${bandKey(rate.thicknessMinMm, rate.thicknessMaxMm)}|${bandKey(rate.qtyMinSqm, rate.qtyMaxSqm)}`, rate]));
  return { countries, thicknessBands, qtyBands, bandKey, byCell };
}

function FormulaConfigEditModal({ config, saving, onClose, onSubmit }) {
  const schema = buildFormulaFormSchema(config);
  const matrix = buildFreightMatrixDims(config.freightRates);

  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(schema),
    defaultValues: buildFormulaDefaultValues(config),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const dutyFieldArray = useFieldArray({ control, name: 'dutyRates' });
  const clearanceFieldArray = useFieldArray({ control, name: 'clearanceFees' });

  function submit(values) {
    const payload = {};
    FORMULA_SCALAR_FIELDS.forEach((field) => {
      const raw = Number(values[field.key]);
      payload[field.key] = field.isPercent ? raw / 100 : raw;
    });
    payload.freightRates = config.freightRates.map((rate) => ({
      originCountry: rate.originCountry,
      thicknessMinMm: rate.thicknessMinMm,
      thicknessMaxMm: rate.thicknessMaxMm,
      qtyMinSqm: rate.qtyMinSqm,
      qtyMaxSqm: rate.qtyMaxSqm,
      amountThb: Number(values[`freight_${rate.freightRateId}`]),
    }));
    payload.dutyRates = values.dutyRates.map((duty) => ({
      productType: duty.productType.trim(),
      productLabel: duty.productLabel.trim(),
      dutyPct: Number(duty.dutyPct) / 100,
    }));
    payload.clearanceFees = values.clearanceFees.map((fee) => ({
      qtyMinSqm: Number(fee.qtyMinSqm),
      qtyMaxSqm: fee.qtyMaxSqm === '' ? null : Number(fee.qtyMaxSqm),
      amountThb: Number(fee.amountThb),
    }));
    onSubmit(payload);
  }

  return (
    <Modal
      title="แก้ไขสูตรคำนวณราคาขาย (ดีล)"
      subtitle="(จะบันทึกเป็นเวอร์ชันใหม่)"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button type="submit" form="formula-config-edit-form" className="primary-button" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึกเวอร์ชันใหม่'}
          </button>
        </>
      }
    >
      <form id="formula-config-edit-form" onSubmit={handleSubmit(submit)} noValidate>
        <div style={{ display: 'grid', gap: 20 }}>
          <section>
            <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>ค่าคงที่ในสูตร</h3>
            <div style={{ display: 'grid', gap: 12 }}>
              {FORMULA_SCALAR_FIELDS.map(({ key, label, hint }) => (
                <FormField key={key} label={label} htmlFor={`formula-${key}`} hint={hint} error={errors[key]?.message}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <input
                      id={`formula-${key}`}
                      type="number" step="0.0001"
                      style={{ flex: 1 }}
                      aria-invalid={errors[key] ? true : undefined}
                      aria-describedby={errors[key] ? fieldErrorId(`formula-${key}`) : undefined}
                      {...register(key)}
                    />
                  </div>
                </FormField>
              ))}
            </div>
          </section>

          <section>
            <h3 style={{ margin: '0 0 4px', fontSize: 13, fontWeight: 700 }}>ค่าขนส่ง (THB ต่อรอบขนส่งจากโรงงาน)</h3>
            <p style={{ margin: '0 0 8px', fontSize: 11, color: 'var(--color-text-muted)' }}>
              ช่อง &quot;— ว่าง&quot; หมายถึงไม่กำหนดค่าขนส่งไว้ในตารางของ CEO (ไม่ใช่ 0 บาท) และไม่สามารถแก้ไขในหน้านี้ได้
            </p>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ background: 'var(--color-surface-muted)' }}>
                    <th style={{ padding: '6px 10px', textAlign: 'left' }}>ประเทศ</th>
                    <th style={{ padding: '6px 10px', textAlign: 'left' }}>ความหนา</th>
                    {matrix.qtyBands.map((band) => (
                      <th key={matrix.bandKey(band.min, band.max)} style={{ padding: '6px 10px', textAlign: 'left', whiteSpace: 'nowrap' }}>
                        {qtyBandLabel(band.min, band.max)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {matrix.countries.flatMap((country) => matrix.thicknessBands.map((thickness) => (
                    <tr key={`${country}|${matrix.bandKey(thickness.min, thickness.max)}`} style={{ borderBottom: '1px solid var(--color-surface-subtle)' }}>
                      <td style={{ padding: '6px 10px', fontWeight: 600 }}>{country}</td>
                      <td style={{ padding: '6px 10px', color: 'var(--color-text-muted)' }}>{thicknessBandLabel(thickness.min, thickness.max)}</td>
                      {matrix.qtyBands.map((qty) => {
                        const cellKey = `${country}|${matrix.bandKey(thickness.min, thickness.max)}|${matrix.bandKey(qty.min, qty.max)}`;
                        const rate = matrix.byCell.get(cellKey);
                        if (!rate) {
                          return (
                            <td key={cellKey} style={{ padding: '6px 10px', color: 'var(--color-text-muted)', fontStyle: 'italic' }}>
                              — ว่าง
                            </td>
                          );
                        }
                        const fieldName = `freight_${rate.freightRateId}`;
                        return (
                          <td key={cellKey} style={{ padding: '6px 10px' }}>
                            <input
                              type="number" step="1" min="0"
                              style={{ width: 90 }}
                              aria-label={`ค่าขนส่ง ${country} ${thicknessBandLabel(thickness.min, thickness.max)} ${qtyBandLabel(qty.min, qty.max)}`}
                              aria-invalid={errors[fieldName] ? true : undefined}
                              {...register(fieldName)}
                            />
                          </td>
                        );
                      })}
                    </tr>
                  )))}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>อัตราภาษีนำเข้าตามประเภทสินค้า (T)</h3>
            <div style={{ display: 'grid', gap: 8 }}>
              {dutyFieldArray.fields.map((field, index) => (
                <div key={field.id} style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <FormField label="รหัสประเภท" htmlFor={`duty-type-${field.id}`} error={errors.dutyRates?.[index]?.productType?.message}>
                    <input id={`duty-type-${field.id}`} style={{ width: 140 }} {...register(`dutyRates.${index}.productType`)} />
                  </FormField>
                  <FormField label="ชื่อ (ไทย)" htmlFor={`duty-label-${field.id}`} error={errors.dutyRates?.[index]?.productLabel?.message}>
                    <input id={`duty-label-${field.id}`} style={{ width: 160 }} {...register(`dutyRates.${index}.productLabel`)} />
                  </FormField>
                  <FormField label="ภาษี %" htmlFor={`duty-pct-${field.id}`} error={errors.dutyRates?.[index]?.dutyPct?.message}>
                    <input id={`duty-pct-${field.id}`} type="number" step="0.01" style={{ width: 90 }} {...register(`dutyRates.${index}.dutyPct`)} />
                  </FormField>
                  <button type="button" className="secondary-button" style={{ marginTop: 22, fontSize: 11, padding: '3px 8px' }}
                    onClick={() => dutyFieldArray.remove(index)}>
                    ลบ
                  </button>
                </div>
              ))}
              {typeof errors.dutyRates?.message === 'string' && (
                <p role="alert" style={{ margin: 0, color: 'var(--color-danger)', fontSize: 11, fontWeight: 600 }}>{errors.dutyRates.message}</p>
              )}
              <button type="button" className="secondary-button" style={{ fontSize: 12, alignSelf: 'flex-start' }}
                onClick={() => dutyFieldArray.append({ productType: '', productLabel: '', dutyPct: '0' })}>
                + เพิ่มประเภทสินค้า
              </button>
            </div>
          </section>

          <section>
            <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>ค่าธรรมเนียมพิธีการศุลกากร (S)</h3>
            <div style={{ display: 'grid', gap: 8 }}>
              {clearanceFieldArray.fields.map((field, index) => (
                <div key={field.id} style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <FormField label="ตร.ม. ตั้งแต่" htmlFor={`clr-min-${field.id}`} error={errors.clearanceFees?.[index]?.qtyMinSqm?.message}>
                    <input id={`clr-min-${field.id}`} type="number" step="1" style={{ width: 90 }} {...register(`clearanceFees.${index}.qtyMinSqm`)} />
                  </FormField>
                  <FormField label="ถึง (เว้นว่าง = ไม่จำกัด)" htmlFor={`clr-max-${field.id}`} error={errors.clearanceFees?.[index]?.qtyMaxSqm?.message}>
                    <input id={`clr-max-${field.id}`} type="number" step="1" style={{ width: 90 }} {...register(`clearanceFees.${index}.qtyMaxSqm`)} />
                  </FormField>
                  <FormField label="ค่าธรรมเนียม (บาท)" htmlFor={`clr-amt-${field.id}`} error={errors.clearanceFees?.[index]?.amountThb?.message}>
                    <input id={`clr-amt-${field.id}`} type="number" step="1" style={{ width: 110 }} {...register(`clearanceFees.${index}.amountThb`)} />
                  </FormField>
                  <button type="button" className="secondary-button" style={{ marginTop: 22, fontSize: 11, padding: '3px 8px' }}
                    onClick={() => clearanceFieldArray.remove(index)}>
                    ลบ
                  </button>
                </div>
              ))}
              {typeof errors.clearanceFees?.message === 'string' && (
                <p role="alert" style={{ margin: 0, color: 'var(--color-danger)', fontSize: 11, fontWeight: 600 }}>{errors.clearanceFees.message}</p>
              )}
              <button type="button" className="secondary-button" style={{ fontSize: 12, alignSelf: 'flex-start' }}
                onClick={() => clearanceFieldArray.append({ qtyMinSqm: '0', qtyMaxSqm: '', amountThb: '0' })}>
                + เพิ่มช่วง
              </button>
            </div>
          </section>
        </div>
      </form>
    </Modal>
  );
}

export function CeoSettingsPage({ showToast }) {
  const queryClient = useQueryClient();

  // FX rate inline edit: currency → draftRate string
  const [editFx, setEditFx] = useState({});          // currency → string
  // UX-03: per-currency inline validation error, keyed the same way so one
  // row's error can never bleed onto another row.
  const [fxErrors, setFxErrors] = useState({});       // currency → message

  // Config edit modal state
  const [editingConfig, setEditingConfig] = useState(null);  // PriceCalcConfigDto or null

  // BRANCH 1: pricing-formula-config edit modal state
  const [editingFormulaConfig, setEditingFormulaConfig] = useState(false);

  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((response) => response.fxRates ?? []),
  });
  const fxRates = fxRatesQuery.data ?? [];

  const configsQuery = useQuery({
    queryKey: queryKeys.priceCalcConfigs(),
    queryFn: () => api.priceCalcConfigs.list().then((response) => response.configs ?? []),
  });
  const configs = configsQuery.data ?? [];

  // BRANCH 1: kept as its own query, deliberately NOT folded into `loading` below -- a slow or
  // failed /api/pricing-formula-config must not block the pre-existing FX/price-calc sections.
  const formulaConfigQuery = useQuery({
    queryKey: queryKeys.pricingFormulaConfig(),
    queryFn: () => api.pricingFormulaConfig.get().then((response) => response.formulaConfig),
  });
  const formulaConfig = formulaConfigQuery.data ?? null;

  const loading = fxRatesQuery.isLoading || configsQuery.isLoading;

  useEffect(() => {
    if (fxRatesQuery.error) showToast('error', fxRatesQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [fxRatesQuery.error, showToast]);
  useEffect(() => {
    if (configsQuery.error) showToast('error', configsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [configsQuery.error, showToast]);
  useEffect(() => {
    if (formulaConfigQuery.error) showToast('error', formulaConfigQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [formulaConfigQuery.error, showToast]);

  const saveFxRateMutation = useMutation({
    mutationFn: ({ currency, rateToThb }) => api.fxRates.upsert(currency, { rateToThb }),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.fxRates() });
      setEditFx((p) => { const n = { ...p }; delete n[variables.currency]; return n; });
      showToast('success', `อัปเดตอัตรา ${variables.currency} แล้ว`);
    },
    onError: (e) => showToast('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  // A single shared mutation acts on whichever currency row is being saved —
  // this reads the in-flight variables back out to know which row's button
  // should show a busy state (there's no per-row mutation instance).
  function isSavingFx(currency) {
    return saveFxRateMutation.isPending && saveFxRateMutation.variables?.currency === currency;
  }

  function clearFxError(currency) {
    setFxErrors((p) => {
      if (!(currency in p)) return p;
      const n = { ...p };
      delete n[currency];
      return n;
    });
  }

  function saveFxRate(currency) {
    const val = editFx[currency];
    if (!val || isNaN(Number(val)) || Number(val) <= 0) {
      // UX-03: surfaced inline on this row's input (aria-invalid +
      // aria-describedby -> role="alert"), not toast-only.
      setFxErrors((p) => ({ ...p, [currency]: 'กรุณากรอกอัตราแลกเปลี่ยนที่ถูกต้อง' }));
      return;
    }
    clearFxError(currency);
    saveFxRateMutation.mutate({ currency, rateToThb: Number(val) });
  }

  function openConfigEdit(cfg) {
    setEditingConfig(cfg);
  }

  const saveConfigMutation = useMutation({
    mutationFn: (payload) => api.priceCalcConfigs.update(payload),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.priceCalcConfigs() });
      showToast('success', `บันทึกสูตรราคาประเทศ ${variables.country} เป็นเวอร์ชันใหม่แล้ว`);
      setEditingConfig(null);
    },
    onError: (e) => showToast('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  // BRANCH 1: creates a new pricing_formula_config version (never mutates the current one).
  const saveFormulaConfigMutation = useMutation({
    mutationFn: (payload) => api.pricingFormulaConfig.update(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.pricingFormulaConfig() });
      showToast('success', 'บันทึกสูตรคำนวณราคาขายเป็นเวอร์ชันใหม่แล้ว');
      setEditingFormulaConfig(false);
    },
    onError: (e) => showToast('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  if (loading) return <div style={{ padding: 40, color: 'var(--color-text-muted)' }}>กำลังโหลดการตั้งค่าการคำนวณราคา…</div>;

  return (
    <div className="page-stack">
      <header>
        <h1 style={{ margin: '0 0 4px', fontSize: 22, fontWeight: 800 }}>ตั้งค่าการคำนวณราคา</h1>
        <p style={{ margin: 0, color: 'var(--color-text-muted)', fontSize: 13 }}>CEO สามารถปรับค่าได้ตลอดเวลา — ระบบเก็บประวัติทุกเวอร์ชัน</p>
      </header>

      {/* FX Rates */}
      <section className="table-panel">
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-border)' }}>
          <h2>อัตราแลกเปลี่ยน (1 หน่วย = ? บาท)</h2>
        </div>
        <div style={{ padding: '8px 18px', fontSize: 11, color: 'var(--color-text-muted)', borderBottom: '1px solid var(--color-surface-subtle)', display: 'flex', gap: 12 }}>
          <span>ดึงอัตโนมัติจากธนาคารแห่งประเทศไทยทุกวัน 18:00 (เวลาไทย)</span>
          <span style={{ color: 'var(--color-text-muted)' }}>• ติดต่อผู้ดูแลระบบหากยังไม่เปิดใช้งานการดึงอัตราอัตโนมัติ</span>
        </div>
        <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--color-surface-muted)' }}>
              {['สกุลเงิน', 'อัตรา (THB)', 'วันที่มีผล', 'แหล่งข้อมูล', 'แก้ไขเอง'].map((h) => (
                <th key={h} style={{ padding: '8px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--color-icon-muted)', borderBottom: '1px solid var(--color-border)' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {fxRates.map((fx) => {
              const isEditing = editFx[fx.currency] !== undefined;
              const isBot = fx.source === 'BOT';
              const fxError = fxErrors[fx.currency];
              const fxInputId = `fx-rate-${fx.currency}`;
              const fxErrorId = `${fxInputId}-error`;
              return (
                <tr key={fx.currency} style={{ borderBottom: '1px solid var(--color-surface-subtle)' }}>
                  <td style={{ padding: '8px 16px', fontWeight: 700 }}>
                    <code style={{ background: 'var(--color-surface-subtle)', padding: '2px 6px', borderRadius: 4 }}>{fx.currency}</code>
                  </td>
                  <td style={{ padding: '8px 16px' }}>
                    {isEditing ? (
                      <div>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                          <input
                            id={fxInputId}
                            type="number" step="0.0001" min="0"
                            value={editFx[fx.currency]}
                            onChange={(e) => {
                              setEditFx((p) => ({ ...p, [fx.currency]: e.target.value }));
                              clearFxError(fx.currency);
                            }}
                            aria-invalid={fxError ? true : undefined}
                            aria-describedby={fxError ? fxErrorId : undefined}
                            style={{ width: 100, padding: '4px 8px', border: `1px solid ${fxError ? 'var(--color-danger)' : 'var(--color-info-border-strong)'}`, borderRadius: 4, fontSize: 13 }}
                          />
                          <button type="button" className="primary-button"
                            style={{ fontSize: 12, padding: '4px 10px' }}
                            disabled={isSavingFx(fx.currency)}
                            onClick={() => saveFxRate(fx.currency)}>
                            {isSavingFx(fx.currency) ? '…' : 'บันทึก'}
                          </button>
                          <button type="button" className="secondary-button"
                            style={{ fontSize: 12, padding: '4px 10px' }}
                            onClick={() => {
                              setEditFx((p) => { const n = { ...p }; delete n[fx.currency]; return n; });
                              clearFxError(fx.currency);
                            }}>
                            ยกเลิก
                          </button>
                        </div>
                        {fxError && (
                          <p id={fxErrorId} role="alert" style={{ margin: '4px 0 0', color: 'var(--color-danger)', fontSize: 11, fontWeight: 600 }}>
                            {fxError}
                          </p>
                        )}
                      </div>
                    ) : (
                      <strong>{fx.currency === 'THB' ? '1.0000' : moneyDisplay(fx.rateToThb)}</strong>
                    )}
                  </td>
                  <td style={{ padding: '8px 16px', color: 'var(--color-text-muted)', fontSize: 12 }}>{fx.effectiveDate}</td>
                  <td style={{ padding: '8px 16px' }}>
                    {isBot
                      ? <span style={{ background: 'var(--color-info-bg)', color: 'var(--color-info)', padding: '2px 8px', borderRadius: 10, fontSize: 11, fontWeight: 600 }}>ธปท. อัตโนมัติ</span>
                      : <span style={{ background: 'var(--color-surface-subtle)', color: 'var(--color-text-muted)', padding: '2px 8px', borderRadius: 10, fontSize: 11 }}>กรอกเอง</span>
                    }
                  </td>
                  <td style={{ padding: '8px 16px' }}>
                    {fx.currency !== 'THB' && !isEditing && (
                      <button type="button" className="secondary-button"
                        style={{ fontSize: 11, padding: '3px 8px' }}
                        onClick={() => setEditFx((p) => ({ ...p, [fx.currency]: String(fx.rateToThb) }))}>
                        แก้ไขเอง
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>
      </section>

      {/* Price Calc Config */}
      <section className="table-panel">
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-border)' }}>
          <h2>สูตรคำนวณราคา (ต่อ ตร.ม.) แต่ละประเทศ</h2>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ background: 'var(--color-surface-muted)' }}>
                {['ประเทศ', 'เวอร์ชัน', 'ค่าเรือ/ตร.ม.', 'ประกัน/ตร.ม.', 'โรงงาน→ท่าเรือ', 'ท่าเรือ→โกดัง', 'ภาษีนำเข้า', 'อัตรากำไร', ''].map((h) => (
                  <th key={h} style={{ padding: '8px 14px', textAlign: 'left', fontWeight: 600, color: 'var(--color-icon-muted)', borderBottom: '1px solid var(--color-border)', whiteSpace: 'nowrap' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {configs.map((cfg) => (
                <tr key={cfg.configId} style={{ borderBottom: '1px solid var(--color-surface-subtle)' }}>
                  <td style={{ padding: '8px 14px', fontWeight: 700 }}>{cfg.country}</td>
                  <td style={{ padding: '8px 14px', color: 'var(--color-text-muted)' }}>เวอร์ชัน {cfg.version}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.freightPerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.insurancePerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.inlandFactoryToPortPerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.inlandPortToWarehousePerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{pctDisplay(cfg.importDutyPct)}</td>
                  <td style={{ padding: '8px 14px', fontWeight: 600, color: 'var(--color-success)' }}>{pctDisplay(cfg.marginPct)}</td>
                  <td style={{ padding: '8px 14px' }}>
                    <button type="button" className="secondary-button"
                      style={{ fontSize: 11, padding: '3px 8px' }}
                      onClick={() => openConfigEdit(cfg)}>
                      แก้ไข
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ padding: '10px 16px', borderTop: '1px solid var(--color-surface-subtle)', fontSize: 11, color: 'var(--color-text-muted)' }}>
          สูตร: CIF = ค่าสินค้า(THB/ตร.ม.) + ค่าเรือ + ประกัน → ต้นทุน = CIF + ภาษี + ขนส่งภายใน → ราคาขาย = ต้นทุน × (1 + อัตรากำไร)
        </div>
      </section>

      {/* BRANCH 1: สูตรคำนวณราคาขาย (ดีล) — sales.pricing_formula_config + its 3 child tables.
          Deliberately below the existing per-country price-calc table above; a distinct config
          from it and does not touch it. */}
      <section className="table-panel">
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2>สูตรคำนวณราคาขาย (ดีล)</h2>
          {formulaConfig && (
            <button type="button" className="secondary-button" style={{ fontSize: 11, padding: '3px 8px' }}
              onClick={() => setEditingFormulaConfig(true)}>
              แก้ไขสูตรคำนวณราคาขาย
            </button>
          )}
        </div>

        {formulaConfigQuery.isLoading && (
          <div style={{ padding: 18, color: 'var(--color-text-muted)', fontSize: 12 }}>กำลังโหลดสูตรคำนวณราคาขาย…</div>
        )}
        {!formulaConfigQuery.isLoading && !formulaConfig && (
          <div style={{ padding: 18, color: 'var(--color-text-muted)', fontSize: 12 }}>ไม่พบสูตรคำนวณราคาขาย</div>
        )}

        {formulaConfig && (
          <>
            <div style={{ padding: '8px 18px', fontSize: 11, color: 'var(--color-text-muted)', borderBottom: '1px solid var(--color-surface-subtle)', display: 'flex', gap: 12 }}>
              <span>เวอร์ชัน {formulaConfig.version}</span>
              <span>• มีผลตั้งแต่ {formulaConfig.effectiveFrom}</span>
            </div>

            <div style={{ padding: '12px 18px' }}>
              <h3 style={{ margin: '0 0 6px', fontSize: 12, fontWeight: 700, color: 'var(--color-icon-muted)' }}>ค่าคงที่ในสูตร</h3>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                  <tbody>
                    <tr>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>ตัวคูณมูลค่าประกันภัย (ตัวคูณดิบ)</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{multiplierDisplay(formulaConfig.insuranceValueFactor)}</td>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>อัตราค่าประกันภัย (ตัวคูณดิบ)</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{multiplierDisplay(formulaConfig.insuranceRate)}</td>
                    </tr>
                    <tr>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>บัฟเฟอร์ประกันภัย B1 (ตัวคูณดิบ)</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{multiplierDisplay(formulaConfig.insuranceBuffer)}</td>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>บัฟเฟอร์ต้นทุน B2 (ตัวคูณดิบ)</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{multiplierDisplay(formulaConfig.costBuffer)}</td>
                    </tr>
                    <tr>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>บัฟเฟอร์ราคาขาย B3 (ตัวคูณดิบ)</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{multiplierDisplay(formulaConfig.sellingBuffer)}</td>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>อัตรากำไรเริ่มต้น</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600, color: 'var(--color-success)' }}>{pctDisplay(formulaConfig.defaultMarginPct)}</td>
                    </tr>
                    <tr>
                      <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>ปัดราคาขายขึ้นเป็นทวีคูณของ</td>
                      <td style={{ padding: '4px 10px', fontWeight: 600 }}>{moneyDisplay(formulaConfig.sellingPriceRoundUpTo)} บาท</td>
                      <td />
                      <td />
                    </tr>
                  </tbody>
                </table>
              </div>
              <p style={{ margin: '6px 0 0', fontSize: 10, color: 'var(--color-text-muted)' }}>
                B1/B2/B3 และ 1.15/0.0045 เป็นตัวคูณดิบ (ไม่ใช่ % และไม่ใช่ VAT) — VAT 7% แยกคิดตอนออกใบเสนอราคาลูกค้าเสมอ
              </p>
            </div>

            <div style={{ padding: '0 18px 12px' }}>
              <h3 style={{ margin: '0 0 6px', fontSize: 12, fontWeight: 700, color: 'var(--color-icon-muted)' }}>ค่าขนส่ง (THB ต่อรอบขนส่งจากโรงงาน)</h3>
              <p style={{ margin: '0 0 6px', fontSize: 10, color: 'var(--color-text-muted)' }}>
                &quot;— ว่าง&quot; หมายถึงไม่กำหนดค่าไว้ในตารางของ CEO (ไม่ใช่ 0 บาท)
              </p>
              {(() => {
                const matrix = buildFreightMatrixDims(formulaConfig.freightRates);
                return (
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                      <thead>
                        <tr style={{ background: 'var(--color-surface-muted)' }}>
                          <th style={{ padding: '6px 10px', textAlign: 'left' }}>ประเทศ</th>
                          <th style={{ padding: '6px 10px', textAlign: 'left' }}>ความหนา</th>
                          {matrix.qtyBands.map((band) => (
                            <th key={matrix.bandKey(band.min, band.max)} style={{ padding: '6px 10px', textAlign: 'left', whiteSpace: 'nowrap' }}>
                              {qtyBandLabel(band.min, band.max)}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {matrix.countries.flatMap((country) => matrix.thicknessBands.map((thickness) => (
                          <tr key={`${country}|${matrix.bandKey(thickness.min, thickness.max)}`} style={{ borderBottom: '1px solid var(--color-surface-subtle)' }}>
                            <td style={{ padding: '6px 10px', fontWeight: 600 }}>{country}</td>
                            <td style={{ padding: '6px 10px', color: 'var(--color-text-muted)' }}>{thicknessBandLabel(thickness.min, thickness.max)}</td>
                            {matrix.qtyBands.map((qty) => {
                              const cellKey = `${country}|${matrix.bandKey(thickness.min, thickness.max)}|${matrix.bandKey(qty.min, qty.max)}`;
                              const rate = matrix.byCell.get(cellKey);
                              return (
                                <td key={cellKey} style={rate ? { padding: '6px 10px' } : { padding: '6px 10px', color: 'var(--color-text-muted)', fontStyle: 'italic' }}>
                                  {rate ? moneyDisplay(rate.amountThb) : '— ว่าง'}
                                </td>
                              );
                            })}
                          </tr>
                        )))}
                      </tbody>
                    </table>
                  </div>
                );
              })()}
            </div>

            <div style={{ padding: '0 18px 12px', display: 'flex', gap: 24, flexWrap: 'wrap' }}>
              <div>
                <h3 style={{ margin: '0 0 6px', fontSize: 12, fontWeight: 700, color: 'var(--color-icon-muted)' }}>อัตราภาษีนำเข้า (T)</h3>
                <table style={{ borderCollapse: 'collapse', fontSize: 12 }}>
                  <tbody>
                    {formulaConfig.dutyRates.map((duty) => (
                      <tr key={duty.dutyRateId}>
                        <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>{duty.productLabel} ({duty.productType})</td>
                        <td style={{ padding: '4px 10px', fontWeight: 600 }}>{pctDisplay(duty.dutyPct)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div>
                <h3 style={{ margin: '0 0 6px', fontSize: 12, fontWeight: 700, color: 'var(--color-icon-muted)' }}>ค่าธรรมเนียมพิธีการศุลกากร (S)</h3>
                <table style={{ borderCollapse: 'collapse', fontSize: 12 }}>
                  <tbody>
                    {formulaConfig.clearanceFees.map((fee) => (
                      <tr key={fee.clearanceFeeId}>
                        <td style={{ padding: '4px 10px', color: 'var(--color-text-muted)' }}>{qtyBandLabel(fee.qtyMinSqm, fee.qtyMaxSqm)}</td>
                        <td style={{ padding: '4px 10px', fontWeight: 600 }}>{moneyDisplay(fee.amountThb)} บาท</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        )}
      </section>

      {/* Config Edit Modal */}
      {editingConfig && (
        <ConfigEditModal
          config={editingConfig}
          saving={saveConfigMutation.isPending}
          onClose={() => setEditingConfig(null)}
          onSubmit={(payload) => saveConfigMutation.mutate(payload)}
        />
      )}

      {/* BRANCH 1: Formula Config Edit Modal */}
      {editingFormulaConfig && formulaConfig && (
        <FormulaConfigEditModal
          config={formulaConfig}
          saving={saveFormulaConfigMutation.isPending}
          onClose={() => setEditingFormulaConfig(false)}
          onSubmit={(payload) => saveFormulaConfigMutation.mutate(payload)}
        />
      )}
    </div>
  );
}
