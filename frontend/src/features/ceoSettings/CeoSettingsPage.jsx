import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useFieldArray } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

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
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="config-edit-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึกเวอร์ชันใหม่'}
          </Button>
        </>
      }
    >
      <SafeForm id="config-edit-form" onSubmit={handleSubmit(submit)} noValidate>
        <div className="grid gap-3">
          {CONFIG_FIELDS.map(({ key, label, suffix }) => (
            <FormField key={key} label={label} htmlFor={`cfg-${key}`} error={errors[key]?.message}>
              {/*
                FormField's automatic aria-invalid/aria-describedby wiring
                only augments a DIRECT child whose id matches htmlFor — the
                input here is nested inside a wrapper div (to sit next to
                the unit suffix), so it's wired by hand instead, same as
                FormField's other manual-wiring callers.
              */}
              <div className="flex items-center gap-1.5">
                <input
                  id={`cfg-${key}`}
                  type="number" step="0.01" min="0"
                  className="flex-1"
                  aria-invalid={errors[key] ? true : undefined}
                  aria-describedby={errors[key] ? fieldErrorId(`cfg-${key}`) : undefined}
                  {...register(key)}
                />
                <span className="text-text-muted text-xs min-w-[70px]">{suffix}</span>
              </div>
            </FormField>
          ))}
        </div>
      </SafeForm>
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
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="formula-config-edit-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'บันทึกเวอร์ชันใหม่'}
          </Button>
        </>
      }
    >
      <SafeForm id="formula-config-edit-form" onSubmit={handleSubmit(submit)} noValidate>
        <div className="grid gap-5">
          <section>
            <h3 className="m-0 mb-2 text-sm font-bold">ค่าคงที่ในสูตร</h3>
            <div className="grid gap-3">
              {FORMULA_SCALAR_FIELDS.map(({ key, label, hint }) => (
                <FormField key={key} label={label} htmlFor={`formula-${key}`} hint={hint} error={errors[key]?.message}>
                  <div className="flex items-center gap-1.5">
                    <input
                      id={`formula-${key}`}
                      type="number" step="0.0001"
                      className="flex-1"
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
            <h3 className="m-0 mb-1 text-sm font-bold">ค่าขนส่ง (THB ต่อรอบขนส่งจากโรงงาน)</h3>
            <p className="m-0 mb-2 text-2xs text-text-muted">
              ช่อง &quot;— ว่าง&quot; หมายถึงไม่กำหนดค่าขนส่งไว้ในตารางของ CEO (ไม่ใช่ 0 บาท) — แก้ไขได้เฉพาะค่าที่มีอยู่แล้วในหน้านี้
              เพิ่ม/ลบรายการค่าขนส่งได้จากตารางในหน้าตั้งค่า (ไม่ใช่จากหน้าต่างนี้)
            </p>
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-xs">
                <thead>
                  <tr className="bg-surface-muted">
                    <th className="px-2.5 py-1.5 text-left">ประเทศ</th>
                    <th className="px-2.5 py-1.5 text-left">ความหนา</th>
                    {matrix.qtyBands.map((band) => (
                      <th key={matrix.bandKey(band.min, band.max)} className="px-2.5 py-1.5 text-left whitespace-nowrap">
                        {qtyBandLabel(band.min, band.max)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {matrix.countries.flatMap((country) => matrix.thicknessBands.map((thickness) => (
                    <tr key={`${country}|${matrix.bandKey(thickness.min, thickness.max)}`} className="border-b border-surface-subtle">
                      <td className="px-2.5 py-1.5 font-semibold">{country}</td>
                      <td className="px-2.5 py-1.5 text-text-muted">{thicknessBandLabel(thickness.min, thickness.max)}</td>
                      {matrix.qtyBands.map((qty) => {
                        const cellKey = `${country}|${matrix.bandKey(thickness.min, thickness.max)}|${matrix.bandKey(qty.min, qty.max)}`;
                        const rate = matrix.byCell.get(cellKey);
                        if (!rate) {
                          return (
                            <td key={cellKey} className="px-2.5 py-1.5 text-text-muted italic">
                              — ว่าง
                            </td>
                          );
                        }
                        const fieldName = `freight_${rate.freightRateId}`;
                        return (
                          <td key={cellKey} className="px-2.5 py-1.5">
                            <input
                              type="number" step="1" min="0"
                              className="w-[90px]"
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
            <h3 className="m-0 mb-2 text-sm font-bold">อัตราภาษีนำเข้าตามประเภทสินค้า (T)</h3>
            <div className="grid gap-2">
              {dutyFieldArray.fields.map((field, index) => (
                <div key={field.id} className="flex gap-2 items-start">
                  <FormField label="รหัสประเภท" htmlFor={`duty-type-${field.id}`} error={errors.dutyRates?.[index]?.productType?.message}>
                    <input id={`duty-type-${field.id}`} className="w-[140px]" {...register(`dutyRates.${index}.productType`)} />
                  </FormField>
                  <FormField label="ชื่อ (ไทย)" htmlFor={`duty-label-${field.id}`} error={errors.dutyRates?.[index]?.productLabel?.message}>
                    <input id={`duty-label-${field.id}`} className="w-[160px]" {...register(`dutyRates.${index}.productLabel`)} />
                  </FormField>
                  <FormField label="ภาษี %" htmlFor={`duty-pct-${field.id}`} error={errors.dutyRates?.[index]?.dutyPct?.message}>
                    <input id={`duty-pct-${field.id}`} type="number" step="0.01" className="w-[90px]" {...register(`dutyRates.${index}.dutyPct`)} />
                  </FormField>
                  <Button type="button" variant="secondary" className="mt-[22px] text-2xs px-2 py-[3px]"
                    onClick={() => dutyFieldArray.remove(index)}>
                    ลบ
                  </Button>
                </div>
              ))}
              {typeof errors.dutyRates?.message === 'string' && (
                <p role="alert" className="m-0 text-danger text-2xs font-semibold">{errors.dutyRates.message}</p>
              )}
              <Button type="button" variant="secondary" className="text-xs self-start"
                onClick={() => dutyFieldArray.append({ productType: '', productLabel: '', dutyPct: '0' })}>
                + เพิ่มประเภทสินค้า
              </Button>
            </div>
          </section>

          <section>
            <h3 className="m-0 mb-2 text-sm font-bold">ค่าธรรมเนียมพิธีการศุลกากร (S)</h3>
            <div className="grid gap-2">
              {clearanceFieldArray.fields.map((field, index) => (
                <div key={field.id} className="flex gap-2 items-start">
                  <FormField label="ตร.ม. ตั้งแต่" htmlFor={`clr-min-${field.id}`} error={errors.clearanceFees?.[index]?.qtyMinSqm?.message}>
                    <input id={`clr-min-${field.id}`} type="number" step="1" className="w-[90px]" {...register(`clearanceFees.${index}.qtyMinSqm`)} />
                  </FormField>
                  <FormField label="ถึง (เว้นว่าง = ไม่จำกัด)" htmlFor={`clr-max-${field.id}`} error={errors.clearanceFees?.[index]?.qtyMaxSqm?.message}>
                    <input id={`clr-max-${field.id}`} type="number" step="1" className="w-[90px]" {...register(`clearanceFees.${index}.qtyMaxSqm`)} />
                  </FormField>
                  <FormField label="ค่าธรรมเนียม (บาท)" htmlFor={`clr-amt-${field.id}`} error={errors.clearanceFees?.[index]?.amountThb?.message}>
                    <input id={`clr-amt-${field.id}`} type="number" step="1" className="w-[110px]" {...register(`clearanceFees.${index}.amountThb`)} />
                  </FormField>
                  <Button type="button" variant="secondary" className="mt-[22px] text-2xs px-2 py-[3px]"
                    onClick={() => clearanceFieldArray.remove(index)}>
                    ลบ
                  </Button>
                </div>
              ))}
              {typeof errors.clearanceFees?.message === 'string' && (
                <p role="alert" className="m-0 text-danger text-2xs font-semibold">{errors.clearanceFees.message}</p>
              )}
              <Button type="button" variant="secondary" className="text-xs self-start"
                onClick={() => clearanceFieldArray.append({ qtyMinSqm: '0', qtyMaxSqm: '', amountThb: '0' })}>
                + เพิ่มช่วง
              </Button>
            </div>
          </section>
        </div>
      </SafeForm>
    </Modal>
  );
}

// ── Freight-row add (issue #436, PR #455) ───────────────────────────────────────────────────────
// Lives on the READ-ONLY matrix panel, not FormulaConfigEditModal above — every freight add/delete
// reissues EVERY freightRateId (a whole new config version), and that modal's own fields are named
// `freight_${rate.freightRateId}`. Opening the amount-edit modal at the same moment a freightRateId
// changes underneath it would silently orphan its form state.
const addFreightRateSchema = z.object({
  originCountry: z.string().trim().min(1, 'กรุณากรอกประเทศต้นทาง'),
  thicknessMinMm: numberFieldSchema,
  thicknessMaxMm: numberFieldSchema,
  qtyMinSqm: numberFieldSchema,
  // Blank = open-ended top band (qty_max_sqm IS NULL), same "blank is meaningful, not rejected"
  // idiom as clearanceFeeRowSchema above -- must send null, never '' and never coerced to 0.
  qtyMaxSqm: z.string().refine((value) => value === '' || !Number.isNaN(Number(value)), 'กรอกตัวเลข หรือเว้นว่างไว้สำหรับไม่จำกัด'),
  amountThb: numberFieldSchema,
}).refine(
  (v) => Number(v.thicknessMinMm) < Number(v.thicknessMaxMm),
  { message: 'ช่วงความหนาต้องเรียงจากน้อยไปมาก (ตั้งแต่ < ถึง)', path: ['thicknessMaxMm'] },
).refine(
  (v) => v.qtyMaxSqm === '' || Number(v.qtyMinSqm) < Number(v.qtyMaxSqm),
  { message: 'ช่วงจำนวนต้องเรียงจากน้อยไปมาก (ตั้งแต่ < ถึง)', path: ['qtyMaxSqm'] },
);

/**
 * `prefill` seeds the form from a clicked blank cell's coordinates (country + both bands) so the
 * CEO only has to type the amount; the header's own "+ เพิ่มค่าขนส่ง" button opens this with an
 * empty `prefill` instead. Every field stays editable either way — a misclicked cell is not a
 * dead end.
 *
 * Client-side validation covers only what is LOCAL and unambiguous: required fields, non-negative,
 * and min < max on each axis (both via addFreightRateSchema above). The overlap rule
 * (PricingFormulaConfigController#validate, run against the WHOLE resulting matrix, not just this
 * row) is deliberately NOT reimplemented here -- mirroring it would be a second implementation
 * with nothing guarding it against drifting from the real one. Its 400 surfaces verbatim through
 * the existing showToast('error', ...) path below.
 */
function AddFreightRateModal({ prefill, saving, onClose, onSubmit }) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(addFreightRateSchema),
    defaultValues: {
      originCountry: prefill.originCountry ?? '',
      thicknessMinMm: prefill.thicknessMinMm != null ? String(prefill.thicknessMinMm) : '',
      thicknessMaxMm: prefill.thicknessMaxMm != null ? String(prefill.thicknessMaxMm) : '',
      qtyMinSqm: prefill.qtyMinSqm != null ? String(prefill.qtyMinSqm) : '',
      qtyMaxSqm: prefill.qtyMaxSqm != null ? String(prefill.qtyMaxSqm) : '',
      amountThb: '',
    },
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  function submit(values) {
    onSubmit({
      originCountry: values.originCountry.trim(),
      thicknessMinMm: Number(values.thicknessMinMm),
      thicknessMaxMm: Number(values.thicknessMaxMm),
      qtyMinSqm: Number(values.qtyMinSqm),
      qtyMaxSqm: values.qtyMaxSqm === '' ? null : Number(values.qtyMaxSqm),
      amountThb: Number(values.amountThb),
    });
  }

  return (
    <Modal
      title="เพิ่มค่าขนส่ง"
      subtitle="(จะบันทึกเป็นเวอร์ชันใหม่ของสูตรคำนวณราคาขาย)"
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="add-freight-rate-form" variant="primary" disabled={saving}>
            {saving ? 'กำลังบันทึก…' : 'เพิ่มค่าขนส่ง'}
          </Button>
        </>
      }
    >
      <SafeForm id="add-freight-rate-form" onSubmit={handleSubmit(submit)} noValidate>
        <div className="grid gap-3">
          <FormField label="ประเทศต้นทาง" htmlFor="freight-add-country" error={errors.originCountry?.message}>
            <input
              id="freight-add-country"
              aria-invalid={errors.originCountry ? true : undefined}
              aria-describedby={errors.originCountry ? fieldErrorId('freight-add-country') : undefined}
              {...register('originCountry')}
            />
          </FormField>
          <div className="grid gap-3 md:grid-cols-2">
            <FormField label="ความหนาตั้งแต่ (มม.)" htmlFor="freight-add-thickness-min" error={errors.thicknessMinMm?.message}>
              <input
                id="freight-add-thickness-min" type="number" step="0.01" min="0"
                aria-invalid={errors.thicknessMinMm ? true : undefined}
                aria-describedby={errors.thicknessMinMm ? fieldErrorId('freight-add-thickness-min') : undefined}
                {...register('thicknessMinMm')}
              />
            </FormField>
            <FormField label="ถึง (<) (มม.)" htmlFor="freight-add-thickness-max" error={errors.thicknessMaxMm?.message}>
              <input
                id="freight-add-thickness-max" type="number" step="0.01" min="0"
                aria-invalid={errors.thicknessMaxMm ? true : undefined}
                aria-describedby={errors.thicknessMaxMm ? fieldErrorId('freight-add-thickness-max') : undefined}
                {...register('thicknessMaxMm')}
              />
            </FormField>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <FormField label="จำนวนตั้งแต่ (ตร.ม.)" htmlFor="freight-add-qty-min" error={errors.qtyMinSqm?.message}>
              <input
                id="freight-add-qty-min" type="number" step="1" min="0"
                aria-invalid={errors.qtyMinSqm ? true : undefined}
                aria-describedby={errors.qtyMinSqm ? fieldErrorId('freight-add-qty-min') : undefined}
                {...register('qtyMinSqm')}
              />
            </FormField>
            <FormField
              label="ถึง (<) (ตร.ม.)" htmlFor="freight-add-qty-max" error={errors.qtyMaxSqm?.message}
              hint="เว้นว่าง = ไม่จำกัด"
            >
              <input
                id="freight-add-qty-max" type="number" step="1" min="0"
                aria-invalid={errors.qtyMaxSqm ? true : undefined}
                aria-describedby={errors.qtyMaxSqm ? fieldErrorId('freight-add-qty-max') : undefined}
                {...register('qtyMaxSqm')}
              />
            </FormField>
          </div>
          <FormField label="ค่าขนส่ง (บาท)" htmlFor="freight-add-amount" error={errors.amountThb?.message}>
            <input
              id="freight-add-amount" type="number" step="1" min="0"
              aria-invalid={errors.amountThb ? true : undefined}
              aria-describedby={errors.amountThb ? fieldErrorId('freight-add-amount') : undefined}
              {...register('amountThb')}
            />
          </FormField>
          <p className="m-0 text-2xs text-text-muted">
            ช่วงเป็นแบบ [ตั้งแต่, ถึง) — รวมค่าตั้งแต่ แต่ไม่รวมค่าถึง เช่น 1–&lt;101 มม. ครอบคลุม 1 ถึง 100.99 มม.
          </p>
        </div>
      </SafeForm>
    </Modal>
  );
}

/**
 * Issue #436: freight-row delete confirmation. Deliberately does NOT compute deletability
 * client-side — "only the top or bottom band of a ladder can be removed" lives in
 * validateRemovalLeavesNoInteriorGap (mirrored in mockApi.js from
 * PricingFormulaConfigController), and reimplementing it here would be a second, unguarded
 * copy. The server's Thai 400/404 surfaces verbatim through the existing showToast('error', ...)
 * path if this row turns out not to be an edge row after all.
 */
function DeleteFreightRateModal({ rate, deleting, onClose, onConfirm }) {
  return (
    <Modal
      title="ยืนยันการลบค่าขนส่ง"
      onClose={onClose}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" variant="danger" disabled={deleting} onClick={onConfirm}>
            {deleting ? 'กำลังลบ…' : 'ลบค่าขนส่ง'}
          </Button>
        </>
      }
    >
      <p className="m-0 text-sm">
        ลบค่าขนส่ง <strong>{rate.originCountry}</strong> หนา {thicknessBandLabel(rate.thicknessMinMm, rate.thicknessMaxMm)}{' '}
        ช่วง {qtyBandLabel(rate.qtyMinSqm, rate.qtyMaxSqm)} จำนวน <strong>{moneyDisplay(rate.amountThb)} บาท</strong> ใช่หรือไม่?
      </p>
      <p className="m-0 mt-2 text-2xs text-text-muted">
        ลบได้เฉพาะช่วงบนสุดหรือล่างสุดของแต่ละช่วงความหนา/จำนวนเท่านั้น — หากลบช่วงกลางไม่ได้ ระบบจะแจ้งเหตุผลให้ทราบ
      </p>
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

  // Issue #436: freight-row add. null = closed; an object (possibly {}) = open, with the object's
  // fields prefilling the form when opened from a specific blank cell.
  const [addFreightPrefill, setAddFreightPrefill] = useState(null);
  // Issue #436: freight-row delete confirmation. null = closed; the freight row object = open.
  const [deletingFreightRate, setDeletingFreightRate] = useState(null);

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

  // Issue #436: adds one freight row as a new config version. setQueryData (not
  // invalidateQueries) because the response already IS the whole new config — refetching would
  // just be a race against the same data this response already carries.
  const addFreightRateMutation = useMutation({
    mutationFn: (payload) => api.pricingFormulaConfig.addFreightRate(payload),
    onSuccess: (data) => {
      queryClient.setQueryData(queryKeys.pricingFormulaConfig(), data.formulaConfig);
      showToast('success', 'เพิ่มค่าขนส่งแล้ว');
      setAddFreightPrefill(null);
    },
    onError: (e) => showToast('error', e.message || 'เพิ่มค่าขนส่งไม่สำเร็จ'),
  });

  // Issue #436: deletes one freight row as a new config version. Remember every freightRateId has
  // changed after this — setQueryData (not invalidateQueries), same reasoning as add above.
  const deleteFreightRateMutation = useMutation({
    mutationFn: (freightRateId) => api.pricingFormulaConfig.deleteFreightRate(freightRateId),
    onSuccess: (data) => {
      queryClient.setQueryData(queryKeys.pricingFormulaConfig(), data.formulaConfig);
      showToast('success', 'ลบค่าขนส่งแล้ว');
      setDeletingFreightRate(null);
    },
    onError: (e) => showToast('error', e.message || 'ลบค่าขนส่งไม่สำเร็จ'),
  });

  if (loading) return <div className="p-10 text-text-muted">กำลังโหลดการตั้งค่าการคำนวณราคา…</div>;

  return (
    <PageStack>
      <header>
        <h1 className="m-0 mb-1 text-xl font-extrabold">ตั้งค่าการคำนวณราคา</h1>
        <p className="m-0 text-text-muted text-sm">CEO สามารถปรับค่าได้ตลอดเวลา — ระบบเก็บประวัติทุกเวอร์ชัน</p>
      </header>

      {/* FX Rates */}
      <Panel flush title="อัตราแลกเปลี่ยน (1 หน่วย = ? บาท)">
        <div className="px-[18px] py-2 text-2xs text-text-muted border-b border-surface-subtle flex gap-3">
          <span>ดึงอัตโนมัติจากธนาคารแห่งประเทศไทยทุกวัน 18:00 (เวลาไทย)</span>
          <span className="text-text-muted">• ติดต่อผู้ดูแลระบบหากยังไม่เปิดใช้งานการดึงอัตราอัตโนมัติ</span>
        </div>
        <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="bg-surface-muted">
              {['สกุลเงิน', 'อัตรา (THB)', 'วันที่มีผล', 'แหล่งข้อมูล', 'แก้ไขเอง'].map((h) => (
                <th key={h} className="px-4 py-2 text-left font-semibold text-icon-muted border-b border-border">{h}</th>
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
                <tr key={fx.currency} className="border-b border-surface-subtle">
                  <td className="px-4 py-2 font-bold">
                    <code className="bg-surface-subtle px-1.5 py-0.5 rounded-[4px]">{fx.currency}</code>
                  </td>
                  <td className="px-4 py-2">
                    {isEditing ? (
                      <div>
                        <div className="flex gap-1.5 items-center">
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
                            className={`w-[100px] py-1 px-2 rounded-[4px] text-sm border ${fxError ? 'border-danger' : 'border-info-border-strong'}`}
                          />
                          <Button type="button" variant="primary" className="text-xs px-[10px] py-1"
                            disabled={isSavingFx(fx.currency)}
                            onClick={() => saveFxRate(fx.currency)}>
                            {isSavingFx(fx.currency) ? '…' : 'บันทึก'}
                          </Button>
                          <Button type="button" variant="secondary" className="text-xs px-[10px] py-1"
                            onClick={() => {
                              setEditFx((p) => { const n = { ...p }; delete n[fx.currency]; return n; });
                              clearFxError(fx.currency);
                            }}>
                            ยกเลิก
                          </Button>
                        </div>
                        {fxError && (
                          <p id={fxErrorId} role="alert" className="m-0 mt-1 text-danger text-2xs font-semibold">
                            {fxError}
                          </p>
                        )}
                      </div>
                    ) : (
                      <strong>{fx.currency === 'THB' ? '1.0000' : moneyDisplay(fx.rateToThb)}</strong>
                    )}
                  </td>
                  <td className="px-4 py-2 text-text-muted text-xs">{fx.effectiveDate}</td>
                  <td className="px-4 py-2">
                    {isBot
                      ? <span className="bg-info-bg text-info px-2 py-0.5 rounded-[10px] text-2xs font-semibold">ธปท. อัตโนมัติ</span>
                      : <span className="bg-surface-subtle text-text-muted px-2 py-0.5 rounded-[10px] text-2xs">กรอกเอง</span>
                    }
                  </td>
                  <td className="px-4 py-2">
                    {fx.currency !== 'THB' && !isEditing && (
                      <Button type="button" variant="secondary" className="text-2xs px-2 py-[3px]"
                        onClick={() => setEditFx((p) => ({ ...p, [fx.currency]: String(fx.rateToThb) }))}>
                        แก้ไขเอง
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>
      </Panel>

      {/* Price Calc Config */}
      <Panel flush title="สูตรคำนวณราคา (ต่อ ตร.ม.) แต่ละประเทศ">
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr className="bg-surface-muted">
                {['ประเทศ', 'เวอร์ชัน', 'ค่าเรือ/ตร.ม.', 'ประกัน/ตร.ม.', 'โรงงาน→ท่าเรือ', 'ท่าเรือ→โกดัง', 'ภาษีนำเข้า', 'อัตรากำไร', ''].map((h) => (
                  <th key={h} className="px-[14px] py-2 text-left font-semibold text-icon-muted border-b border-border whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {configs.map((cfg) => (
                <tr key={cfg.configId} className="border-b border-surface-subtle">
                  <td className="px-[14px] py-2 font-bold">{cfg.country}</td>
                  <td className="px-[14px] py-2 text-text-muted">เวอร์ชัน {cfg.version}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.freightPerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.insurancePerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.inlandFactoryToPortPerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.inlandPortToWarehousePerSqm)}</td>
                  <td className="px-[14px] py-2">{pctDisplay(cfg.importDutyPct)}</td>
                  <td className="px-[14px] py-2 font-semibold text-success">{pctDisplay(cfg.marginPct)}</td>
                  <td className="px-[14px] py-2">
                    <Button type="button" variant="secondary" className="text-2xs px-2 py-[3px]"
                      onClick={() => openConfigEdit(cfg)}>
                      แก้ไข
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-4 py-2.5 border-t border-surface-subtle text-2xs text-text-muted">
          สูตร: CIF = ค่าสินค้า(THB/ตร.ม.) + ค่าเรือ + ประกัน → ต้นทุน = CIF + ภาษี + ขนส่งภายใน → ราคาขาย = ต้นทุน × (1 + อัตรากำไร)
        </div>
      </Panel>

      {/* BRANCH 1: สูตรคำนวณราคาขาย (ดีล) — sales.pricing_formula_config + its 3 child tables.
          Deliberately below the existing per-country price-calc table above; a distinct config
          from it and does not touch it. */}
      <Panel
        flush
        title="สูตรคำนวณราคาขาย (ดีล)"
        actions={formulaConfig && (
          <Button type="button" variant="secondary" className="text-2xs px-2 py-[3px]"
            onClick={() => setEditingFormulaConfig(true)}>
            แก้ไขสูตรคำนวณราคาขาย
          </Button>
        )}
      >
        {formulaConfigQuery.isLoading && (
          <div className="p-[18px] text-text-muted text-xs">กำลังโหลดสูตรคำนวณราคาขาย…</div>
        )}
        {!formulaConfigQuery.isLoading && !formulaConfig && (
          <div className="p-[18px] text-text-muted text-xs">ไม่พบสูตรคำนวณราคาขาย</div>
        )}

        {formulaConfig && (
          <>
            <div className="px-[18px] py-2 text-2xs text-text-muted border-b border-surface-subtle flex gap-3">
              <span>เวอร์ชัน {formulaConfig.version}</span>
              <span>• มีผลตั้งแต่ {formulaConfig.effectiveFrom}</span>
            </div>

            <div className="px-[18px] py-3">
              <h3 className="m-0 mb-1.5 text-xs font-bold text-icon-muted">ค่าคงที่ในสูตร</h3>
              <div className="overflow-x-auto">
                <table className="w-full border-collapse text-xs">
                  <tbody>
                    <tr>
                      <td className="px-2.5 py-1 text-text-muted">ตัวคูณมูลค่าประกันภัย (ตัวคูณดิบ)</td>
                      <td className="px-2.5 py-1 font-semibold">{multiplierDisplay(formulaConfig.insuranceValueFactor)}</td>
                      <td className="px-2.5 py-1 text-text-muted">อัตราค่าประกันภัย (ตัวคูณดิบ)</td>
                      <td className="px-2.5 py-1 font-semibold">{multiplierDisplay(formulaConfig.insuranceRate)}</td>
                    </tr>
                    <tr>
                      <td className="px-2.5 py-1 text-text-muted">บัฟเฟอร์ประกันภัย B1 (ตัวคูณดิบ)</td>
                      <td className="px-2.5 py-1 font-semibold">{multiplierDisplay(formulaConfig.insuranceBuffer)}</td>
                      <td className="px-2.5 py-1 text-text-muted">บัฟเฟอร์ต้นทุน B2 (ตัวคูณดิบ)</td>
                      <td className="px-2.5 py-1 font-semibold">{multiplierDisplay(formulaConfig.costBuffer)}</td>
                    </tr>
                    <tr>
                      <td className="px-2.5 py-1 text-text-muted">บัฟเฟอร์ราคาขาย B3 (ตัวคูณดิบ)</td>
                      <td className="px-2.5 py-1 font-semibold">{multiplierDisplay(formulaConfig.sellingBuffer)}</td>
                      <td className="px-2.5 py-1 text-text-muted">อัตรากำไรเริ่มต้น</td>
                      <td className="px-2.5 py-1 font-semibold text-success">{pctDisplay(formulaConfig.defaultMarginPct)}</td>
                    </tr>
                    <tr>
                      <td className="px-2.5 py-1 text-text-muted">ปัดราคาขายขึ้นเป็นทวีคูณของ</td>
                      <td className="px-2.5 py-1 font-semibold">{moneyDisplay(formulaConfig.sellingPriceRoundUpTo)} บาท</td>
                      <td />
                      <td />
                    </tr>
                  </tbody>
                </table>
              </div>
              <p className="m-0 mt-1.5 text-[10px] text-text-muted">
                B1/B2/B3 และ 1.15/0.0045 เป็นตัวคูณดิบ (ไม่ใช่ % และไม่ใช่ VAT) — VAT 7% แยกคิดตอนออกใบเสนอราคาลูกค้าเสมอ
              </p>
            </div>

            <div className="pt-0 px-[18px] pb-3">
              <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
                <h3 className="m-0 text-xs font-bold text-icon-muted">ค่าขนส่ง (THB ต่อรอบขนส่งจากโรงงาน)</h3>
                <Button
                  type="button"
                  variant="secondary"
                  className="text-2xs px-2 py-[3px]"
                  onClick={() => setAddFreightPrefill({})}
                >
                  + เพิ่มค่าขนส่ง
                </Button>
              </div>
              <p className="m-0 mb-1.5 text-[10px] text-text-muted">
                &quot;— ว่าง&quot; หมายถึงไม่กำหนดค่าไว้ในตารางของ CEO (ไม่ใช่ 0 บาท) — กดปุ่ม + ในช่องว่างเพื่อเพิ่มค่าขนส่งช่องนั้นโดยตรง
              </p>
              {(() => {
                const matrix = buildFreightMatrixDims(formulaConfig.freightRates);
                return (
                  <div className="overflow-x-auto">
                    <table className="w-full border-collapse text-xs">
                      <thead>
                        <tr className="bg-surface-muted">
                          <th className="px-2.5 py-1.5 text-left">ประเทศ</th>
                          <th className="px-2.5 py-1.5 text-left">ความหนา</th>
                          {matrix.qtyBands.map((band) => (
                            <th key={matrix.bandKey(band.min, band.max)} className="px-2.5 py-1.5 text-left whitespace-nowrap">
                              {qtyBandLabel(band.min, band.max)}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {matrix.countries.flatMap((country) => matrix.thicknessBands.map((thickness) => (
                          <tr key={`${country}|${matrix.bandKey(thickness.min, thickness.max)}`} className="border-b border-surface-subtle">
                            <td className="px-2.5 py-1.5 font-semibold">{country}</td>
                            <td className="px-2.5 py-1.5 text-text-muted">{thicknessBandLabel(thickness.min, thickness.max)}</td>
                            {matrix.qtyBands.map((qty) => {
                              const cellKey = `${country}|${matrix.bandKey(thickness.min, thickness.max)}|${matrix.bandKey(qty.min, qty.max)}`;
                              const rate = matrix.byCell.get(cellKey);
                              const cellLabel = `${country} หนา ${thicknessBandLabel(thickness.min, thickness.max)} ช่วง ${qtyBandLabel(qty.min, qty.max)}`;
                              return (
                                <td key={cellKey} className="px-2.5 py-1.5">
                                  {rate ? (
                                    <span className="flex items-center gap-1.5 whitespace-nowrap">
                                      <span>{moneyDisplay(rate.amountThb)}</span>
                                      {/* Always visible, not hover-only — hover does not exist on
                                          touch. Deletability (edge rows only) is NOT computed here;
                                          see validateRemovalLeavesNoInteriorGap's mirror in
                                          mockApi.js and the confirm modal's own comment for why. */}
                                      <Button
                                        type="button"
                                        variant="secondary"
                                        className="px-1 py-0 text-2xs leading-none text-danger"
                                        aria-label={`ลบค่าขนส่ง ${cellLabel}`}
                                        onClick={() => setDeletingFreightRate(rate)}
                                      >
                                        ×
                                      </Button>
                                    </span>
                                  ) : (
                                    <Button
                                      type="button"
                                      variant="secondary"
                                      className="text-2xs px-1.5 py-[1px] text-text-muted italic"
                                      aria-label={`เพิ่มค่าขนส่ง ${cellLabel}`}
                                      onClick={() => setAddFreightPrefill({
                                        originCountry: country,
                                        thicknessMinMm: thickness.min,
                                        thicknessMaxMm: thickness.max,
                                        qtyMinSqm: qty.min,
                                        qtyMaxSqm: qty.max,
                                      })}
                                    >
                                      + ว่าง
                                    </Button>
                                  )}
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

            <div className="pt-0 px-[18px] pb-3 flex gap-6 flex-wrap">
              <div>
                <h3 className="m-0 mb-1.5 text-xs font-bold text-icon-muted">อัตราภาษีนำเข้า (T)</h3>
                <table className="border-collapse text-xs">
                  <tbody>
                    {formulaConfig.dutyRates.map((duty) => (
                      <tr key={duty.dutyRateId}>
                        <td className="px-2.5 py-1 text-text-muted">{duty.productLabel} ({duty.productType})</td>
                        <td className="px-2.5 py-1 font-semibold">{pctDisplay(duty.dutyPct)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div>
                <h3 className="m-0 mb-1.5 text-xs font-bold text-icon-muted">ค่าธรรมเนียมพิธีการศุลกากร (S)</h3>
                <table className="border-collapse text-xs">
                  <tbody>
                    {formulaConfig.clearanceFees.map((fee) => (
                      <tr key={fee.clearanceFeeId}>
                        <td className="px-2.5 py-1 text-text-muted">{qtyBandLabel(fee.qtyMinSqm, fee.qtyMaxSqm)}</td>
                        <td className="px-2.5 py-1 font-semibold">{moneyDisplay(fee.amountThb)} บาท</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        )}
      </Panel>

      {/* The "ตัวคูณราคาตั้งประมาณการ (หน้าสร้างดีล)" panel stood here until 2026-08-10. It set a
          coarse display multiplier that the deal-create modal applied on top of the raw catalog
          price to show a "ราคาตั้ง (ประมาณการ)". That estimate was removed on the owner's
          instruction after UAT — reps read its output as a selling price when the real one only
          comes out of the pricing-request → CEO costing chain — so this control had nothing left
          to configure. The deal-create modal now shows the catalog price in its own currency plus
          a straight FX conversion, with no multiplier anywhere. "สูตรคำนวณราคา" above is
          untouched: that section IS the margin policy and always was a separate thing. */}

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

      {/* Issue #436: Add Freight Rate Modal — read-only matrix panel only, see the block comment
          on addFreightRateSchema for why this never lives inside FormulaConfigEditModal. */}
      {addFreightPrefill && (
        <AddFreightRateModal
          prefill={addFreightPrefill}
          saving={addFreightRateMutation.isPending}
          onClose={() => setAddFreightPrefill(null)}
          onSubmit={(payload) => addFreightRateMutation.mutate(payload)}
        />
      )}

      {/* Issue #436: Delete Freight Rate confirmation — same read-only-matrix-only placement as
          the add modal above. */}
      {deletingFreightRate && (
        <DeleteFreightRateModal
          rate={deletingFreightRate}
          deleting={deleteFreightRateMutation.isPending}
          onClose={() => setDeletingFreightRate(null)}
          onConfirm={() => deleteFreightRateMutation.mutate(deletingFreightRate.freightRateId)}
        />
      )}
    </PageStack>
  );
}
