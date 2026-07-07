import React from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { CollapsibleSection } from './CollapsibleSection.jsx';

globalThis.React = React;

describe('CollapsibleSection', () => {
  it('renders title and children when open', () => {
    render(
      <CollapsibleSection title="ข้อมูลลูกค้า">
        <p>เนื้อหาแบบฟอร์ม</p>
      </CollapsibleSection>,
    );

    expect(screen.getByText('ข้อมูลลูกค้า')).toBeTruthy();
    expect(screen.getByText('เนื้อหาแบบฟอร์ม')).toBeTruthy();

    const button = screen.getByRole('button', { name: 'ข้อมูลลูกค้า' });
    expect(button.getAttribute('aria-expanded')).toBe('true');
  });

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

  it('starts closed when defaultOpen is false', () => {
    render(
      <CollapsibleSection title="ตัวเลือกเพิ่มเติม" defaultOpen={false}>
        <p>ซ่อนอยู่</p>
      </CollapsibleSection>,
    );

    expect(screen.getByRole('button', { name: 'ตัวเลือกเพิ่มเติม' }).getAttribute('aria-expanded')).toBe(
      'false',
    );
    expect(screen.queryByText('ซ่อนอยู่')).toBeNull();
  });
});
