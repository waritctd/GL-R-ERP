import React from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { EmployeeListPage } from './EmployeeListPage.jsx';
import { formatMoney } from '../../utils/format.js';

globalThis.React = React;

const user = { employeeId: 1, name: 'HR ทดสอบ', role: 'hr', manager: false };

// One of each status. ACT and PRB are both "currently employed"; RSG is the only
// one the default view hides.
const employees = [
  {
    id: 1,
    code: 'EMP-001',
    nameTh: 'สมชาย ใจดี',
    nameEn: 'Somchai Jaidee',
    nickName: 'ชาย',
    initials: 'สจ',
    divisionId: 'D1',
    divisionTh: 'ฝ่ายผลิต',
    departmentTh: 'แผนกประกอบ',
    positionTh: 'หัวหน้าฝ่ายผลิต',
    hireDate: '2022-01-12',
    salary: 45000,
    statusId: 'ACT',
    statusTh: 'ทำงานปกติ',
    statusTone: 'success',
    active: true,
  },
  {
    id: 2,
    code: 'EMP-002',
    nameTh: 'นภาพร สุขสวัสดิ์',
    nameEn: 'Napaporn Suksawat',
    nickName: 'แนน',
    initials: 'นส',
    divisionId: 'D2',
    divisionTh: 'ฝ่ายบัญชี',
    departmentTh: 'แผนกบัญชี',
    positionTh: 'เจ้าหน้าที่บัญชี',
    hireDate: '2026-03-03',
    salary: 21500,
    statusId: 'PRB',
    statusTh: 'ทดลองงาน',
    statusTone: 'warning',
    active: true,
  },
  {
    id: 3,
    code: 'EMP-003',
    nameTh: 'อาทิตย์ พงษ์ไพร',
    nameEn: 'Arthit Pongprai',
    nickName: 'ทิตย์',
    initials: 'อพ',
    divisionId: 'D1',
    divisionTh: 'ฝ่ายผลิต',
    departmentTh: 'แผนกคลังสินค้า',
    positionTh: 'พนักงานคลังสินค้า',
    hireDate: '2020-06-01',
    salary: 18000,
    statusId: 'RSG',
    statusTh: 'ลาออก',
    statusTone: 'danger',
    active: false,
  },
];

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="search-params">{location.search}</span>;
}

function renderPage(initialEntry = '/employees') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <EmployeeListPage user={user} employees={employees} onCreateEmployee={() => {}} />
      <LocationProbe />
    </MemoryRouter>,
  );
}

function params() {
  return screen.getByTestId('search-params').textContent;
}

function rowNames(container) {
  return [...container.querySelectorAll('.data-row')].map((row) => row.textContent);
}

describe('EmployeeListPage search normalisation', () => {
  // The shipped bug: `query` was trimmed for nameEn/code but nameTh and nickName
  // were matched against the raw input, so a trailing space returned nothing
  // while the search box still showed text.
  it('matches Thai names when the query has surrounding whitespace', () => {
    renderPage('/employees?q=%20%E0%B8%AA%E0%B8%A1%E0%B8%8A%E0%B8%B2%E0%B8%A2%20');

    expect(screen.getByText('สมชาย ใจดี')).toBeTruthy();
    expect(screen.queryByText('นภาพร สุขสวัสดิ์')).toBeNull();
  });

  it('matches Thai nicknames when the query has surrounding whitespace', () => {
    renderPage('/employees?q=%20%E0%B9%81%E0%B8%99%E0%B8%99%20');

    expect(screen.getByText('นภาพร สุขสวัสดิ์')).toBeTruthy();
    expect(screen.queryByText('สมชาย ใจดี')).toBeNull();
  });

  it('matches code and English name case-insensitively', () => {
    const { unmount } = renderPage('/employees?q=emp-001');
    expect(screen.getByText('สมชาย ใจดี')).toBeTruthy();
    unmount();

    renderPage('/employees?q=NAPAPORN');
    expect(screen.getByText('นภาพร สุขสวัสดิ์')).toBeTruthy();
  });
});

