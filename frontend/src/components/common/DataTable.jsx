import { Fragment, isValidElement, useEffect, useMemo, useRef, useState } from 'react';
import {
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { Button } from './Button.jsx';
import { EmptyState } from './EmptyState.jsx';
import { Icon } from './Icon.jsx';
import { Skeleton } from './Skeleton.jsx';

/**
 * Formalized `SortHeader` (lifted from TicketListPage) — a clickable
 * `columnheader` cell for sortable columns. Styled via `.sort-header` /
 * `.is-active` in styles.css (no inline hex).
 */
function SortHeader({ label, active, dir, onSort }) {
  return (
    <button
      type="button"
      className={`sort-header${active ? ' is-active' : ''}`}
      onClick={onSort}
    >
      {label}
      <Icon
        name={active ? (dir === 'asc' ? 'chevronUp' : 'chevronDown') : 'chevronDown'}
        size={13}
        style={{ opacity: active ? 1 : 0.3 }}
      />
    </button>
  );
}

function defaultCompare(a, b) {
  if (a == null && b == null) return 0;
  if (a == null) return -1;
  if (b == null) return 1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  const aDate = a instanceof Date ? a.getTime() : null;
  const bDate = b instanceof Date ? b.getTime() : null;
  if (aDate != null && bDate != null) return aDate - bDate;
  return String(a).localeCompare(String(b), 'th');
}

function textFromRendered(value) {
  if (value == null || typeof value === 'boolean') return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(textFromRendered).join('');
  if (isValidElement(value)) return textFromRendered(value.props.children);
  return String(value);
}

// Elements a row-level click handler must defer to instead of hijacking — a
// button, link (e.g. the identity-cell `<Link>` a caller renders per FIX A),
// or form control nested in a cell should fire its own handler/navigation,
// not the row's `onRowClick`. FIX F8 (review-remediation): also covers
// `label`/`summary`/`option`, the ARIA roles a screen-reader user could land
// on with a click (`link`/`checkbox`/`menuitem`), and an explicitly
// focusable `[tabindex]` element — but NOT `tabindex="-1"` specifically:
// that value means "programmatically focusable, not in the tab order" (e.g.
// a wrapper a caller focuses imperatively after some action), not "this is a
// real interactive control the row must defer to". Treating it as
// interactive would silently kill row activation for any row that happens to
// contain one, so it is carved out of the selector.
const INTERACTIVE_SELECTOR = [
  'button',
  'a[href]',
  'input',
  'textarea',
  'select',
  'label',
  'summary',
  'option',
  '[role="button"]',
  '[role="link"]',
  '[role="checkbox"]',
  '[role="menuitem"]',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

function isInteractiveTarget(target, boundary) {
  if (typeof target?.closest !== 'function') return false;
  const match = target.closest(INTERACTIVE_SELECTOR);
  if (!match) return false;
  // FIX F8: `closest()` walks all the way up to the document root, not just
  // to `boundary` (the row) — the old `match !== boundary` check only ever
  // excluded the row matching itself (which it never does), so a match found
  // *outside* the row (e.g. some ancestor toolbar button) would incorrectly
  // suppress row activation. Require the match to actually be inside the row.
  return match !== boundary && typeof boundary?.contains === 'function' && boundary.contains(match);
}

function csvEscape(value) {
  const stringValue = value == null ? '' : String(value);
  if (!/[",\r\n]/.test(stringValue)) return stringValue;
  return `"${stringValue.replaceAll('"', '""')}"`;
}

function buildCsv(columns, rows) {
  const headers = columns.map((column) => (typeof column.header === 'string' ? column.header : column.key));
  const lines = [
    headers.map(csvEscape).join(','),
    ...rows.map((row) => columns.map((column) => {
      const value = typeof column.searchAccessor === 'function'
        ? column.searchAccessor(row)
        : textFromRendered(column.render(row));
      return csvEscape(value);
    }).join(',')),
  ];
  return lines.join('\r\n');
}

// FIX F9: this used to be two different strings — 'ไม่มีข้อมูลที่จะแสดง' for
// the sr-only zero-row announcement and 'ไม่พบข้อมูล' for the EmptyState
// fallback title — despite meaning the same thing and being unreachable in
// practice today (every current caller passes its own `emptyState.title`).
// One shared constant so a future caller that omits `emptyState.title` gets
// the same wording in both places instead of a silent mismatch.
const DEFAULT_EMPTY_MESSAGE = 'ไม่พบข้อมูล';

function downloadCsv(csv, filename = 'data-table-export.csv') {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * Generic, controlled-data table primitive. Keeps emitting the exact
 * `{gridClassName} table-head` / `{gridClassName} data-row` classes the
 * per-page `grid-template-columns` CSS keys off, while formalizing sorting,
 * in-table search, client pagination, loading skeletons, and an empty state.
 *
 * `rows` should already reflect any page-level filters (status tabs, date
 * range, etc.) — this component only owns search/sort/pagination on top of
 * whatever it's given.
 */
export function DataTable({
  columns,
  rows,
  getRowKey,
  gridClassName,
  pageSize = 20,
  searchable = false,
  searchPlaceholder = 'ค้นหา...',
  searchValue,
  onSearchChange,
  rowClassName,
  rowStyle,
  loading = false,
  error,
  errorMessage = 'โหลดข้อมูลไม่สำเร็จ ข้อมูลเดิมจะยังแสดงอยู่ถ้ามีอยู่แล้ว',
  retryLabel = 'ลองอีกครั้ง',
  onRetry,
  emptyState,
  // FIX G: the pre-DataTable `.ticket-result-count` band showed "ตรงเงื่อนไข
  // N จาก M รายการ" (N matched out of M total), so a filter's own effect
  // (how much it hid) stayed visible. This footer's own N (`sortedRows.length`
  // below) already states "matched", but folding that band into this footer
  // dropped M — the caller can pass its unfiltered population size here to
  // restore it as an appended "(กรองจาก M)" clause. Omit it, or pass a value
  // equal to the matched count, to render the footer exactly as before.
  unfilteredTotal,
  initialSort,
  sort,
  onSortChange,
  toolbarExtra,
  caption,
  stickyHeader = false,
  exportable = false,
  onExportCsv,
  mobileCard,
  // Optional per-row detail panel. Return an element to expand that row, null to leave it
  // collapsed. Callers should expose their own explicit expand/open control.
  renderExpanded,
  // Optional semantic table footer. Receives the filtered/sorted rows before pagination so callers
  // can reconcile the full visible result set, not just the current page.
  footerRow,
  showPagination = true,
  // Optional row-level navigation/activation. Purely additive and default-off:
  // every existing caller that omits this prop renders and behaves exactly as
  // before. When provided, clicking the desktop `<tr>` (outside of a nested
  // interactive control) fires `onRowClick` as a *mouse-only convenience* —
  // it is not a keyboard or assistive-tech affordance. `role="button"` on a
  // `<tr>` is a "Children Presentational: True" role in ARIA 1.2: it prunes
  // every descendant (customer name, badges, the stage cell, etc.) from the
  // accessibility tree and replaces name computation with a single
  // `aria-label`, so an earlier version of this component that gave the row
  // that role/tabIndex/keydown made every other cell unreachable to a screen
  // reader (FIX A). The caller is expected to render a real `<Link>` in its
  // identity column instead — that is the keyboard and assistive-tech
  // activation target, and it also gives touch/tablet users (721–1040px,
  // where there is no hover) a visible affordance that the row opens
  // something. Scoped to the desktop table only: the mobile card list
  // (`mobileCard`) already expects callers to give each card its own
  // explicit open affordance (see e.g. TicketListPage's `DealOpenButton`).
  onRowClick,
  // Optional row selection. Unlike `onRowClick` navigation above, selection is
  // a stateful table interaction: the row itself becomes focusable and exposes
  // `aria-current` (see the render below for why not `aria-selected`) so callers
  // can use the whole row as the selection target without hiding the cells
  // behind a `role="button"` name. Only one selectable row sits in the Tab
  // order at a time; ArrowUp/ArrowDown/Home/End move focus within the rows.
  onRowSelect,
  isRowSelected,
  // Overlay/detail callers can opt into focusing the clicked row before the
  // detail opens so a dialog focus-restoration hook has the row as its origin.
  // Persistent side panels should leave this false so mouse clicks do not
  // steal focus from the user's current toolbar/control.
  focusRowOnSelectClick = false,
}) {
  // Below 720px a dense grid crushes every column into an unreadable stub
  // (ids as "PR-…", clipped badges). When a page supplies `mobileCard`, render
  // that instead of the column cells. Search/sort/pagination act on data, not
  // markup, so they are unaffected.
  const isMobile = useIsMobile();
  const asCards = isMobile && typeof mobileCard === 'function';
  // Optional controlled search: when the caller passes `searchValue` (and
  // typically `onSearchChange`, e.g. to mirror it into a URL query param),
  // this component defers to it instead of owning the text itself. Omitting
  // both keeps every other caller's existing uncontrolled behavior unchanged.
  const isControlledSearch = searchValue !== undefined;
  const [internalSearch, setInternalSearch] = useState('');
  const search = isControlledSearch ? searchValue : internalSearch;
  const setSearch = isControlledSearch ? (value) => onSearchChange?.(value) : setInternalSearch;
  // Optional controlled sort, same contract as the controlled search above:
  // when the caller passes `sort` (typically mirrored into a URL query param so
  // header clicks and an out-of-table sort control stay in step), this component
  // defers to it. Omitting both `sort` and `onSortChange` leaves every existing
  // caller on the uncontrolled `initialSort` path unchanged.
  const isControlledSort = sort !== undefined;
  const [internalSorting, setInternalSorting] = useState(
    initialSort?.key ? [{ id: initialSort.key, desc: initialSort.dir === 'desc' }] : [],
  );
  const controlledSorting = useMemo(
    () => (sort?.key ? [{ id: sort.key, desc: sort.dir === 'desc' }] : []),
    [sort?.key, sort?.dir],
  );
  const sorting = isControlledSort ? controlledSorting : internalSorting;
  const setSorting = (updater) => {
    const next = typeof updater === 'function' ? updater(sorting) : updater;
    if (!isControlledSort) {
      setInternalSorting(next);
      return;
    }
    const [first] = next;
    onSortChange?.(first ? { key: first.id, dir: first.desc ? 'desc' : 'asc' } : null);
  };
  const [pagination, setPagination] = useState({ pageIndex: 0, pageSize });
  const rowRefs = useRef(new Map());

  const columnMap = useMemo(
    () => new Map(columns.map((column) => [column.key, column])),
    [columns],
  );

  const tableColumns = useMemo(() => columns.map((column) => ({
    id: column.key,
    accessorFn: (row) => (typeof column.searchAccessor === 'function' ? column.searchAccessor(row) : row),
    enableSorting: Boolean(column.sortable),
    enableGlobalFilter: Boolean(searchable && typeof column.searchAccessor === 'function'),
    sortingFn: (rowA, rowB, columnId) => {
      const sourceColumn = columnMap.get(columnId);
      if (!sourceColumn) return 0;
      const accessor = sourceColumn.sortAccessor || sourceColumn.render;
      return defaultCompare(accessor(rowA.original), accessor(rowB.original));
    },
  })), [columns, columnMap, searchable]);

  // eslint-disable-next-line react-hooks/incompatible-library -- requested headless table engine; returned methods are used directly in render.
  const table = useReactTable({
    data: rows,
    columns: tableColumns,
    state: {
      sorting,
      globalFilter: searchable ? search : '',
      pagination,
    },
    onSortingChange: setSorting,
    onPaginationChange: setPagination,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    getColumnCanGlobalFilter: (column) => Boolean(column.columnDef.enableGlobalFilter),
    globalFilterFn: (row, columnId, filterValue) => {
      if (!searchable) return true;
      const query = String(filterValue || '').trim().toLowerCase();
      if (!query) return true;
      const sourceColumn = columnMap.get(columnId);
      if (typeof sourceColumn?.searchAccessor !== 'function') return false;
      const value = sourceColumn.searchAccessor(row.original);
      return value != null && String(value).toLowerCase().includes(query);
    },
    autoResetPageIndex: false,
  });

  const sortedRows = table.getPrePaginationRowModel().rows.map((row) => row.original);
  const pageRows = table.getPaginationRowModel().rows.map((row) => row.original);
  const selectable = typeof onRowSelect === 'function';
  const pageRowKeys = useMemo(
    () => pageRows.map((row) => getRowKey(row)),
    [pageRows, getRowKey],
  );
  const selectedPageRow = selectable && typeof isRowSelected === 'function'
    ? pageRows.find((row) => Boolean(isRowSelected(row)))
    : null;
  const selectedRowKey = selectedPageRow ? getRowKey(selectedPageRow) : null;
  const fallbackRowKey = selectedRowKey ?? pageRowKeys[0] ?? null;
  const [activeRowKey, setActiveRowKey] = useState(null);
  const totalPages = Math.max(1, table.getPageCount());
  const safePage = Math.min(pagination.pageIndex + 1, totalPages);
  const from = sortedRows.length === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const to = Math.min(safePage * pageSize, sortedRows.length);
  const hasError = Boolean(error);
  const isZeroRows = !loading && !hasError && sortedRows.length === 0;
  // FIX B: this used to hardcode "ไม่พบข้อมูลที่ตรงกับตัวกรองที่เลือก" (no
  // results matching the selected filter) for every consumer, even pages
  // with no filters at all — e.g. HR opening `/payroll` for a month with no
  // run yet. Prefer the caller's own `emptyState.title` (already authored
  // per page for the EmptyState illustration) and fall back to a neutral
  // message that doesn't assert a filter exists. FIX F9: this is now the
  // single default "no data" string, shared with the EmptyState fallbacks
  // below, instead of a second, differently-worded default living there.
  const zeroRowsMessage = emptyState?.title ?? DEFAULT_EMPTY_MESSAGE;
  // FIX G: only append the filtered-out denominator when the caller passed
  // one and it actually differs from the matched count — a caller that never
  // filters (or omits the prop) sees the summary unchanged. Hoisted above
  // both the live region and the visible footer so both stay in sync. FIX
  // F7: previously only the visible footer carried this clause, so it
  // disappeared in exactly the case that matters most — a filter matching
  // zero rows, i.e. "you filtered 15 down to nothing".
  const filteredNote = unfilteredTotal != null && unfilteredTotal !== sortedRows.length
    ? ` (กรองจาก ${unfilteredTotal})`
    : '';
  const paginationSummary = `แสดง ${from}–${to} จาก ${sortedRows.length} รายการ${filteredNote}`;
  // FIX F1 (review-remediation): a live region that mounts or unmounts in the
  // very same render as its own content change is unreliable per the
  // WAI-ARIA Authoring Practices — a screen reader needs the node present in
  // the tree *before* the mutation to reliably pick it up. The previous
  // split design — a conditionally-mounted sr-only `<div>` that only existed
  // at zero rows, plus `aria-live` on the visible footer `<span>` that only
  // existed at non-zero rows — meant the two most important transitions
  // (15 rows -> 0, and 0 -> 15) each unmounted the very node that was
  // supposed to announce the change, so neither ever fired. Keeping exactly
  // one always-mounted region sidesteps that: the node persists across every
  // transition and only its text content changes, which is what `aria-live`
  // is built to pick up. The visible footer span is no longer a live region
  // itself (see renderPagination) — it is now purely decorative,
  // `aria-hidden`, text, so nothing is announced twice in a linear read.
  const liveRegionText = (loading || hasError)
    ? ''
    : isZeroRows
      ? `${zeroRowsMessage}${filteredNote}`
      : paginationSummary;
  const sortKey = sorting[0]?.id ?? null;
  const sortDir = sorting[0]?.desc ? 'desc' : 'asc';
  const canExport = exportable || typeof onExportCsv === 'function';

  useEffect(() => {
    setPagination((current) => ({ ...current, pageIndex: 0, pageSize }));
  }, [pageSize]);

  useEffect(() => {
    setPagination((current) => ({ ...current, pageIndex: 0 }));
  }, [search]);

  useEffect(() => {
    if (pagination.pageIndex + 1 > totalPages) {
      setPagination((current) => ({ ...current, pageIndex: totalPages - 1 }));
    }
  }, [pagination.pageIndex, totalPages]);

  useEffect(() => {
    if (!selectable) return;
    setActiveRowKey((current) => (pageRowKeys.some((key) => Object.is(key, current)) ? current : fallbackRowKey));
  }, [fallbackRowKey, pageRowKeys, selectable]);

  function handleSort(key) {
    setSorting((current) => {
      if (current[0]?.id === key) return [{ id: key, desc: !current[0].desc }];
      return [{ id: key, desc: false }];
    });
    setPagination((current) => ({ ...current, pageIndex: 0 }));
  }

  // Mouse-only navigation activation (FIX A/D): `onRowClick` still does not
  // make the row a keyboard target. Keyboard selection is handled separately
  // by the explicit `onRowSelect` contract below.
  function handleRowActivateClick(event, row, activate, rowKey) {
    if (typeof activate !== 'function') return;
    // FIX F5: `cursor-pointer` advertises the whole row as clickable, so a
    // cmd/ctrl/shift/alt-click anywhere in the row (not just on the identity
    // `<Link>`, which already handles this correctly on its own) must bail
    // out of the row's own same-tab `onRowClick` navigation instead of
    // hijacking it. This does NOT give the rest of the row the same "open in
    // a new tab/window" behaviour a real `<a>` would — a modified click that
    // lands outside the identity `<Link>` simply does nothing at all once it
    // bails here, which is the correct behaviour (nothing else in the row is
    // a navigable target) but is not "the same" as the link's own handling.
    // `event.button !== 0` alongside these modifier checks is dead code in a
    // `click` handler specifically: a real browser never dispatches `click`
    // for a middle/right button press (those fire `auxclick`/`contextmenu`
    // instead), so this branch cannot be reached via genuine user input. It
    // is left in place only as defense-in-depth against a non-primary click
    // arriving here some other way (e.g. a synthetic event from a testing
    // tool or an unusual input device); DataTable.test.jsx's "non-primary
    // button" case exercises exactly that synthetic scenario, not anything a
    // browser produces.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (isInteractiveTarget(event.target, event.currentTarget)) return;
    // FIX F3: dragging a text selection across a plain-text cell (e.g. the
    // deal code sub-line) ends in a `click` on mouseup with a non-collapsed
    // selection still present — letting that fall through to `onRowClick`
    // would discard the selection and navigate the row away, so there would
    // be no way to copy any text out of the table. That drag-select case is
    // what this guard actually protects. Double-clicking to select a word is
    // NOT protected by it, despite an earlier version of this comment
    // claiming otherwise: the first click of a double-click fires on mouseup
    // with the selection still collapsed (word selection only happens after,
    // on `dblclick`), so it sails through this check and navigates before
    // the word is ever selected. Bail whenever the click leaves behind a
    // live (non-collapsed) selection — the case that matters is drag-select.
    const selectionText = typeof window !== 'undefined' && typeof window.getSelection === 'function'
      ? window.getSelection()?.toString()
      : '';
    if (selectionText) return;
    if (selectable) {
      setActiveRowKey(rowKey);
      if (focusRowOnSelectClick) event.currentTarget.focus();
    }
    activate(row);
  }

  function focusRowByKey(rowKey) {
    setActiveRowKey(rowKey);
    rowRefs.current.get(rowKey)?.focus();
  }

  function handleSelectableRowKeyDown(event, row, rowKey) {
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      setActiveRowKey(rowKey);
      onRowSelect?.(row);
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    const currentIndex = pageRowKeys.findIndex((key) => Object.is(key, rowKey));
    if (currentIndex === -1) return;
    event.preventDefault();
    const lastIndex = pageRowKeys.length - 1;
    const nextIndex = event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? lastIndex
        : event.key === 'ArrowDown'
          ? Math.min(currentIndex + 1, lastIndex)
          : Math.max(currentIndex - 1, 0);
    focusRowByKey(pageRowKeys[nextIndex]);
  }

  function handleExportCsv() {
    const csv = buildCsv(columns, sortedRows);
    if (typeof onExportCsv === 'function') {
      onExportCsv(csv, sortedRows);
      return;
    }
    downloadCsv(csv);
  }

  const skeletonRowCount = Math.min(pageSize, 8);

  function columnClassName(column, row) {
    const extraClassName = typeof column.className === 'function'
      ? column.className(row)
      : column.className;
    return cn(column.align === 'right' && 'text-right', extraClassName);
  }

  function renderErrorRegion() {
    if (!hasError) return null;
    return (
      <div className="data-table-error" role="alert">
        <span className="data-table-error-copy">
          <Icon name="triangleAlert" size={16} />
          <span>{errorMessage}</span>
        </span>
        {typeof onRetry === 'function' ? (
          <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
            <Icon name="refresh" size={15} />
            {retryLabel}
          </Button>
        ) : null}
      </div>
    );
  }

  function renderPagination() {
    if (loading || sortedRows.length === 0) return null;
    return (
      <footer className="pagination">
        {/* FIX F1: no `aria-live` here any more — the single, always-mounted
            region above the toolbar (see `liveRegionText`) is now the sole
            spoken status for the table, so this text would otherwise be
            announced twice in a linear read. `aria-hidden` keeps it a purely
            visual restatement for sighted users. */}
        <span aria-hidden="true" style={{ fontSize: 13 }}>
          {paginationSummary}
        </span>
        <div>
          <Button
            type="button"
            variant="icon"
            size="sm"
            onClick={() => setPagination((current) => ({
              ...current,
              pageIndex: Math.max(0, current.pageIndex - 1),
            }))}
            disabled={safePage === 1}
            title="หน้าก่อนหน้า"
          >
            <Icon name="chevronLeft" size={16} />
          </Button>
          <span style={{ fontSize: 13, minWidth: 72, textAlign: 'center' }}>
            หน้า {safePage} / {totalPages}
          </span>
          <Button
            type="button"
            variant="icon"
            size="sm"
            onClick={() => setPagination((current) => ({
              ...current,
              pageIndex: Math.min(totalPages - 1, current.pageIndex + 1),
            }))}
            disabled={safePage === totalPages}
            title="หน้าถัดไป"
          >
            <Icon name="chevronRight" size={16} />
          </Button>
        </div>
      </footer>
    );
  }

  function renderMobileList() {
    return (
      <section className="table-panel" aria-busy={loading ? 'true' : undefined}>
        {loading ? (
          <ul className="record-card-list" aria-label="กำลังโหลดข้อมูล">
            {Array.from({ length: skeletonRowCount }, (_, index) => (
              <li
                className="record-card flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 text-left"
                aria-hidden="true"
                key={index}
              >
                <Skeleton height={14} />
                <Skeleton height={14} width="70%" />
                <Skeleton height={14} width="44%" />
              </li>
            ))}
          </ul>
        ) : sortedRows.length === 0 ? (
          hasError ? null : (
            <EmptyState
              icon={emptyState?.icon}
              title={emptyState?.title ?? DEFAULT_EMPTY_MESSAGE}
              description={emptyState?.description}
              titleAnnouncedElsewhere
            />
          )
        ) : (
          <ul className="record-card-list">
            {pageRows.map((row) => {
              const key = getRowKey(row);
              const extraClassName = rowClassName ? rowClassName(row) : '';
              const expanded = renderExpanded ? renderExpanded(row) : null;
              return (
                <li
                  key={key}
                  className={cn(
                    'record-card flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 text-left',
                    extraClassName,
                  )}
                >
                  {mobileCard(row)}
                  {expanded ? (
                    <div className="rounded-b-md border border-t-0 border-border bg-surface-subtle px-4 py-3">
                      {expanded}
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        {showPagination ? renderPagination() : null}
      </section>
    );
  }

  function renderTable() {
    return (
      <section className="table-panel" aria-busy={loading ? 'true' : undefined}>
        {(loading || sortedRows.length > 0) ? (
          <table className="data-table-table">
            {caption ? <caption className="sr-only">{caption}</caption> : null}
            <thead>
              <tr className={`${gridClassName} table-head${stickyHeader ? ' is-sticky' : ''}`}>
                {columns.map((column) => (
                  <th
                    key={column.key}
                    scope="col"
                    aria-sort={column.sortable
                      ? (sortKey === column.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none')
                      : undefined}
                    className={columnClassName(column)}
                  >
                    {column.sortable ? (
                      <SortHeader
                        label={column.header}
                        active={sortKey === column.key}
                        dir={sortDir}
                        onSort={() => handleSort(column.key)}
                      />
                    ) : (
                      column.headerNode ?? column.header
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                Array.from({ length: skeletonRowCount }, (_, index) => (
                  <tr className={`${gridClassName} data-row`} aria-hidden="true" key={index}>
                    {columns.map((column) => (
                      <td key={column.key}>
                        <Skeleton height={14} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : (
                pageRows.map((row) => {
                  const key = getRowKey(row);
                  const extraClassName = rowClassName ? ` ${rowClassName(row)}` : '';
                  const style = rowStyle ? rowStyle(row) : undefined;
                  const expanded = renderExpanded ? renderExpanded(row) : null;
                  const selected = selectable && typeof isRowSelected === 'function'
                    ? Boolean(isRowSelected(row))
                    : false;
                  const activateRow = selectable ? onRowSelect : onRowClick;
                  const currentTabStop = selectable && Object.is(key, activeRowKey ?? fallbackRowKey);
                  return (
                    <Fragment key={key}>
                      <tr
                        ref={selectable ? (node) => {
                          if (node) {
                            rowRefs.current.set(key, node);
                          } else {
                            rowRefs.current.delete(key);
                          }
                        } : undefined}
                        className={cn(
                          `${gridClassName} data-row${extraClassName}`,
                          // `hover:bg-surface-hover` (#fbfcff on a white surface) reads as
                          // essentially no hover at all — swap to the stronger, already-defined
                          // `surface-subtle` tone so the affordance is actually visible.
                          //
                          // Mouse-only convenience (FIX A): no role/tabIndex/keydown here —
                          // see the `onRowClick` doc comment above for why. Keyboard
                          // activation and the row's accessible name now come from the
                          // caller's own identity-column `<Link>`.
                          (onRowClick || selectable) && 'cursor-pointer transition-colors hover:bg-surface-subtle',
                          selectable && 'is-selectable',
                          selected && 'is-selected',
                        )}
                        style={style}
                        role={selectable ? 'row' : undefined}
                        tabIndex={selectable ? (currentTabStop ? 0 : -1) : undefined}
                        // Item 4d fix (2026-07-31): `aria-selected` is only valid ARIA on a row that
                        // is a descendant of a `grid`/`treegrid` (WAI-ARIA `aria-selected` "Used in
                        // roles": option, row (grid/treegrid only), tab, ...) -- this is a plain
                        // `<table>`/`role="row"` with no grid ancestor, so assistive tech is not
                        // guaranteed to announce it (and implementing a real `grid` here would mean
                        // full arrow-key grid navigation, out of scope for this fix). `aria-current`
                        // is a GLOBAL ARIA state valid on any role, and "true"/"false" here reads as
                        // "the current item in this set of rows" -- a legitimate, valid stand-in that
                        // needs no markup change beyond the attribute name. The visible "✓ เลือกอยู่"
                        // text cue callers render in their identity column (e.g. PayrollPage.jsx) is
                        // unaffected and stays the primary, always-reliable signal either way.
                        aria-current={selectable ? selected : undefined}
                        onClick={activateRow ? (event) => handleRowActivateClick(event, row, activateRow, key) : undefined}
                        onKeyDown={selectable ? (event) => handleSelectableRowKeyDown(event, row, key) : undefined}
                      >
                        {columns.map((column) => (
                          <td
                            key={column.key}
                            className={columnClassName(column, row)}
                            data-label={typeof column.header === 'string' ? column.header : undefined}
                          >
                            {column.render(row)}
                          </td>
                        ))}
                      </tr>
                      {expanded ? (
                        <tr className="data-table-expanded-row">
                          <td colSpan={columns.length}>
                            <div className="border-b border-border bg-surface-subtle px-4 py-3">
                              {expanded}
                            </div>
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })
              )}
            </tbody>
            {!loading && sortedRows.length > 0 && footerRow ? (
              <tfoot>
                {footerRow({ columns, rows: sortedRows, pageRows })}
              </tfoot>
            ) : null}
          </table>
        ) : (
          hasError ? null : (
            <EmptyState
              icon={emptyState?.icon}
              title={emptyState?.title ?? DEFAULT_EMPTY_MESSAGE}
              description={emptyState?.description}
              titleAnnouncedElsewhere
            />
          )
        )}
        {showPagination ? renderPagination() : null}
      </section>
    );
  }

  return (
    <div className="data-table">
      {/* FIX F1: exactly one always-mounted live region for the whole table
          — see `liveRegionText` above for why it must never conditionally
          mount/unmount, and why the visible footer span is `aria-hidden`
          instead of carrying its own `aria-live`. */}
      <div aria-live="polite" className="sr-only">{liveRegionText}</div>
      {(searchable || toolbarExtra || canExport) ? (
        <div className="data-table-toolbar">
          {searchable ? (
            <label className="data-table-search search-field">
              <Icon name="search" />
              <input
                type="search"
                value={search}
                placeholder={searchPlaceholder}
                onChange={(event) => setSearch(event.target.value)}
                aria-label={searchPlaceholder}
                className="mobile:min-h-[44px]"
              />
            </label>
          ) : null}
          {toolbarExtra}
          {canExport ? (
            <Button type="button" variant="secondary" onClick={handleExportCsv}>
              <Icon name="fileText" />
              Export CSV
            </Button>
          ) : null}
        </div>
      ) : null}

      {renderErrorRegion()}
      {asCards ? renderMobileList() : renderTable()}
    </div>
  );
}
