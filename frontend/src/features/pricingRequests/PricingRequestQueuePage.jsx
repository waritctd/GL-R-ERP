import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import { SalesTabs } from '../sales/SalesTabs.jsx';
import { formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import { canPickupPricingRequest, pricingRequestRecipientLabel } from './pricingRequestMeta.js';

// Import's three meaningful stages (owner ruling, 2026-08-11) — รับเรื่อง,
// เจรจาราคากับโรงงาน, รอ CEO อนุมัติราคา — bracketed by the two ends of the
// queue: SUBMITTED (nothing has picked it up yet) and CANCELLED (dead, but
// still needs to be findable). Deliberately absent: DRAFT, the sales rep's
// private scratchpad, which is never Import's work; and MORE_INFO_REQUIRED,
// whose ขอข้อมูลเพิ่มเติม feature was removed from the product entirely.
// "ทั้งหมด" still lets import/ceo/sales_manager see the full picture.
//
// `statuses` is a LIST, not a string, because เจรจาราคากับโรงงาน still has to
// match TWO stored values. V140 merged COSTING_IN_PROGRESS into
// AWAITING_FACTORY_RESPONSE and dropped it from the DB constraint, but rows sit
// in the old value for the whole window between this code deploying and the
// migration running — so the chip keeps matching both ON PURPOSE. Do not
// "simplify" it to a single status: doing so makes those rows unreachable from
// the chip that is supposed to find them, and the failure is invisible in tests
// because every fixture is already on the new value.
//
// GET /api/pricing-requests takes a SINGLE `status` value
// (PricingRequestController#list -> PricingRequestService.list, which 400s on
// anything that is not one valid status — and COSTING_IN_PROGRESS is no longer
// one), so a two-status chip cannot be expressed server-side at all. It
// therefore fetches everything (status=undefined) and narrows the rows below.
// Once V140 has run everywhere, this entry can collapse to a single-value chip
// and the client-side filter can go with it.
//
// Labels are read from pricingRequestStatusLabel (utils/format.js), the
// canonical source, so a chip can never drift from the badge on the row it
// filters to. Only the "all" chip carries its own label.
const STATUS_FILTERS = [
  { key: 'ALL', label: 'ทั้งหมด', statuses: [] },
  { key: 'SUBMITTED', statuses: ['SUBMITTED'] },
  { key: 'IMPORT_REVIEWING', statuses: ['IMPORT_REVIEWING'] },
  { key: 'FACTORY_NEGOTIATION', statuses: ['AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'] },
  { key: 'READY_FOR_CEO_REVIEW', statuses: ['READY_FOR_CEO_REVIEW'] },
  { key: 'CANCELLED', statuses: ['CANCELLED'] },
];

function statusFilterLabel(filter) {
  return filter.label ?? pricingRequestStatusLabel(filter.statuses[0]).label;
}

// Task tabs (GLA-110): a role-aware "what should I work on" view that sits above the
// existing status chips. This is pure client-side scoping over the SAME shared,
// unscoped /api/pricing-requests queue endpoint — everyone still sees every request
// under ทั้งหมด, so this is NOT an authz change (nothing here touches who the
// backend lets read what). Only import and ceo get task tabs: they are the two
// roles this queue is actually a worklist for (import triages/works requests,
// ceo approves them); sales_manager keeps browsing the plain chip list it always
// has, since it has no personal "my work" subset of this queue — it oversees the
// whole pipeline, so it lands on ทั้งหมด (owner ruling, 2026-09-19) rather than
// import's own SUBMITTED inbox.
//
// Each role's first tab doubles as its count badge and its default landing tab
// (Owner ruling: import starts on its own claimed work, ceo starts on what is
// waiting on it — neither should have to click past ทั้งหมด to find their own
// queue).
const IMPORT_MY_WORK_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'];
const CEO_MY_REVIEW_STATUSES = ['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'];

// 2026-09-19 owner ruling: a task tab (งานของฉัน / รอรับเรื่อง / รอฉันพิจารณา) is a worklist,
// not a browse view — the request that has been waiting longest belongs on top, so it doesn't
// get buried by newer arrivals. ทั้งหมด (and any role with no task tabs, e.g. sales_manager) is
// deliberately left in the API's own order, unaffected. `submittedAt` is when the request
// actually entered this queue; `createdAt` covers the rare DRAFT-era row without one yet, and
// `id` is the final, always-present tie-break (PricingRequestDtos.java ~36-39; mirrored in
// mockApi.js ~1686).
function byOldestFirst(a, b) {
  const aTime = new Date(a.submittedAt ?? a.createdAt ?? 0).getTime();
  const bTime = new Date(b.submittedAt ?? b.createdAt ?? 0).getTime();
  return aTime - bTime || a.id - b.id;
}

const TASK_TABS_BY_ROLE = {
  import: [
    { key: 'MY_WORK', label: 'งานของฉัน', hasBadge: true, emptyTitle: 'ไม่มีงานที่คุณรับเรื่องค้างอยู่' },
    { key: 'UNCLAIMED', label: 'รอรับเรื่อง', emptyTitle: 'ไม่มีคำขอที่รอรับเรื่อง' },
    { key: 'ALL', label: 'ทั้งหมด', emptyTitle: 'ไม่มีคำขอราคาในเงื่อนไขนี้' },
  ],
  ceo: [
    { key: 'MY_REVIEW', label: 'รอฉันพิจารณา', hasBadge: true, emptyTitle: 'ไม่มีคำขอราคาที่รอคุณพิจารณา' },
    { key: 'ALL', label: 'ทั้งหมด', emptyTitle: 'ไม่มีคำขอราคาในเงื่อนไขนี้' },
  ],
};

const DEFAULT_EMPTY_TITLE = 'ไม่มีคำขอราคาในเงื่อนไขนี้';
// Shown instead of a tab's own "nothing here" copy when its query actually failed — a fetch error
// read as confident "you have no work" is worse than no message at all (review finding, GLA-110).
const ERROR_EMPTY_TITLE = 'โหลดคิวขอราคาไม่สำเร็จ';

/**
 * GLA-110: the ผู้รับเรื่อง cell reads "คุณ" for the viewer's own claimed rows instead of their own
 * name — shared between the desktop column and the mobile QueueCard so the two can never drift.
 * Returns `null` (not '-') when there is no assignee at all, so a caller that wants to omit the
 * whole line (QueueCard) can tell "nobody yet" apart from "somebody, unnamed".
 */
function assigneeName(row, user) {
  if (row.assignedImportId != null && Number(row.assignedImportId) === Number(user?.id)) return 'คุณ';
  return row.assignedImportName ?? null;
}

function buildColumns(user) {
  return [
    {
      key: 'requestCode',
      header: 'เลขที่คำขอราคา',
      sortable: true,
      searchAccessor: (row) => row.requestCode,
      // A Link (not the whole-row `onRowClick` DataTable optionally offers) so
      // this table can carry a pickup <button> per row without nesting one
      // interactive element inside another — `onRowClick`'s own
      // interactive-target guard would let the button work fine, but this page
      // still opts out of it: with a per-row link *and* a per-row pickup
      // button already doing double duty, adding a third, whole-row target
      // would just be a redundant way to reach the same place.
      render: (row) => <Link to={`/pricing-requests/${row.id}`} className="text-xs text-info underline"><code>{row.requestCode}</code></Link>,
    },
    {
      key: 'deal',
      header: 'ดีล / ลูกค้า',
      searchAccessor: (row) => `${row.ticketCode ?? ''} ${row.customerName ?? ''} ${row.projectName ?? ''}`,
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-bold">{row.customerName ?? '-'}</span>
          <span className="text-2xs text-text-muted">{row.ticketCode}{row.projectName ? ` · ${row.projectName}` : ''}</span>
        </div>
      ),
    },
    {
      key: 'recipient',
      header: 'ผู้รับ',
      render: (row) => (
        <span>
          {pricingRequestRecipientLabel(row.recipientType)}
          {row.recipientLabel ? ` · ${row.recipientLabel}` : ''}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'สถานะ',
      sortable: true,
      searchAccessor: (row) => row.status,
      render: (row) => {
        const status = pricingRequestStatusLabel(row.status);
        return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
      },
    },
    {
      key: 'itemCount',
      header: 'จำนวนรายการ',
      align: 'right',
      sortable: true,
      render: (row) => row.itemCount,
    },
    {
      key: 'requiredDate',
      header: 'ต้องการภายใน',
      sortable: true,
      render: (row) => formatThaiDate(row.requiredDate),
    },
    {
      key: 'assignedImportName',
      header: 'ผู้รับเรื่อง',
      // GLA-110: "คุณ" for the viewer's own claimed rows — see assigneeName's own doc.
      // Most useful on งานของฉัน, where every row is this exact comparison, but left
      // on for every tab so a request picked up by "me" reads the same way from
      // ทั้งหมด too.
      render: (row) => assigneeName(row, user) ?? '-',
    },
  ];
}

function QueueCard({ row, user, onPickup, canPickup, pickingUp }) {
  const status = pricingRequestStatusLabel(row.status);
  // GLA-110 (review fix): this card never showed ผู้รับเรื่อง at all, so "คุณ" — the
  // whole point of assigneeName — never appeared on mobile. Omit the line entirely
  // rather than falling back to '-' the way the desktop column does: a card is
  // already dense, and "no assignee yet" is exactly what SUBMITTED rows in รอรับเรื่อง
  // look like, so the line would be near-permanent noise there.
  const assignee = assigneeName(row, user);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <Link to={`/pricing-requests/${row.id}`} className="min-w-0 truncate text-xs text-info underline" onClick={(event) => event.stopPropagation()}>
          <code>{row.requestCode}</code>
        </Link>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      <strong className="min-w-0 truncate text-md leading-snug font-extrabold text-text">
        {row.customerName ?? '-'}
      </strong>
      <span className="min-w-0 truncate text-xs text-text-muted">
        {row.ticketCode}{row.projectName ? ` · ${row.projectName}` : ''}
      </span>
      <span className="min-w-0 truncate text-xs text-text-muted">
        {pricingRequestRecipientLabel(row.recipientType)}{row.recipientLabel ? ` · ${row.recipientLabel}` : ''} · {row.itemCount} รายการ
      </span>
      {row.requiredDate ? (
        <span className="text-xs text-text-muted">ต้องการภายใน {formatThaiDate(row.requiredDate)}</span>
      ) : null}
      {assignee ? (
        <span className="text-xs text-text-muted">ผู้รับเรื่อง: {assignee}</span>
      ) : null}
      {canPickup ? (
        <Button
          type="button"
          variant="primary"
          className="mt-1"
          disabled={pickingUp}
          onClick={() => onPickup(row.id)}
        >
          รับเรื่อง
        </Button>
      ) : null}
    </>
  );
}

