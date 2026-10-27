---
title: Overtime
layer: HR
schemas: [hr]
tags: [repo-map, domain/overtime]
synced: develop@fe5138dd
---
# Overtime
> Overtime filing and the two-step (manager → CEO) approval chain.

**Layer:** HR  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.overtime` — [overtime/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/overtime)
- `backend/src/main/java/th/co/glr/hr/overtime/ApproveOvertimeRequest.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeAttendanceBounds.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeCalculation.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeController.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeDayType.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeEmployeeAccess.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeEmployeeOption.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeFilter.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeRepository.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeRequestDto.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeResponses.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeService.java`
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeStatus.java`
- `backend/src/main/java/th/co/glr/hr/overtime/ReviewOvertimeRequest.java`
- `backend/src/main/java/th/co/glr/hr/overtime/SubmitOvertimeRequest.java`

## Frontend
### `features/overtime` — [overtime/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/overtime)
- `frontend/src/features/overtime/OvertimePage.jsx`
- `frontend/src/features/overtime/OvertimePanel.jsx`

## DB
- Schemas: `hr`

## Related
[[attendance|Attendance]] · [[payroll|Payroll]] · [[employee|Employee & Self-service]]

## Gotchas
- Issue #199: mock let HR approve OT but real OvertimeService returns 403 — verify authz on the real service.
- day_type / pay multiplier derive from hr.holiday, never from the client's claim.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
