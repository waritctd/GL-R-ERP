import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DataTable } from './DataTable.jsx';

globalThis.React = React;

const realMatchMedia = window.matchMedia;

afterEach(() => {
  window.matchMedia = realMatchMedia;
});

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

  // Without gridClassName every `.data-row` is a one-column grid: each cell stacks full-width and
  // one record fills the screen. #1222 shipped exactly that on the commission approval page, and
  // the Activity Log page had it too. Fail loudly in dev/tests instead.
  it('throws in dev when gridClassName is missing, instead of stacking every cell in one column', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      expect(() => render(
        <DataTable columns={baseColumns} rows={makeRows(2)} getRowKey={(row) => row.id} />,
      )).toThrow(/gridClassName/);
    } finally {
      consoleError.mockRestore();
    }
  });

  it('in production a missing gridClassName logs once, not on every render', async () => {
    vi.stubEnv('DEV', false);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      // fresh module: the once-flag is module state, so this must not depend on test order
      vi.resetModules();
      const { DataTable: FreshTable } = await import('./DataTable.jsx');
      const table = (rows) => <FreshTable columns={baseColumns} rows={rows} getRowKey={(row) => row.id} />;
      const { rerender } = render(table(makeRows(2)));
      rerender(table(makeRows(3)));
      rerender(table(makeRows(4)));
      expect(consoleError.mock.calls.filter(([message]) => String(message).includes('gridClassName'))).toHaveLength(1);
    } finally {
      consoleError.mockRestore();
      vi.unstubAllEnvs();
    }
  });

  // Sorting a column that has NO sortAccessor ──────────────────────────────────────────────── The fallback used
  // to be `sortAccessor || render`, so a column whose render returned a React ELEMENT handed the comparator two
  // objects.
  describe('columns without a sortAccessor', () => {
    it('compares a formatted money cell numerically, not as text', () => {
      // Text alone is not enough, and this is the case that proves it: as text `฿1,790,714.29` sorts BEFORE
      // `฿600,616.00`, which is a wrong answer rather than a missing one. Zero and a negative are in here
      // deliberately.
      const rows = [
        { id: 1, total: 1790714.29 },
        { id: 2, total: 600616 },
        { id: 3, total: 29583.36 },
        { id: 4, total: 2032870.7 },
        { id: 5, total: 0 },
        // TWO negatives, not one. With a single negative the test stays green even when the sign is dropped from the
        // number pattern: it falls back to text, and Thai collation happens to place `฿-…` first anyway, so the
        // expectation is unchanged.
        { id: 6, total: -1234 },
        { id: 7, total: -9999 },
      ];
      const baht = (n) => `฿${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      render(
        <DataTable
          columns={[{
            key: 'total',
            header: 'Total',
            sortable: true,
            render: (row) => <span className="tabular-nums">{baht(row.total)}</span>,
          }]}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="t"
          pageSize={10}
        />,
      );

      fireEvent.click(screen.getByRole('button', { name: /Total/ }));
      expect(screen.getAllByRole('cell').map((el) => el.textContent)).toEqual([
        '฿-9,999.00', '฿-1,234.00', '฿0.00', '฿29,583.36', '฿600,616.00', '฿1,790,714.29',
        '฿2,032,870.70',
      ]);
    });
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

  // Optional row-level navigation (purely additive, default-off): a caller that passes `onRowClick` gets a
  // mouse-only activatable row (FIX A — see below for why this is no longer also a keyboard/assistive-tech
  // target); a caller that omits it (every case above this one) must see no row semantics at all.
  describe('onRowClick', () => {
    function actionColumnsWithButton(onAction) {
      return [
        ...baseColumns,
        {
          key: 'action',
          header: 'Action',
          render: (row) => (
            <button type="button" onClick={() => onAction(row.id)}>
              Secondary {row.name}
            </button>
          ),
        },
      ];
    }

    it('activates the row on click', () => {
      const onRowClick = vi.fn();
      const rows = makeRows(2);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          onRowClick={onRowClick}
        />,
      );

      const row = screen.getByText('Employee 01').closest('tr');
      fireEvent.click(row);
      expect(onRowClick).toHaveBeenCalledWith(rows[0]);
    });

    it('does not double-activate when a nested button inside the row is clicked', () => {
      const onRowClick = vi.fn();
      const onAction = vi.fn();
      const rows = makeRows(1);
      render(
        <DataTable
          columns={actionColumnsWithButton(onAction)}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          onRowClick={onRowClick}
        />,
      );

      fireEvent.click(screen.getByRole('button', { name: 'Secondary Employee 01' }));
      expect(onAction).toHaveBeenCalledWith(1);
      expect(onRowClick).not.toHaveBeenCalled();
    });

    // FIX F5: `cursor-pointer` advertises the whole row as clickable, so a modified click (cmd/ctrl/shift/alt)
    // anywhere in the row must bail out of the same-tab `onRowClick` navigation.
    describe('modified and non-primary clicks (FIX F5)', () => {
      it.each([
        ['metaKey', { metaKey: true }],
        ['ctrlKey', { ctrlKey: true }],
        ['shiftKey', { shiftKey: true }],
        ['altKey', { altKey: true }],
        ['a non-primary button', { button: 1 }],
      ])('does not fire onRowClick on a click with %s', (_label, eventInit) => {
        const onRowClick = vi.fn();
        const rows = makeRows(1);
        render(
          <DataTable
            columns={baseColumns}
            rows={rows}
            getRowKey={(row) => row.id}
            gridClassName="employee-table"
            onRowClick={onRowClick}
          />,
        );

        fireEvent.click(screen.getByText('Employee 01').closest('tr'), eventInit);
        expect(onRowClick).not.toHaveBeenCalled();
      });

      it('still fires onRowClick on an unmodified primary click', () => {
        const onRowClick = vi.fn();
        const rows = makeRows(1);
        render(
          <DataTable
            columns={baseColumns}
            rows={rows}
            getRowKey={(row) => row.id}
            gridClassName="employee-table"
            onRowClick={onRowClick}
          />,
        );

        fireEvent.click(screen.getByText('Employee 01').closest('tr'), { button: 0 });
        expect(onRowClick).toHaveBeenCalledWith(rows[0]);
      });
    });

    // FIX F3: dragging a text selection across a plain-text cell (or double-clicking to select a word) both end in
    // a `click` on mouseup with a non-collapsed selection still present.
    describe('active text selection (FIX F3)', () => {
      const realGetSelection = window.getSelection;

      afterEach(() => {
        window.getSelection = realGetSelection;
      });

      it('does not fire onRowClick when the click leaves behind a live text selection', () => {
        window.getSelection = () => ({ toString: () => 'Employee' });
        const onRowClick = vi.fn();
        const rows = makeRows(1);
        render(
          <DataTable
            columns={baseColumns}
            rows={rows}
            getRowKey={(row) => row.id}
            gridClassName="employee-table"
            onRowClick={onRowClick}
          />,
        );

        fireEvent.click(screen.getByText('Employee 01').closest('tr'));
        expect(onRowClick).not.toHaveBeenCalled();
      });

      it('still fires onRowClick when the selection is empty (collapsed)', () => {
        window.getSelection = () => ({ toString: () => '' });
        const onRowClick = vi.fn();
        const rows = makeRows(1);
        render(
          <DataTable
            columns={baseColumns}
            rows={rows}
            getRowKey={(row) => row.id}
            gridClassName="employee-table"
            onRowClick={onRowClick}
          />,
        );

        fireEvent.click(screen.getByText('Employee 01').closest('tr'));
        expect(onRowClick).toHaveBeenCalledWith(rows[0]);
      });
    });
  });

  describe('onRowSelect', () => {
    function actionColumnsWithButton(onAction) {
      return [
        ...baseColumns,
        {
          key: 'action',
          header: 'Action',
          render: (row) => (
            <button type="button" onClick={() => onAction(row.id)}>
              Secondary {row.name}
            </button>
          ),
        },
      ];
    }

    it('selects from click, Enter, and Space without double-firing nested controls', () => {
      const onRowSelect = vi.fn();
      const onAction = vi.fn();
      const rows = makeRows(1);
      render(
        <DataTable
          columns={actionColumnsWithButton(onAction)}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          onRowSelect={onRowSelect}
          isRowSelected={() => false}
        />,
      );

      const row = screen.getByText('Employee 01').closest('tr');
      fireEvent.click(row);
      fireEvent.keyDown(row, { key: 'Enter' });
      fireEvent.keyDown(row, { key: ' ' });
      fireEvent.click(screen.getByRole('button', { name: 'Secondary Employee 01' }));

      expect(onRowSelect).toHaveBeenCalledTimes(3);
      expect(onRowSelect).toHaveBeenCalledWith(rows[0]);
      expect(onAction).toHaveBeenCalledWith(1);
    });
  });

  // FIX F1 (review-remediation): a live region that mounts/unmounts in the same render as its own content change
  // is unreliable per WAI-ARIA — a screen reader needs the node present in the tree *before* the mutation.
  describe('announces the result count', () => {
    it('announces the visible range and total via a single always-mounted live region, distinct from the aria-hidden visible footer', () => {
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

      const regions = document.querySelectorAll('[aria-live="polite"]');
      expect(regions).toHaveLength(1);
      expect(regions[0].textContent).toBe('แสดง 1–10 จาก 25 รายการ');
      expect(regions[0].tagName).toBe('DIV');
      expect(regions[0].className).toContain('sr-only');
      // Not nested inside the visible footer — it is its own top-level node.
      expect(regions[0].closest('footer')).toBeNull();

      // The visible footer restates the same text for sighted users, but purely decoratively: no `aria-live`, and
      // hidden from the accessibility tree entirely so it is never announced a second time.
      const footerSpan = document.querySelector('footer span[aria-hidden="true"]');
      expect(footerSpan.textContent).toBe('แสดง 1–10 จาก 25 รายการ');
      expect(footerSpan.hasAttribute('aria-live')).toBe(false);
    });
  });
});
