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

// Import's stages (รับเรื่อง, เจรจาราคากับโรงงาน, รอ CEO อนุมัติราคา) bracketed by SUBMITTED
// (not picked up yet) and CANCELLED (dead, but must stay findable). DRAFT is the sales rep's
// private scratchpad, never Import's work; MORE_INFO_REQUIRED no longer exists.
// "ทั้งหมด" shows the full queue to import/ceo/sales_manager.
//
// `statuses` is a LIST because เจรจาราคากับโรงงาน must match TWO stored values: rows can still
// sit in COSTING_IN_PROGRESS (merged into AWAITING_FACTORY_RESPONSE by migration V140) until the
// migration runs. Do not "simplify" to one status: those rows would become unreachable, and tests
// would not notice because every fixture uses the new value.
//
// GET /api/pricing-requests takes a SINGLE `status` (PricingRequestService.list 400s on anything
// else), so a two-status chip cannot be expressed server-side: fetch everything
// (status=undefined) and narrow the rows below. Once V140 has run everywhere this can collapse
// to one value and the client-side filter can go.
//
// Labels come from pricingRequestStatusLabel (utils/format.js) so a chip never drifts from the
// row badge. Only the "all" chip carries its own label.
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

// Task tabs: a role-aware "what should I work on" view above the status chips. Pure client-side
// scoping over the SAME unscoped /api/pricing-requests endpoint (everyone still sees every
// request under ทั้งหมด), so NOT an authz change. Only import and ceo get task tabs: the queue
// is their worklist. sales_manager oversees the whole pipeline, so it has no tabs and lands on
// ทั้งหมด rather than import's SUBMITTED inbox.
//
// Each role's first tab is its count badge and default landing tab (import: its own claimed
// work; ceo: what waits on it).
const IMPORT_MY_WORK_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'];
const CEO_MY_REVIEW_STATUSES = ['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'];

// A task tab (งานของฉัน / รอรับเรื่อง / รอฉันพิจารณา) is a worklist: longest-waiting request on
// top. ทั้งหมด (and roles with no task tabs) keeps the API's own order. `submittedAt` is when it
// entered this queue; `createdAt` covers a DRAFT-era row without one; `id` is the final tie-break
// (PricingRequestDtos.java ~36-39; mirrored in mockApi.js ~1686).
function byOldestFirst(a, b) {
  const aTime = new Date(a.submittedAt ?? a.createdAt ?? 0).getTime();
  const bTime = new Date(b.submittedAt ?? b.createdAt ?? 0).getTime();
  return aTime - bTime || a.id - b.id;
}

const TASK_TABS_BY_ROLE = {
  import: [
    { key: 'MY_WORK', label: 'งานของฉัน', hasBadge: true, emptyTitle: 'ไม่มีงานที่คุณรับเรื่องค้างอยู่' },
    { key: 'UNCLAIMED', label: 'รอรับเรื่อง', hasBadge: true, emptyTitle: 'ไม่มีคำขอที่รอรับเรื่อง' },
    { key: 'ALL', label: 'ทั้งหมด', emptyTitle: 'ไม่มีคำขอราคาในเงื่อนไขนี้' },
  ],
  ceo: [
    { key: 'MY_REVIEW', label: 'รอฉันพิจารณา', hasBadge: true, emptyTitle: 'ไม่มีคำขอราคาที่รอคุณพิจารณา' },
    { key: 'ALL', label: 'ทั้งหมด', emptyTitle: 'ไม่มีคำขอราคาในเงื่อนไขนี้' },
  ],
};

const DEFAULT_EMPTY_TITLE = 'ไม่มีคำขอราคาในเงื่อนไขนี้';
// Shown instead of a tab's own "nothing here" copy when its query failed: a fetch error read as
// "you have no work" is worse than no message.
const ERROR_EMPTY_TITLE = 'โหลดคิวขอราคาไม่สำเร็จ';

