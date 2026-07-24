# Current UI Inventory

Every route family reachable in the current application, with the **materially
distinct** screens/states inside it (not just URLs). Grounded in `App.jsx` +
live capture (`../evidence/current/`). Shell chrome (sidebar, topbar, drawer) is
constant and inventoried once at the end.

Legend for materially-distinct surfaces: **T** tab · **M** modal · **D** drawer ·
**W** wizard step · **E** edit state · **∅** empty · **⌛** loading · **✗** error ·
**RO** read-only · **⛔** permission state · **📎** upload · **↔** long-content.

## Route families

### 1. Landing / role dashboards — `/`
Nine role-branched landings (one per role, `App.jsx` `/` branch): CeoOverview,
HrOverview, SalesOverview, ManagerOverview, ImportOverview, AccountOverview,
DivisionManagerOverview, EmployeeSelfService, EmployeeDashboard (fallback).
- States seen: **populated** (account/import worklists), **∅** (CEO all-zero cards
  + "ไม่มีรายการที่รอการตัดสินใจ"). Each is a distinct screen — captured per role.

### 2. Deal pipeline — `/tickets`, `/ticket-overview`, `/tickets/:id`, `/tickets/:ticketId/deposit`
- `/tickets` list: **T** ดีลทั้งหมด / ภาพรวม (SalesTabs); phase-chip filter (เฟส 1–5
  counts); **collapsible** "ตัวกรองเพิ่มเติม (Lifecycle · Flags)" (drawer-like);
  search; `DataTable` (desktop) that **reflows to record cards** (mobile).
- `/tickets/:id` detail: full-width back bar; 5 summary cards; pipeline strip (เฟส
  1–5) + "ดูขั้นตอนทั้งหมด (14 ขั้น)"; hold/continue **action panel**; activity/tracking.
- **M** create-deal wizard (6 sections: ลูกค้า/โครงการ/ผู้ติดต่อ/รายการสินค้า/รายละเอียด/ตรวจสอบ,
  "0/6 เสร็จ", disabled-submit until required sections done).
- `/tickets/:ticketId/deposit` DepositNoticePage (document generation).
- States: populated, ∅, ✗ ("not linked to employee" toast), on-hold/waiting.

### 3. Pricing requests — `/pricing-requests`, `/pricing-requests/:id`
- Queue list (Import/CEO/sales_manager). Detail = the multi-step PCR aggregate
  (factory quotes, costing, CEO pricing decision, quotation). *Detail not
  visually captured — queue empty for the ceo seed (gap, see AUDIT_GAPS).*

### 4. Procurement / factory POs — `/procurement`, `/factory-purchase-orders(/:id)`
- ProcurementFulfilmentPage (fulfilment worklist + embedded raw PO list). Detail
  = ProcurementDetailPage. *Rows here are not `DataTable` row-buttons → detail not
  auto-captured (gap).*

### 5. Commissions — `/commissions`
- Metric cards + month picker + "เพิ่มค่าคอมด้วยตนเอง" (manual) + `DataTable`
  (INVOICE/SALES/ยอด/ฐานค่าคอม/สถานะ). State seen: **∅** ("ยังไม่มีรายการค่าคอม").

### 6. Finance worklist — `/finance` (Account/CEO)
- AccountFinancePage — money lifecycle (deposit → final → close → invoice/commission).

### 7. Payroll — `/payroll` (HR)
- Metric cards (r-/-/-/฿0), month picker, **Process Payroll** + Preview + bank
  export dropdown + วันที่จ่าย + Email payslips; `DataTable` (per-employee) + right
  detail rail. States: **∅** ("ยังไม่มีข้อมูลเงินเดือน", 0 employees). **Desktop-only**
  (`DesktopOnlyNotice` on mobile).

### 8. Catalog / price import — `/catalog`, `/price-import`
- CatalogSearchPage (search + results). PriceImportPage (**📎** upload → validate →
  stage → commit; factory/version pickers). *Upload states not exercised (gap).*

### 9. Employees — `/employees`, `/employees/:id`
- List: `DataTable` + filters + create **M** (EmployeeFormModal). Detail: header +
  **T** ข้อมูลส่วนตัว / การจ้างงาน / ประวัติ / **ข้อมูลอ่อนไหว (HR-only sensitive)**; **E** แก้ไข.

### 10. Profile & profile-requests — `/profile`, `/requests`, `/my-requests`(→profile)
- ProfilePage (self: info + own request table + create request **M**). `/requests`
  = HR review queue (badge count). 

### 11. Attendance — `/attendance` (ungated)
- Day view + per-day drill-down; role-framed ("เวลาทำงาน" self vs "ทีมในฝ่าย" mgr).

### 12. Employee requests (OT + welfare) — `/employee-requests`, `/overtime`(→tab)
- RequestsPage with **T** OT / welfare (special-money), `?tab=` carried in URL.

### 13. Leave — `/leave`
- LeavePage (submit + approve; over-quota → unpaid path).

### 14. CEO settings — `/ceo-settings` (CEO)
- CeoSettingsPage (price/FX config).

## Shell chrome (constant)
- **Sidebar**: brand, ungrouped `/`, 5 collapsible groups, account row + logout.
  260px fixed ≥721px; becomes focus-trapped **drawer** (Escape/backdrop close) ≤720px.
- **Topbar**: hamburger (mobile), title + `roleLabel`, notification bell (badge),
  user text, UserMenu (profile/logout).
- **Global**: Toast, ErrorBoundary (keyed per route), RouteFallback (Suspense),
  loading veil.

## Distinct-state coverage (this audit)
Captured: populated, ∅ (ceo landing, commissions, payroll), ✗ (sales toast),
⛔/redirect (denied-probe), M (create-deal, employee create), T (deal tabs,
employee tabs), D (mobile drawer, more-filters), record-card reflow (tickets
mobile), pipeline/waiting states, ↔ long Thai names (deal/company names).
**Not** exercised (→ AUDIT_GAPS): ⌛ loading, 📎 upload progress, validation-error
messages (create uses disabled-submit), destructive-confirm dialogs, large-dataset
tables, 200% zoom, mobile keyboard/focus.
