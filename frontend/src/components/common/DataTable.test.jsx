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
    // FIX F1: the pagination summary text now legitimately appears twice —
    // once in the always-mounted sr-only `aria-live` region, once in the
    // visible (but `aria-hidden`) footer span — see the `announces the
    // result count` describe block below for why that split exists.
    expect(screen.getAllByText('แสดง 1–10 จาก 25 รายการ').length).toBe(2);
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

    // FIX B: the same title now also appears in the sr-only aria-live zero-
    // row announcement (see the `announces the result count` describe block
    // below), so there are legitimately two matches — assert the visible
    // EmptyState illustration specifically rather than screen.getByText,
    // which would throw on the ambiguity.
    expect(document.querySelector('.empty-state strong').textContent).toBe('ไม่พบข้อมูลพนักงาน');
    expect(screen.getAllByText('ไม่พบข้อมูลพนักงาน')).toHaveLength(2);
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

  // Optional row-level navigation (purely additive, default-off): a caller
  // that passes `onRowClick` gets a mouse-only activatable row (FIX A — see
  // below for why this is no longer also a keyboard/assistive-tech target);
  // a caller that omits it (every case above this one) must see no row
  // semantics at all.
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

    // FIX A/D: the row is no longer a focusable target at all — `role="button"`
    // on a `<tr>` is a "Children Presentational: True" role in ARIA 1.2, which
    // prunes every descendant (customer name, badges, etc.) out of the
    // accessibility tree and replaces name computation with the explicit
    // `aria-label`, so the row's own keyboard activation used to make the
    // rest of the row's content unreachable to a screen reader. Keyboard
    // activation now belongs to the caller's own identity `<Link>` instead
    // (see TicketListPage's `customer` column), so there is nothing left for
    // a keydown-repeat guard (FIX D) to protect either.
    it('does not activate via keydown on the row — the row is not a focusable target any more', () => {
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

      const row = screen.getByText('Employee 02').closest('tr');
      expect(() => fireEvent.keyDown(row, { key: 'Enter' })).not.toThrow();
      expect(onRowClick).not.toHaveBeenCalled();
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

    it('renders no row semantics at all when onRowClick is omitted', () => {
      const rows = makeRows(1);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
        />,
      );

      const row = screen.getByText('Employee 01').closest('tr');
      expect(row.hasAttribute('tabindex')).toBe(false);
      expect(row.hasAttribute('role')).toBe(false);
      expect(row.hasAttribute('aria-label')).toBe(false);
      expect(row.className).not.toContain('cursor-pointer');
      // No listener means React never attaches an onclick DOM prop; clicking
      // it is a silent no-op rather than throwing.
      expect(() => fireEvent.click(row)).not.toThrow();
    });

    // FIX A: a `role="button"` + `tabIndex` + `aria-label` row prunes every
    // descendant out of the accessibility tree (ARIA 1.2 "Children
    // Presentational: True") and hides the row's real content — customer
    // name, badges, the stage cell — behind a single opaque label. The row
    // must stay a plain, non-interactive `<tr>` to assistive tech, even when
    // `onRowClick` is set; the caller's own identity `<Link>` is what exposes
    // keyboard/AT semantics now (see TicketListPage's `customer` column and
    // its tests).
    it('never adds role, tabIndex, or aria-label to the row, even when onRowClick is set', () => {
      const rows = makeRows(1);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          onRowClick={() => {}}
        />,
      );

      const row = screen.getByText('Employee 01').closest('tr');
      expect(row.hasAttribute('role')).toBe(false);
      expect(row.hasAttribute('aria-label')).toBe(false);
      expect(row.hasAttribute('tabindex')).toBe(false);
    });

    // FIX F8: the boundary check (`isInteractiveTarget`) must only defer to
    // an interactive element NESTED INSIDE the row — an interactive ancestor
    // that sits above the row (e.g. some outer toolbar/container the row
    // happens to render inside) is not the caller's own nested control and
    // must not suppress row activation. The pre-fix check only asked
    // `match !== boundary`, which is true for an outside ancestor just as
    // much as for a real nested control, so it would have wrongly deferred.
    it('still activates on click when an interactive ancestor sits ABOVE the row (outside it), not nested inside it', () => {
      const onRowClick = vi.fn();
      const rows = makeRows(1);
      render(
        <div role="button">
          <DataTable
            columns={baseColumns}
            rows={rows}
            getRowKey={(row) => row.id}
            gridClassName="employee-table"
            onRowClick={onRowClick}
          />
        </div>,
      );

      fireEvent.click(screen.getByText('Employee 01').closest('tr'));
      expect(onRowClick).toHaveBeenCalledWith(rows[0]);
    });

    // FIX F8 follow-up: `tabindex="-1"` means "programmatically focusable,
    // not in the tab order" — not "a real interactive control". Matching a
    // bare `[tabindex]` would treat any row containing such an element (e.g.
    // a caller-managed focus target) as interactive and silently kill row
    // activation. The selector is narrowed to exclude `tabindex="-1"`.
    it('still activates on click when a nested element carries only tabindex="-1" (programmatically focusable, not a real control)', () => {
      const onRowClick = vi.fn();
      const rows = makeRows(1);
      const columns = [
        baseColumns[0],
        { key: 'note', header: 'Note', render: () => <span tabIndex={-1}>note</span> },
      ];
      render(
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          onRowClick={onRowClick}
        />,
      );

      fireEvent.click(screen.getByText('note'));
      expect(onRowClick).toHaveBeenCalledWith(rows[0]);
    });

    it('still activates on click when the click lands on the row itself (not a nested control)', () => {
      // Regression guard for the isInteractiveTarget boundary exclusion:
      // even though the row no longer carries role="button" itself (FIX A),
      // the guard must still let a plain click on the row (not a nested
      // control) through to onRowClick.
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

    // FIX F5: `cursor-pointer` advertises the whole row as clickable, so a
    // modified click (cmd/ctrl/shift/alt) anywhere in the row must bail out
    // of the same-tab `onRowClick` navigation. It does NOT get the same
    // "open in a new tab/window" behaviour the identity `<Link>` gives on its
    // own — a modified click outside that link just does nothing further,
    // which is correct but is not "the same" browser behaviour. The
    // non-primary-button case below is a defense-in-depth branch that a real
    // browser never reaches through a `click` event (middle/right clicks
    // fire `auxclick`/`contextmenu`, not `click`) — this exercises the
    // synthetic event directly rather than anything a browser produces.
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

    // FIX F3: dragging a text selection across a plain-text cell (or
    // double-clicking to select a word) both end in a `click` on mouseup with
    // a non-collapsed selection still present. Letting that reach onRowClick
    // would discard the selection and navigate the row away, making it
    // impossible to copy any text out of the table.
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

  // FIX F1 (review-remediation): a live region that mounts/unmounts in the
  // same render as its own content change is unreliable per WAI-ARIA — a
  // screen reader needs the node present in the tree *before* the mutation.
  // The design this replaced (previously documented under "FIX C") had the
  // visible footer `<span>` itself carry `aria-live`, conditionally rendered
  // only when there were rows, plus a separate sr-only `<div>` that only
  // existed at zero rows. That split meant the two transitions that matter
  // most — 15 rows -> 0, and 0 -> 15 — each unmounted the very node that was
  // supposed to announce the change, so neither transition was ever spoken.
  // Now there is exactly one always-mounted `aria-live` region (a bare
  // sr-only `<div>`, not inside the footer) whose text changes; the visible
  // footer span is `aria-hidden` and carries no `aria-live` of its own, so
  // sighted users still see the count restated without a screen reader
  // hearing it twice.
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

      // The visible footer restates the same text for sighted users, but
      // purely decoratively: no `aria-live`, and hidden from the
      // accessibility tree entirely so it is never announced a second time.
      const footerSpan = document.querySelector('footer span[aria-hidden="true"]');
      expect(footerSpan.textContent).toBe('แสดง 1–10 จาก 25 รายการ');
      expect(footerSpan.hasAttribute('aria-live')).toBe(false);
    });

    // FIX F1 transition coverage. What this actually catches: a genuine
    // conditional mount (`{liveRegionText ? <div aria-live="polite".../> :
    // null}`) still passes a naive "render 15 rows, rerender straight to 0
    // rows, compare the node reference" check — `liveRegionText` is
    // non-empty on BOTH sides of that transition (a pagination summary on
    // one side, the zero-rows message on the other), so React reuses the
    // same DOM slot for the `<div>` either way, conditional mount or not.
    // The only state where `liveRegionText` is actually empty (`''`) is
    // `loading` — that is the one rerender where a conditional mount would
    // unmount the node outright, so the sequence below routes the same
    // mounted instance through it and asserts, at every single step, that
    // exactly one `[aria-live]` node exists and that it is the same node
    // throughout (not just before/after, but through the empty-text step
    // too). If the node were ever unmounted and a new one remounted in its
    // place, a screen reader would miss the announcement.
    it('keeps exactly one always-mounted live-region node across loaded(15) -> loading -> loaded(0), not just before/after', () => {
      const rows = makeRows(15);
      const { rerender } = render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionBefore = document.querySelector('[aria-live="polite"]');
      expect(regionBefore.textContent).toBe('แสดง 1–15 จาก 15 รายการ');

      // `liveRegionText` is '' here — the step a conditional mount would drop.
      rerender(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
          loading
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionDuringLoading = document.querySelector('[aria-live="polite"]');
      expect(regionDuringLoading).toBe(regionBefore);
      expect(regionDuringLoading.textContent).toBe('');

      rerender(
        <DataTable
          columns={baseColumns}
          rows={[]}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionAfter = document.querySelector('[aria-live="polite"]');
      expect(regionAfter).toBe(regionBefore);
      expect(regionAfter.textContent).toBe('ไม่พบข้อมูล');
    });

    it('keeps exactly one always-mounted live-region node across loaded(0) -> loading -> loaded(15), not just before/after', () => {
      const { rerender } = render(
        <DataTable
          columns={baseColumns}
          rows={[]}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionBefore = document.querySelector('[aria-live="polite"]');
      expect(regionBefore.textContent).toBe('ไม่พบข้อมูล');

      const rows = makeRows(15);
      // `liveRegionText` is '' here — the step a conditional mount would drop.
      rerender(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
          loading
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionDuringLoading = document.querySelector('[aria-live="polite"]');
      expect(regionDuringLoading).toBe(regionBefore);
      expect(regionDuringLoading.textContent).toBe('');

      rerender(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
        />,
      );

      expect(document.querySelectorAll('[aria-live]')).toHaveLength(1);
      const regionAfter = document.querySelector('[aria-live="polite"]');
      expect(regionAfter).toBe(regionBefore);
      expect(regionAfter.textContent).toBe('แสดง 1–15 จาก 15 รายการ');
    });

    // FIX B: the zero-row message used to hardcode "ไม่พบข้อมูลที่ตรงกับ
    // ตัวกรองที่เลือก" (no results matching the selected filter) for every
    // consumer, even pages with no filters at all (e.g. /payroll for a month
    // with no run yet). It must now prefer the caller's own
    // `emptyState.title`.
    it('announces the caller-supplied emptyState.title when a filter leaves zero matches', () => {
      render(
        <DataTable
          columns={baseColumns}
          rows={[]}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่มีรอบจ่ายเงินเดือนสำหรับเดือนนี้' }}
        />,
      );

      // No visible footer at all when there are zero rows...
      expect(screen.queryByText(/จาก 0 รายการ/)).toBeNull();
      // ...but the sr-only live region still has something to say about it.
      const region = document.querySelector('[aria-live="polite"]');
      expect(region.textContent).toBe('ไม่มีรอบจ่ายเงินเดือนสำหรับเดือนนี้');
    });

    it('falls back to a neutral message (never asserting a filter exists) when no emptyState.title is given', () => {
      render(
        <DataTable
          columns={baseColumns}
          rows={[]}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
        />,
      );

      const region = document.querySelector('[aria-live="polite"]');
      expect(region.textContent).not.toBe('');
      expect(region.textContent).not.toMatch(/ตัวกรอง/);
    });

    // FIX F1 changed this from "no live region at all" to "the always-mounted
    // region stays in the tree (so a screen reader always has a node primed
    // to pick up the next announcement) but its text is emptied while
    // loading, to avoid announcing a stale or misleading count that belongs
    // to the *previous* rows.
    it('empties the live region while loading, rather than removing it, to avoid announcing a stale or misleading count', () => {
      render(
        <DataTable
          columns={baseColumns}
          rows={makeRows(5)}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          loading
        />,
      );

      const region = document.querySelector('[aria-live="polite"]');
      expect(region).not.toBeNull();
      expect(region.textContent).toBe('');
    });
  });

  // FIX G: the pre-DataTable `.ticket-result-count` band showed "ตรงเงื่อนไข
  // N จาก M รายการ" (N matched out of M total), so filtering never hid how
  // much was filtered out. This footer's own N already states "matched" —
  // `unfilteredTotal` restores the M denominator as an appended clause.
  describe('unfilteredTotal', () => {
    // FIX F1 means the summary text now legitimately renders twice (the
    // sr-only live region and the aria-hidden visible footer), so these use
    // getAllByText rather than getByText (which throws on >1 match).
    it('appends the filtered-out denominator when unfilteredTotal differs from the matched count', () => {
      const rows = makeRows(5);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          unfilteredTotal={20}
        />,
      );

      expect(screen.getAllByText('แสดง 1–5 จาก 5 รายการ (กรองจาก 20)')).toHaveLength(2);
    });

    it('omits the clause when unfilteredTotal is not passed', () => {
      const rows = makeRows(5);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
        />,
      );

      expect(screen.getAllByText('แสดง 1–5 จาก 5 รายการ')).toHaveLength(2);
    });

    it('omits the clause when unfilteredTotal equals the matched count (no filter actually active)', () => {
      const rows = makeRows(5);
      render(
        <DataTable
          columns={baseColumns}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          unfilteredTotal={5}
        />,
      );

      expect(screen.getAllByText('แสดง 1–5 จาก 5 รายการ')).toHaveLength(2);
    });

    // FIX F7: the zero-row live-region announcement must also carry the
    // denominator — this is the case the review flagged as mattering most
    // ("you filtered 15 down to nothing"), and it is the one case the footer
    // itself can never show it in, because the footer doesn't render at all
    // at zero rows.
    it('carries the filtered-out denominator in the zero-row live-region announcement too', () => {
      render(
        <DataTable
          columns={baseColumns}
          rows={[]}
          getRowKey={(row) => row.id}
          gridClassName="employee-table"
          emptyState={{ title: 'ไม่พบข้อมูล' }}
          unfilteredTotal={15}
        />,
      );

      // No visible footer at all when there are zero rows.
      expect(screen.queryByText(/จาก 0 รายการ/)).toBeNull();
      const region = document.querySelector('[aria-live="polite"]');
      expect(region.textContent).toBe('ไม่พบข้อมูล (กรองจาก 15)');
    });
  });
});
