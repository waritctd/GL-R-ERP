# GL&R ERP — API Documentation

| | |
|---|---|
| **Document** | 06 — API Documentation |
| **Version** | 1.0 · 2 July 2026 |
| **Base URL** | `/api` (browser calls are same-origin via the Vercel proxy → `https://gl-r-erp.onrender.com/api`) |
| **Auth** | Session cookie (`SESSION`, HttpOnly/Secure) + CSRF double-submit header on mutations |
| **Content type** | `application/json` unless noted |

---

## Table of Contents

1. [Conventions](#1-conventions)
2. [Authentication](#2-authentication--apiauth)
3. [Employees](#3-employees--apiemployees)
4. [Profile Requests](#4-profile-requests--apiprofile-requests)
5. [Attendance](#5-attendance--apiattendance)
6. [Overtime](#6-overtime--apiovertime)
7. [Leave](#7-leave--apileave)
8. [Sales Tickets](#8-sales-tickets--apitickets)
9. [Customers](#9-customers--apicustomers)
10. [Documents](#10-documents--api)
11. [Commissions](#11-commissions--apicommissions)
12. [Payroll](#12-payroll--apipayroll)
13. [Dashboard & Notifications](#13-dashboard--notifications)

---

## 1. Conventions

- **Roles** referenced below map to `@PreAuthorize` guards in the controllers. Where no role is listed, any authenticated user may call it (service-layer scoping still applies — e.g., employees only see their own records).
- **Errors** use a consistent JSON envelope from `ApiExceptionHandler`. Unknown routes return **404** (PR #65); auth failures **401/403**; validation **400**.
- **Auth-logged** endpoints write to `hr.audit_log`.
- IDs are `BIGINT`. Money is decimal. Timestamps are ISO-8601 with offset.

## 2. Authentication — `/api/auth`

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/api/auth/login` | public | Log in with `{email, password}`; sets session + CSRF cookies. Enforces lockout on repeated failure. |
| POST | `/api/auth/change-password` | authenticated | `{currentPassword, newPassword}`; clears the forced-change gate. |
| POST | `/api/auth/logout` | authenticated | Invalidates the session. |
| GET | `/api/auth/me` | authenticated | Returns the current `UserPrincipal` (id, email, name, role, employeeId, divisionId, manager, mustChangePassword). |

## 3. Employees — `/api/employees`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/employees` | HR/ADMIN (full list); others scoped | List/search employees; filters (division, department, status); opt-in pagination. |
| POST | `/api/employees` | HR/ADMIN | Create a full employee record. |
| GET | `/api/employees/{id}` | HR/ADMIN or self | Fetch one employee; sensitive fields audit-logged. |
| PATCH | `/api/employees/{id}` | HR/ADMIN | Partial update; assignment changes keep dated history. |

## 4. Profile Requests — `/api/profile-requests`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/profile-requests` | HR (all) / employee (own) | List change requests. |
| POST | `/api/profile-requests` | authenticated | Submit a correction to own profile. |
| PATCH | `/api/profile-requests/{id}` | HR | Approve (applies change) or reject with note. |

## 5. Attendance — `/api/attendance`

| Method | Path | Role / Auth | Description |
|---|---|---|---|
| POST | `/api/attendance/punch` | **Device agent token** | Ingest a normalized punch from the SC700 agent. Token verified against the device's stored SHA-256 hash. |
| POST | `/api/attendance/devices/{deviceCode}/agent-token` | HR/ADMIN | Mint/rotate a device agent token; returns the plaintext once. |
| POST | `/api/attendance/imports/dat` | HR/ADMIN | Upload a `.dat` transaction file for historical backfill (size-capped). |
| GET | `/api/attendance/punches` | scoped | Punch history: employees see own, managers see division, HR all. Filters by employee/date. |

## 6. Overtime — `/api/overtime`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/overtime` | scoped | List OT requests (own / division / all). |
| POST | `/api/overtime` | authenticated | Create an OT request (date, planned times, day type, reason). |
| GET | `/api/overtime/employees` | manager/HR | Employees whose OT the caller may approve. |
| POST | `/api/overtime/{id}/approve` | manager/HR | Approve; payable OT flows to payroll. |
| POST | `/api/overtime/{id}/reject` | manager/HR | Reject with reason. |
| POST | `/api/overtime/{id}/cancel` | owner (pending) | Cancel own pending request. |

## 7. Leave — `/api/leave`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/leave` | scoped | List leave requests. |
| POST | `/api/leave` | authenticated | Submit leave; quota checked automatically. |
| GET | `/api/leave/employees` | manager/HR | Employees in approval scope. |
| GET | `/api/leave/types` | authenticated | Leave types + quotas. |
| GET | `/api/leave/balances` | scoped | Remaining balances. |
| POST | `/api/leave/{id}/approve` | manager/HR | Approve. |
| POST | `/api/leave/{id}/reject` | manager/HR | Reject. |
| POST | `/api/leave/{id}/cancel` | owner (pending) | Cancel own pending request. |

## 8. Sales Tickets — `/api/tickets`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/tickets` | sales+ | List/search tickets. |
| POST | `/api/tickets` | sales+ | Create a draft ticket. |
| GET | `/api/tickets/{id}` | sales+ | Ticket detail incl. events. |
| POST | `/api/tickets/{id}/submit` | owner | Submit into queue. |
| POST | `/api/tickets/{id}/pickup` | import/manager | Take ownership for pricing. |
| POST | `/api/tickets/{id}/propose-price` | import/sales | Propose a price. |
| POST | `/api/tickets/{id}/approve` | SALES_MANAGER/CEO/ADMIN | Approve proposed price. |
| POST | `/api/tickets/{id}/reject` | SALES_MANAGER/CEO/ADMIN | Send back for re-pricing. |
| POST | `/api/tickets/{id}/quotation` | sales+ | Issue a quotation. |
| POST | `/api/tickets/{id}/close` | sales+ | Close the deal. |
| POST | `/api/tickets/{id}/cancel` | sales+ | Cancel the ticket. |
| PATCH | `/api/tickets/{id}/items` | sales+ | Edit line items (flags `has_edits`). |
| POST | `/api/tickets/{id}/comments` | sales+ | Add a comment (recorded as an event). |

## 9. Customers — `/api/customers`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/customers` | sales+ | Searchable customer directory. |

## 10. Deposit Notices — `/api/...`

Tables and paths were renamed in V29 (`document` → `deposit_notice`). The note-templates path stays generic.

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/document-note-templates` | sales+ | List reusable note templates (path kept generic). |
| POST | `/api/tickets/{ticketId}/deposit-notice/draft` | sales+ | Create a deposit-notice draft from a ticket. |
| GET | `/api/tickets/{ticketId}/deposit-notices` | sales+ | List a ticket's deposit notices. |
| GET | `/api/deposit-notices/{docId}` | sales+ | Fetch a deposit notice. |
| PUT | `/api/deposit-notices/{docId}` | sales+ | Edit a draft deposit notice. |
| POST | `/api/deposit-notices/{docId}/preview` | sales+ | Render an HTML preview. |
| POST | `/api/deposit-notices/{docId}/issue` | sales+ | Issue with a running number. |
| GET | `/api/deposit-notices/{docId}/file?format=xlsx` | sales+ | Download the generated **XLSX** file. `format=pdf` is a placeholder stub (roadmap). |
| POST | `/api/tickets/{ticketId}/revision` | sales+ | Open a revision (bumps `revision_no`). |

## 11. Commissions — `/api/commissions`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/commissions` | scoped | List commission records. |
| POST | `/api/commissions` | SALES/SALES_MANAGER/CEO/ADMIN | Submit a commission from invoice details. |
| PATCH | `/api/commissions/{id}/deductions` | SALES_MANAGER/CEO/ADMIN | Adjust deductions pre-approval. |
| POST | `/api/commissions/{id}/approve` | SALES_MANAGER/CEO/ADMIN | Approve; feeds payroll. |
| POST | `/api/commissions/{id}/clawback` | SALES_MANAGER/CEO/ADMIN | Record a negative clawback. |
| POST | `/api/commissions/simulator` | SALES/SALES_MANAGER/CEO/ADMIN | Preview commission without saving. |
| GET | `/api/commissions/payroll-ready` | HR/payroll | Approved amounts aggregated per employee/month. |

## 12. Payroll — `/api/payroll`

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/payroll` | HR/ADMIN | List processed periods. |
| POST | `/api/payroll/preview` | HR/ADMIN | Side-effect-free calculation for a month + inputs. |
| POST | `/api/payroll/process` | HR/ADMIN | Persist the period (audit-logged `PROCESS_PAYROLL`). |
| GET | `/api/payroll/{periodId}/bank-export` | HR/ADMIN | Download `glr-payroll-<id>.txt` (audit-logged `EXPORT_PAYROLL_BANK_FILE`). |

## 13. Dashboard & Notifications

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/api/dashboard/summary` | authenticated | Role-aware summary aggregates. |
| GET | `/api/notifications` | authenticated | In-app notification feed. |
| PATCH | `/api/notifications/{id}/read` | owner | Mark a notification read. |

*End of document.*
