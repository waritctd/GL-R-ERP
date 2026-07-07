# แผนสร้างฟีเจอร์ Ticket + Dashboard (GL-R-ERP / Sales Module)

> เอกสารนี้ใช้เป็น context ให้ **Claude Code** ทำงานต่อใน VSCode
> วางไว้ใน repo (เช่น `docs/ticket-dashboard-plan.md`) แล้วสั่ง Claude Code ให้ "อ่านไฟล์นี้ก่อนเริ่ม"
>
> **Stack จริง:** React (frontend) + Java Spring Boot (backend) + PostgreSQL

---

## 0. ขอบเขตฟีเจอร์นี้

ระบบ "การ์ดติดตาม/ticket" สำหรับ **คำขอราคา (price request)** ของทีม Sales:

- Sales เปิด ticket คำขอราคา (อ้างอิงสินค้าจากฐานราคาที่ดึงจาก catalogue)
- ส่งต่อให้ฝ่าย **Import** หาราคา → **CEO อนุมัติราคา**
- **เมื่อ CEO อนุมัติ → ปลดล็อกให้ Sales กด "Generate ใบเสนอราคา" ได้**
- แจ้งเตือน (in-app + email) ทุกครั้งที่สถานะเปลี่ยน
- **Dashboard** สรุปภาพรวม ticket ตามสถานะ/ผู้รับผิดชอบ/งานค้าง

> โมดูลดึง PDF→ราคา (`catalog_extract.py`) เป็นคนละส่วน — รันเป็น worker/CLI เขียนลง DB เดียวกัน ฟีเจอร์นี้แค่ "อ้างอิง" ตารางราคานั้น

---

## 1. Stack & สมมติฐาน (⚠️ ให้ M0 ยืนยันกับ repo จริง)

**Backend — Java Spring Boot**

- Spring Web (REST controllers), Spring Data JPA, **Spring Security + JWT**, Bean Validation
- PostgreSQL + migration ด้วย **Flyway** (หรือ Liquibase ถ้า repo ใช้อยู่แล้ว)
- Email: `JavaMailSender` + SMTP บริษัท

**Frontend — React**

- React Router (เส้นทาง), **TanStack Query** (เรียก API + cache), form lib ตามสะดวก
- ✅ ยืนยันกับ repo: TypeScript หรือ JS? build tool (Vite/CRA)? UI lib (Tailwind/MUI/อื่น)? มี auth อยู่แล้วไหม?

**Run:** Maven/Gradle + Docker Compose (postgres + backend + frontend)

> ของเดิมใน repo อะไรใช้ซ้ำได้ให้ใช้ก่อน อย่าสร้างซ้ำ — M0 จะเช็คให้

---

## 2. Data Model (ticket เป็นแกนกลาง — JPA @Entity)

**users** — `id`, `name`, `email`, `passwordHash`, `role` (`SALES`|`IMPORT`|`CEO`|`ADMIN`), `active`, `createdAt`

**tickets** (แกนหลัก)

- `id`, `code` (เช่น PR-2026-0001), `type` (`PRICE_REQUEST`)
- `title`, `status` (ดูข้อ 3), `priority` (`LOW`|`NORMAL`|`HIGH`)
- `createdBy` (FK users=Sales), `assignedTo` (FK users, nullable)
- `customerName`, `note`, `createdAt`, `updatedAt`, `closedAt`

**ticket_items** (1 ticket หลายรายการสินค้า)

- `id`, `ticketId` (FK), `productCode`, `productName`, `size`, `color`, `qty`, `unit`
- `proposedPrice` (Import กรอก), `approvedPrice` (หลัง CEO อนุมัติ), `currency`

**ticket_events** (timeline/audit — ทุก action เก็บที่นี่)

- `id`, `ticketId` (FK), `actorId` (FK users)
- `kind` (`CREATED`|`SUBMITTED`|`PICKED_UP`|`PRICE_PROPOSED`|`APPROVED`|`REJECTED`|`QUOTATION_ISSUED`|`COMMENTED`|`CLOSED`|`CANCELLED`)
- `fromStatus`, `toStatus` (nullable), `message`, `createdAt`

