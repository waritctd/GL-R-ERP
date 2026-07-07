import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Button } from './Button.jsx';

globalThis.React = React;

describe('Button', () => {
  it('renders children', () => {
    render(<Button>บันทึก</Button>);
    expect(screen.getByRole('button', { name: 'บันทึก' })).toBeTruthy();
  });

  it('uses primary md classes by default', () => {
    render(<Button>ค่าเริ่มต้น</Button>);
    const button = screen.getByRole('button', { name: 'ค่าเริ่มต้น' });

    expect(button.type).toBe('button');
    expect(button.className).toContain('inline-flex');
    expect(button.className).toContain('min-h-[38px]');
    expect(button.className).toContain('bg-primary');
    expect(button.className).toContain('text-surface');
    expect(button.className).toContain('px-4');
  });

  it('applies variant classes', () => {
    render(
      <>
        <Button variant="secondary">รอง</Button>
        <Button variant="success">สำเร็จ</Button>
        <Button variant="danger">ลบ</Button>
        <Button variant="text">ลิงก์</Button>
        <Button variant="icon" aria-label="ตั้งค่า">i</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'รอง' }).className).toContain('border-border-input');
    expect(screen.getByRole('button', { name: 'สำเร็จ' }).className).toContain('bg-success');
    expect(screen.getByRole('button', { name: 'ลบ' }).className).toContain('text-danger');
    expect(screen.getByRole('button', { name: 'ลิงก์' }).className).toContain('border-0');
    expect(screen.getByRole('button', { name: 'ตั้งค่า' }).className).toContain('w-11');
  });

  it('applies small size classes', () => {
    render(
      <>
        <Button size="sm">เล็ก</Button>
        <Button variant="icon" size="sm" aria-label="ไอคอนเล็ก">i</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'เล็ก' }).className).toContain('min-h-[32px]');
    expect(screen.getByRole('button', { name: 'เล็ก' }).className).toContain('text-sm');
    expect(screen.getByRole('button', { name: 'ไอคอนเล็ก' }).className).toContain('w-9');
    expect(screen.getByRole('button', { name: 'ไอคอนเล็ก' }).className).toContain('min-h-[36px]');
  });

  it('fires onClick and respects disabled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>คลิก</Button>);

    fireEvent.click(screen.getByRole('button', { name: 'คลิก' }));
    expect(onClick).toHaveBeenCalledTimes(1);

    render(<Button disabled onClick={onClick}>ปิดอยู่</Button>);
    const disabledButton = screen.getByRole('button', { name: 'ปิดอยู่' });
    expect(disabledButton.disabled).toBe(true);
    fireEvent.click(disabledButton);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('merges caller className last', () => {
    render(<Button className="px-2 min-h-[40px]">กำหนดเอง</Button>);
    const button = screen.getByRole('button', { name: 'กำหนดเอง' });

    expect(button.className).toContain('px-2');
    expect(button.className).not.toContain('px-4');
    expect(button.className).toContain('min-h-[40px]');
    expect(button.className).not.toContain('min-h-[38px]');
  });
});
