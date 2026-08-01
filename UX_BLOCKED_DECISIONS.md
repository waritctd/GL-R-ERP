# UX Blocked Decisions

## Batch 3 Employee — Profile Change Request Reason

- Screen/route: `/profile`, modal `ขอแก้ไข`
- Evidence available: `frontend/GL&R Design Audit/Screen Audits/Employee Screens.md` item 28 (`modal--profile--ขอแก้ไข.png`) and backlog issue E-003 request that the modal distinguish current value, requested value, and reason.
- Missing decision: Whether profile change requests should collect a separate employee-entered reason, and where that reason should be stored/displayed.
- Why implementation would alter or assume business behavior: `api.profileRequests.create` and the mock/backend-shaped request rows persist `fieldKey`, `fieldLabel`, `oldValue`, and `newValue`; adding `reason` would change the request payload/contract or create unsaved UI-only data.
- Safe frontend-only work that can proceed: Clarify the existing current-value and requested-value presentation, preserve the current payload shape, and leave reason capture blocked until the API/product decision exists.
