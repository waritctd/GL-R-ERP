import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage.jsx';

globalThis.React = React;

// Task 4 (slot signatures, 2026-09-26): SIGNATURE_CARD_ROLES now includes 'sales', so
// SignatureCard mounts for the existing 'sales' fixtures below too -- it calls useMutation, which
// needs a QueryClientProvider ancestor (this page has none of its own; the real app supplies one
// higher up the tree) and calls api.employees.hasSignature on mount, which must be mocked the same
// way SignatureCard.test.jsx already does so no real network call happens under jsdom.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      ...actual.api,
      employees: {
        hasSignature: vi.fn().mockResolvedValue(false),
        getSignature: vi.fn((id) => `/api/employees/${id}/signature`),
        uploadSignature: vi.fn(),
        deleteSignature: vi.fn(),
      },
    },
  };
});

const employee = { nameTh: 'ทดสอบ พนักงาน', nameEn: 'Test Employee', code: 'E001' };

function renderProfilePage(user) {
  // TaxAllowanceSummaryPanel (always rendered on this page) calls useNavigate, so it needs a
  // Router ancestor even though this test never navigates. QueryClientProvider is needed by
  // SignatureCard (see the mock comment above) whenever it mounts.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ProfilePage
          user={user}
          employee={employee}
          profileRequests={[]}
          onCreateRequest={vi.fn()}
          taxAllowanceSummary={undefined}
        />
      </MemoryRouter>
    </QueryClientProvider>,
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

// Task 4 (slot signatures, 2026-09-26): a sales rep now draws in the quotation's
// ผู้พิมพ์/พนักงานขาย slots too, so SIGNATURE_CARD_ROLES was widened to include 'sales' --
// EmployeeSignatureService#upload already permits "self" regardless of role, this was purely a
// frontend gate lagging the backend's actual capability (backend authz untouched).
describe('ProfilePage signature card role gate', () => {
  it('shows the signature card for role "sales" (widened in this change)', () => {
    renderProfilePage({ role: 'sales', employeeId: 9 });

    expect(screen.getByText('ลายเซ็นสำหรับใบเสนอราคา')).toBeTruthy();
  });

  it('still shows the signature card for the pre-existing approver roles', () => {
    renderProfilePage({ role: 'ceo', employeeId: 1 });
    expect(screen.getByText('ลายเซ็นสำหรับใบเสนอราคา')).toBeTruthy();
  });

  it('still hides the signature card for a role with no slot in the signature block', () => {
    renderProfilePage({ role: 'hr', employeeId: 2 });
    expect(screen.queryByText('ลายเซ็นสำหรับใบเสนอราคา')).toBeNull();
  });
});
