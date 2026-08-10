import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage.jsx';

globalThis.React = React;

const employee = {
  nameTh: 'สมชาย ใจดี',
  nameEn: 'Somchai Jaidee',
  code: 'GLR-0001',
  positionTh: 'พนักงาน',
  divisionTh: 'HR-ฝ่ายบุคคล',
  phone: '081-234-5678',
  email: 'somchai@glr.co.th',
  currentAddress: { line1: '123/45 ถนนสุขุมวิท' },
  emergencyContact: { name: 'สมหญิง ใจดี', phone: '089-999-8888' },
};

function renderProfile(profileRequests = []) {
  return render(
    <MemoryRouter>
      <ProfilePage
        user={{ role: 'employee', employeeId: 1 }}
        employee={employee}
        profileRequests={profileRequests}
        onCreateRequest={vi.fn()}
        taxAllowanceSummary={null}
      />
    </MemoryRouter>,
  );
}

/**
 * The contact list renders one "ขอแก้ไข" button per editable field. A sighted user reads each
 * against the label beside it; a screen reader hears four buttons with the same name, in a list,
 * with nothing to choose between them. WCAG 2.4.6 — the accessible name has to carry the context
 * the layout is carrying visually.
 */
describe('ProfilePage — each change-request button names its own field', () => {
  it('gives every button a distinct accessible name', () => {
    renderProfile();

    for (const label of ['เบอร์โทรศัพท์', 'อีเมล', 'ที่อยู่ปัจจุบัน', 'ผู้ติดต่อฉุกเฉิน']) {
      expect(screen.getByRole('button', { name: `ขอแก้ไข${label}` })).not.toBeNull();
    }

    // The point of the fix, stated as the property rather than as four lookups: no two buttons on
    // this page may share an accessible name. A `getByRole` per field would still pass if the
    // aria-labels were added but duplicated across rows.
    const names = screen.getAllByRole('button').map((button) => (
      button.getAttribute('aria-label') ?? button.textContent.trim()
    ));
    expect(new Set(names).size).toBe(names.length);
  });

  // A button that says "awaiting approval" for the phone number must not announce itself as
  // "request a change to the phone number" — the label tracks the state, and stays distinct.
  it('names the pending state per field too', () => {
    renderProfile([{ id: 1, fieldKey: 'phone', status: 'pending', fieldLabel: 'เบอร์โทรศัพท์' }]);

    expect(screen.getByRole('button', { name: 'เบอร์โทรศัพท์: รออนุมัติ' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ขอแก้ไขเบอร์โทรศัพท์' })).toBeNull();
    // The other three are unaffected.
    expect(screen.getByRole('button', { name: 'ขอแก้ไขอีเมล' })).not.toBeNull();
  });
});
