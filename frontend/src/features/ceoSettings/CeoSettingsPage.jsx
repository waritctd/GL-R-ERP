import { useEffect, useState } from 'react';
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

  async function load() {
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
  }

  useEffect(() => { load(); }, []);

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

  if (loading) return <div style={{ padding: 40, color: '#94a3b8' }}>กำลังโหลด...</div>;

  return (
    <div className="page-stack">
      <header>
        <h1 style={{ margin: '0 0 4px', fontSize: 22, fontWeight: 800 }}>ตั้งค่าการคำนวณราคา</h1>
        <p style={{ margin: 0, color: '#64748b', fontSize: 13 }}>CEO สามารถปรับค่าได้ตลอดเวลา — ระบบเก็บประวัติทุก version</p>
      </header>

      {/* FX Rates */}
      <section className="table-panel">
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid #e6eaf0' }}>
          <h2>อัตราแลกเปลี่ยน (1 หน่วย = ? บาท)</h2>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: '#f8fafc' }}>
              {['สกุลเงิน', 'อัตรา (THB)', 'วันที่มีผล', 'แก้ไข'].map((h) => (
                <th key={h} style={{ padding: '8px 16px', textAlign: 'left', fontWeight: 600, color: '#475569', borderBottom: '1px solid #e6eaf0' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {fxRates.map((fx) => {
              const isEditing = editFx[fx.currency] !== undefined;
              return (
                <tr key={fx.currency} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: '8px 16px', fontWeight: 700 }}>
                    <code style={{ background: '#f1f5f9', padding: '2px 6px', borderRadius: 4 }}>{fx.currency}</code>
                  </td>
                  <td style={{ padding: '8px 16px' }}>
                    {isEditing ? (
                      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                        <input
                          type="number" step="0.0001" min="0"
                          value={editFx[fx.currency]}
                          onChange={(e) => setEditFx((p) => ({ ...p, [fx.currency]: e.target.value }))}
                          style={{ width: 100, padding: '4px 8px', border: '1px solid #93c5fd', borderRadius: 4, fontSize: 13 }}
                          autoFocus
                        />
                        <button type="button" className="primary-button"
                          style={{ fontSize: 12, padding: '4px 10px' }}
                          disabled={savingFx[fx.currency]}
                          onClick={() => saveFxRate(fx.currency)}>
                          {savingFx[fx.currency] ? '...' : 'บันทึก'}
                        </button>
                        <button type="button" className="secondary-button"
                          style={{ fontSize: 12, padding: '4px 10px' }}
                          onClick={() => setEditFx((p) => { const n = { ...p }; delete n[fx.currency]; return n; })}>
                          ยกเลิก
                        </button>
                      </div>
                    ) : (
                      <strong>{fx.currency === 'THB' ? '1.0000' : moneyDisplay(fx.rateToThb)}</strong>
                    )}
                  </td>
                  <td style={{ padding: '8px 16px', color: '#64748b', fontSize: 12 }}>{fx.effectiveDate}</td>
                  <td style={{ padding: '8px 16px' }}>
                    {fx.currency !== 'THB' && !isEditing && (
                      <button type="button" className="secondary-button"
                        style={{ fontSize: 11, padding: '3px 8px' }}
                        onClick={() => setEditFx((p) => ({ ...p, [fx.currency]: String(fx.rateToThb) }))}>
                        แก้ไข
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
        <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid #e6eaf0' }}>
          <h2>สูตรคำนวณราคา (ต่อ ตร.ม.) แต่ละประเทศ</h2>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ background: '#f8fafc' }}>
                {['ประเทศ', 'Ver', 'ค่าเรือ/ตร.ม.', 'ประกัน/ตร.ม.', 'โรงงาน→ท่าเรือ', 'ท่าเรือ→โกดัง', 'ภาษีนำเข้า', 'Margin', ''].map((h) => (
                  <th key={h} style={{ padding: '8px 14px', textAlign: 'left', fontWeight: 600, color: '#475569', borderBottom: '1px solid #e6eaf0', whiteSpace: 'nowrap' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {configs.map((cfg) => (
                <tr key={cfg.configId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: '8px 14px', fontWeight: 700 }}>{cfg.country}</td>
                  <td style={{ padding: '8px 14px', color: '#64748b' }}>v{cfg.version}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.freightPerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.insurancePerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.inlandFactoryToPortPerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{moneyDisplay(cfg.inlandPortToWarehousePerSqm)}</td>
                  <td style={{ padding: '8px 14px' }}>{pctDisplay(cfg.importDutyPct)}</td>
                  <td style={{ padding: '8px 14px', fontWeight: 600, color: '#059669' }}>{pctDisplay(cfg.marginPct)}</td>
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
        <div style={{ padding: '10px 16px', borderTop: '1px solid #f1f5f9', fontSize: 11, color: '#94a3b8' }}>
          สูตร: CIF = ค่าสินค้า(THB/ตร.ม.) + ค่าเรือ + ประกัน → ต้นทุน = CIF + ภาษี + ขนส่งภายใน → ราคาขาย = ต้นทุน × (1 + Margin)
        </div>
      </section>

      {/* Config Edit Modal */}
      {editingConfig && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <div style={{ background: '#fff', borderRadius: 12, padding: 24, width: 480, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,0.3)' }}>
            <h3 style={{ margin: '0 0 16px', fontSize: 16, fontWeight: 700 }}>
              แก้ไข config — {editingConfig.country}
              <span style={{ fontSize: 11, color: '#94a3b8', fontWeight: 400, marginLeft: 8 }}>(จะบันทึกเป็นเวอร์ชันใหม่)</span>
            </h3>
            <div style={{ display: 'grid', gap: 12 }}>
              {[
                { key: 'freightPerSqm',             label: 'ค่าขนส่งทางเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
                { key: 'insurancePerSqm',            label: 'ค่าประกันภัย (THB/ตร.ม.)',     suffix: 'บาท/ตร.ม.' },
                { key: 'inlandFactoryToPortPerSqm',  label: 'ขนส่ง โรงงาน→ท่าเรือ (THB/ตร.ม.)', suffix: 'บาท/ตร.ม.' },
                { key: 'inlandPortToWarehousePerSqm', label: 'ขนส่ง ท่าเรือ→โกดัง (THB/ตร.ม.)',  suffix: 'บาท/ตร.ม.' },
                { key: 'importDutyPct',              label: 'อัตราภาษีนำเข้า (%)',           suffix: '%' },
                { key: 'marginPct',                  label: 'Margin (%)',                    suffix: '%' },
              ].map(({ key, label, suffix }) => (
                <label key={key} style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13 }}>
                  <span style={{ fontWeight: 600 }}>{label}</span>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <input
                      type="number" step="0.01" min="0"
                      value={configDraft[key] ?? ''}
                      onChange={(e) => setConfigDraft((p) => ({ ...p, [key]: e.target.value }))}
                      style={{ flex: 1 }}
                    />
                    <span style={{ color: '#64748b', fontSize: 12, minWidth: 70 }}>{suffix}</span>
                  </div>
                </label>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 20 }}>
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
