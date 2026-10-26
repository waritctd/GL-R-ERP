---
title: Dashboard
layer: Platform
schemas: [hr, sales]
tags: [repo-map, domain/dashboard]
synced: develop@fe5138dd
---
# Dashboard
> Cross-domain rollups and the landing dashboards.

**Layer:** Platform  ·  **DB schemas:** `hr, sales`

## Backend
### `th.co.glr.hr.dashboard` — [dashboard/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/dashboard)
- `backend/src/main/java/th/co/glr/hr/dashboard/AttendanceSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardController.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardPendingVisibility.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardQueryScope.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardRepository.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardScopeType.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardService.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DivisionHeadcountDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/HeadcountSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/NotificationSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/PendingApprovalsSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/TicketSummaryDto.java`

## Frontend
### `features/dashboard` — [dashboard/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/dashboard)
- `frontend/src/features/dashboard/AccountOverview.jsx`
- `frontend/src/features/dashboard/ActionQueue.jsx`
- `frontend/src/features/dashboard/CeoOverview.jsx`
- `frontend/src/features/dashboard/DivisionManagerOverview.jsx`
- `frontend/src/features/dashboard/EmployeeDashboard.jsx`
- `frontend/src/features/dashboard/EmployeeSelfService.jsx`
- `frontend/src/features/dashboard/HrDashboard.jsx`
- `frontend/src/features/dashboard/HrOverview.jsx`
- `frontend/src/features/dashboard/ImportOverview.jsx`
- `frontend/src/features/dashboard/ManagerOverview.jsx`
- `frontend/src/features/dashboard/SalesOverview.jsx`

## DB
- Schemas: `hr, sales`

## Related
[[deposit-billing-finance|Deposit · Billing · Finance]] · [[sales-deal-ticket|Sales Deal & Ticket]]

## Gotchas
_(none recorded)_
