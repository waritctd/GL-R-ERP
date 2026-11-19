import React from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { CollapsibleSection } from './CollapsibleSection.jsx';

globalThis.React = React;

describe('CollapsibleSection', () => {
  it('toggles aria-expanded and hides/shows the body when the header is clicked', () => {
    render(
      <CollapsibleSection title="ข้อมูลลูกค้า">
        <p>เนื้อหาแบบฟอร์ม</p>
      </CollapsibleSection>,
    );

    const button = screen.getByRole('button', { name: 'ข้อมูลลูกค้า' });

    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('เนื้อหาแบบฟอร์ม')).toBeNull();

    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('เนื้อหาแบบฟอร์ม')).toBeTruthy();
  });
});
