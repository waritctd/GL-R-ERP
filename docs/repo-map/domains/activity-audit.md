---
title: Activity & Audit
layer: Platform
schemas: [hr]
tags: [repo-map, domain/activity-audit]
synced: develop@fe5138dd
---
# Activity & Audit
> The activity log (request filter + recorder) and the audit trail.

**Layer:** Platform  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.activity` — [activity/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/activity)
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogController.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogEntry.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogEntryDto.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogFilter.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogRecorder.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogRepository.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogService.java`
- `backend/src/main/java/th/co/glr/hr/activity/ActivityLogSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/activity/AppEvent.java`
- `backend/src/main/java/th/co/glr/hr/activity/AppEventBuffer.java`
- `backend/src/main/java/th/co/glr/hr/activity/AppEventDto.java`
- `backend/src/main/java/th/co/glr/hr/activity/AppEventWriter.java`
- `backend/src/main/java/th/co/glr/hr/activity/AuditEventDto.java`
- `backend/src/main/java/th/co/glr/hr/activity/DatabaseLogAppender.java`
- `backend/src/main/java/th/co/glr/hr/activity/ScheduledJobEventConfig.java`
### `th.co.glr.hr.audit` — [audit/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/audit)
- `backend/src/main/java/th/co/glr/hr/audit/AuditLogRepository.java`
- `backend/src/main/java/th/co/glr/hr/audit/AuditService.java`

## Frontend
### `features/activityLog` — [activityLog/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/activityLog)
- `frontend/src/features/activityLog/ActivityLogPage.jsx`
- `frontend/src/features/activityLog/actionLabels.js`

## DB
- Schemas: `hr`

## Related
[[auth-session|Auth & Session]]

## Gotchas
- ActivityLogFilter wraps every request; it needs the JDBC template, so a DB/Flyway failure aborts the whole app at boot.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
