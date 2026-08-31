import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage.jsx';

globalThis.React = React;

const employee = { nameTh: 'ทดสอบ พนักงาน', nameEn: 'Test Employee', code: 'E001' };

function renderProfilePage(user) {
  // TaxAllowanceSummaryPanel (always rendered on this page) calls useNavigate, so it needs a
  // Router ancestor even though this test never navigates.
  return render(
    <MemoryRouter>
      <ProfilePage
        user={user}
        employee={employee}
        profileRequests={[]}
        onCreateRequest={vi.fn()}
        taxAllowanceSummary={undefined}
      />
    </MemoryRouter>,
  );
}

// The "ขอแก้ไข" affordance is identity-gated (canRequestChange = !!user.employeeId), not
// role-gated — see ProfilePage.jsx and ProfileRequestController#create, which the frontend gate
// mirrors. These are the two cases the widening actually changes: a non-"employee" role now sees
// the button, and an account with no linked employee record still does not.
describe('ProfilePage change-request affordance', () => {
  it('shows "ขอแก้ไข" for a non-employee role that IS linked to an employee record', () => {
    renderProfilePage({ role: 'sales', employeeId: 9 });

    expect(screen.getAllByRole('button', { name: 'ขอแก้ไข' }).length).toBeGreaterThan(0);
  });

  it('hides "ขอแก้ไข" for a user with no employeeId, regardless of role', () => {
    renderProfilePage({ role: 'sales', employeeId: null });

    expect(screen.queryByRole('button', { name: 'ขอแก้ไข' })).toBeNull();
    // The empty-state hint that names the button must not dangle either.
    expect(screen.queryByText('กด "ขอแก้ไข" ที่ข้อมูลติดต่อด้านบนเพื่อส่งคำขอ')).toBeNull();
  });
});
