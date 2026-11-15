import { useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatShortDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex, importStepMeta } from '../importProgress/importSteps.js';
import { formatShortThaiDay } from '../pricingRequests/factoryContactMeta.js';
import { failed } from './dashboardParts.jsx';

/**
 * งานนำเข้า — import's landing (spec 2026-11-15 §7.11 item 1, plan task 5.1, design a-import-work). One worklist,
 * grouped by what happens next, replacing the 4-tile band and the three separate lists:
 *
 *   รอรับเรื่อง    SUBMITTED pricing requests nobody holds yet — รับเรื่อง claims inline (the same
 *                  api.pricingRequests.pickup the queue and request page use; the server decides who may).
 *   รอราคาโรงงาน  requests in import's hands (IMPORT_REVIEWING / AWAITING_FACTORY_RESPONSE, plus the retired
 *                  COSTING_IN_PROGRESS for the deploy-before-migration window). ของฉัน = assigned to me (the
 *                  queue page's งานของฉัน rule); ทั้งทีม = everyone's.
 *   กำลังขนส่ง    every live (ISSUED) per-factory ใบขอซื้อ row short of RECEIVED, with its step and ETA, plus a
 *                  pre-V184 deal still on the deal-level chain (no stored rows). The step is advanced on
 *                  /fulfilment only (§7.11 item 3), so the row's action goes there.
 *   ถึงโกดังแล้ว  ISSUED rows at RECEIVED on a deal not yet fully delivered — sales' turn; no action here.
 *
 * Data: the two calls this page always made (both role-scoped server side) and the per-deal stored rows exactly as
 * /fulfilment reads them (same candidate filter, same query keys, so the two pages share cache). No new endpoint.
 */

const PRICING_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'];
const LEGACY_IN_TRANSIT = ['IR_ISSUED', 'IR_SENT', 'SHIPPING'];
const PRICING_CHIP = {
  IMPORT_REVIEWING: { label: 'รับเรื่องแล้ว', tone: 'neutral' },
  AWAITING_FACTORY_RESPONSE: { label: 'รอโรงงานตอบ', tone: 'warning' },
  COSTING_IN_PROGRESS: { label: 'รอโรงงานตอบ', tone: 'warning' },
};

const byOldest = (a, b) => String(a.submittedAt ?? '').localeCompare(String(b.submittedAt ?? '')) || a.id - b.id;
const pcrName = (pr) => pr.customerName || pr.projectName || pr.ticketCode || pr.requestCode;

function etaText(row) {
  if (row.expectedArrivalFrom) {
    const to = row.expectedArrivalTo && row.expectedArrivalTo !== row.expectedArrivalFrom
      ? `–${formatShortThaiDay(row.expectedArrivalTo)}` : '';
    return `ETA ${formatShortThaiDay(row.expectedArrivalFrom)}${to}`;
  }
  if (row.leadTimeMinDays != null && row.leadTimeMaxDays != null) return `ETA ~${row.leadTimeMinDays}–${row.leadTimeMaxDays} วัน`;
  return 'ยังไม่มี ETA';
}

function Group({ groupKey, label, hint, rows, isFailed, children }) {
  const busy = rows === null && !isFailed;
  return (
    <section
      data-testid={`import-group-${groupKey}`}
      aria-busy={busy ? 'true' : undefined}
      aria-label={label}
      className="border-b border-border last:border-b-0"
    >
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 bg-surface-subtle px-5 py-2.5 mobile:px-3.5">
        <h2 className="m-0 text-sm font-extrabold text-text">{label}</h2>
        {rows !== null && !isFailed ? (
          <span data-testid="group-count" className="rounded-full bg-surface-muted px-2 text-xs font-bold tabular-nums text-text-secondary">
            {rows.length}
          </span>
        ) : null}
        <small className="min-w-0 text-xs text-text-muted">{hint}</small>
      </div>
      {isFailed ? (
        <p className="m-0 px-5 py-3 text-sm font-semibold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
      ) : busy ? (
        <div className="grid gap-2 px-5 py-3 mobile:px-3.5" role="status" aria-label={`กำลังโหลด${label}`}>
          <Skeleton height={36} />
        </div>
      ) : rows.length === 0 ? (
        <p className="m-0 px-5 py-3 text-sm text-text-muted mobile:px-3.5">ไม่มีรายการ</p>
      ) : children}
    </section>
  );
}

