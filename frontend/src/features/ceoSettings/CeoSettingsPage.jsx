import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/index.js';

function pctDisplay(val) {
  return val != null ? `${(Number(val) * 100).toFixed(2)}%` : '-';
}

function moneyDisplay(val) {
  return val != null ? Number(val).toLocaleString('th-TH', { minimumFractionDigits: 2 }) : '-';
}

export function CeoSettingsPage({ showToast }) {
  const [fxRates, setFxRates] = useState([]);
  const [configs, setConfigs] = useState([]);
  const [loading, setLoading] = useState(true);

  // FX rate inline edit: currency → draftRate string
  const [editFx, setEditFx] = useState({});          // currency → string
  const [savingFx, setSavingFx] = useState({});       // currency → bool

  // Config edit modal state
  const [editingConfig, setEditingConfig] = useState(null);  // PriceCalcConfigDto or null
  const [configDraft, setConfigDraft] = useState({});
  const [savingConfig, setSavingConfig] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [fxRes, cfgRes] = await Promise.all([
        api.fxRates.list(),
        api.priceCalcConfigs.list(),
      ]);
      setFxRates(fxRes.fxRates ?? []);
      setConfigs(cfgRes.configs ?? []);
    } catch (e) {
      showToast('error', e.message || 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => { load(); }, [load]);

  async function saveFxRate(currency) {
    const val = editFx[currency];
    if (!val || isNaN(Number(val)) || Number(val) <= 0) {
      showToast('error', 'กรุณากรอกอัตราแลกเปลี่ยนที่ถูกต้อง');
      return;
    }
    setSavingFx((p) => ({ ...p, [currency]: true }));
    try {
      await api.fxRates.upsert(currency, { rateToThb: Number(val) });
      const fxRes = await api.fxRates.list();
      setFxRates(fxRes.fxRates ?? []);
      setEditFx((p) => { const n = { ...p }; delete n[currency]; return n; });
      showToast('success', `อัปเดตอัตรา ${currency} แล้ว`);
    } catch (e) {
      showToast('error', e.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSavingFx((p) => ({ ...p, [currency]: false }));
    }
  }

  function openConfigEdit(cfg) {
    setEditingConfig(cfg);
    setConfigDraft({
      country:                    cfg.country,
      freightPerSqm:              String(cfg.freightPerSqm),
      insurancePerSqm:            String(cfg.insurancePerSqm),
      inlandFactoryToPortPerSqm:  String(cfg.inlandFactoryToPortPerSqm),
      inlandPortToWarehousePerSqm: String(cfg.inlandPortToWarehousePerSqm),
      importDutyPct:              String(Number(cfg.importDutyPct) * 100),
      marginPct:                  String(Number(cfg.marginPct) * 100),
    });
  }

  async function saveConfig() {
    setSavingConfig(true);
    try {
      const payload = {
        country:                    configDraft.country,
        freightPerSqm:              Number(configDraft.freightPerSqm),
        insurancePerSqm:            Number(configDraft.insurancePerSqm),
        inlandFactoryToPortPerSqm:  Number(configDraft.inlandFactoryToPortPerSqm),
        inlandPortToWarehousePerSqm: Number(configDraft.inlandPortToWarehousePerSqm),
        importDutyPct:              Number(configDraft.importDutyPct) / 100,
        marginPct:                  Number(configDraft.marginPct) / 100,
      };
      await api.priceCalcConfigs.update(payload);
      const cfgRes = await api.priceCalcConfigs.list();
      setConfigs(cfgRes.configs ?? []);
      setEditingConfig(null);
      showToast('success', `บันทึก config ประเทศ ${configDraft.country} (เวอร์ชันใหม่) แล้ว`);
    } catch (e) {
      showToast('error', e.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSavingConfig(false);
    }
  }

  if (loading) return <div className="p-[40px] text-text-faint">กำลังโหลด...</div>;

  return (
    <div className="page-stack">
      <header>
        <h1 className="m-0 mb-1 text-xl font-extrabold">ตั้งค่าการคำนวณราคา</h1>
        <p className="m-0 text-text-muted text-sm">CEO สามารถปรับค่าได้ตลอดเวลา — ระบบเก็บประวัติทุก version</p>
      </header>

      {/* FX Rates */}
      <section className="table-panel">
        <div className="panel-header px-[18px] py-[14px] border-b border-border">
          <h2>อัตราแลกเปลี่ยน (1 หน่วย = ? บาท)</h2>
        </div>
        <div className="px-[18px] py-2 text-[11px] text-text-muted border-b border-surface-subtle flex gap-3">
          <span>ดึงอัตโนมัติจาก BOT API ทุกวัน 18:00 (เวลาไทย)</span>
          <span className="text-icon-faint">• ตั้งค่า BOT_API_TOKEN เพื่อเปิดใช้งาน</span>
        </div>
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="bg-surface-muted">
              {['สกุลเงิน', 'อัตรา (THB)', 'วันที่มีผล', 'แหล่งข้อมูล', 'แก้ไข (Manual)'].map((h) => (
                <th key={h} className="px-4 py-2 text-left font-semibold text-icon-muted border-b border-border">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {fxRates.map((fx) => {
              const isEditing = editFx[fx.currency] !== undefined;
              const isBot = fx.source === 'BOT';
              return (
                <tr key={fx.currency} className="border-b border-surface-subtle">
                  <td className="px-4 py-2 font-bold">
                    <code className="bg-surface-subtle px-[6px] py-0.5 rounded-[4px]">{fx.currency}</code>
                  </td>
                  <td className="px-4 py-2">
                    {isEditing ? (
                      <div className="flex gap-[6px] items-center">
                        <input
                          type="number" step="0.0001" min="0"
                          value={editFx[fx.currency]}
                          onChange={(e) => setEditFx((p) => ({ ...p, [fx.currency]: e.target.value }))}
                          className="w-[100px] px-2 py-1 rounded-[4px] text-sm"
                          style={{ border: '1px solid #93c5fd' }}
                        />
                        <button type="button" className="primary-button text-xs px-[10px] py-1"
                          disabled={savingFx[fx.currency]}
                          onClick={() => saveFxRate(fx.currency)}>
                          {savingFx[fx.currency] ? '...' : 'บันทึก'}
                        </button>
                        <button type="button" className="secondary-button text-xs px-[10px] py-1"
                          onClick={() => setEditFx((p) => { const n = { ...p }; delete n[fx.currency]; return n; })}>
                          ยกเลิก
                        </button>
                      </div>
                    ) : (
                      <strong>{fx.currency === 'THB' ? '1.0000' : moneyDisplay(fx.rateToThb)}</strong>
                    )}
                  </td>
                  <td className="px-4 py-2 text-text-muted text-xs">{fx.effectiveDate}</td>
                  <td className="px-4 py-2">
                    {isBot
                      ? <span className="status-badge status-info">BOT อัตโนมัติ</span>
                      : <span className="status-badge status-neutral">กรอกเอง</span>
                    }
                  </td>
                  <td className="px-4 py-2">
                    {fx.currency !== 'THB' && !isEditing && (
                      <button type="button" className="secondary-button text-[11px] px-2 py-[3px]"
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
      </section>

      {/* Price Calc Config */}
      <section className="table-panel">
        <div className="panel-header px-[18px] py-[14px] border-b border-border">
          <h2>สูตรคำนวณราคา (ต่อ ตร.ม.) แต่ละประเทศ</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr className="bg-surface-muted">
                {['ประเทศ', 'Ver', 'ค่าเรือ/ตร.ม.', 'ประกัน/ตร.ม.', 'โรงงาน→ท่าเรือ', 'ท่าเรือ→โกดัง', 'ภาษีนำเข้า', 'Margin', ''].map((h) => (
                  <th key={h} className="px-[14px] py-2 text-left font-semibold text-icon-muted border-b border-border whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {configs.map((cfg) => (
                <tr key={cfg.configId} className="border-b border-surface-subtle">
                  <td className="px-[14px] py-2 font-bold">{cfg.country}</td>
                  <td className="px-[14px] py-2 text-text-muted">v{cfg.version}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.freightPerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.insurancePerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.inlandFactoryToPortPerSqm)}</td>
                  <td className="px-[14px] py-2">{moneyDisplay(cfg.inlandPortToWarehousePerSqm)}</td>
                  <td className="px-[14px] py-2">{pctDisplay(cfg.importDutyPct)}</td>
                  <td className="px-[14px] py-2 font-semibold text-success">{pctDisplay(cfg.marginPct)}</td>
                  <td className="px-[14px] py-2">
                    <button type="button" className="secondary-button text-[11px] px-2 py-[3px]"
                      onClick={() => openConfigEdit(cfg)}>
                      แก้ไข
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-4 py-[10px] border-t border-surface-subtle text-[11px] text-text-faint">
          สูตร: CIF = ค่าสินค้า(THB/ตร.ม.) + ค่าเรือ + ประกัน → ต้นทุน = CIF + ภาษี + ขนส่งภายใน → ราคาขาย = ต้นทุน × (1 + Margin)
        </div>
      </section>

      {/* Config Edit Modal */}
      {editingConfig && (
        <div
          className="fixed inset-0 flex items-center justify-center z-[1000]"
          style={{ background: 'rgba(0,0,0,0.4)' }}
        >
          <div
            className="bg-surface rounded-[12px] p-6 w-[480px] max-h-[90vh] overflow-y-auto"
            style={{ boxShadow: '0 20px 60px rgba(0,0,0,0.3)' }}
          >
            <h3 className="m-0 mb-4 text-[16px] font-bold">
              แก้ไข config — {editingConfig.country}
              <span className="text-[11px] text-text-faint font-normal ml-2">(จะบันทึกเป็นเวอร์ชันใหม่)</span>
            </h3>
            <div className="grid gap-3">
              {[
                { key: 'freightPerSqm',             label: 'ค่าขนส่งทางเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
                { key: 'insurancePerSqm',            label: 'ค่าประกันภัย (THB/ตร.ม.)',     suffix: 'บาท/ตร.ม.' },
                { key: 'inlandFactoryToPortPerSqm',  label: 'ขนส่ง โรงงาน→ท่าเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
                { key: 'inlandPortToWarehousePerSqm', label: 'ขนส่ง ท่าเรือ→โกดัง (THB/ตร.ม.)',  suffix: 'บาท/ตร.ม.' },
                { key: 'importDutyPct',              label: 'อัตราภาษีนำเข้า (%)',           suffix: '%' },
                { key: 'marginPct',                  label: 'Margin (%)',                    suffix: '%' },
              ].map(({ key, label, suffix }) => (
                <label key={key} className="flex flex-col gap-1 text-sm">
                  <span className="font-semibold">{label}</span>
                  <div className="flex items-center gap-[6px]">
                    <input
                      type="number" step="0.01" min="0"
                      value={configDraft[key] ?? ''}
                      onChange={(e) => setConfigDraft((p) => ({ ...p, [key]: e.target.value }))}
                      className="flex-1"
                    />
                    <span className="text-text-muted text-xs min-w-[70px]">{suffix}</span>
                  </div>
                </label>
              ))}
            </div>
            <div className="flex gap-2 mt-5">
              <button type="button" className="primary-button" disabled={savingConfig} onClick={saveConfig}>
                {savingConfig ? 'กำลังบันทึก...' : 'บันทึกเวอร์ชันใหม่'}
              </button>
              <button type="button" className="secondary-button" onClick={() => setEditingConfig(null)}>ยกเลิก</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
