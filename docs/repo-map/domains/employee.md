---
title: Employee & Self-service
layer: HR
schemas: [hr, hr_restricted]
tags: [repo-map, domain/employee]
synced: develop@ef9b17dc
---
# Employee & Self-service
> Employee master, profile, and self-service edit/requests (incl. tax allowance).

**Layer:** HR  ·  **DB schemas:** `hr, hr_restricted`

## Backend
### `th.co.glr.hr.employee` — [employee/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/employee)
- `backend/src/main/java/th/co/glr/hr/employee/AddressDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/AssignmentDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmergencyContactDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeCodeGenerator.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeController.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeFilter.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeReferenceRepository.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeRepository.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeResponses.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeService.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeStatus.java`
- `backend/src/main/java/th/co/glr/hr/employee/ManagerApproverRepository.java`
- `backend/src/main/java/th/co/glr/hr/employee/PasswordResetResult.java`
- `backend/src/main/java/th/co/glr/hr/employee/SalaryHistoryDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/SensitiveDto.java`
- `backend/src/main/java/th/co/glr/hr/employee/UpsertEmployeeRequest.java`
### `th.co.glr.hr.profile` — [profile/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/profile)
- `backend/src/main/java/th/co/glr/hr/profile/CreateProfileRequestRequest.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestController.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestDto.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestRecord.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestRepository.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestResponses.java`
- `backend/src/main/java/th/co/glr/hr/profile/ProfileRequestService.java`
- `backend/src/main/java/th/co/glr/hr/profile/UpdateProfileRequestRequest.java`

## Frontend
### `features/employees` — [employees/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/employees)
- `frontend/src/features/employees/EmployeeDetailPage.jsx`
- `frontend/src/features/employees/EmployeeFormModal.jsx`
- `frontend/src/features/employees/EmployeeListPage.jsx`
- `frontend/src/features/employees/ResetPasswordDialog.jsx`
### `features/profile` — [profile/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/profile)
- `frontend/src/features/profile/ChangeRequestModal.jsx`
- `frontend/src/features/profile/ProfilePage.jsx`
- `frontend/src/features/profile/SignatureCard.jsx`
### `features/profileRequests` — [profileRequests/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/profileRequests)
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx`
### `features/requests` — [requests/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/requests)
- `frontend/src/features/requests/RequestsPage.jsx`
### `features/taxAllowance` — [taxAllowance/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/taxAllowance)
- `frontend/src/features/taxAllowance/TaxAllowanceActionRow.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceBreakdown.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceDrilldown.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceEvidenceCount.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceEvidencePanel.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceForm.jsx`
- `frontend/src/features/taxAllowance/TaxAllowancePage.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceReviewPage.jsx`
- `frontend/src/features/taxAllowance/TaxAllowanceSummaryPanel.jsx`
- `frontend/src/features/taxAllowance/taxAllowanceCaps.js`
- `frontend/src/features/taxAllowance/taxAllowanceSchema.js`
- `frontend/src/features/taxAllowance/taxAllowanceStatus.js`

## DB
- Schemas: `hr, hr_restricted`
- Key migrations: `V139 missing-role personas + hire dates (demo)`

## Related
[[attendance|Attendance]] · [[leave|Leave]] · [[overtime|Overtime]] · [[payroll|Payroll]] · [[auth-session|Auth & Session]]

## Gotchas
- DivisionAccessPolicy.roleFor derives a user's role from their division — role is NOT stored directly.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