// One line of the worklist: id · who/what · detail · state · action. Five columns on a wide screen; on a phone the
// id and name lead and the rest wraps beneath, never wider than the viewport.
function Row({ testId, code, title, sub, detail, chip, action }) {
  return (
    <div
      data-testid={testId}
      className="grid grid-cols-[7.5rem_minmax(0,1.6fr)_minmax(0,1.3fr)_minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 border-t border-border-subtle px-5 py-2.5 first:border-t-0 nav-drawer:grid-cols-[7rem_minmax(0,1fr)_auto] mobile:grid-cols-[minmax(0,1fr)_auto] mobile:px-3.5"
    >
      <code className="truncate text-xs font-bold text-text-secondary mobile:col-span-2">{code}</code>
      <span className="min-w-0">
        <strong className="block truncate text-sm font-bold text-text">{title}</strong>
        {sub ? <span className="block truncate text-xs text-text-muted">{sub}</span> : null}
      </span>
      <span className="min-w-0 text-sm text-text-secondary nav-drawer:col-start-2 nav-drawer:row-start-2 mobile:col-start-1 mobile:row-start-auto [overflow-wrap:anywhere]">{detail}</span>
      <span className="min-w-0 nav-drawer:col-start-2 mobile:col-start-1">{chip}</span>
      <span className="justify-self-end nav-drawer:col-start-3 nav-drawer:row-span-2 nav-drawer:row-start-1 mobile:col-start-2 mobile:row-start-2">{action}</span>
    </div>
  );
}

