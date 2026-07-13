import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

// ── tiny helpers ──────────────────────────────────────────────────────────────

function num(n) {
  return n != null ? Number(n).toLocaleString('th-TH') : '0';
}

function statusTone(status) {
  if (status === 'ACTIVE') return 'green';
  if (status === 'DRAFT') return 'blue';
  if (status === 'ARCHIVED') return 'neutral';
  return 'neutral';
}

// ── sub-components ────────────────────────────────────────────────────────────

function UploadReportCard({ report, onValidate, validating }) {
  return (
    <Panel title="ผลการอ่านไฟล์">
      <div className="flex flex-wrap gap-4 mb-4">
        <div className="flex flex-col items-center p-3 bg-blue-50 rounded-md min-w-[90px]">
          <span className="text-2xl font-bold text-blue-700">{num(report.parsedRows)}</span>
          <span className="text-xs text-muted">อ่านได้</span>
        </div>
        <div className="flex flex-col items-center p-3 bg-green-50 rounded-md min-w-[90px]">
          <span className="text-2xl font-bold text-green-700">{num(report.stagedRows)}</span>
          <span className="text-xs text-muted">นำเข้า staging</span>
        </div>
        {report.parseErrors?.length > 0 && (
          <div className="flex flex-col items-center p-3 bg-red-50 rounded-md min-w-[90px]">
            <span className="text-2xl font-bold text-red-700">{report.parseErrors.length}</span>
            <span className="text-xs text-muted">ข้อผิดพลาด</span>
          </div>
        )}
      </div>

      {report.parseErrors?.length > 0 && (
        <details className="mb-4">
          <summary className="cursor-pointer text-sm text-red-600 font-medium">
            ดูข้อผิดพลาด ({report.parseErrors.length} รายการ)
          </summary>
          <ul className="mt-2 space-y-1 max-h-48 overflow-y-auto">
            {report.parseErrors.map((e, i) => (
              <li key={i} className="text-xs text-red-700 bg-red-50 px-3 py-1 rounded font-mono">
                {e}
              </li>
            ))}
          </ul>
        </details>
      )}

      <Button variant="primary" onClick={onValidate} disabled={validating}>
        {validating ? 'กำลังตรวจสอบ…' : 'ตรวจสอบข้อมูลซ้ำ'}
      </Button>
    </Panel>
  );
}

function StagingReportCard({ report, onCommit, committing }) {
  const diffItems = [
    { label: 'สินค้าใหม่', value: report.newRows, tone: 'green' },
    { label: 'ราคาเปลี่ยน', value: report.changedRows, tone: 'yellow' },
    { label: 'ลบออก', value: report.removedRows, tone: 'red' },
    { label: 'ไม่เปลี่ยน', value: report.unchangedRows, tone: 'neutral' },
  ];
  const hasErrors = report.errorRows > 0;

  return (
    <Panel title="ตรวจสอบแล้ว — พร้อม commit">
      <div className="flex flex-wrap gap-4 mb-4">
        <div className="flex flex-col items-center p-3 bg-surface border border-border rounded-md min-w-[90px]">
          <span className="text-2xl font-bold">{num(report.totalRows)}</span>
          <span className="text-xs text-muted">แถวทั้งหมด</span>
        </div>
        {hasErrors && (
          <div className="flex flex-col items-center p-3 bg-red-50 rounded-md min-w-[90px]">
            <span className="text-2xl font-bold text-red-700">{num(report.errorRows)}</span>
            <span className="text-xs text-muted">แถวมีปัญหา</span>
          </div>
        )}
      </div>

      <p className="text-sm font-medium mb-2">เทียบกับ version ที่ active ล่าสุด:</p>
      <div className="flex flex-wrap gap-3 mb-4">
        {diffItems.map(({ label, value, tone }) => (
          <div key={label} className="flex flex-col items-center p-2 border border-border rounded min-w-[80px]">
            <span className={`text-xl font-bold ${tone === 'green' ? 'text-green-700' : tone === 'yellow' ? 'text-yellow-700' : tone === 'red' ? 'text-red-700' : ''}`}>
              {num(value)}
            </span>
            <span className="text-xs text-muted">{label}</span>
          </div>
        ))}
      </div>

      {hasErrors && (
        <p className="text-sm text-yellow-700 bg-yellow-50 px-3 py-2 rounded mb-4">
          มี {report.errorRows} แถวที่มีข้อผิดพลาด — จะถูกข้ามเมื่อ commit
        </p>
      )}

      <Button variant="primary" onClick={onCommit} disabled={committing}>
        {committing ? 'กำลัง commit…' : 'Commit ราคา'}
      </Button>
    </Panel>
  );
}

