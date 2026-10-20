---
title: Attendance
layer: HR
schemas: [hr]
tags: [repo-map, domain/attendance]
synced: develop@ef9b17dc
---
# Attendance
> Punch clock, attendance records, corrections, and the work calendar.

**Layer:** HR  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.attendance` — [attendance/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/attendance)
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceCardBackfillRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceCardBackfillResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceController.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDailyResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDatImportRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDatParser.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDeviceCredential.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDeviceDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceDevicesResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceEmployeesResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceImportErrorRecord.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceImportResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMarkPresentRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMarkPresentResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMonthlySummaryExporter.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMonthlySummaryResult.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMonthlySummaryRow.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMonthlySummaryService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendancePunchDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendancePunchFilter.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendancePunchRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendancePunchResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendancePunchesResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceRecalculateRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceRecalculateResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceRepository.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceScope.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceSql.java`
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceUnmappedResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/DatParseResult.java`
- `backend/src/main/java/th/co/glr/hr/attendance/LeaveContribution.java`
- `backend/src/main/java/th/co/glr/hr/attendance/NormalizedAttendancePunch.java`
- `backend/src/main/java/th/co/glr/hr/attendance/RotateAgentTokenResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionController.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionFilter.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionRepository.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionRequestDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionStatus.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/AttendanceCorrectionType.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/ReviewAttendanceCorrectionRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/correction/SubmitAttendanceCorrectionRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyCalculator.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyFilter.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyRecalcJob.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyRecord.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyRepository.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyRow.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDayFlag.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDayStatus.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceEmployeeOption.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceWfhRosterResult.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/EmployeeDay.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/PunchRecord.java`
- `backend/src/main/java/th/co/glr/hr/attendance/daily/UnmappedBadge.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/BotHolidayFetchService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/CompanyWideWorkScheduleResolver.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/DbHolidayCalendar.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayAdminService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayCalendar.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayController.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayCreateRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayRepository.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidayUpdateRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/HolidaysResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/ScheduleAssignment.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/ScopeType.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/TieredWorkScheduleResolver.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkSchedule.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentAdminService.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentController.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentCreateRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentEndRequest.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleAssignmentsResponse.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleController.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleResolver.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkScheduleSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/WorkSchedulesResponse.java`

## Frontend
### `features/attendance` — [attendance/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/attendance)
- `frontend/src/features/attendance/AttendancePage.jsx`
### `features/attendanceCalendar` — [attendanceCalendar/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/attendanceCalendar)
- `frontend/src/features/attendanceCalendar/AssignmentFormModal.jsx`
- `frontend/src/features/attendanceCalendar/AssignmentsTab.jsx`
- `frontend/src/features/attendanceCalendar/AttendanceCalendarPage.jsx`
- `frontend/src/features/attendanceCalendar/HolidayFormModal.jsx`
- `frontend/src/features/attendanceCalendar/HolidaysTab.jsx`
- `frontend/src/features/attendanceCalendar/WorkSchedulesTab.jsx`
- `frontend/src/features/attendanceCalendar/attendanceCalendarTabs.js`
- `frontend/src/features/attendanceCalendar/dateHelpers.js`
- `frontend/src/features/attendanceCalendar/holidayFetch.js`
- `frontend/src/features/attendanceCalendar/scopeTypes.js`
### `features/attendanceCorrection` — [attendanceCorrection/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/attendanceCorrection)
- `frontend/src/features/attendanceCorrection/AttendanceCorrectionPanel.jsx`
- `frontend/src/features/attendanceCorrection/AttendanceCorrectionRequestModal.jsx`

## DB
- Schemas: `hr`

## Related
[[overtime|Overtime]] · [[payroll|Payroll]] · [[employee|Employee & Self-service]]

## Gotchas
- AttendanceScopeIntegrationTest is the reference pattern for real-DB authz scope tests.
- POST /api/attendance/punch is one of the few anonymous-allowed endpoints (SecurityConfig).

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
