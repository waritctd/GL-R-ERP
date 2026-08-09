import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FilterField, Panel, PageStack, StatGrid } from './Layout.jsx';

globalThis.React = React;

describe('Panel', () => {
  it('renders children', () => {
    render(<Panel><p>เนื้อหาแผง</p></Panel>);
    expect(screen.getByText('เนื้อหาแผง')).toBeTruthy();
  });

  it('applies the panel surface classes', () => {
    render(<Panel data-testid="panel"><p>เนื้อหา</p></Panel>);
    const panel = screen.getByTestId('panel');

    expect(panel.tagName).toBe('SECTION');
    expect(panel.className).toContain('bg-surface');
    expect(panel.className).toContain('border-border');
    expect(panel.className).toContain('rounded-md');
    // Item 3 (owner-approved polish): cards are border-only now — a
    // `shadow-sm` alongside the border was visually redundant at that
    // token's 1px/3%-opacity value. See Layout.jsx's Panel doc comment.
    expect(panel.className).not.toContain('shadow-sm');
  });

  it('renders a title and actions via Panel.Header when provided', () => {
    render(
      <Panel title="หัวข้อ" actions={<button type="button">ดำเนินการ</button>}>
        <p>เนื้อหา</p>
      </Panel>,
    );

    expect(screen.getByText('หัวข้อ')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'ดำเนินการ' })).toBeTruthy();
  });

  it('keeps flush worklist panel titles inset from the border', () => {
    render(
      <Panel title="สิ่งที่ต้องทำ" flush>
        <p>เนื้อหา</p>
      </Panel>,
    );

    const header = screen.getByRole('heading', { name: 'สิ่งที่ต้องทำ' }).closest('div');
    expect(header?.className).toContain('px-5');
    expect(header?.className).toContain('border-b');
    // The card lends no padding in this mode, so the header must not also carry
    // the default bottom margin — it would push the rule away from the title.
    expect(header?.className).toContain('mb-0');
  });

  it('does not give a bordered header to a panel that merely zeroes its padding', () => {
    // The old API inferred "flush" by regex-sniffing `p-0` out of `className`,
    // so any caller zeroing padding for an unrelated reason silently acquired a
    // rule under its title. `flush` is now the only way to ask for that.
    render(
      <Panel title="สิ่งที่ต้องทำ" className="!p-0">
        <p>เนื้อหา</p>
      </Panel>,
    );

    const header = screen.getByRole('heading', { name: 'สิ่งที่ต้องทำ' }).closest('div');
    expect(header?.className).not.toContain('border-b');
    expect(header?.className).toContain('mb-4');
  });

  it('merges caller className last', () => {
    render(<Panel data-testid="panel" className="p-2">เนื้อหา</Panel>);
    expect(screen.getByTestId('panel').className).toContain('p-2');
  });
});

describe('PageStack', () => {
  it('renders children with the page-stack grid classes', () => {
    render(<PageStack data-testid="stack"><p>เนื้อหา</p></PageStack>);
    const stack = screen.getByTestId('stack');

    expect(screen.getByText('เนื้อหา')).toBeTruthy();
    expect(stack.className).toContain('grid');
    expect(stack.className).toContain('max-w-[1320px]');
  });
});

describe('StatGrid', () => {
  it('renders children with the 4-col responsive grid classes', () => {
    render(
      <StatGrid data-testid="stat-grid">
        <div>การ์ด 1</div>
        <div>การ์ด 2</div>
      </StatGrid>,
    );
    const grid = screen.getByTestId('stat-grid');

    expect(screen.getByText('การ์ด 1')).toBeTruthy();
    expect(screen.getByText('การ์ด 2')).toBeTruthy();
    expect(grid.className).toContain('grid-cols-4');
    expect(grid.className).toContain('nav-drawer:grid-cols-2');
    // ≤720px stays 2-up (not a single stacked column) — a single column was
    // forcing users to scroll past several screens of stat tiles before
    // reaching real content.
    expect(grid.className).toContain('mobile:grid-cols-2');
  });
});

// jsdom has no layout engine, so none of this can assert the geometry the
// component exists to produce — every rect is 0×0 here. What it CAN pin is the
// contract that produces it: the classes are on the rendered element, and the
// label association is the right one. The measured geometry these classes
// produce is recorded in Layout.jsx's own comment.
describe('FilterField', () => {
  it('can shrink below its content and takes a full line on mobile', () => {
    render(<FilterField label="จากวันที่" data-testid="f"><input type="date" readOnly value="2026-08-10" /></FilterField>);
    const field = screen.getByTestId('f');

    // The bug this component exists to remove: a flex item's automatic minimum
    // size is its content's min-content width, so a field wrapping a native
    // date control could not shrink and pushed the row out of the bar on iOS.
    expect(field.className).toContain('min-w-0');
    // One field per line at ≤720px — equal widths, one label x-position.
    expect(field.className).toContain('mobile:basis-full');
    // A shared floor above mobile, and deliberately NO `grow`: a lone wrapped
    // field would otherwise absorb its whole line (measured 565px beside 218px
    // siblings at 768px), which is the raggedness this replaces.
    expect(field.className).toContain('basis-[190px]');
    expect(field.className.split(/\s+/)).not.toContain('grow');
  });

  it('wraps its control in the <label> when the field holds exactly one control', () => {
    render(<FilterField label="สถานะ"><select><option>ทุกสถานะ</option></select></FilterField>);
    // Implicit association: the <label> is the wrapper.
    expect(screen.getByLabelText('สถานะ').tagName).toBe('SELECT');
  });

  it('associates the label with the named control only, when the field holds several', () => {
    render(
      <FilterField label="วันที่" htmlFor="d">
        <div>
          <button type="button" aria-label="วันก่อนหน้า">‹</button>
          <input id="d" type="date" readOnly value="2026-08-10" />
          <button type="button" aria-label="วันถัดไป">›</button>
        </div>
      </FilterField>,
    );

    // Wrong-way-round on purpose: the risk is the name landing on a BUTTON, or
    // on nothing, because a <label> wrapping three controls is ambiguous. That
    // is what broke AttendancePage's findByLabelText('วันที่').
    const named = screen.getByLabelText('วันที่');
    expect(named.tagName).toBe('INPUT');
    expect(named.getAttribute('type')).toBe('date');
    // ...and the buttons keep their own names rather than inheriting the field's.
    expect(screen.getByRole('button', { name: 'วันก่อนหน้า' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'วันถัดไป' })).toBeTruthy();
  });
});