function CommitResultCard({ result }) {
  return (
    <Panel title="Commit สำเร็จ">
      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col items-center p-3 bg-green-50 rounded-md min-w-[90px]">
          <span className="text-2xl font-bold text-green-700">{num(result.inserted)}</span>
          <span className="text-xs text-muted">เพิ่มใหม่</span>
        </div>
        <div className="flex flex-col items-center p-3 bg-blue-50 rounded-md min-w-[90px]">
          <span className="text-2xl font-bold text-blue-700">{num(result.updated)}</span>
          <span className="text-xs text-muted">อัปเดต</span>
        </div>
        <div className="flex flex-col items-center p-3 bg-surface border border-border rounded-md min-w-[90px]">
          <span className="text-2xl font-bold">{num(result.archived)}</span>
          <span className="text-xs text-muted">เก็บ archive</span>
        </div>
      </div>
    </Panel>
  );
}

function VersionsPanel({ versions, currentVersionId }) {
  if (!versions.length) return null;
  return (
    <Panel title="ประวัติการนำเข้า">
      <ul className="space-y-2 max-h-64 overflow-y-auto">
        {versions.map((v) => (
          <li
            key={v.versionId}
            className={`flex items-center justify-between gap-2 px-3 py-2 rounded border ${v.versionId === currentVersionId ? 'border-blue-400 bg-blue-50' : 'border-border bg-surface'}`}
          >
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">{v.label || `Version ${v.versionId}`}</p>
              <p className="text-xs text-muted">
                {v.createdAt ? new Date(v.createdAt).toLocaleString('th-TH') : ''}{' '}
                {v.uploadedByName ? `· ${v.uploadedByName}` : ''}
              </p>
            </div>
            <StatusBadge tone={statusTone(v.status)}>{v.status}</StatusBadge>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

// ── main page ─────────────────────────────────────────────────────────────────

export function PriceImportPage({ showToast }) {
  const [factories, setFactories] = useState([]);
  const [factoryId, setFactoryId] = useState('');
  const [versions, setVersions] = useState([]);

  const [file, setFile] = useState(null);
  const [label, setLabel] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadReport, setUploadReport] = useState(null);

  const [validating, setValidating] = useState(false);
  const [stagingReport, setStagingReport] = useState(null);

  const [committing, setCommitting] = useState(false);
  const [commitResult, setCommitResult] = useState(null);

  const [currentVersionId, setCurrentVersionId] = useState(null);
  const [error, setError] = useState('');
  const fileRef = useRef(null);

  useEffect(() => {
    api.priceImport.factories().then(setFactories).catch(() => {});
  }, []);

  const loadVersions = useCallback(async (fid) => {
    if (!fid) return;
    try {
      const data = await api.priceImport.versions(fid);
      setVersions(Array.isArray(data) ? data : []);
    } catch {
      setVersions([]);
    }
  }, []);

  function handleFactoryChange(e) {
    const fid = e.target.value;
    setFactoryId(fid);
    setUploadReport(null);
    setStagingReport(null);
    setCommitResult(null);
    setCurrentVersionId(null);
    setFile(null);
    setLabel('');
    setError('');
    if (fileRef.current) fileRef.current.value = '';
    loadVersions(fid);
  }

  function handleFileChange(e) {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    if (f && !label) setLabel(f.name.replace(/\.[^.]+$/, ''));
    setUploadReport(null);
    setStagingReport(null);
    setCommitResult(null);
    setCurrentVersionId(null);
    setError('');
  }

  async function handleUpload() {
    if (!factoryId || !file) return;
    setUploading(true);
    setError('');
    try {
      const report = await api.priceImport.upload(Number(factoryId), file, label || undefined);
      setUploadReport(report);
      setCurrentVersionId(report.versionId);
      setStagingReport(null);
      setCommitResult(null);
      await loadVersions(factoryId);
    } catch (err) {
      setError(err.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploading(false);
    }
  }

  async function handleValidate() {
    if (!currentVersionId) return;
    setValidating(true);
    setError('');
    try {
      await api.priceImport.validate(currentVersionId);
      const report = await api.priceImport.staging(currentVersionId);
      setStagingReport(report);
    } catch (err) {
      setError(err.message || 'ตรวจสอบไม่สำเร็จ');
    } finally {
      setValidating(false);
    }
  }

  async function handleCommit() {
    if (!currentVersionId) return;
    setCommitting(true);
    setError('');
    try {
      const result = await api.priceImport.commit(currentVersionId);
      setCommitResult(result);
      setStagingReport(null);
      setUploadReport(null);
      setFile(null);
      setLabel('');
      if (fileRef.current) fileRef.current.value = '';
      await loadVersions(factoryId);
      showToast?.('success', 'Commit ราคาสำเร็จ');
    } catch (err) {
      setError(err.message || 'Commit ไม่สำเร็จ');
    } finally {
      setCommitting(false);
    }
  }

  const selectedFactory = factories.find((f) => String(f.factoryId) === String(factoryId));
  const canUpload = factoryId && file && !uploading;

  return (
    <PageStack>
      <PageHeader title="นำเข้าราคาโรงงาน" subtitle="อัปโหลด price list Excel แล้ว validate ก่อน commit" />

      {/* Factory + file selection */}
      <Panel title="เลือกโรงงานและไฟล์">
        <div className="flex flex-wrap gap-4 mb-4">
          <div className="flex-1 min-w-[200px]">
            <label className="block text-sm font-medium mb-1" htmlFor="factory-select">
              โรงงาน
            </label>
            <select
              id="factory-select"
              className="input w-full"
              value={factoryId}
              onChange={handleFactoryChange}
            >
              <option value="">— เลือกโรงงาน —</option>
              {factories.map((f) => (
                <option key={f.factoryId} value={f.factoryId}>
                  {f.name}
                </option>
              ))}
            </select>
          </div>

          {factoryId && (
            <div className="flex-1 min-w-[200px]">
              <label className="block text-sm font-medium mb-1" htmlFor="label-input">
                Label (ชื่อ version)
              </label>
              <input
                id="label-input"
                type="text"
                className="input w-full"
                placeholder={`เช่น Price List 2026 Q3`}
                value={label}
                onChange={(e) => setLabel(e.target.value)}
              />
            </div>
          )}
        </div>

        {factoryId && (
          <>
            <label className="block text-sm font-medium mb-1" htmlFor="file-input">
              ไฟล์ Excel (.xlsx)
              {selectedFactory?.numberFormat && (
                <span className="ml-2 text-xs text-muted font-normal">
                  รูปแบบตัวเลข: {selectedFactory.numberFormat?.toUpperCase() ?? 'EU'}
                </span>
              )}
            </label>
            <div className="flex items-center gap-3">
              <input
                id="file-input"
                ref={fileRef}
                type="file"
                accept=".xlsx,.xls"
                className="text-sm"
                onChange={handleFileChange}
              />
              <Button
                variant="primary"
                onClick={handleUpload}
                disabled={!canUpload}
              >
                <Icon name="upload" />
                {uploading ? 'กำลังอ่าน…' : 'อัปโหลด'}
              </Button>
            </div>
          </>
        )}
      </Panel>

      {error && (
        <div className="bg-red-50 border border-red-300 text-red-700 px-4 py-3 rounded-md text-sm">
          {error}
        </div>
      )}

      {/* Workflow panels — appear sequentially */}
      {uploadReport && !stagingReport && !commitResult && (
        <UploadReportCard
          report={uploadReport}
          onValidate={handleValidate}
          validating={validating}
        />
      )}

      {stagingReport && !commitResult && (
        <StagingReportCard
          report={stagingReport}
          onCommit={handleCommit}
          committing={committing}
        />
      )}

      {commitResult && <CommitResultCard result={commitResult} />}

      {/* Version history for selected factory */}
      <VersionsPanel versions={versions} currentVersionId={currentVersionId} />
    </PageStack>
  );
}
