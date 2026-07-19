import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
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

// UX-03 slice 3: every pricing input must be a real, non-negative number.
// Before this, an empty field silently became Number('') === 0 on save —
// a blank freight/margin field would zero out that input and under-price
// every deal for the country, with no warning. Do not weaken this to allow
// blank values; do not change the >= 0 bound without an explicit pricing
// decision.
const numberFieldSchema = z
  .string()
  .refine((value) => value !== '' && !Number.isNaN(Number(value)) && Number(value) >= 0, 'กรุณากรอกตัวเลขที่ถูกต้อง (ตั้งแต่ 0 ขึ้นไป)');

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
  { key: 'marginPct', label: 'Margin (%)', suffix: '%' },
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
      title={`แก้ไข config — ${config.country}`}
      subtitle="(จะบันทึกเป็นเวอร์ชันใหม่)"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button type="submit" form="config-edit-form" className="primary-button" disabled={saving}>
            {saving ? 'กำลังบันทึก...' : 'บันทึกเวอร์ชันใหม่'}
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

export function CeoSettingsPage({ showToast }) {
  const queryClient = useQueryClient();

  // FX rate inline edit: currency → draftRate string
  const [editFx, setEditFx] = useState({});          // currency → string
  // UX-03: per-currency inline validation error, keyed the same way so one
  // row's error can never bleed onto another row.
  const [fxErrors, setFxErrors] = useState({});       // currency → message

  // Config edit modal state
  const [editingConfig, setEditingConfig] = useState(null);  // PriceCalcConfigDto or null

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

  const loading = fxRatesQuery.isLoading || configsQuery.isLoading;

  useEffect(() => {
    if (fxRatesQuery.error) showToast('error', fxRatesQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [fxRatesQuery.error, showToast]);
  useEffect(() => {
    if (configsQuery.error) showToast('error', configsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [configsQuery.error, showToast]);

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
  // should show a "..." busy state (there's no per-row mutation instance).
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
      showToast('success', `บันทึก config ประเทศ ${variables.country} (เวอร์ชันใหม่) แล้ว`);
      setEditingConfig(null);
    },
    onError: (e) => showToast('error', e.message || 'บันทึกไม่สำเร็จ'),
  });

  if (loading) return <div style={{ padding: 40, color: 'var(--color-text-muted)' }}>กำลังโหลด...</div>;

  return (
    <div className="page-stack">
      <header>
        <h1 style={{ margin: '0 0 4px', fontSize: 22, fontWeight: 800 }}>ตั้งค่าการคำนวณราคา</h1>
        <p style={{ margin: 0, color: 'var(--color-text-muted)', fontSize: 13 }}>CEO สามารถปรับค่าได้ตลอดเวลา — ระบบเก็บประวัติทุก version</p>
      </header>

      {/* FX Rates */}
      <section className="table-panel">
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-border)' }}>
          <h2>อัตราแลกเปลี่ยน (1 หน่วย = ? บาท)</h2>
        </div>
        <div style={{ padding: '8px 18px', fontSize: 11, color: 'var(--color-text-muted)', borderBottom: '1px solid var(--color-surface-subtle)', display: 'flex', gap: 12 }}>
          <span>ดึงอัตโนมัติจาก BOT API ทุกวัน 18:00 (เวลาไทย)</span>
          <span style={{ color: 'var(--color-text-muted)' }}>• ตั้งค่า BOT_API_TOKEN เพื่อเปิดใช้งาน</span>
        </div>
        <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--color-surface-muted)' }}>
              {['สกุลเงิน', 'อัตรา (THB)', 'วันที่มีผล', 'แหล่งข้อมูล', 'แก้ไข (Manual)'].map((h) => (
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
                            {isSavingFx(fx.currency) ? '...' : 'บันทึก'}
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
                      ? <span style={{ background: 'var(--color-info-bg)', color: 'var(--color-info)', padding: '2px 8px', borderRadius: 10, fontSize: 11, fontWeight: 600 }}>BOT อัตโนมัติ</span>
                      : <span style={{ background: 'var(--color-surface-subtle)', color: 'var(--color-text-muted)', padding: '2px 8px', borderRadius: 10, fontSize: 11 }}>กรอกเอง</span>
                    }
                  </td>
                  <td style={{ padding: '8px 16px' }}>
                    {fx.currency !== 'THB' && !isEditing && (
                      <button type="button" className="secondary-button"
                        style={{ fontSize: 11, padding: '3px 8px' }}
                        onClick={() => setEditFx((p) => ({ ...p, [fx.currency]: String(fx.rateToThb) }))}>
                        Override
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
                {['ประเทศ', 'Ver', 'ค่าเรือ/ตร.ม.', 'ประกัน/ตร.ม.', 'โรงงาน→ท่าเรือ', 'ท่าเรือ→โกดัง', 'ภาษีนำเข้า', 'Margin', ''].map((h) => (
                  <th key={h} style={{ padding: '8px 14px', textAlign: 'left', fontWeight: 600, color: 'var(--color-icon-muted)', borderBottom: '1px solid var(--color-border)', whiteSpace: 'nowrap' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {configs.map((cfg) => (
                <tr key={cfg.configId} style={{ borderBottom: '1px solid var(--color-surface-subtle)' }}>
                  <td style={{ padding: '8px 14px', fontWeight: 700 }}>{cfg.country}</td>
                  <td style={{ padding: '8px 14px', color: 'var(--color-text-muted)' }}>v{cfg.version}</td>
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
          สูตร: CIF = ค่าสินค้า(THB/ตร.ม.) + ค่าเรือ + ประกัน → ต้นทุน = CIF + ภาษี + ขนส่งภายใน → ราคาขาย = ต้นทุน × (1 + Margin)
        </div>
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
    </div>
  );
}
