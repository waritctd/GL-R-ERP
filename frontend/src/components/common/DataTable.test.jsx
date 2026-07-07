import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DataTable } from './DataTable.jsx';

globalThis.React = React;

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
    expect(screen.queryByText('ไม่พบข้อมูล')).toBeNull();
  });

  it('renders rows as buttons and fires onRowClick when provided', () => {
    const rows = makeRows(3);
    const onRowClick = (row) => {
      onRowClick.calledWith = row;
    };
    render(
      <DataTable
        columns={baseColumns}
        rows={rows}
        getRowKey={(row) => row.id}
        gridClassName="employee-table"
        onRowClick={onRowClick}
      />,
    );

    const rowButton = screen.getByText('Employee 01').closest('button');
    expect(rowButton).toBeTruthy();
    fireEvent.click(rowButton);
    expect(onRowClick.calledWith.id).toBe(1);
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
});