describe('EmployeeListPage status filter', () => {
  it('defaults to currently-employed staff, hiding resigned employees', () => {
    renderPage();

    expect(screen.getByText('สมชาย ใจดี')).toBeTruthy();
    expect(screen.getByText('นภาพร สุขสวัสดิ์')).toBeTruthy();
    expect(screen.queryByText('อาทิตย์ พงษ์ไพร')).toBeNull();
  });

  it('shows only resigned staff for ?status=RSG', () => {
    renderPage('/employees?status=RSG');

    expect(screen.getByText('อาทิตย์ พงษ์ไพร')).toBeTruthy();
    expect(screen.queryByText('สมชาย ใจดี')).toBeNull();
  });

  it('shows every status for ?status=all', () => {
    const { container } = renderPage('/employees?status=all');

    expect(rowNames(container)).toHaveLength(3);
  });

  it('falls back to the default view for an unknown status value', () => {
    renderPage('/employees?status=not-a-status');

    expect(screen.getByText('สมชาย ใจดี')).toBeTruthy();
    expect(screen.queryByText('อาทิตย์ พงษ์ไพร')).toBeNull();
    expect(screen.getByLabelText('สถานะ').value).toBe('current');
  });

  it('writes the param when narrowing, and removes it when returning to the default', () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('สถานะ'), { target: { value: 'RSG' } });
    expect(params()).toContain('status=RSG');

    fireEvent.change(screen.getByLabelText('สถานะ'), { target: { value: 'current' } });
    expect(params()).toContain('status=current');
  });
});

describe('EmployeeListPage division and department params', () => {
  it('clears a stale department when the division changes', () => {
    renderPage('/employees?div=D1&dept=%E0%B9%81%E0%B8%9C%E0%B8%99%E0%B8%81%E0%B8%9B%E0%B8%A3%E0%B8%B0%E0%B8%81%E0%B8%AD%E0%B8%9A');

    expect(params()).toContain('dept=');

    fireEvent.change(screen.getByLabelText('ฝ่าย'), { target: { value: 'D2' } });

    expect(params()).toContain('div=D2');
    expect(params()).not.toContain('dept=');
  });

  it('clears every param via ล้าง and restores the default view', () => {
    renderPage('/employees?status=all&div=D1');

    fireEvent.click(screen.getByRole('button', { name: /ล้าง/ }));

    expect(params()).toBe('');
    expect(screen.queryByText('อาทิตย์ พงษ์ไพร')).toBeNull();
  });
});

describe('EmployeeListPage filter disclosure', () => {
  it('opens expanded on desktop and toggles closed', () => {
    renderPage();

    const toggle = screen.getByRole('button', { name: /^ตัวกรอง/ });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByLabelText('สถานะ')).toBeTruthy();

    fireEvent.click(toggle);

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText('สถานะ')).toBeNull();
  });

  it('summarises active filters as chips when collapsed', () => {
    renderPage('/employees?div=D1');

    fireEvent.click(screen.getByRole('button', { name: /^ตัวกรอง/ }));

    // Default status counts as a chip on purpose: it is what hides resigned staff.
    expect(screen.getByRole('button', { name: /ปฏิบัติงานอยู่/ })).toBeTruthy();
    const divisionChip = screen.getByRole('button', { name: /ฝ่ายผลิต/ });

    fireEvent.click(divisionChip);
    expect(params()).not.toContain('div=');
  });
});

describe('EmployeeListPage sorting', () => {
  it('defaults to name ascending', () => {
    const { container } = renderPage('/employees?status=all');

    const names = rowNames(container);
    expect(names[0]).toContain('นภาพร');
    expect(names[2]).toContain('อาทิตย์');
  });

  it('writes the sort param and reorders rows', () => {
    const { container } = renderPage('/employees?status=all');

    fireEvent.change(screen.getByLabelText('เรียงตาม'), { target: { value: 'salary.desc' } });

    expect(params()).toContain('sort=salary.desc');
    expect(rowNames(container)[0]).toContain('สมชาย');
  });
});

describe('EmployeeListPage mobile cards', () => {
  const realMatchMedia = window.matchMedia;

  afterEach(() => {
    window.matchMedia = realMatchMedia;
  });

  function stubMobile() {
    window.matchMedia = (query) => ({
      matches: query === '(max-width: 720px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    });
  }

  it('renders a compact card carrying identity, role, code and salary', () => {
    stubMobile();
    const { container } = renderPage();

    const cards = container.querySelectorAll('.record-card');
    expect(cards).toHaveLength(2);

    const first = within(cards[0]);
    expect(first.getByText('นภาพร สุขสวัสดิ์')).toBeTruthy();
    expect(first.getByText(/แนน/)).toBeTruthy();
    expect(first.getByText(/EMP-002/)).toBeTruthy();
    expect(first.getByText(formatMoney(21500))).toBeTruthy();
    expect(first.getByText('ทดลองงาน')).toBeTruthy();

    // Card mode replaces the column grid outright, so no header cells remain.
    expect(screen.queryByText('ตำแหน่ง / แผนก')).toBeNull();
    expect(container.querySelectorAll('.data-row')).toHaveLength(0);
  });

  it('starts with the filter panel collapsed to save vertical space', () => {
    stubMobile();
    renderPage();

    expect(screen.getByRole('button', { name: /^ตัวกรอง/ }).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText('สถานะ')).toBeNull();
  });
});
