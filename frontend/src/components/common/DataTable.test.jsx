import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { DataTable } from './DataTable.jsx';

globalThis.React = React;

const realMatchMedia = window.matchMedia;

afterEach(() => {
  window.matchMedia = realMatchMedia;
});

function stubMobile(matches = true) {
  window.matchMedia = (query) => ({
    matches: query === '(max-width: 720px)' ? matches : false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  });
}

function makeRows(count) {
  return Array.from({ length: count }, (_, index) => ({
    id: index + 1,
    name: `Employee ${String(index + 1).padStart(2, '0')}`,
    age: 20 + (index % 10),
  }));
}

const baseColumns = [
  { key: 'name', header: 'Name', sortable: true, sortAccessor: (row) => row.name, searchAccessor: (row) => row.name, render: (row) => row.name },
  { key: 'age', header: 'Age', sortable: true, sortAccessor: (row) => row.age, render: (row) => row.age },
];

describe('DataTable', () => {
  it('renders headers and a page of rows bounded by pageSize', () => {
    const rows = makeRows(25);
    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={10}
      />,
    );

    expect(screen.getByText('Name')).toBeTruthy();
    expect(screen.getByText('Age')).toBeTruthy();
    expect(screen.getByText('Employee 01')).toBeTruthy();
    expect(screen.getByText('Employee 10')).toBeTruthy();
    expect(screen.queryByText('Employee 11')).toBeNull();
    expect(screen.getByText('แสดง 1–10 จาก 25 รายการ')).toBeTruthy();
    expect(screen.getByText('หน้า 1 / 3')).toBeTruthy();
  });

  it('uses native desktop table structure with header and body groups', () => {
    const { container } = render(
      <DataTable
        columns={baseColumns}
        rows={makeRows(2)}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
      />,
    );

    const table = screen.getByRole('table');
    expect(table.tagName).toBe('TABLE');
    expect(table.querySelector('thead')).toBeTruthy();
    expect(table.querySelector('tbody')).toBeTruthy();

    const headers = within(table).getAllByRole('columnheader');
    expect(headers).toHaveLength(2);
    expect(headers[0].tagName).toBe('TH');
    expect(headers[0].getAttribute('scope')).toBe('col');
    expect(headers[0].getAttribute('aria-sort')).toBe('none');
    expect(headers[1].getAttribute('aria-sort')).toBe('none');
    expect(container.querySelectorAll('tbody .data-row')).toHaveLength(2);
  });

  it('slices rows per page and prev/next buttons navigate', () => {
    const rows = makeRows(25);
    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={10}
      />,
    );

    const nextButton = screen.getByRole('button', { name: 'หน้าถัดไป' });
    const prevButton = screen.getByRole('button', { name: 'หน้าก่อนหน้า' });
    expect(prevButton.disabled).toBe(true);

    fireEvent.click(nextButton);
    expect(screen.getByText('หน้า 2 / 3')).toBeTruthy();
    expect(screen.getByText('Employee 11')).toBeTruthy();
    expect(screen.queryByText('Employee 01')).toBeNull();

    fireEvent.click(nextButton);
    expect(screen.getByText('หน้า 3 / 3')).toBeTruthy();
    expect(screen.getByText('Employee 25')).toBeTruthy();
    const nextButtonAtEnd = screen.getByRole('button', { name: 'หน้าถัดไป' });
    expect(nextButtonAtEnd.disabled).toBe(true);

    fireEvent.click(prevButton);
    expect(screen.getByText('หน้า 2 / 3')).toBeTruthy();
  });

  it('reorders rows when a sortable header is clicked', () => {
    const rows = [
      { id: 1, name: 'Charlie', age: 30 },
      { id: 2, name: 'Alice', age: 25 },
      { id: 3, name: 'Bob', age: 40 },
    ];
    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={10}
      />,
    );

    const nameHeaderButton = screen.getByRole('button', { name: /Name/ });
    fireEvent.click(nameHeaderButton);

    const cells = screen.getAllByRole('cell').map((el) => el.textContent);
    const nameOrderAsc = cells.filter((text) => ['Alice', 'Bob', 'Charlie'].includes(text));
    expect(nameOrderAsc).toEqual(['Alice', 'Bob', 'Charlie']);

    fireEvent.click(nameHeaderButton);
    const cellsDesc = screen.getAllByRole('cell').map((el) => el.textContent);
    const nameOrderDesc = cellsDesc.filter((text) => ['Alice', 'Bob', 'Charlie'].includes(text));
    expect(nameOrderDesc).toEqual(['Charlie', 'Bob', 'Alice']);
  });

  it('filters rows via search and resets to page 1', () => {
    const rows = makeRows(25);
    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={10}
        searchable
        searchPlaceholder="ค้นหาพนักงาน"
      />,
    );

    const nextButton = screen.getByRole('button', { name: 'หน้าถัดไป' });
    fireEvent.click(nextButton);
    expect(screen.getByText('หน้า 2 / 3')).toBeTruthy();

    const searchInput = screen.getByPlaceholderText('ค้นหาพนักงาน');
    fireEvent.change(searchInput, { target: { value: 'Employee 02' } });

    expect(screen.getByText('หน้า 1 / 1')).toBeTruthy();
    expect(screen.getByText('Employee 02')).toBeTruthy();
    expect(screen.queryByText('Employee 01')).toBeNull();
  });

  it('renders EmptyState when there are zero rows and not loading', () => {
    render(
      <DataTable
        columns={baseColumns}
        rows={[]}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        emptyState={{ icon: 'users', title: 'ไม่พบข้อมูลพนักงาน', description: 'ลองเปลี่ยนตัวกรอง' }}
      />,
    );

    expect(screen.getByText('ไม่พบข้อมูลพนักงาน')).toBeTruthy();
    expect(screen.getByText('ลองเปลี่ยนตัวกรอง')).toBeTruthy();
  });

  it('renders skeleton rows inside an aria-busy container while loading', () => {
    const { container } = render(
      <DataTable
        columns={baseColumns}
        rows={[]}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={5}
        loading
      />,
    );

    const busyContainer = container.querySelector('[aria-busy="true"]');
    expect(busyContainer).toBeTruthy();
    expect(busyContainer.querySelectorAll('.skeleton').length).toBeGreaterThan(0);
    expect(container.querySelector('.data-row[aria-hidden="true"]')).toBeTruthy();
    expect(screen.getAllByRole('row')).toHaveLength(1);
    expect(screen.queryByText('ไม่พบข้อมูล')).toBeNull();
  });

  it('renders native table rows and keeps nested action buttons valid', () => {
    const rows = makeRows(3);
    const onAction = vi.fn();
    const actionColumns = [
      ...baseColumns,
      {
        key: 'action',
        header: 'Action',
        render: (row) => (
          <button type="button" onClick={() => onAction(row.id)}>
            Open {row.name}
          </button>
        ),
      },
    ];
    render(
      <DataTable
        columns={actionColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
      />,
    );

    expect(screen.getByRole('table')).toBeTruthy();
    const firstRow = screen.getByText('Employee 01').closest('tr');
    expect(firstRow).toBeTruthy();
    expect(firstRow.tagName).toBe('TR');
    expect(firstRow.closest('button')).toBeNull();

    const action = screen.getByRole('button', { name: 'Open Employee 01' });
    expect(action.closest('tr')).toBe(firstRow);
    expect(action.closest('button')).toBe(action);

    fireEvent.click(action);
    expect(onAction).toHaveBeenCalledWith(1);
  });

  it('keeps explicit open actions separate from nested secondary row actions', () => {
    const rows = makeRows(1);
    const onOpen = vi.fn();
    const onSecondary = vi.fn();
    const columns = [
      baseColumns[0],
      {
        key: 'actions',
        header: 'Actions',
        render: (row) => (
          <span>
            <button type="button" onClick={() => onOpen(row.id)}>Open record</button>
            <button type="button" onClick={() => onSecondary(row.id)}>More actions</button>
          </span>
        ),
      },
    ];

    render(
      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    expect(onSecondary).toHaveBeenCalledWith(1);
    expect(onOpen).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Open record' }));
    expect(onOpen).toHaveBeenCalledWith(1);
  });

  it('renders mobile cards as a list, not table rows', () => {
    stubMobile();
    const rows = makeRows(2);
    const onOpen = vi.fn();
    const { container } = render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        mobileCard={(row) => (
          <>
            <strong>{row.name}</strong>
            <button type="button" onClick={() => onOpen(row.id)}>Open {row.name}</button>
          </>
        )}
      />,
    );

    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByRole('list')).toBeTruthy();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(container.querySelectorAll('[role="row"]')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Open Employee 01' }).closest('li')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Open Employee 01' }));
    expect(onOpen).toHaveBeenCalledWith(1);
  });

  it('does not emit nested interactive-control warnings with cell and card actions', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const { unmount } = render(
        <DataTable
          columns={[
            baseColumns[0],
            { key: 'action', header: 'Action', render: () => <button type="button">Secondary</button> },
          ]}
          rows={makeRows(1)}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          mobileCard={(row) => <button type="button">Open {row.name}</button>}
        />,
      );
      unmount();

      stubMobile();
      render(
        <DataTable
          columns={baseColumns}
          rows={makeRows(1)}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          mobileCard={(row) => <button type="button">Open {row.name}</button>}
        />,
      );

      const messages = consoleError.mock.calls.flat().map((part) => String(part)).join('\n');
      expect(messages).not.toMatch(/validateDOMNesting|cannot appear as a descendant/i);
    } finally {
      consoleError.mockRestore();
    }
  });

  it('shows a calm inline error region and retries without raw exception details', () => {
    const onRetry = vi.fn();
    render(
      <DataTable
        columns={baseColumns}
        rows={makeRows(1)}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        error={new Error('database exploded')}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByRole('alert').textContent).toContain('โหลดข้อมูลไม่สำเร็จ');
    expect(screen.getByRole('alert').textContent).not.toContain('database exploded');
    expect(screen.getByText('Employee 01')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /ลองอีกครั้ง/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('adds the sticky header class only when stickyHeader is enabled', () => {
    const { container } = render(
      <DataTable
        columns={baseColumns}
        rows={makeRows(1)}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        stickyHeader
      />,
    );

    expect(container.querySelector('.employee-table.table-head.is-sticky')).toBeTruthy();
  });

  it('exports the current sorted and filtered rows as CSV', () => {
    const onExportCsv = vi.fn();
    const rows = [
      { id: 1, name: 'Charlie', age: 30 },
      { id: 2, name: 'Alice', age: 25 },
      { id: 3, name: 'Bob', age: 40 },
    ];

    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        pageSize={1}
        searchable
        searchPlaceholder="ค้นหาพนักงาน"
        initialSort={{ key: 'name', dir: 'asc' }}
        onExportCsv={onExportCsv}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('ค้นหาพนักงาน'), { target: { value: 'i' } });
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

    expect(onExportCsv).toHaveBeenCalledWith(
      'Name,Age\r\nAlice,25\r\nCharlie,30',
      [rows[1], rows[0]],
    );
  });

  // Controlled sort: callers that mirror sort state elsewhere (a URL param, an
  // out-of-table select) pass `sort` and own the value; header clicks then report
  // upwards instead of mutating internal state. Callers passing neither prop stay
  // on the uncontrolled `initialSort` path, which the cases above cover.
  it('renders the caller-supplied sort order and reports header clicks upward', () => {
    const rows = makeRows(3);
    const onSortChange = vi.fn();

    const { container, rerender } = render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        sort={{ key: 'name', dir: 'desc' }}
        onSortChange={onSortChange}
      />,
    );

    const names = () => [...container.querySelectorAll('.data-row')].map((row) => row.textContent);
    expect(names()[0]).toContain('Employee 03');

    fireEvent.click(screen.getByText('Name'));
    expect(onSortChange).toHaveBeenCalledWith({ key: 'name', dir: 'asc' });

    // State lives with the caller, so nothing moves until the prop changes.
    expect(names()[0]).toContain('Employee 03');

    rerender(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        sort={{ key: 'name', dir: 'asc' }}
        onSortChange={onSortChange}
      />,
    );

    expect(names()[0]).toContain('Employee 01');
  });
});