export function ImportOverview({ user, showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [scope, setScope] = useState('mine');

  const queueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r?.items ?? []),
  });
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });
  const queue = useMemo(() => queueQuery.data ?? [], [queueQuery.data]);
  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);

  // Review M5 (accepted): this page deliberately SHARES /fulfilment's per-deal reads — the same candidate filter, the
  // same api.storedImportRequests.listForTicket call and the same queryKeys.storedImportRequests(ticketId) cache keys —
  // so the two pages never disagree and opening one warms the other. It is the same bounded N+1 (see
  // ImportFulfilmentPage's header); a cross-deal endpoint would replace it on both pages at once.
  const candidates = useMemo(() => tickets.filter((t) => t.fulfillmentStatus != null
    && t.fulfillmentStatus !== 'FROM_STOCK' && t.fulfillmentStatus !== 'FULLY_DELIVERED'), [tickets]);
  const rowsQueries = useQueries({
    queries: candidates.map((t) => ({
      queryKey: queryKeys.storedImportRequests(t.id),
      queryFn: () => api.storedImportRequests.listForTicket(t.id).then((r) => r?.importRequests ?? []),
    })),
  });

  const queueReady = queueQuery.data !== undefined;
  const queueFailed = failed(queueQuery);
  const shipmentsReady = ticketsQuery.data !== undefined && rowsQueries.every((q) => q.data !== undefined || q.isError);
  const shipmentsFailed = failed(ticketsQuery);

  const pickup = useMemo(() => queue.filter((pr) => pr.status === 'SUBMITTED').sort(byOldest), [queue]);
  const pricing = useMemo(() => queue
    .filter((pr) => PRICING_STATUSES.includes(pr.status))
    .filter((pr) => scope === 'team' || (pr.assignedImportId != null && Number(pr.assignedImportId) === Number(user?.id)))
    .sort(byOldest), [queue, scope, user?.id]);

  const { transit, arrived } = useMemo(() => {
    const moving = [];
    const done = [];
    candidates.forEach((ticket, i) => {
      const rows = rowsQueries[i]?.data;
      if (!rows) return;
      const issued = rows.filter((r) => r.status === 'ISSUED' && importStepIndex(r.importStep) >= 0);
      if (issued.length === 0) {
        if (LEGACY_IN_TRANSIT.includes(ticket.fulfillmentStatus)) moving.push({ legacy: true, ticket });
        return;
      }
      issued.forEach((row) => (row.importStep === 'RECEIVED' ? done : moving).push({ legacy: false, ticket, row }));
    });
    const stepOf = (x) => (x.legacy ? -1 : importStepIndex(x.row.importStep));
    moving.sort((a, b) => stepOf(b) - stepOf(a) || String(a.ticket.code).localeCompare(String(b.ticket.code)));
    return { transit: moving, arrived: done };
  }, [candidates, rowsQueries]);

  const claim = useMutation({
    mutationFn: (pr) => api.pricingRequests.pickup(pr.id),
    onSuccess: (_d, pr) => {
      showToast?.('success', `รับเรื่อง ${pr.requestCode} แล้ว`);
      queryClient.invalidateQueries({ queryKey: ['pricingRequests'] });
    },
    onError: (e) => showToast?.('error', e?.message || 'รับเรื่องไม่สำเร็จ'),
  });

  const settled = (queueReady || queueFailed) && (shipmentsReady || shipmentsFailed);
  const total = pickup.length + pricing.length + transit.length + arrived.length;
  // Review H2: a per-deal read that failed is NOT "nothing to do" — those deals are named, and the shipment counts are
  // marked partial, exactly as /fulfilment shows its own per-deal failures.
  const erroredDeals = candidates.filter((_t, i) => rowsQueries[i]?.isError);
  const partial = erroredDeals.length > 0;
  const nothing = settled && !queueFailed && !shipmentsFailed && !partial && total === 0;
  const retryErrored = () => erroredDeals.forEach((t) => queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(t.id) }));

  const scopeButton = (key, label) => (
    <button
      key={key}
      type="button"
      aria-pressed={scope === key}
      onClick={() => setScope(key)}
      className={cn(
        'inline-flex min-h-9 items-center rounded-full border px-3 text-xs font-bold mobile:min-h-11',
        scope === key ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover',
      )}
    >
      {label}
    </button>
  );

  return (
    <PageStack>
      <PageHeader
        title="งานนำเข้า"
        subtitle={settled ? `จัดกลุ่มตามขั้นต่อไป · ${total} งาน` : 'จัดกลุ่มตามขั้นต่อไป'}
        actions={<div className="flex gap-2" role="group" aria-label="งานของใคร">{scopeButton('mine', 'ของฉัน')}{scopeButton('team', 'ทั้งทีม')}</div>}
      />

      {partial ? (
        <div className="grid gap-2 rounded-md border border-danger-border bg-danger-bg p-4" data-testid="import-rows-error">
          <p className="m-0 flex flex-wrap items-center justify-between gap-2 text-sm font-bold text-danger-dark" data-testid="import-partial">
            <span>ข้อมูลไม่ครบ — โหลดบางดีลไม่สำเร็จ ลองใหม่</span>
            <Button type="button" variant="secondary" size="sm" className="mobile:min-h-11" onClick={retryErrored}>ลองใหม่</Button>
          </p>
          <p className="m-0 text-xs font-bold text-danger-dark">โหลดใบขอซื้อของดีลต่อไปนี้ไม่สำเร็จ</p>
          <ul className="m-0 list-disc pl-5 text-xs text-danger-dark">
            {erroredDeals.map((t) => (
              <li key={t.id}>
                <Link to={`/import/deals/${t.id}`} className="underline">{t.customerName || t.title || t.code}</Link>
                {' — '}{rowsQueries[candidates.indexOf(t)]?.error?.message ?? 'โหลดไม่สำเร็จ'}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {nothing ? (
        <div className="flex items-center gap-3 rounded-md border border-border bg-surface p-5 text-text-muted">
          <Icon name="check" size={18} />
          <span className="text-sm font-bold">ไม่มีงานนำเข้าที่ต้องทำตอนนี้</span>
        </div>
      ) : (
        <div className="overflow-hidden rounded-md border border-border bg-surface">
          <Group groupKey="pickup" label="รอรับเรื่อง" hint="คำขอราคาใหม่จากฝ่ายขาย"
            rows={queueReady ? pickup : null} isFailed={queueFailed}>
            {pickup.map((pr) => (
              <Row
                key={pr.id}
                testId={`import-row-pickup-${pr.id}`}
                code={pr.requestCode}
                title={pcrName(pr)}
                sub={[pr.requestedByName, pr.submittedAt ? `ส่งมา ${formatShortDate(pr.submittedAt)}` : null].filter(Boolean).join(' · ')}
                detail={`${pr.itemCount ?? 0} รายการ`}
                chip={pr.ticketCode ? <span className="text-xs text-text-muted">{pr.ticketCode}</span> : null}
                action={(
                  <Button type="button" variant="primary" size="sm" className="mobile:min-h-11"
                    disabled={claim.isPending} onClick={() => claim.mutate(pr)}>
                    รับเรื่อง
                  </Button>
                )}
              />
            ))}
          </Group>

          <Group groupKey="pricing" label="รอราคาโรงงาน" hint={scope === 'team' ? 'ทั้งทีม · รับเรื่องแล้ว รอโรงงานตอบ' : 'ของฉัน · รับเรื่องแล้ว รอโรงงานตอบ'}
            rows={queueReady ? pricing : null} isFailed={queueFailed}>
            {pricing.map((pr) => {
              const chip = PRICING_CHIP[pr.status];
              return (
                <Row
                  key={pr.id}
                  testId={`import-row-pricing-${pr.id}`}
                  code={pr.requestCode}
                  title={pcrName(pr)}
                  sub={[pr.requestedByName, scope === 'team' && pr.assignedImportName ? `รับโดย ${pr.assignedImportName}` : null].filter(Boolean).join(' · ')}
                  detail={`${pr.itemCount ?? 0} รายการ`}
                  chip={chip ? <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge> : null}
                  action={(
                    <Button type="button" variant="secondary" size="sm" className="mobile:min-h-11"
                      onClick={() => navigate(`/pricing-requests/${pr.id}`)}>
                      เปิด
                    </Button>
                  )}
                />
              );
            })}
          </Group>

          <Group groupKey="transit" label="กำลังขนส่ง" hint="เลื่อนขั้นตอนที่หน้า การขนส่ง"
            rows={shipmentsReady ? transit : null} isFailed={shipmentsFailed}>
            {transit.map((x) => (x.legacy ? (
              <Row
                key={`legacy-${x.ticket.id}`}
                testId={`import-row-transit-legacy-${x.ticket.id}`}
                code={x.ticket.code}
                title={x.ticket.customerName || x.ticket.title || x.ticket.code}
                sub="ดีลรูปแบบเดิม (ยังไม่ติดตามรายโรงงาน)"
                detail={<StatusBadge tone={fulfilmentStatusLabel(x.ticket.fulfillmentStatus).tone}>{fulfilmentStatusLabel(x.ticket.fulfillmentStatus).label}</StatusBadge>}
                chip={null}
                action={(
                  <Button type="button" variant="secondary" size="sm" className="mobile:min-h-11"
                    onClick={() => navigate(`/import/deals/${x.ticket.id}`)}>
                    เปิด
                  </Button>
                )}
              />
            ) : (
              <Row
                key={x.row.id}
                testId={`import-row-transit-${x.row.id}`}
                code={x.row.docNumber ?? x.ticket.code}
                title={x.ticket.customerName || x.ticket.title || x.ticket.code}
                sub={x.row.factoryName}
                detail={(
                  <span className="inline-flex flex-wrap items-center gap-1.5">
                    <StatusBadge tone="info">{importStepMeta(x.row.importStep)?.label ?? x.row.importStep}</StatusBadge>
                    <span className="text-xs tabular-nums text-text-muted">{importStepIndex(x.row.importStep) + 1}/{IMPORT_STEPS.length}</span>
                  </span>
                )}
                chip={<span className="text-xs font-bold tabular-nums text-text-secondary">{etaText(x.row)}</span>}
                action={(
                  <Button type="button" variant="secondary" size="sm" className="mobile:min-h-11"
                    onClick={() => navigate('/fulfilment')}>
                    อัปเดต
                  </Button>
                )}
              />
            )))}
          </Group>

          <Group groupKey="arrived" label="ถึงโกดังแล้ว" hint="รอฝ่ายขายนัดส่งลูกค้า · ไม่มีงานของคุณ"
            rows={shipmentsReady ? arrived : null} isFailed={shipmentsFailed}>
            {arrived.map((x) => (
              <Row
                key={x.row.id}
                testId={`import-row-arrived-${x.row.id}`}
                code={x.row.docNumber ?? x.ticket.code}
                title={x.ticket.customerName || x.ticket.title || x.ticket.code}
                sub={x.row.factoryName}
                detail={(
                  <span className="inline-flex flex-wrap items-center gap-1.5">
                    <StatusBadge tone="success">ถึงโกดัง</StatusBadge>
                    <span className="text-xs tabular-nums text-text-muted">{IMPORT_STEPS.length}/{IMPORT_STEPS.length}</span>
                  </span>
                )}
                chip={x.row.importStepAt ? <span className="text-xs text-text-muted">ถึง {formatShortThaiDay(x.row.importStepAt)}</span> : null}
                action={null}
              />
            ))}
          </Group>
        </div>
      )}
    </PageStack>
  );
}