**quotations** (ใบเสนอราคาที่ออกหลัง CEO อนุมัติ — เชื่อมฟีเจอร์ออกเอกสาร)

- `id`, `ticketId` (FK), `number`, `issuedBy` (FK users=Sales), `issuedAt`, `pdfPath` (nullable ตอนแรก), `totalAmount`, `currency`

**notifications** — `id`, `userId`, `ticketId`, `type`, `message`, `read`, `createdAt`

---

## 3. State Machine (ปรับตาม workflow จริง)

```
draft ─submit─▶ submitted ─pickup─▶ in_review ─propose price─▶ price_proposed ─CEO approve─▶ approved
                                         ▲                            │                         │
                                         └──────── CEO reject ────────┘                         │
                                                                                    Sales: Generate ใบเสนอราคา
                                                                                                │
                                                                                                ▼
                                                                                        quotation_issued
                                                                                                │
                                                                          [⏸ TBD: ลูกค้าจ่าย/ส่งมอบ → done]
                                                                                                ▼
                                                                                             closed
  (cancel ได้ทุกจุดก่อน closed)
```

- `draft` — Sales ร่าง
- `submitted` — ส่งให้ Import/CEO แล้ว (**notify Import + CEO**)
- `in_review` — Import รับเรื่อง กำลังหาราคา
- `price_proposed` — Import กรอกราคาเสนอแล้ว รอ CEO (**notify CEO**)
- `approved` — **CEO อนุมัติราคา** → ปลดล็อกปุ่ม Generate ใบเสนอราคาให้ Sales (**notify Sales**)
- `rejected` — CEO ตีกลับ → กลับไป `in_review` ให้ Import แก้ราคา
- `quotation_issued` — Sales กด generate ใบเสนอราคาแล้ว
- `closed` — งานเสร็จ (ลูกค้าจ่าย/ส่งมอบ) — **⏸ ส่วนนี้ workflow ยังไม่สรุป ดูข้อ 3.1**
- `cancelled` — ยกเลิก
- ทุก transition → เขียน `ticket_events` + ยิง notification

### 3.1 ส่วนปิดงานที่ยัง hold ไว้ (ออกแบบให้ยืดหยุ่น ไม่ล็อกตาย)

workflow หลัง `quotation_issued` (จ่ายเงิน/ส่งมอบ → ปิด) ยังไม่ชัวร์ จึง **อย่าเพิ่งฮาร์ดโค้ดเงื่อนไข**

- ตอนนี้: ทำปุ่ม **"Mark as done / ปิดงาน"** แบบ manual (Sales เจ้าของ หรือ Admin กดเอง) → `quotation_issued → closed`
- รองรับอนาคต: เผื่อ field `paymentStatus`, `deliveryStatus` ใน `tickets` ไว้ (ตอนนี้ปล่อย null/UNSET) เพื่อพออนาคตจะ auto-close ตาม "จ่ายแล้ว" หรือ "ส่งมอบแล้ว" ค่อยเติม logic โดยไม่ต้องรื้อ
- เก็บ transition `*→closed` ไว้ในฟังก์ชันกลางที่เดียว จะได้เปลี่ยนทริกเกอร์ทีหลังได้ง่าย

---

## 4. บทบาท & สิทธิ์ (RBAC — บังคับที่ Spring Security เสมอ)

| การกระทำ                                         | SALES          | IMPORT | CEO | ADMIN |
| ------------------------------------------------ | -------------- | ------ | --- | ----- |
| สร้าง/แก้ ticket (ของตัวเอง)                     | ✅             | –      | –   | ✅    |
| ดู ticket                                        | เฉพาะของตัวเอง | ✅     | ✅  | ✅    |
| รับเรื่อง + กรอกราคาเสนอ                         | –              | ✅     | –   | ✅    |
| **อนุมัติ/ตีกลับราคา**                           | –              | –      | ✅  | ✅    |
| **Generate ใบเสนอราคา** (ต้อง status=`approved`) | ✅ (ของตัวเอง) | –      | –   | ✅    |
| ปิดงาน (manual ชั่วคราว)                         | ✅ (ของตัวเอง) | –      | –   | ✅    |
| จัดการผู้ใช้/role                                | –              | –      | –   | ✅    |

