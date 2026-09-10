import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationListPage } from './QuotationListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      dealQuotations: {
        list: vi.fn(),
        counts: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo' };
const salesManagerUser = { id: 9, name: 'ผึ้ง', role: 'sales_manager' };

function row(overrides = {}) {
  return {
    id: 1,
    number: 'QD69-0001',
    customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด',
    projectName: null,
    salesRepName: 'คุณสมหมาย ขายดี',
    grandTotal: 123456.78,
    docStatus: 'DRAFT',
    quotationDate: '2026-09-01',
    ...overrides,
  };
}

// Prints the current search string so a test can assert the tab actually travels in the URL
// (owner feedback F5: "driven by the URL (`?status=`)") rather than only in component state.
function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location-search">{location.search}</p>;
}

function renderListPage(user, initialPath = '/quotations') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route
            path="/quotations"
            element={<><QuotationListPage user={user} /><LocationProbe /></>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('QuotationListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    // BARE, no `{ counts: ... }` envelope — DealQuotationController#counts returns the
    // DealQuotationCountsDto record itself (review finding MED-3). This fixture wrapped it while
    // the page still read `r?.counts ?? r`, so it passed on the tolerance rather than on the
    // contract; with the tolerance gone the fixture has to match the controller.
    api.dealQuotations.counts.mockResolvedValue({ all: 7, pendingApproval: 2, needsRework: 3, cancelled: 1, approved: 4 });
  });

  it('sales lands on ทั้งหมด (no approval queue of its own)', async () => {
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
  });

  it('sales_manager / ceo default to the รออนุมัติ queue', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));

    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    api.dealQuotations.counts.mockResolvedValue({});
    renderListPage(salesManagerUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  // F5: the five tabs the owner asked for, in order — ร่าง and ถูกแทนที่ are deliberately gone.
  it('renders exactly ทั้งหมด · รออนุมัติ · แก้ · ยกเลิก · อนุมัติแล้ว', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const labels = screen.getAllByRole('tab').map((tab) => tab.textContent.replace(/\d+$/, ''));
    expect(labels).toEqual(['ทั้งหมด', 'รออนุมัติ', 'แก้', 'ยกเลิก', 'อนุมัติแล้ว']);
    expect(screen.queryByRole('tab', { name: /ถูกแทนที่/ })).toBeNull();
  });

  it('shows each tab its own count from GET /deal-quotations/counts', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.counts).toHaveBeenCalledTimes(1));
    const tabs = await screen.findAllByRole('tab');
    // Label + count, concatenated, in DEAL_QUOTATION_STATUS_TABS order.
    await waitFor(() => expect(tabs.map((t) => t.textContent)).toEqual([
      'ทั้งหมด7', 'รออนุมัติ2', 'แก้3', 'ยกเลิก1', 'อนุมัติแล้ว4',
    ]));
  });

  // Counts are advisory chrome. A failed counts call must not blank the list or print a 0 that
  // would read as "nothing here" — the tabs simply carry no number.
  it('still renders the tabs and the list when the counts request fails', async () => {
    api.dealQuotations.counts.mockRejectedValue(new Error('boom'));
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['ทั้งหมด', 'รออนุมัติ', 'แก้', 'ยกเลิก', 'อนุมัติแล้ว']);
  });

  it('drives the แก้ tab off needsRework=true, not a docStatus, and writes it to the URL', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^แก้/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ needsRework: true }));
    await waitFor(() => expect(screen.getByTestId('location-search').textContent).toBe('?status=NEEDS_REWORK'));
  });

  it('opens the tab named by ?status= rather than the role default', async () => {
    renderListPage(ceoUser, '/quotations?status=CANCELLED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'CANCELLED' }));
    expect(api.dealQuotations.list).not.toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' });
    expect(screen.getByRole('tab', { name: /^ยกเลิก/ }).getAttribute('aria-selected')).toBe('true');
  });

  // A retired or mistyped key must fall back to this role's default tab, not render an empty list.
  it('falls back to the role default when ?status= names no tab', async () => {
    renderListPage(ceoUser, '/quotations?status=SUPERSEDED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  // Regression: an approver's default tab is รออนุมัติ, and "no ?status=" MEANS that default —
  // so ทั้งหมด has to write its own key. Clearing the param instead bounced the CEO straight back
  // to รออนุมัติ, making ทั้งหมด unreachable for the one role whose default is not it.
  it('lets an approver actually reach ทั้งหมด, writing status=all to the URL', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^ทั้งหมด/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
    expect(screen.getByTestId('location-search').textContent).toBe('?status=all');
    expect(screen.getByRole('tab', { name: /^ทั้งหมด/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('renders the required columns: number, customer, sales rep, total, status, date', async () => {
    renderListPage(salesUser);

    await screen.findByText('QD69-0001');
    expect(screen.getByText('บริษัท แฟชั่นไอส์แลนด์ จำกัด')).not.toBeNull();
    expect(screen.getByText('คุณสมหมาย ขายดี')).not.toBeNull();
    // ร่าง is no longer a tab, so the row's own StatusBadge is the only place it appears.
    expect(screen.getByText('ร่าง')).not.toBeNull();
  });
});

describe('QuotationListPage column floors (responsive review, 2026-09-10)', () => {
  // ⚠️ WHAT THIS CAN AND CANNOT PROVE. jsdom does no grid layout and no text measurement, so it
  // cannot observe the defect this pins: at a 721px viewport the six `minmax(0, …)` tracks were
  // shrunk below their own text and the cells clip rather than overflow, so `QT-2026-0005-2`
  // rendered `QT-2026-000…` — losing the `-N` suffix that is the only thing distinguishing a
  // revision from the document it replaces. That was measured in a real browser and can only be
  // RE-measured in one. This is a TEXT guard on the decisions that fix it:
  //
  //   1. FIXED floors on the four columns that cannot wrap (เลขที่, ยอดรวม, สถานะ, วันที่) plus a
  //      smaller one on พนักงานขาย. Fixed, never `min-content` — the head row and each data row are
  //      separate grid containers, so `min-content` sizes each row from its own content and the
  //      columns stagger row to row.
  //   2. ลูกค้า / โครงการ keeps `minmax(0, …)` and stays the SECOND track: it is the designated
  //      absorber, the one column of free text that can give up width without losing anything.
  //   3. Both free-text cells render a <span> wrapper, because styles.css's wrap escape hatch
  //      (`.data-row > td > strong | small | span`) reaches nothing else — a <div> child, or a bare
  //      string with no child element at all, inherits the cell's `nowrap` and is truncated.
  //
  // The MAGNITUDES are pinned literally, not merely counted. Shrinking every floor, or permuting
  // them so ยอดรวม inherits สถานะ's, would keep any count-based assertion green while silently
  // clipping the money again — that exact escape was found on the sibling page's guard.
  function gridClasses(el, what) {
    expect(el, `no ${what} to read the grid classes from`).toBeTruthy();
    return el.className;
  }

  it('floors the columns that cannot wrap and leaves ลูกค้า / โครงการ as the absorber', async () => {
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    const { container } = renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    // Asserted on the head row AND a data row. They share one constant today, so this cannot
    // diverge — but splitting the grid and flooring only one is exactly how the header would stop
    // lining up with the body, and a guard that reads only the head row would stay green.
    for (const [el, what] of [
      [container.querySelector('.table-head'), '.table-head'],
      [container.querySelector('.data-row'), '.data-row'],
    ]) {
      const classes = gridClasses(el, what);

      // `min-content` is the specific WRONG floor: it re-sizes per row and staggers the columns.
      expect(classes, `${what}: min-content floors stagger the columns row to row`)
        .not.toContain('min-content');

      // Two shrinkable tracks — ลูกค้า / โครงการ and พนักงานขาย, the two free-text columns that
      // wrap — and they are tracks TWO and THREE.
      const shrinkable = classes.match(/minmax\(0,/g) ?? [];
      expect(shrinkable, `${what}: only the two free-text columns may shrink below their content`)
        .toHaveLength(2);

      // The whole track list, pinned literally. The values are each column's widest realistic
      // value measured in the live cell typography and rounded up; ยอดรวม and วันที่ are sized
      // against the FALLBACK font, not Sarabun, because Sarabun is swap-loaded from Google Fonts
      // and absent entirely on an on-prem host that cannot reach them. Re-measure before changing
      // any of these — see the source comment beside LIST_TABLE_GRID.
      expect(classes).toContain(
        'grid-cols-[minmax(6.875rem,1fr)_minmax(0,2.2fr)_minmax(0,1.3fr)'
        + '_minmax(8.25rem,1.1fr)_minmax(4.75rem,1fr)_minmax(6.5rem,1fr)]',
      );
    }
  });

  it('keeps LIST_TABLE_GRID as ONE unbroken literal so Tailwind can see it', () => {
    // Reading this component's own SOURCE, not its rendered output, and that is the point.
    // Tailwind's scanner matches whole class strings in the source text; splitting the literal
    // across a `+` concatenation — which looks like a harmless tidy-up, and is exactly what the
    // assertion above does to itself — leaves the arbitrary value unseen, so the rule is NEVER
    // EMITTED. `grid-template-columns` then goes unset, `.table-head`/`.data-row` supply
    // `display:grid` with no columns of their own, and every row collapses to one implicit track
    // (measured: 63.5px tall becomes 242px, all six cells stacked).
    //
    // Nothing else catches it: lint passes, this suite passes, `npm run build` exits 0 with no
    // warning, and the dev server keeps rendering correctly from Tailwind's cache. The only
    // evidence is that `dist/assets/*.css` stops containing the value. A source-text assertion is
    // the cheapest guard that fails in CI instead.
    // `import.meta.url` is not a file: URL under vitest's transform, so resolve from the runner's
    // root (frontend/) instead.
    const source = readFileSync(
      resolve(process.cwd(), 'src/features/quotations/QuotationListPage.jsx'), 'utf8',
    );
    const declaration = source
      .split('\n')
      .find((line) => line.includes('const LIST_TABLE_GRID'));
    expect(declaration, 'LIST_TABLE_GRID declaration not found').toBeTruthy();
    // The whole value must sit on that one line, ending the statement there.
    expect(declaration.trimEnd().endsWith("]';"), 'the grid literal must not be split or continued')
      .toBe(true);
    for (const track of ['6.875rem', '2.2fr', '1.3fr', '8.25rem', '4.75rem', '6.5rem']) {
      expect(declaration, `track ${track} must be in the same literal`).toContain(track);
    }
  });

  it('pins the COLUMNS order the track list is measured against', async () => {
    // The grid string and the COLUMNS array are two halves of one contract, and pinning only the
    // string leaves the other half free. Swapping the ยอดรวม and สถานะ entries in COLUMNS, with the
    // grid untouched, puts a grand total in the 76px status track: measured at 721px,
    // `฿600,616.00` breaks across two lines mid-number while สถานะ sits in 132px. That is the same
    // defect the permuted-floors mutation covers, reached from the other side.
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    const { container } = renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const headers = [...container.querySelector('.table-head').children]
      .map((cell) => cell.textContent.replace(/[▲▼↑↓]/g, '').trim());
    expect(headers).toEqual([
      'เลขที่', 'ลูกค้า / โครงการ', 'พนักงานขาย', 'ยอดรวม', 'สถานะ', 'วันที่',
    ]);
  });

  it('gives both free-text cells a <span> so they can wrap instead of truncating', async () => {
    api.dealQuotations.list.mockResolvedValue({
      items: [row({ customerName: 'บริษัท เดโม เรสซิเดนซ์ จำกัด', projectName: 'Residence Nawamin 76' })],
    });
    const { container } = renderListPage(salesUser);
    await screen.findByText('บริษัท เดโม เรสซิเดนซ์ จำกัด');

    const cells = [...container.querySelector('.data-row').children];

    // ลูกค้า / โครงการ — the wrapper must be a <span>. A <div> here inherits the cell's nowrap,
    // which is what stopped the absorber absorbing anything.
    const customerCell = cells[1];
    const customerWrapper = customerCell.firstElementChild;
    expect(customerWrapper?.tagName, 'ลูกค้า / โครงการ wrapper must be a <span>, not a <div>')
      .toBe('SPAN');
    expect(customerWrapper.textContent).toContain('บริษัท เดโม เรสซิเดนซ์ จำกัด');
    expect(customerWrapper.textContent).toContain('Residence Nawamin 76');

    // พนักงานขาย — a bare string leaves no child element for the escape hatch to match at all.
    const repCell = cells[2];
    expect(repCell.firstElementChild?.tagName, 'พนักงานขาย must render inside a <span>')
      .toBe('SPAN');
    expect(repCell.textContent).toBe('คุณสมหมาย ขายดี');
  });
});