/**
 * The ผู้รับเรื่อง cell reads "คุณ" for the viewer's own claimed rows; shared by the desktop column
 * and the mobile QueueCard so they cannot drift. Returns `null` (not '-') when there is no
 * assignee, so a caller can omit the whole line (QueueCard) and tell "nobody yet" apart from
 * "somebody, unnamed".
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
      // "คุณ" for the viewer's own claimed rows, on every tab (see assigneeName).
      render: (row) => assigneeName(row, user) ?? '-',
    },
  ];
}

function QueueCard({ row, user, onPickup, canPickup, pickingUp }) {
  const status = pricingRequestStatusLabel(row.status);
  // Omit the line when there is no assignee instead of '-' like the desktop column: the card is
  // dense, and "no assignee yet" is what every SUBMITTED row in รอรับเรื่อง looks like.
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
 * Import's cross-deal PricingRequest queue. Route-guarded by
 * ROLE_PERMISSIONS.canViewPricingRequestQueue (import/ceo/sales_manager),
 * see app/permissions.js PATH_GUARDS for '/pricing-requests'.
 *
 * import and ceo also get task tabs above the status chips (TASK_TABS_BY_ROLE), each scoping the
 * SAME shared queue endpoint to "what should I work on"; everyone still sees everything under
 * ทั้งหมด:
 *   - import: งานของฉัน (its own claimed, still-open requests: IMPORT_REVIEWING /
 *     AWAITING_FACTORY_RESPONSE / legacy COSTING_IN_PROGRESS, see STATUS_FILTERS), รอรับเรื่อง
 *     (SUBMITTED, unclaimed), ทั้งหมด.
 *   - ceo: รอฉันพิจารณา (READY_FOR_CEO_REVIEW + CEO_REVIEWING, both waiting on the CEO), ทั้งหมด.
 *   - sales_manager: no tabs, chips only, defaulting to ทั้งหมด.
 * The first tab of each role doubles as its badge count, fetched via its own
 * `enabled: hasTaskTabs` query so the count shows while another tab is active. Status chips
 * render only inside ทั้งหมด (or always, for roles with no tabs): task tabs are already
 * status-scoped, so chips there would intersect down to nothing.
 *
 * - Task-tab rows are sorted oldest-first (byOldestFirst); ทั้งหมด is untouched.
 *   `<DataTable key={activeTab}>` remounts on every tab switch (resetting search text and page on
 *   purpose) so a manual column sort from a previous tab cannot fight this default.
 * - An empty งานของฉัน with unclaimed work waiting (รอรับเรื่อง count > 0) shows a "ไม่มีงานค้าง"
 *   prompt (`role="status"`, so it is announced) with a button to รอรับเรื่อง instead of a bare
 *   empty state (`showEmptyWorkPrompt`). That is why unclaimedQuery runs for the whole import
 *   role, not only while its own tab is active.
 * - Landing on รอรับเรื่อง (tab click or the prompt button, `goToUnclaimed`) refetches it when
 *   stale; the button also moves focus to that tab (Tabs.jsx's DOM id convention). A failed
 *   pickup invalidates the same broad key as a successful one, so a 409 (someone else claimed
 *   it) drops the stale row.
 */
