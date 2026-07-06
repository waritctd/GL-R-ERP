import { useEffect, useMemo, useState } from 'react';
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
    <span role="columnheader" aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
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

/**
 * Generic, controlled-data table primitive. Keeps emitting the exact
 * `{gridClassName} table-head` / `{gridClassName} table-row` classes the
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
}) {
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState(initialSort?.key ?? null);
  const [sortDir, setSortDir] = useState(initialSort?.dir ?? 'asc');
  const [page, setPage] = useState(1);

  const searchableColumns = useMemo(
    () => columns.filter((column) => typeof column.searchAccessor === 'function'),
    [columns],
  );

  const searched = useMemo(() => {
    if (!searchable || !search.trim()) return rows;
    const query = search.trim().toLowerCase();
    return rows.filter((row) => searchableColumns.some((column) => {
      const value = column.searchAccessor(row);
      return value != null && String(value).toLowerCase().includes(query);
    }));
  }, [rows, searchable, search, searchableColumns]);

  const sorted = useMemo(() => {
    if (!sortKey) return searched;
    const column = columns.find((item) => item.key === sortKey);
    if (!column) return searched;
    const dir = sortDir === 'asc' ? 1 : -1;
    const accessor = column.sortAccessor || column.render;
    const copy = [...searched];
    copy.sort((a, b) => dir * defaultCompare(accessor(a), accessor(b)));
    return copy;
  }, [searched, sortKey, sortDir, columns]);

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageRows = sorted.slice((safePage - 1) * pageSize, safePage * pageSize);
  const from = sorted.length === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const to = Math.min(safePage * pageSize, sorted.length);

  useEffect(() => {
    setPage(1);
  }, [search]);

  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);

  function handleSort(key) {
    if (key === sortKey) {
      setSortDir((direction) => (direction === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
    setPage(1);
  }

  const RowTag = onRowClick ? 'button' : 'div';
  const skeletonRowCount = Math.min(pageSize, 8);

  return (
    <div className="data-table">
      {(searchable || toolbarExtra) ? (
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
        </div>
      ) : null}

      <section className="table-panel" role="table">
        <div className={`${gridClassName} table-head`} role="row">
          {columns.map((column) => (
            column.sortable ? (
              <SortHeader
                key={column.key}
                label={column.header}
                active={sortKey === column.key}
                dir={sortDir}
                onSort={() => handleSort(column.key)}
              />
            ) : (
              <span key={column.key} role="columnheader">
                {column.headerNode ?? column.header}
              </span>
            )
          ))}
        </div>

        {loading ? (
          <div aria-busy="true" aria-label="กำลังโหลดข้อมูล">
            {Array.from({ length: skeletonRowCount }, (_, index) => (
              <div className={`${gridClassName} table-row`} role="row" key={index}>
                {columns.map((column) => (
                  <span key={column.key} role="cell">
                    <Skeleton height={14} />
                  </span>
                ))}
              </div>
            ))}
          </div>
        ) : sorted.length === 0 ? (
          <EmptyState
            icon={emptyState?.icon}
            title={emptyState?.title ?? 'ไม่พบข้อมูล'}
            description={emptyState?.description}
          />
        ) : pageRows.map((row) => {
          const key = getRowKey(row);
          const extraClassName = rowClassName ? ` ${rowClassName(row)}` : '';
          const style = rowStyle ? rowStyle(row) : undefined;
          return (
            <RowTag
              key={key}
              type={onRowClick ? 'button' : undefined}
              role="row"
              className={`${gridClassName} table-row${extraClassName}`}
              style={style}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((column) => (
                <span key={column.key} role="cell">
                  {column.render(row)}
                </span>
              ))}
            </RowTag>
          );
        })}

        {!loading && sorted.length > 0 && (
          <footer className="pagination">
            <span style={{ fontSize: 13 }}>แสดง {from}–{to} จาก {sorted.length} รายการ</span>
            <div>
              <button
                type="button"
                className="icon-button"
                onClick={() => setPage((p) => Math.max(1, p - 1))}
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
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
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
