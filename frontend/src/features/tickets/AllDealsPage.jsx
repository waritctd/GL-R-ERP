import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { DealBoard } from '../salesBoard/DealBoard.jsx';
import {
  currentQuarter, otherCurrencyTotals, sumValue, totalLabel,
} from '../salesBoard/boardFormat.js';
import {
  boardDealMatches, closedCounts, readStoredView, resolveDealsView, sortByQuiet, writeStoredView,
} from './allDealsView.js';
import { rangeFromParams } from './movementRange.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { TicketListPage } from './TicketListPage.jsx';

/** Roles whose /tickets is งานทั้งหมด with a board (spec 2026-11-15 §7.1, review a-board). */
const BOARD_ROLES = new Set(['sales', 'sales_manager', 'ceo']);

/**
 * /tickets — งานทั้งหมด. sales / sales_manager / ceo open on the five-column board and can switch to the existing
 * table (ตาราง ⇄ บอร์ด); the choice is remembered per user. A deep link carrying a table-only filter
 * (`?owner=`, `?life=`, …) opens the table, so no existing link loses its filter. Any other role that reaches
 * this route gets the table alone, as before.
 */
export function AllDealsPage({ user, showToast }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [stored, setStored] = useState(() => readStoredView(user));

  if (!BOARD_ROLES.has(user.role)) return <TicketListPage user={user} showToast={showToast} />;

  const view = resolveDealsView(searchParams, stored);
  function switchTo(next) {
    writeStoredView(user, next);
    setStored(next);
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      params.set('view', next);
      return params;
    }, { replace: true });
  }

  if (view === 'table') {
    return (
      <TicketListPage
        user={user}
        showToast={showToast}
        viewSwitch={(
          <Button type="button" variant="secondary" onClick={() => switchTo('board')}>
            <Icon name="dashboard" size={16} />
            บอร์ด
          </Button>
        )}
      />
    );
  }
  return <AllDealsBoard user={user} showToast={showToast} onShowTable={() => switchTo('table')} />;
}

const CHIP_CLASS = 'mobile:min-h-11';

/** The paused / closed deals the board leaves out, each a link to the table filtered to it. */
function ClosedLinks({ counts }) {
  const items = [
    { life: 'ON_HOLD', label: 'พักไว้', always: true },
    { life: 'DORMANT', label: 'พักยาว', always: false },
    { life: 'CLOSED_LOST', label: 'เสียงาน', always: true },
    { life: 'CANCELLED', label: 'ยกเลิก', always: true },
  ].filter((item) => item.always || counts[item.life] > 0);
  return (
    <nav aria-label="งานที่พักไว้หรือปิดแล้ว" className="flex flex-wrap items-center gap-x-1 text-sm text-text-muted">
      {items.map((item, index) => (
        <span key={item.life} className="inline-flex items-center gap-1">
          {index > 0 ? <span aria-hidden="true">·</span> : null}
          <Link
            to={`/tickets?view=table&life=${item.life}`}
            className={`inline-flex items-center gap-1 rounded px-1 text-text-muted no-underline hover:text-text hover:underline focus-visible:underline ${CHIP_CLASS}`}
          >
            {item.label}{' '}<b className="font-bold tabular-nums">{counts[item.life]}</b>
          </Link>
        </span>
      ))}
      <Icon name="chevronRight" size={14} aria-hidden="true" />
    </nav>
  );
}

const FILTERS = [
  { key: 'quarter', label: 'ไตรมาสนี้' },
  { key: 'all', label: 'ทั้งหมด' },
  { key: 'quiet', label: 'เฉพาะงานเงียบ' },
];

