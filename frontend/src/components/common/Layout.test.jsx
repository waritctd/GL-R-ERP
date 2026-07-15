import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Panel, PageStack, StatGrid } from './Layout.jsx';

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
    expect(panel.className).toContain('rounded-md');
    expect(panel.className).toContain('shadow-sm');
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
    expect(grid.className).toContain('max-[1040px]:grid-cols-2');
    // ≤720px stays 2-up (not a single stacked column) — a single column was
    // forcing users to scroll past several screens of stat tiles before
    // reaching real content.
    expect(grid.className).toContain('max-[720px]:grid-cols-2');
  });
});
