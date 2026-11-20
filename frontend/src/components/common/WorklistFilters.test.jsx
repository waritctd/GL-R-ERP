import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { WorklistFilters } from './WorklistFilters.jsx';

globalThis.React = React;

const items = [
  { key: '', label: 'ทั้งหมด', count: 11 },
  { key: 'a', label: 'สถานะ A', count: 3, dotClassName: 'bg-phase-1' },
  { key: 'b', label: 'สถานะ B', count: 4 },
];

describe('WorklistFilters', () => {
  it('calls onSelect with the clicked chip key', () => {
    const onSelect = vi.fn();
    render(<WorklistFilters items={items} activeKey="" onSelect={onSelect} ariaLabel="ตัวกรอง" />);

    fireEvent.click(screen.getByRole('button', { name: /สถานะ B/ }));
    expect(onSelect).toHaveBeenCalledWith('b');
  });
});
