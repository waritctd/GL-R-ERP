import { describe, expect, it } from 'vitest';
import {
  canManagerCancelRequest,
  canReviewRequest,
  visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';

const employee = { role: 'employee', employeeId: 1 };
const nonHrManager = { role: 'employee', employeeId: 5, manager: true };
const hr = { role: 'hr', employeeId: 99 };
const ceo = { role: 'ceo', employeeId: 1 };

const submittedRequestUnderNonHrManager = {
  id: 501, status: 'SUBMITTED', managerEmployeeId: 5,
};
const submittedRequestUnderSomeoneElse = {
  id: 502, status: 'SUBMITTED', managerEmployeeId: 999,
};

// api.leave.employees() shape: self + direct reports (hr/ceo: every active employee).
// Only `.length` matters to hasTeamMembers.
const selfOnlyEmployeeOptions = [
  {
    employeeId: 5, employeeName: 'หัวหน้างาน', self: true, directReport: false,
  },
];

describe('visibleLeaveSurfaceTabIds', () => {
  it('a plain employee with no reports does not see "review" or "team"', () => {
    expect(visibleLeaveSurfaceTabIds(employee, [])).toEqual(['me']);
    // Still hidden even with OTHER people's requests loaded -- none of them are this user's own reports.
    expect(visibleLeaveSurfaceTabIds(employee, [submittedRequestUnderSomeoneElse])).toEqual(['me']);
    // ...and even with employeeOptions unset (the default -- a caller that never wired the signal through at all).
    expect(visibleLeaveSurfaceTabIds(employee, [], selfOnlyEmployeeOptions)).toEqual(['me']);
  });

  it('a non-HR manager (not in ROLE_PERMISSIONS.canReviewLeave) DOES see "review" once a report\'s request loads', () => {
    // THE load-bearing case: a plain division manager is not in canReviewLeave, but
    // LeaveService.canReviewEmployee grants any direct manager review rights over their reports.
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderNonHrManager]))
      .toEqual(['me', 'review']);
  });

  // hr/ceo oversee leave but never file it (LeaveService#resolveTargetEmployee 403s a
  // self-submission), so 'me' is hidden for them.
  describe('"me" is hidden for the queue-only roles (hr/ceo)', () => {
    it('hr and ceo do not get a "ของฉัน" tab', () => {
      expect(visibleLeaveSurfaceTabIds(hr, [])).not.toContain('me');
      expect(visibleLeaveSurfaceTabIds(ceo, [])).not.toContain('me');
    });

    it('every other role still gets it', () => {
      expect(visibleLeaveSurfaceTabIds(employee, [])).toContain('me');
      expect(visibleLeaveSurfaceTabIds(nonHrManager, [])).toContain('me');
    });
  });

});

describe('canReviewRequest / canManagerCancelRequest (ported from LeavePage.jsx)', () => {
  it('canReviewRequest requires SUBMITTED plus manager-FK match or canReviewAll', () => {
    expect(canReviewRequest(submittedRequestUnderNonHrManager, nonHrManager, false)).toBe(true);
    expect(canReviewRequest(submittedRequestUnderSomeoneElse, nonHrManager, false)).toBe(false);
    expect(canReviewRequest({ ...submittedRequestUnderSomeoneElse, status: 'APPROVED' }, hr, true)).toBe(false);
  });

  it('canManagerCancelRequest allows an already-APPROVED request', () => {
    const approvedUnderManager = { id: 505, status: 'APPROVED', managerEmployeeId: 5 };
    expect(canManagerCancelRequest(approvedUnderManager, nonHrManager, false)).toBe(true);
    expect(canManagerCancelRequest({ ...approvedUnderManager, status: 'CANCELLED' }, nonHrManager, false)).toBe(false);
  });

  // A pending row carries exactly two decisions (อนุมัติ / ปฏิเสธ); assert no third button,
  // for a manager AND a canReviewAll actor.
  it('canManagerCancelRequest refuses a still-SUBMITTED request', () => {
    expect(canManagerCancelRequest(submittedRequestUnderNonHrManager, nonHrManager, false)).toBe(false);
    expect(canManagerCancelRequest(submittedRequestUnderSomeoneElse, hr, true)).toBe(false);
  });

  it('canReviewRequest rejects a server-authoritative canReview=true on an already-decided request', () => {
    // Mirrors LeaveService.withCanReviewFlag, which computes canReview independent of status by design -- callers
    // must still gate on status themselves. Without this check, an already-decided (non-SUBMITTED) row would
    // render Approve/Reject buttons in ReviewQueueTab.
    expect(canReviewRequest({ status: 'REJECTED', canReview: true }, employee, false)).toBe(false);
    expect(canReviewRequest({ status: 'APPROVED', canReview: true }, employee, false)).toBe(false);
    expect(canReviewRequest({ status: 'CANCELLED', canReview: true }, employee, false)).toBe(false);
  });
});
