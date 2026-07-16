import { isValidElement, useEffect, useMemo, useState } from 'react';
import {
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { EmptyState } from './EmptyState.jsx';
import { Icon } from './Icon.jsx';
import { Skeleton } from './Skeleton.jsx';

/**
 * Formalized `SortHeader` (lifted from TicketListPage) — a clickable
 * `columnheader` cell for sortable columns. Styled via `.sort-header` /
 * `.is-active` in styles.css (no inline hex).
 */
function SortHeader({ label, active, dir, onSort, align }) {
  return (
    <span
      role="columnheader"
      aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={align === 'right' ? 'flex justify-end' : undefined}
    >
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
    </span>
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
  onRowClick,
  rowClassName,
  rowStyle,
  loading = false,
  emptyState,
  initialSort,
  toolbarExtra,
  stickyHeader = false,
  exportable = false,
  onExportCsv,
  mobileCard,
}) {
  // Below 720px a dense grid crushes every column into an unreadable stub
  // (ids as "PR-…", clipped badges). When a page supplies `mobileCard`, render
  // that instead of the column cells. Search/sort/pagination act on data, not
  // markup, so they are unaffected.
  const isMobile = useIsMobile();
  const asCards = isMobile && typeof mobileCard === 'function';
  const [search, setSearch] = useState('');
  const [sorting, setSorting] = useState(
    initialSort?.key ? [{ id: initialSort.key, desc: initialSort.dir === 'desc' }] : [],
  );
  const [pagination, setPagination] = useState({ pageIndex: 0, pageSize });

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
  const totalPages = Math.max(1, table.getPageCount());
  const safePage = Math.min(pagination.pageIndex + 1, totalPages);
  const from = sortedRows.length === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const to = Math.min(safePage * pageSize, sortedRows.length);
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

  function handleSort(key) {
    setSorting((current) => {
      if (current[0]?.id === key) return [{ id: key, desc: !current[0].desc }];
      return [{ id: key, desc: false }];
    });
    setPagination((current) => ({ ...current, pageIndex: 0 }));
  }

  function handleExportCsv() {
    const csv = buildCsv(columns, sortedRows);
    if (typeof onExportCsv === 'function') {
      onExportCsv(csv, sortedRows);
      return;
    }
    downloadCsv(csv);
  }

  const RowTag = onRowClick ? 'button' : 'div';
  const skeletonRowCount = Math.min(pageSize, 8);

  return (
    <div className="data-table">
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
              />
            </label>
          ) : null}
          {toolbarExtra}
          {canExport ? (
            <button type="button" className="secondary-button" onClick={handleExportCsv}>
              <Icon name="fileText" />
              Export CSV
            </button>
          ) : null}
        </div>
      ) : null}

      <section className="table-panel" role="table">
        {asCards ? null : (
          <div className={`${gridClassName} table-head${stickyHeader ? ' is-sticky' : ''}`} role="row">
            {columns.map((column) => (
              column.sortable ? (
                <SortHeader
                  key={column.key}
                  label={column.header}
                  active={sortKey === column.key}
                  dir={sortDir}
                  align={column.align}
                  onSort={() => handleSort(column.key)}
                />
              ) : (
                <span key={column.key} role="columnheader" className={column.align === 'right' ? 'text-right' : undefined}>
                  {column.headerNode ?? column.header}
                </span>
              )
            ))}
          </div>
        )}

        {loading ? (
          <div aria-busy="true" aria-label="กำลังโหลดข้อมูล">
            {Array.from({ length: skeletonRowCount }, (_, index) => (
              <div className={`${gridClassName} data-row`} role="row" key={index}>
                {columns.map((column) => (
                  <span key={column.key} role="cell">
                    <Skeleton height={14} />
                  </span>
                ))}
              </div>
            ))}
          </div>
        ) : sortedRows.length === 0 ? (
          <EmptyState
            icon={emptyState?.icon}
            title={emptyState?.title ?? 'ไม่พบข้อมูล'}
            description={emptyState?.description}
          />
        ) : pageRows.map((row) => {
          const key = getRowKey(row);
          const extraClassName = rowClassName ? ` ${rowClassName(row)}` : '';

          if (asCards) {
            return (
              <RowTag
                key={key}
                type={onRowClick ? 'button' : undefined}
                role="row"
                className={cn(
                  'record-card flex w-full min-w-0 flex-col items-stretch gap-2 text-left',
                  'mt-2.5 first:mt-0 rounded-md border border-solid border-border bg-surface p-4',
                  onRowClick && 'cursor-pointer hover:bg-surface-hover',
                  extraClassName,
                )}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
              >
                {mobileCard(row)}
              </RowTag>
            );
          }

          const style = rowStyle ? rowStyle(row) : undefined;
          return (
            <RowTag
              key={key}
              type={onRowClick ? 'button' : undefined}
              role="row"
              className={`${gridClassName} data-row${extraClassName}`}
              style={style}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((column) => (
                <span
                  key={column.key}
                  role="cell"
                  className={column.align === 'right' ? 'text-right' : undefined}
                  data-label={typeof column.header === 'string' ? column.header : undefined}
                >
                  {column.render(row)}
                </span>
              ))}
            </RowTag>
          );
        })}

        {!loading && sortedRows.length > 0 && (
          <footer className="pagination">
            <span style={{ fontSize: 13 }}>แสดง {from}–{to} จาก {sortedRows.length} รายการ</span>
            <div>
              <button
                type="button"
                className="icon-button"
                onClick={() => setPagination((current) => ({
                  ...current,
                  pageIndex: Math.max(0, current.pageIndex - 1),
                }))}
                disabled={safePage === 1}
                title="หน้าก่อนหน้า"
                aria-label="หน้าก่อนหน้า"
              >
                <Icon name="chevronLeft" size={16} />
              </button>
              <span style={{ fontSize: 13, minWidth: 72, textAlign: 'center' }}>
                หน้า {safePage} / {totalPages}
              </span>
              <button
                type="button"
                className="icon-button"
                onClick={() => setPagination((current) => ({
                  ...current,
                  pageIndex: Math.min(totalPages - 1, current.pageIndex + 1),
                }))}
                disabled={safePage === totalPages}
                title="หน้าถัดไป"
                aria-label="หน้าถัดไป"
              >
                <Icon name="chevronRight" size={16} />
              </button>
            </div>
          </footer>
        )}
      </section>
    </div>
  );
}
