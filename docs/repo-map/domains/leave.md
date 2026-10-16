---
title: Leave
layer: HR
schemas: [hr]
tags: [repo-map, domain/leave]
synced: develop@ef9b17dc
---
# Leave
> Leave requests, balances, policy rules, and approval.

**Layer:** HR  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.leave` — [leave/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/leave)
- `backend/src/main/java/th/co/glr/hr/leave/ApprovedLeaveSpanDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/EmployeeLeaveSpan.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveAttachmentDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveAttachmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveBalanceDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveCalendarContextDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveCalendarContextService.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveCalendarHolidayDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveCalendarWorkScheduleDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveContactDefaultsDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveController.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveDayCountBasis.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveDayMath.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveEmployeeAccess.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveEmployeeOption.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveFilter.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveFormData.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveFormHtmlRenderer.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveFormPdf.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveFormRenderer.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePolicyDocumentDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePolicyDocumentRepository.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePreviewCounters.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePreviewDepth.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePreviewDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeavePreviewRequest.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveQuotaPoolPreference.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveQuotaYearSplit.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveReportEmployeeDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveReportRenderer.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRepository.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRequestDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRequestSpan.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveResponses.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveReviewSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRuleCode.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRuleEnforcement.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRuleMessages.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRuleOutcome.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRuleWarningDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveService.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveStatus.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveSubmissionMailer.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveTeamMemberBalanceDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveTypeDto.java`
- `backend/src/main/java/th/co/glr/hr/leave/ReviewLeaveRequest.java`
- `backend/src/main/java/th/co/glr/hr/leave/SubmitLeaveRequest.java`

## Frontend
### `features/leave` — [leave/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/leave)
- `frontend/src/features/leave/LeaveFilterBar.jsx`
- `frontend/src/features/leave/LeavePolicyBar.jsx`
- `frontend/src/features/leave/LeavePolicyDocumentPage.jsx`
- `frontend/src/features/leave/LeaveReportDownload.jsx`
- `frontend/src/features/leave/LeaveRequestPage.jsx`
- `frontend/src/features/leave/LeaveRulePanel.jsx`
- `frontend/src/features/leave/LeaveSurfacePage.jsx`
- `frontend/src/features/leave/MyLeaveTab.jsx`
- `frontend/src/features/leave/ReviewQueueTab.jsx`
- `frontend/src/features/leave/TeamLeaveTab.jsx`
- `frontend/src/features/leave/TeamQuotaSummary.jsx`
- `frontend/src/features/leave/UpcomingLeaveList.jsx`
- `frontend/src/features/leave/leaveFormatting.js`
- `frontend/src/features/leave/leaveRequestTable.jsx`
- `frontend/src/features/leave/leaveSurfaceTabs.js`

## DB
- Schemas: `hr`

## Related
[[employee|Employee & Self-service]] · [[payroll|Payroll]] · [[attendance|Attendance]]

## Gotchas
- Leave/quota math is business-logic-sensitive — re-check against the spec before PR (CLAUDE.md).

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