---

## 5. API (REST — Spring Boot controllers)

```
POST   /api/tickets                      สร้าง (draft)
GET    /api/tickets?status=&mine=        list + filter (กรองตาม role อัตโนมัติ)
GET    /api/tickets/{id}                 รายละเอียด + items + events
PATCH  /api/tickets/{id}                 แก้ field
POST   /api/tickets/{id}/submit          draft → submitted
POST   /api/tickets/{id}/pickup          submitted → in_review (IMPORT)
POST   /api/tickets/{id}/propose-price   in_review → price_proposed (IMPORT, ส่ง items+ราคา)
POST   /api/tickets/{id}/approve         price_proposed → approved (CEO)
POST   /api/tickets/{id}/reject          price_proposed → in_review (CEO, +เหตุผล)
POST   /api/tickets/{id}/quotation       approved → quotation_issued (SALES, สร้าง quotation)
POST   /api/tickets/{id}/close           quotation_issued → closed (manual ชั่วคราว)
POST   /api/tickets/{id}/comments        คอมเมนต์ (event=COMMENTED)
GET    /api/dashboard/summary            ตัวเลขสรุป
GET    /api/notifications                ของผู้ใช้ปัจจุบัน
PATCH  /api/notifications/{id}/read
```

- ทุก endpoint ตรวจ auth + role (`@PreAuthorize`)
- การเปลี่ยนสถานะรวมศูนย์ใน `TicketService.transition(ticket, action, actor)` ที่เดียว — เช็ค transition ที่อนุญาต + เขียน event + ยิง notification

---

## 6. หน้าจอ (React)

1. **Dashboard** (`/`) — การ์ดสรุป: ticket ตามสถานะ, ค้างเกิน X วัน, ของฉัน; ตารางล่าสุด
2. **Ticket list** (`/tickets`) — ตาราง + filter (สถานะ/priority/ผู้รับผิดชอบ/ค้นหา); Sales เห็นเฉพาะของตัวเอง
3. **Ticket detail** (`/tickets/:id`) — หัวเรื่อง+สถานะ, ตารางรายการสินค้า, timeline จาก events, **ปุ่ม action เปลี่ยนตาม role+status**:
   - IMPORT เห็นปุ่ม "รับเรื่อง" / "กรอกราคาเสนอ"
   - CEO เห็นปุ่ม "อนุมัติ" / "ตีกลับ"
   - SALES เห็นปุ่ม **"Generate ใบเสนอราคา"** (disabled จนกว่า status=`approved`) + "ปิดงาน"
4. **Create / price-request form** (`/tickets/new`) — ลูกค้า + เพิ่มรายการสินค้า (เลือกจาก catalog หรือกรอกเอง) + qty + note → save draft แล้ว submit
5. **Notification bell** — dropdown + badge ยังไม่อ่าน (poll ทุก ~30 วิ)
6. (admin) **Users** (`/admin/users`)

---

## 7. การแจ้งเตือน

- **In-app**: เขียน `notifications` ทุก transition → bell ดึงมาแสดง
- **Email** (`JavaMailSender`): (ก) submitted → Import+CEO, (ข) price_proposed → CEO, (ค) approved → Sales, (ง) rejected → Import. Template ง่าย ๆ + ลิงก์เข้า ticket
- **LINE**: phase หลัง (LINE Messaging API ผ่าน LINE OA — LINE Notify เลิกแล้ว)

---

## 8. Milestones (task ของ Claude Code — ทำทีละสเตป, commit ย่อย)

**M0 – สำรวจ & เตรียม**

- อ่าน repo: โครง backend/frontend, build tool, auth/DB ที่มี, อะไรใช้ซ้ำได้ → สรุปเป็นไฟล์ + เสนอปรับแผนถ้าต่าง
- ✅ รัน backend + frontend เดิมขึ้นได้, ต่อ Postgres ได้