/**
 * Import's cross-deal PricingRequest queue (commit 6). Route-guarded by
 * ROLE_PERMISSIONS.canViewPricingRequestQueue (import/ceo/sales_manager) —
 * see app/permissions.js PATH_GUARDS for '/pricing-requests'.
 *
 * GLA-110: import and ceo additionally get a row of task tabs above the status
 * chips (TASK_TABS_BY_ROLE), each scoping the SAME shared queue endpoint down
 * to "what should I work on" — the queue stays global and unscoped (everyone
 * still sees everything under ทั้งหมด), this only changes what each role sees
 * by default:
 *   - import: งานของฉัน (its own claimed, still-open requests — IMPORT_REVIEWING /
 *     AWAITING_FACTORY_RESPONSE / legacy COSTING_IN_PROGRESS, see STATUS_FILTERS'
 *     comment on why that legacy value stays), รอรับเรื่อง (SUBMITTED, unclaimed),
 *     ทั้งหมด (today's chip list).
 *   - ceo: รอฉันพิจารณา (READY_FOR_CEO_REVIEW + CEO_REVIEWING — both are waiting on
 *     the CEO, the second because the CEO opened the review but hasn't decided
 *     yet), ทั้งหมด.
 *   - every other role that can reach this page (sales_manager): no tabs — chips
 *     only, defaulting to the ทั้งหมด chip (owner ruling, 2026-09-19: this role
 *     oversees the whole pipeline rather than owning a personal "my work" slice
 *     of it, so it should not land on import's own SUBMITTED inbox).
 * The first tab of each role doubles as its badge count, fetched via its own
 * `enabled: hasTaskTabs` query so the count still shows while a different tab is
 * active. Status chips render only inside the ทั้งหมด tab (or always, for roles
 * with no tabs) — every task tab is already status-scoped, so chips there would
 * just intersect down to nothing.
 *
 * 2026-09-19 additions:
 *   - Task-tab rows are re-sorted oldest-first (byOldestFirst) — a worklist, not a
 *     browse view. ทั้งหมด is untouched. `<DataTable key={activeTab}>` remounts on
 *     every tab switch (also resetting its search text and page, deliberately — a
 *     fresh worklist view per tab) so a manual column-header sort from a previous
 *     tab can never leak into the next one and fight this default.
 *   - An empty งานของฉัน that still has unclaimed work waiting (รอรับเรื่อง count > 0)
 *     shows a "ไม่มีงานค้าง" prompt (`role="status"`, so it's announced) with a button
 *     straight to รอรับเรื่อง, instead of a bare empty state — see `showEmptyWorkPrompt`
 *     below. This is why unclaimedQuery now runs whenever the role is import, not
 *     only while its own tab is active.
 *   - Review fix, same date: landing on รอรับเรื่อง (by tab click OR the prompt's
 *     button — `goToUnclaimed`) force-refetches it when stale, since it no longer
 *     gets react-query's own becomes-enabled refetch now that it's always enabled;
 *     the button additionally moves focus to the รอรับเรื่อง tab (Tabs.jsx's own DOM
 *     id convention), and a failed pickup now invalidates the same broad key a
 *     successful one does, so a 409 (someone else claimed it) drops the stale row.
 */
