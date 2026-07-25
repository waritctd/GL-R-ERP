# Phase 5A Loading, Error And Empty States

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace and ticket-workspace tab content.

This contract is Step 21. The ticket workspace must distinguish loading, error, empty,
permission-limited, not-applicable and completed states. These are operational states, not
styling variants of the same empty box.

## Core Rule

Do not use one empty state for every condition.

Examples:

```text
ยังไม่มีใบขอราคา
```

is different from:

```text
คุณไม่มีสิทธิ์ดูข้อมูลราคา
```

and different from:

```text
ใบขอราคากำลังโหลด
```

The UI must tell users whether they should wait, retry, act, ask another role, ignore the
section for this stage, or treat it as completed reference.

## State Taxonomy

Use these state categories throughout the ticket workspace:

| State | Meaning | Treatment | Must not say |
|---|---|---|---|
| Ticket loading | Initial ticket detail has no usable data yet | Layout-matched workspace skeleton with `aria-busy` | "ไม่พบดีล" |
| Ticket load error | Ticket detail could not load | Full-workspace inline error with retry and back/navigation action | Raw server exception |
| Ticket not found | Ticket id does not exist or is no longer visible as a record | Not-found state with navigation back to list | Generic load error |
| Tab content loading | Active tab query or expanded row query is loading | Tab-local skeleton or compact loading row; header/action bar stay stable | Full page skeleton |
| Tab content error | Active tab query failed | Tab-local inline alert with retry; preserve previously loaded data when available | Empty state |
| Tab content empty | Query succeeded and returned no records | Compact empty row with next permitted action when available | Permission warning |
| Permission-limited content | User can open the workspace/tab but cannot see a specific data class | Compact role-scope notice or omit the tab when the whole tab would 403 | "ไม่มีข้อมูล" |
| Not applicable in current stage | The workflow track is not relevant yet or no longer relevant | Neutral row explaining the stage dependency | Error or permission message |
| Completed content | Track or document is complete | Compact completed row with date/evidence when available | Empty state |

## Ticket-Level States

### Ticket Loading

Use only for the first load when no ticket data is available.

Rules:

- Skeleton geometry should match the final compact workspace: header, tab row, active tab opening.
- `aria-busy="true"` and a Thai loading label are required on the surrounding region.
- Do not show the old stacked panel skeleton if the final workspace is tabbed.
- Do not show a toast for normal initial loading.
- Do not blank previously loaded ticket data during background refresh.

### Ticket Load Error

Use when the ticket detail query fails before usable data exists.

Rules:

- Show a full-workspace `InlineAlert` or error panel with:
  - calm Thai message,
  - retry action,
  - back/navigation action,
  - optional timestamp/freshness text.
- Do not display raw server exception text, stack traces, SQL/Java class names, endpoint paths,
  or untranslated HTTP internals.
- If the app has previously loaded this ticket in the session, keep the stale ticket visible and
  show a non-blocking refresh failure alert instead of replacing it with a full error page.

Recommended copy:

```text
โหลดข้อมูลดีลไม่สำเร็จ
ลองโหลดอีกครั้ง หรือตรวจสอบการเชื่อมต่อ
```

### Ticket Not Found

Use when the record truly is absent.

Rules:

- Keep it distinct from load error.
- Provide a navigation path back to the worklist.
- Do not imply the user lacks permission unless the backend explicitly reports permission denial.
- Do not show a giant blank card.

Recommended copy:

```text
ไม่พบดีลนี้
ดีลอาจถูกลบ ย้ายสิทธิ์การเข้าถึง หรือเลขที่ดีลไม่ถูกต้อง
```

## Tab-Level States

Tab state must never erase the persistent command header, tab row or sticky action bar.

### Tab Content Loading

Use when a tab or expanded record is fetching and no tab-local data is available yet.

Rules:

- Use a compact skeleton/list placeholder inside the tab.
- Label the tab panel region with `aria-busy` when loading.
- Loading an expanded row should show the loading state inside that row only.
- Background refresh with existing data keeps the data visible and may show a small
  `กำลังอัปเดต...` indicator.

Example:

```text
ใบขอราคากำลังโหลด
```

### Tab Content Error

Use when the tab's query fails.

Rules:

- Show a tab-local `InlineAlert` with retry.
- Do not collapse to the tab empty state.
- Do not blank the whole ticket workspace.
- Preserve previously loaded tab data during background refresh failure.
- If a row-detail fetch fails, keep the row visible and show the error inside the expanded area.

Recommended copy:

```text
โหลดใบขอราคาไม่สำเร็จ
ลองใหม่
```

### Tab Content Empty

Use only after a successful load proves there are no records.

Rules:

- Use a compact inline empty row or `CompactEmptySection`, not a roomy card.
- Include the next permitted tab-local action when one exists.
- If the current role cannot create the record, say the record does not exist yet without
  implying permission failure.

Example:

```text
ยังไม่มีใบขอราคา
สร้างใบขอราคาเพื่อส่งให้ฝ่ายนำเข้าเสนอราคา
```

## Permission-Limited Content

Permission-limited is not empty.

Rules:

- If the entire tab's data would 403, omit the tab through role projection.
- If only part of a visible tab is role-limited, show a compact role-scope notice where that
  content would otherwise appear.
- Use Thai-first role language.
- Do not display raw `403`, `Forbidden`, or backend exception text.
- Do not make permission-limited content look completed.
- Do not change backend permissions in this phase.

Recommended copy:

```text
คุณไม่มีสิทธิ์ดูข้อมูลราคา
ดูสถานะรวมได้จากภาพรวม หากต้องการรายละเอียดให้ติดต่อผู้รับผิดชอบราคา
```