function AllDealsBoard({ user, showToast, onShowTable }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [filterChoice, setFilterChoice] = useState(null);
  const [creating, setCreating] = useState(false);
  const isTeam = user.role !== 'sales';
  const searchText = searchParams.get('q') ?? '';
  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  // A rep's board is their own my-work board (the same rows งานของฉัน shows); managers and the CEO read every rep's
  // open deals from the team board. Both are SalesBoardRepository rows: value, quarter and quiet come from the server.
  const boardQuery = useQuery({
    queryKey: isTeam ? queryKeys.salesBoardTeamBoard() : queryKeys.salesBoardMyWork(),
    queryFn: () => (isTeam ? api.salesBoard.teamBoard() : api.salesBoard.myWork()),
  });
  // The พักไว้ / เสียงาน / ยกเลิก counts come from the same ticket list (and cache entry) the table renders, so each
  // count equals the rows its link opens.
  const range = useMemo(() => rangeFromParams(null, null), []);
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(range.from, range.to),
    queryFn: () => api.tickets.list({ movementFrom: range.from, movementTo: range.to })
      .then((response) => response.tickets || []),
  });

  const allDeals = boardQuery.data?.board;
  const searched = useMemo(
    () => (allDeals ?? []).filter((deal) => boardDealMatches(deal, searchText)),
    [allDeals, searchText],
  );
  // Always ทั้งหมด first (ruling 2026-11-16): a team board is full of other quarters, and opening on ไตรมาสนี้ could show
  // nothing at all. งานของฉัน keeps its own rule (defaultBoardFilter).
  const filter = filterChoice ?? 'all';
  const quarter = currentQuarter();
  const shown = useMemo(() => sortByQuiet(
    filter === 'quarter' ? searched.filter((d) => d.expectedQuarter === quarter)
      : filter === 'quiet' ? searched.filter((d) => d.quiet) : searched,
  ), [filter, quarter, searched]);
  // The quiet chip's n is the quiet deals in the search results, in every quarter: exactly the cards pressing it
  // shows (it replaces the quarter chip but keeps the search).
  const quietCount = searched.filter((d) => d.quiet).length;
  const narrowed = filter !== 'all' || searchText.trim() !== '';
  const counts = closedCounts(ticketsQuery.data);

  const createMutation = useMutation({
    mutationFn: (payload) => api.tickets.create(payload),
    onSuccess: (response) => {
      setCreating(false);
      showToast('success', 'สร้างงานเรียบร้อย');
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      const id = response?.ticket?.summary?.id;
      if (id != null) navigate(`/tickets/${id}`);
    },
  });

  function setSearch(value) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set('q', value); else next.delete('q');
      return next;
    }, { replace: true });
  }

  function showEverything() {
    setFilterChoice('all');
    setSearch('');
  }

  const subtitle = boardQuery.isError && !allDeals ? 'โหลดงานไม่สำเร็จ'
    : allDeals
      ? `${shown.length} งานที่เปิดอยู่ · มูลค่ารวม ${totalLabel(sumValue(shown), otherCurrencyTotals(shown))} · เรียงตามวันที่เงียบ`
      : 'กำลังโหลดงาน…';

  return (
    <PageStack>
      <PageHeader
        title="งานทั้งหมด"
        subtitle={subtitle}
        subtitleClassName="tabular-nums"
        actionsClassName="mobile:grid mobile:w-full mobile:grid-cols-2 mobile:gap-2 [&>*:only-child]:mobile:col-span-2"
        actions={(
          <>
            <Button type="button" variant="secondary" onClick={onShowTable}>
              <Icon name="menu" size={16} />
              ตาราง
            </Button>
            {canCreate ? (
              <Button type="button" onClick={() => setCreating(true)}>
                <Icon name="plus" size={16} />
                งานใหม่
              </Button>
            ) : null}
          </>
        )}
      />

      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <label className="relative flex w-80 max-w-full items-center mobile:w-full">
          <span className="sr-only">ค้นหางาน</span>
          <Icon name="search" size={16} className="pointer-events-none absolute left-3 text-icon-muted" aria-hidden="true" />
          <input
            type="search"
            aria-label="ค้นหางาน"
            placeholder="ค้นหาโครงการ ลูกค้า ผู้ออกแบบ"
            value={searchText}
            onChange={(event) => setSearch(event.target.value)}
            className="min-h-[38px] w-full rounded-md border border-border-input bg-surface py-1.5 pl-9 pr-3 text-sm text-text placeholder:text-text-faint focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary mobile:min-h-11 mobile:text-base"
          />
        </label>
        <div role="group" aria-label="กรองงาน" className="flex flex-wrap gap-1.5">
          {FILTERS.map((f) => (
            <Button
              key={f.key}
              type="button"
              size="sm"
              variant={filter === f.key ? 'primary' : 'secondary'}
              aria-pressed={filter === f.key}
              className={CHIP_CLASS}
              onClick={() => setFilterChoice(f.key)}
            >
              {f.key === 'quiet' ? `${f.label} ${quietCount}` : f.label}
            </Button>
          ))}
        </div>
        <span className="flex-1 mobile:hidden" aria-hidden="true" />
        <ClosedLinks counts={counts} />
      </div>

      <section aria-label="บอร์ดงานทั้งหมด" className="min-w-0 rounded-md border border-border bg-surface p-3.5 mobile:p-2.5">
        {boardQuery.isError && !allDeals ? (
          // Fails soft (e.g. a backend image that predates /api/sales/team-board): say so, retry, or use the table,
          // which reads the ticket list and does not depend on the board endpoint.
          <div role="alert" className="flex flex-wrap items-center gap-3 px-1 py-2">
            <p className="m-0 text-sm font-bold text-danger">โหลดบอร์ดไม่สำเร็จ ลองอีกครั้ง หรือดูแบบตาราง</p>
            <span className="flex flex-wrap gap-2">
              <Button type="button" variant="secondary" size="sm" className={CHIP_CLASS} onClick={() => boardQuery.refetch()}>
                <Icon name="refresh" size={14} />
                ลองอีกครั้ง
              </Button>
              <Button type="button" size="sm" className={CHIP_CLASS} onClick={onShowTable}>
                <Icon name="menu" size={14} />
                เปิดแบบตาราง
              </Button>
            </span>
          </div>
        ) : !allDeals ? (
          <div className="grid gap-2" role="status" aria-busy="true" aria-label="กำลังโหลดงานทั้งหมด">
            <Skeleton height={36} />
            <Skeleton height={36} />
          </div>
        ) : (
          narrowed && shown.length === 0 ? (
            <div className="grid justify-items-start gap-2 px-1 py-6">
              <p className="m-0 text-sm font-bold text-text">
                {searchText.trim() && searched.length === 0
                  ? `ไม่พบงานที่ตรงกับ “${searchText.trim()}”`
                  : 'ไม่มีงานในตัวกรองนี้'}
              </p>
              <Button type="button" variant="secondary" size="sm" className={CHIP_CLASS} onClick={showEverything}>
                แสดงงานทั้งหมด
              </Button>
            </div>
          ) : (
            <DealBoard deals={shown} showOwner={isTeam} />
          )
        )}
      </section>

      {creating ? (
        <TicketCreateModal
          user={user}
          onClose={() => setCreating(false)}
          onSubmit={async (payload) => {
            try {
              await createMutation.mutateAsync(payload);
            } catch (error) {
              showToast('error', error?.message || 'สร้างงานไม่สำเร็จ');
              throw error;
            }
          }}
        />
      ) : null}
    </PageStack>
  );
}