export function PricingRequestQueuePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const taskTabs = TASK_TABS_BY_ROLE[user.role] ?? null;
  const hasTaskTabs = !!taskTabs;
  const [activeTab, setActiveTab] = useState(taskTabs ? taskTabs[0].key : null);
  // Every role lands the ทั้งหมด tab/chip-only view on its own ทั้งหมด chip (owner ruling,
  // 2026-09-19) — task-tab roles because it is already status-scoped chrome on top of their own
  // default tab, and no-tab roles (sales_manager) because it oversees the whole pipeline rather
  // than owning a personal slice of it, so SUBMITTED — import's own inbox — is the wrong default.
  const [filterKey, setFilterKey] = useState('ALL');

  // GLA-110 (review fix): this page is never remounted on a role change (AppShell keeps one
  // instance across a session), so without this, a stale `activeTab` from a previous role could
  // point at a tab key the new role's TASK_TABS_BY_ROLE entry does not have at all (e.g. import's
  // 'UNCLAIMED' surviving into a ceo session) — every branch below falls through to the ทั้งหมด
  // behaviour in that case rather than crashing, but it would silently show the wrong default
  // instead of that role's own first tab. Re-derive both pieces of state the instant the role
  // changes, from the same defaults the initial useState above already encodes.
  useEffect(() => {
    const nextTabs = TASK_TABS_BY_ROLE[user.role] ?? null;
    setActiveTab(nextTabs ? nextTabs[0].key : null);
    setFilterKey('ALL');
  }, [user.role]);

  const filter = STATUS_FILTERS.find((f) => f.key === filterKey) ?? STATUS_FILTERS[0];

  // One status -> the API filters it server-side. Zero (ทั้งหมด) or two
  // (เจรจาราคากับโรงงาน) -> fetch everything and narrow below; see STATUS_FILTERS
  // for why the two-status chip cannot be pushed down to the query.
  const queryStatus = filter.statuses.length === 1 ? filter.statuses[0] : undefined;
  const allTabActive = !hasTaskTabs || activeTab === 'ALL';

  const allQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ status: queryStatus, activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ status: queryStatus, activeOnly: true }).then((r) => r.items ?? []),
    enabled: allTabActive,
  });

  // Import's งานของฉัน: the endpoint only takes one `status`, so this fetches
  // everything assigned to the current user and narrows to the three
  // still-open Import statuses client-side. `enabled: hasTaskTabs` (not
  // `activeTab === 'MY_WORK'`) is what keeps the badge counting while another
  // tab is active. The assignedImportId check is re-asserted client-side too
  // (cheap, and defence-in-depth against a mock/backend that returned more
  // than this exact filter asked for — CLAUDE.md's "mock more permissive than
  // production" is the direction that only ever surfaces in prod).
  const myWorkQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ assignedImportId: user.id, activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ assignedImportId: user.id, activeOnly: true }).then((r) => r.items ?? []),
    enabled: hasTaskTabs && user.role === 'import',
  });
  const myWorkRows = useMemo(
    () => (myWorkQuery.data ?? []).filter((row) => IMPORT_MY_WORK_STATUSES.includes(row.status)
      && row.assignedImportId != null && Number(row.assignedImportId) === Number(user.id)),
    [myWorkQuery.data, user.id],
  );

  // ceo's รอฉันพิจารณา: fetches everything (same params CeoOverview's own
  // price-approval card uses — queryKeys.pricingRequestQueue({ activeOnly: true })
  // — so the two share one cache entry) and narrows to the two "waiting on the
  // CEO" statuses client-side.
  const myReviewQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r.items ?? []),
    enabled: hasTaskTabs && user.role === 'ceo',
  });
  const myReviewRows = useMemo(
    () => (myReviewQuery.data ?? []).filter((row) => CEO_MY_REVIEW_STATUSES.includes(row.status)),
    [myReviewQuery.data],
  );

  // รอรับเรื่อง (import only): unclaimed work, pushed server-side as a plain SUBMITTED
  // filter. `enabled` is role-only, not tab-only, as of 2026-09-19 — an empty งานของฉัน
  // needs to know THIS count too (to offer "ไม่มีงานค้าง … ดูคำขอที่รอรับเรื่อง" instead of a
  // bare empty state, see showEmptyWorkPrompt below), so it must be known before its own
  // tab is ever opened, and import now always fetches this list once on load rather than
  // only after opening the tab. No badge is added here on purpose (still exactly one badge
  // on this page) — this is the SAME query/cache key as the (now-retired-as-a-default)
  // SUBMITTED chip, so nothing is fetched here that this page didn't already sometimes fetch
  // before.
  //
  // ⚠️ Freshness trade-off (review finding, 2026-09-19): because `enabled` no longer flips
  // false->true when this tab is opened, react-query's own "refetch on a query becoming
  // enabled" trigger no longer fires here — so without `goToUnclaimed` below explicitly
  // forcing a refetch, this is the single most-contended list on the page (several import
  // users racing to claim the same SUBMITTED rows) yet could sit on a stale cache entry for
  // up to `queryClient.js`'s 30s staleTime after switching in. `goToUnclaimed` closes that gap
  // by refetching on entry — but only `{ stale: true }`, i.e. only when the cached copy has
  // actually aged out, so a user bouncing between tabs inside the 30s window still gets the
  // cheap cache hit the rest of this page relies on.
  const unclaimedQueryKey = queryKeys.pricingRequestQueue({ status: 'SUBMITTED', activeOnly: true });
  const unclaimedQuery = useQuery({
    queryKey: unclaimedQueryKey,
    queryFn: () => api.pricingRequests.queue({ status: 'SUBMITTED', activeOnly: true }).then((r) => r.items ?? []),
    enabled: hasTaskTabs && user.role === 'import',
  });

  // Only the prompt button (below) sets this — a real tab click already keeps/receives focus
  // via the browser's own click handling, so this effect only needs to act on the ONE
  // navigation path that does not originate from clicking the tab itself.
  const focusUnclaimedTabRef = useRef(false);
  useEffect(() => {
    if (activeTab !== 'UNCLAIMED' || !focusUnclaimedTabRef.current) return;
    focusUnclaimedTabRef.current = false;
    // Tabs.jsx builds each tab's DOM id as `${idPrefix}-tab-${item.id}` — see its own render.
    document.getElementById('pcr-queue-tasks-tab-UNCLAIMED')?.focus();
  }, [activeTab]);

  // Shared by the Tabs onChange handler and the ไม่มีงานค้าง prompt's button — both are ways
  // to LAND on รอรับเรื่อง, so both need the same staleness fix above. `focus: true` is only
  // passed by the prompt button, whose click target (a button inside a DIFFERENT panel) is not
  // itself the รอรับเรื่อง tab, unlike a real tab click.
  function goToUnclaimed({ focus = false } = {}) {
    setActiveTab('UNCLAIMED');
    queryClient.refetchQueries({ queryKey: unclaimedQueryKey, stale: true });
    if (focus) focusUnclaimedTabRef.current = true;
  }

  const badgeRows = user.role === 'import' ? myWorkRows : myReviewRows;
  // The query backing whichever tab currently doubles as the badge (myWorkQuery for import,
  // myReviewQuery for ceo) — used both for the badge's own loading/success gating below and,
  // when that tab is the active one, for isLoading/isError in the branch further down.
  const badgeQuery = user.role === 'import' ? myWorkQuery : myReviewQuery;
  const activeTabMeta = taskTabs?.find((t) => t.key === activeTab);

  let rows;
  let isLoading;
  let isError;
  let emptyTitle = DEFAULT_EMPTY_TITLE;
  if (hasTaskTabs && (activeTab === 'MY_WORK' || activeTab === 'MY_REVIEW')) {
    rows = [...badgeRows].sort(byOldestFirst);
    isLoading = badgeQuery.isLoading;
    isError = badgeQuery.isError;
    emptyTitle = activeTabMeta?.emptyTitle ?? emptyTitle;
  } else if (hasTaskTabs && activeTab === 'UNCLAIMED') {
    rows = [...(unclaimedQuery.data ?? [])].sort(byOldestFirst);
    isLoading = unclaimedQuery.isLoading;
    isError = unclaimedQuery.isError;
    emptyTitle = activeTabMeta?.emptyTitle ?? emptyTitle;
  } else {
    // ทั้งหมด tab, or a role with no task tabs at all — today's chip behaviour, API order
    // preserved (no byOldestFirst here — this is a browse view, not a worklist).
    const items = allQuery.data ?? [];
    rows = filter.statuses.length < 2 ? items : items.filter((row) => filter.statuses.includes(row.status));
    isLoading = allQuery.isLoading;
    isError = allQuery.isError;
    emptyTitle = activeTabMeta?.emptyTitle ?? emptyTitle;
  }
  // A failed fetch reading as confident "nothing is yours"/"ทั้งหมด is empty" is worse than no
  // rows at all (review finding) — override the tab's own empty copy and icon, and never show
  // stale/partial rows underneath an error banner.
  if (isError) {
    rows = [];
    emptyTitle = ERROR_EMPTY_TITLE;
  }

  // 2026-09-19: an import user who has genuinely cleared งานของฉัน (loaded, zero rows, no
  // error) but still has unclaimed work sitting in รอรับเรื่อง should be pointed at it rather
  // than shown a bare "nothing here". `unclaimedQuery.isSuccess` (not just `.data`) is the gate
  // for the SAME reason the badge gates on it — "not yet loaded" and "genuinely zero" must
  // read differently, and the error branch above already wins over this regardless.
  const unclaimedCount = unclaimedQuery.isSuccess ? (unclaimedQuery.data?.length ?? 0) : null;
  const showEmptyWorkPrompt = user.role === 'import' && activeTab === 'MY_WORK'
    && !isLoading && !isError && rows.length === 0
    && unclaimedCount != null && unclaimedCount > 0;

  // GLA-110 (review fix): the badge must read as unknown — not a confident "0" — while its query
  // is loading, disabled, or errored. `isSuccess` is the one react-query flag that is only ever
  // true once real data has actually landed, so gate on that rather than on `data` (which this
  // page defaults to `[]` before the first response, making an unloaded badge indistinguishable
  // from a genuinely-empty one).
  const badgeCount = hasTaskTabs && badgeQuery.isSuccess ? badgeRows.length : null;
  const tabItems = taskTabs?.map((tab) => ({
    id: tab.key,
    label: tab.label,
    badge: tab.hasBadge ? badgeCount : undefined,
    // Read as "<label> N รายการ" instead of the ambiguous "<label>N" a bare number would produce
    // — see Tabs.jsx's own badgeLabel doc.
    badgeLabel: tab.hasBadge && badgeCount != null ? `${badgeCount} รายการ` : undefined,
  })) ?? [];

  const pickupMutation = useMutation({
    mutationFn: (id) => api.pricingRequests.pickup(id),
    onSuccess: () => {
      showToast('success', 'รับเรื่องแล้ว');
      queryClient.invalidateQueries({ queryKey: ['pricingRequests'] });
    },
    onError: (error) => {
      showToast('error', error.message || 'รับเรื่องไม่สำเร็จ');
      // 2026-09-19 review: the usual failure here is someone else already picked this row up
      // (a 409 compare-and-set miss, PricingRequestService.pickup) — the row this button was on
      // is already stale, so invalidate the same broad key onSuccess uses to drop it from
      // รอรับเรื่อง/งานของฉัน's badge on the next render instead of leaving a dead รับเรื่อง
      // button pointed at a request that already belongs to someone else.
      queryClient.invalidateQueries({ queryKey: ['pricingRequests'] });
    },
  });

  const columns = [
    ...buildColumns(user),
    {
      key: 'pickup',
      header: '',
      render: (row) => (
        canPickupPricingRequest(user, row) ? (
          <Button
            type="button"
            variant="secondary"
            disabled={pickupMutation.isPending}
            onClick={() => pickupMutation.mutate(row.id)}
            data-testid="pcr-queue-pickup"
          >
            รับเรื่อง
          </Button>
        ) : null
      ),
    },
  ];

  const chipsAndTable = (
    <>
      {allTabActive ? (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สถานะ</span>
          {STATUS_FILTERS.map((option) => {
            const active = filterKey === option.key;
            const label = statusFilterLabel(option);
            return (
              <button
                key={option.key}
                type="button"
                aria-pressed={active}
                className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
                }`}
                onClick={() => setFilterKey(option.key)}
              >
                {label}
              </button>
            );
          })}
        </div>
      ) : null}

      {showEmptyWorkPrompt ? (
        // Not DataTable's own emptyState (EmptyState.jsx has no action/button slot, and this is
        // one specific page's prompt, not a case for widening a shared component) — a small local
        // block reusing the same visual rhythm (icon + heading + sub-line) instead.
        // `role="status"` (implicit aria-live="polite") announces it — a screen-reader user who
        // just cleared their last claimed request should hear "ไม่มีงานค้าง · มี N คำขอรอรับเรื่อง"
        // instead of silence.
        <div role="status" className="grid min-h-[220px] place-items-center content-center gap-2 rounded-md border border-border bg-surface p-6 text-center">
          <Icon name="badgeCheck" size={34} className="text-text-faint" />
          <strong className="text-text">ไม่มีงานค้าง</strong>
          <span className="text-text-muted">มี {unclaimedCount} คำขอรอรับเรื่อง</span>
          <Button type="button" variant="primary" className="mt-1" onClick={() => goToUnclaimed({ focus: true })}>
            ดูคำขอที่รอรับเรื่อง
          </Button>
        </div>
      ) : (
        <DataTable
          // Remounts on every tab switch (stable across chip clicks within one tab, since
          // `activeTab` itself doesn't change for those): a manual column-header sort from a
          // previous tab is uncontrolled internal DataTable state, and without this key it would
          // silently carry over and fight byOldestFirst's default ordering on the next tab. This
          // also resets DataTable's own search text and pagination page on every tab switch —
          // deliberate, not a side effect to work around: each tab is its own fresh worklist
          // view, not a continuation of whatever was typed/paged into a different one.
          key={activeTab ?? 'no-tabs'}
          columns={columns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="pricing-request-queue-table"
          mobileCard={(row) => (
            <QueueCard
              row={row}
              user={user}
              canPickup={canPickupPricingRequest(user, row)}
              pickingUp={pickupMutation.isPending}
              onPickup={(id) => pickupMutation.mutate(id)}
            />
          )}
          searchable
          loading={isLoading}
          emptyState={{ icon: isError ? 'triangleAlert' : 'fileText', title: emptyTitle }}
        />
      )}
    </>
  );

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <SalesTabs role={user.role} />
      <PageHeader
        title="คิวขอราคา"
        subtitle="คำขอราคาที่รอฝ่ายนำเข้าดำเนินการ"
        actions={(
          <Button
            type="button"
            variant="icon"
            onClick={() => queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] })}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </Button>
        )}
      />

      {taskTabs ? (
        <>
          <Tabs
            items={tabItems}
            value={activeTab}
            // A plain tab click still needs the same on-entry refetch-if-stale as the prompt's
            // button — both are ways to land on รอรับเรื่อง — so only that one id is special-cased.
            onChange={(tabId) => (tabId === 'UNCLAIMED' ? goToUnclaimed() : setActiveTab(tabId))}
            ariaLabel="งานที่ต้องทำ"
            idPrefix="pcr-queue-tasks"
          />
          <TabPanel id={activeTab} idPrefix="pcr-queue-tasks" active>
            {chipsAndTable}
          </TabPanel>
        </>
      ) : chipsAndTable}
    </div>
  );
}
