import React from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EmployeeListPage } from './EmployeeListPage.jsx';

globalThis.React = React;

const user = { employeeId: 1, name: 'HR ทดสอบ', role: 'hr', manager: false };

// One of each status. ACT and PRB are both "currently employed"; RSG is the only one the default view hides.
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
  return (
    <>
      <span data-testid="search-params">{location.search}</span>
      <span data-testid="location-path">{location.pathname}</span>
    </>
  );
}

function renderPage(initialEntry = '/employees', employeesOverride = employees) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <EmployeeListPage user={user} employees={employeesOverride} onCreateEmployee={() => {}} />
      <LocationProbe />
    </MemoryRouter>,
  );
}

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
});