## Not Applicable In Current Stage

Not-applicable means the track is not relevant for this deal state yet.

Rules:

- Use neutral text or a quiet inline row.
- Explain the stage dependency when useful.
- Do not show a create action unless the role can actually start that track now.
- Do not treat not-applicable as an error.
- Do not treat not-applicable as completed.

Examples:

```text
ยังไม่ต้องออกใบแจ้งมัดจำในขั้นตอนนี้
```

```text
การส่งมอบจะเริ่มหลังยืนยันคำสั่งซื้อและจัดซื้อสินค้า
```

## Completed Content

Completed content is reference, not empty.

Rules:

- Show the completed state compactly with date, actor or evidence when available.
- Remove routine workflow actions.
- Keep document/download/history access where permitted.
- Do not render a large success card unless the completed state is the current decision result.
- Do not repeat the same completed badge across header, tab heading and row body.

Examples:

```text
ชำระเงินครบแล้ว · 18 ก.ค. 2026
```

```text
ส่งมอบครบแล้ว · ดูประวัติการส่งมอบ
```

## Error Message Policy

User-facing errors must be sanitized.

Allowed:

- Thai summary of what failed.
- Retry action.
- Navigation action.
- Support/reference id if one exists and is safe.
- Quiet detail such as "อัปเดตล่าสุดไม่สำเร็จ" when stale data remains visible.

Not allowed:

- Java class names.
- SQL errors.
- Stack traces.
- Raw endpoint paths.
- Raw `err.message` when it contains backend internals.
- English server messages unless explicitly business-authored.

Recommended mapping:

| Condition | User-facing message |
|---|---|
| Network/offline | เชื่อมต่อระบบไม่ได้ โปรดลองอีกครั้ง |
| 401/expired session | เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่ |
| 403 | คุณไม่มีสิทธิ์ดูข้อมูลนี้ |
| 404 ticket | ไม่พบดีลนี้ |
| 404 row/detail | ไม่พบรายการนี้ |
| 409/422 stale action | สถานะเปลี่ยนไปแล้ว โหลดข้อมูลล่าสุดก่อนดำเนินการต่อ |
| 5xx/unknown | ระบบขัดข้องชั่วคราว โปรดลองอีกครั้ง |

## Background Refresh

Preserve previously loaded data during background refresh.

Rules:

- Use `isLoading` only for first load without data.
- Use `isFetching` with data as a subtle refresh state.
- Keep the header, tab content and action bar visible while refetching.
- If background refresh fails, keep stale data visible and show a compact alert.
- Mutations that invalidate queries must not cause the active workspace to flash into empty,
  not-found or skeleton states.
- `placeholderData` and equivalent previous-data preservation are load-bearing where already
  used; do not remove them casually.

## Tab Failure Boundaries

Each tab owns its own loading/error/empty state.

Rules:

- A Pricing tab failure must not hide Money, Documents or Activity tabs.
- A Documents query failure must not hide generated quotation or invoice document navigation when
  those actions are still available elsewhere.
- An expanded pricing-request detail failure stays inside that row.
- A payment-history failure should not erase the financial summary if the summary is already on
  the ticket.
- Activity history failure should leave the comment/activity composer state explicit rather than
  pretending there are no events.

## Tab Examples

| Tab | Empty | Permission-limited | Not applicable | Completed |
|---|---|---|---|---|
| Pricing | ยังไม่มีใบขอราคา | คุณไม่มีสิทธิ์ดูข้อมูลราคา | ยังไม่ต้องส่งขอราคาในขั้นตอนนี้ | ใบขอราคาเสร็จสิ้นแล้ว |
| Quotations | ยังไม่มีใบเสนอราคา | คุณไม่มีสิทธิ์ดูใบเสนอราคา | รออนุมัติราคาก่อนออกใบเสนอราคา | ลูกค้ายอมรับใบเสนอราคาแล้ว |
| Money | ยังไม่มีรายการรับชำระ | คุณไม่มีสิทธิ์ดูข้อมูลการเงิน | ยังไม่ถึงขั้นตอนวางบิล | ชำระเงินครบแล้ว |
| Fulfilment | ยังไม่มีรายการส่งมอบ | คุณไม่มีสิทธิ์ดูข้อมูลจัดซื้อ | รอยืนยันคำสั่งซื้อก่อนจัดซื้อ/ส่งมอบ | ส่งมอบครบแล้ว |
| Documents | ยังไม่มีเอกสารแนบ | คุณไม่มีสิทธิ์ดูเอกสารนี้ | ยังไม่มีเอกสารที่ต้องแนบในขั้นตอนนี้ | เอกสารครบแล้ว |
| Activity | ยังไม่มีกิจกรรม | คุณไม่มีสิทธิ์ดูประวัติส่วนนี้ | ยังไม่มีกิจกรรมสำหรับขั้นตอนนี้ | แสดงประวัติที่เสร็จแล้ว |

These are examples, not a license to invent permission or stage rules. Use existing backend and
role projection.

## Acceptance Checks

Implementation is not complete until:

- Ticket loading, load error and not-found states are distinct.
- Tab loading, tab error and tab empty states are distinct.
- Permission-limited content never renders as ordinary empty content.
- Not-applicable content never renders as error or completed content.
- Completed content never renders as empty content.
- Tab-local failures do not blank the whole workspace.
- Previously loaded data remains visible during background refresh.
- Query failure messages are sanitized and Thai-first.
- Retry actions are available for recoverable ticket and tab failures.
- Empty states are compact and action-aware.
- Raw server exceptions are never displayed.