export function PricingRequestQueuePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const taskTabs = TASK_TABS_BY_ROLE[user.role] ?? null;
  const hasTaskTabs = !!taskTabs;
  const [activeTab, setActiveTab] = useState(taskTabs ? taskTabs[0].key : null);
  // Every role lands on its own ทั้งหมด chip in the ทั้งหมด view: task-tab roles because it is
  // already status-scoped on top of their default tab, sales_manager because it oversees the
  // whole pipeline and SUBMITTED (import's inbox) is the wrong default.
  const [filterKey, setFilterKey] = useState('ALL');

  // AppShell keeps one instance of this page across a session, so a stale `activeTab` from a
  // previous role could name a tab the new role's TASK_TABS_BY_ROLE entry lacks (import's
  // 'UNCLAIMED' surviving into a ceo session). Branches fall through to ทั้งหมด rather than
  // crash, but would show the wrong default. Re-derive both states when the role changes.
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

  // รอรับเรื่อง (import only): unclaimed work, pushed server-side as a plain SUBMITTED filter.
  // `enabled` is role-only, not tab-only: an empty งานของฉัน needs this count (for the
  // "ไม่มีงานค้าง" prompt, see showEmptyWorkPrompt) and the tab badge reuses it, so import
  // always fetches this list on load. Same query/cache key as the SUBMITTED chip.
  //
  // Freshness trade-off: because `enabled` never flips false->true on tab open, react-query's
  // refetch-on-enable does not fire. This is the most contended list (several import users
  // racing to claim the same rows) and could sit on a stale entry for queryClient.js's 30s
  // staleTime, so `goToUnclaimed` refetches on entry, only `{ stale: true }`, keeping the cheap
  // cache hit when bouncing between tabs inside the 30s window.
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

  // Shared by the Tabs onChange and the ไม่มีงานค้าง prompt's button: both land on รอรับเรื่อง and
  // need the staleness fix above. `focus: true` is passed only by the prompt button, whose click
  // target is not itself the รอรับเรื่อง tab.
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

  // An import user who cleared งานของฉัน (loaded, zero rows, no error) but has unclaimed work in
  // รอรับเรื่อง is pointed at it instead of a bare "nothing here". Gate on
  // `unclaimedQuery.isSuccess` (not `.data`) like the badge: "not yet loaded" and "zero" must
  // read differently. The error branch above wins regardless.
  const unclaimedCount = unclaimedQuery.isSuccess ? (unclaimedQuery.data?.length ?? 0) : null;
  const showEmptyWorkPrompt = user.role === 'import' && activeTab === 'MY_WORK'
    && !isLoading && !isError && rows.length === 0
    && unclaimedCount != null && unclaimedCount > 0;

  // The badge must read as unknown, not "0", while its query is loading, disabled or errored.
  // `isSuccess` is true only once real data has landed; `data` defaults to `[]` here, which
  // would make an unloaded badge look genuinely empty.
  const badgeCount = hasTaskTabs && badgeQuery.isSuccess ? badgeRows.length : null;
  // รอรับเรื่อง has its OWN badge from the unclaimedCount computed above; badgeCount is the
  // role's first-tab count. unclaimedCount is import-only (null for ceo, which has no
  // รอรับเรื่อง tab).
  const tabBadgeCount = (key) => (key === 'UNCLAIMED' ? unclaimedCount : badgeCount);
  const tabItems = taskTabs?.map((tab) => {
    const count = tab.hasBadge ? tabBadgeCount(tab.key) : null;
    return {
      id: tab.key,
      label: tab.label,
      badge: count ?? undefined,
      // Read as "<label> N รายการ" instead of the ambiguous "<label>N" a bare number would produce
      // — see Tabs.jsx's own badgeLabel doc.
      badgeLabel: count != null ? `${count} รายการ` : undefined,
    };
  }) ?? [];

  const pickupMutation = useMutation({
    mutationFn: (id) => api.pricingRequests.pickup(id),
    onSuccess: () => {
      showToast('success', 'รับเรื่องแล้ว');
      queryClient.invalidateQueries({ queryKey: ['pricingRequests'] });
    },
    onError: (error) => {
      showToast('error', error.message || 'รับเรื่องไม่สำเร็จ');
      // The usual failure is someone else already picked this row up (409 compare-and-set miss,
      // PricingRequestService.pickup): the row is stale, so invalidate the same broad key as
      // onSuccess to drop it from the badges instead of leaving a dead รับเรื่อง button.
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
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover'
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
          // Remounts on every tab switch (stable across chip clicks within one tab): a manual
          // column sort is uncontrolled DataTable state and would carry over and fight
          // byOldestFirst. Also resets search text and page, on purpose: each tab is a fresh
          // worklist view.
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