**M1 – Data model + migration (Spring/JPA/Flyway)**

- @Entity ทั้งหมด + Flyway migration + seed ผู้ใช้ 4 role + ข้อมูลตัวอย่าง
- ✅ migrate + seed สำเร็จ, query เห็นข้อมูล

**M2 – Auth + RBAC (Spring Security + JWT) + React login**

- login/JWT, `@PreAuthorize` ตามตารางข้อ 4, React route guard + เก็บ token
- ✅ login 4 role ได้, เรียก endpoint ที่ห้ามถูกบล็อกที่ backend (ไม่ใช่แค่ซ่อนปุ่ม)

**M3 – Ticket API + State Machine + tests**

- endpoints ข้อ 5 + `TicketService.transition()` กลาง + เขียน events
- **JUnit test ครอบ transition** (เส้นทางถูก + เส้นทางผิดต้องโดนปฏิเสธ) — ทำก่อน UI
- ✅ draft→submitted→in_review→price_proposed→approved→quotation_issued ผ่าน, reject loop ทำงาน, transition ผิดถูกปฏิเสธ

**M4 – React screens (list/detail/create) + ปุ่มตาม role/status**

- 3 หน้า + ปุ่ม Generate ใบเสนอราคา (disabled จน approved)
- ✅ ไหลครบทาง UI: Sales เปิด → Import เสนอราคา → CEO อนุมัติ → Sales ออกใบเสนอราคา

**M4.5 – Quotation (ตอนแรกสร้าง record ก่อน, PDF ทีหลัง)**

- endpoint `/quotation` สร้างแถวใน `quotations` + เลขที่รัน (pdfPath เว้นว่างไว้)
- ✅ กดแล้วได้ quotation record ผูกกับ ticket; **PDF จริงไปทำในฟีเจอร์ออกเอกสาร (feature #4) ภายหลัง**

**M5 – Notifications (in-app + email)**

- ✅ ทุก transition ผู้รับเห็น in-app + ได้ email (ทดสอบด้วย Mailpit/MailHog)

**M6 – Dashboard**

- `/api/dashboard/summary` + หน้า dashboard
- ✅ ตัวเลขตรงกับ DB จริง

**(hold) ปิดงานอัตโนมัติ** — รอสรุป workflow จ่ายเงิน/ส่งมอบ (ข้อ 3.1) ตอนนี้ใช้ปุ่มปิด manual ไปก่อน

---

## 9. วิธีสั่ง Claude Code ให้ทำงานได้ดี

- เริ่ม: _"อ่าน `docs/ticket-dashboard-plan.md` และสำรวจ repo แล้วทำ M0 อย่างเดียว หยุดให้ฉันรีวิว"_ — อย่าปล่อยทำรวดเดียวทุก milestone
- ทำทีละ milestone, **commit ย่อยต่อสเตป**
- บังคับเขียน **test ของ state machine (M3) ก่อน UI** — จุด bug แพงสุด
- ให้ยืนยัน assumption stack (ข้อ 1) กับของจริงก่อน M1
- ตรวจ ✅ เกณฑ์รับงานทุก milestone ก่อนไปต่อ
- secrets (DB/SMTP/JWT) อยู่ใน env/`application.properties` ที่ไม่ commit

---

## 10. คำถามที่เหลือ (ไม่บล็อก เริ่ม M0 ได้เลย)

1. React: TypeScript หรือ JS? build tool (Vite/CRA)? UI lib? — M0 เช็คได้
2. คำขอราคา 1 ใบ หลายสินค้าได้ (แผนทำหลายรายการไว้แล้ว — ยืนยัน)
3. ใครกด "ปิดงาน" ระหว่างที่ workflow ยังไม่สรุป — Sales เจ้าของ หรือ Admin? (ตอนนี้ตั้งให้ทั้งคู่)
4. ต้องแนบไฟล์ใน ticket ไหม (รูปสินค้า/เอกสารลูกค้า)
5. SMTP บริษัทพร้อมหรือยัง (จำเป็นตอน M5)
