# M0 Survey — Repo Audit & Plan Adjustments

## 1. Stack ที่ยืนยันได้จาก repo จริง

### Backend
| Item | แผน (TICKET_DASHBOARD_PLAN) | จริงใน repo | ผลกระทบ |
|------|--------------------------|------------|---------|
| Framework | Spring Boot + JPA | Spring Boot **4.1.0**, Java **25** | ✅ ใช้ได้ |
| DB Access | Spring Data JPA (`@Entity`) | **Spring JDBC** (`NamedParameterJdbcTemplate`) | ⚠️ ไม่มี JPA — ต้องเขียน plain SQL repository เหมือน `AppUserRepository` |
| Auth | JWT | **Session cookie** (`HttpSession`) | ⚠️ ไม่มี JWT — ใช้ cookie session อยู่แล้ว, ไม่ต้องเปลี่ยน |
| Security | `@PreAuthorize` | **`spring-security-crypto` เท่านั้น** (BCrypt) ไม่มี Security filter chain | ⚠️ ไม่มี `@PreAuthorize` — role check ทำใน service layer เอง (ดู SessionContext) |
| Migration | Flyway | **Flyway** ✅ (V1, V2 มีแล้ว → V3 ต่อ) | ✅ |
| Email | JavaMailSender | **ยังไม่มี** ใน pom.xml | ➕ ต้องเพิ่ม dependency ตอน M5 |
| Validation | Bean Validation | ✅ มี `spring-boot-starter-validation` | ✅ |

### Frontend
| Item | แผน | จริง | ผลกระทบ |
|------|-----|------|---------|
| Language | TypeScript หรือ JS? | **Plain JavaScript** | ✅ ใช้ JS ต่อ |
| Build tool | Vite/CRA? | **Vite** ✅ | ✅ |
| Routing | React Router | **State-based routing** (useState ใน App.jsx) | ⚠️ ไม่มี React Router — ใช้ pattern เดิม (`route` state + `handleRoute()`) |
| Data fetching | TanStack Query | **Plain fetch / async-await** (hrApi.js) | ⚠️ ไม่มี TanStack Query — ใช้ pattern เดิม |
| UI Library | Tailwind/MUI? | **Custom CSS** (styles.css) + `lucide-react` icons | ✅ ใช้ pattern เดิม |
| Mock toggle | — | `VITE_USE_MOCKS` env var (mockApi.js / hrApi.js) | ✅ เพิ่ม mock สำหรับ ticket ได้เลย |

---

## 2. สิ่งที่ใช้ซ้ำได้ทันที

- **Auth flow**: `/api/auth/login` + `/api/auth/me` + session cookie — ไม่ต้องสร้างใหม่
- **Role system**: ตาราง `hr.role` + `hr.user_role` (many-to-many) เป็น string ยืดหยุ่น — แค่ seed role `sales`, `import`, `ceo` เพิ่ม (role `admin` มีอยู่แล้ว)
- **SessionContext**: pattern ดึง user principal จาก session — copy ไปใช้ใน Sales controllers
- **Repository pattern**: `NamedParameterJdbcTemplate` + Java records — ทำแบบเดิมใน `AppUserRepository`
- **Flyway migrations**: ต่อจาก V2 → สร้าง V3 สำหรับ Sales schema
- **API client** (`hrApi.js`) + **mock pattern** (`mockApi.js`) — เพิ่ม ticket endpoints ได้เลย
- **AppShell / Sidebar / Toast / Modal** — นำมาใช้ใน ticket screens ได้ทันที

---

## 3. ปรับแผนจากของจริง

### 3.1 ไม่ใช้ JPA → ใช้ JDBC
เขียน plain SQL ใน repository class (เหมือน `AppUserRepository`) แทน `@Entity` + JPA Repository
- ข้อดี: consistent กับ codebase เดิม, query ชัดเจน
- V3 migration สร้าง tables ตามแผน data model (ข้อ 2 ของ plan)

### 3.2 ไม่ใช้ JWT → ใช้ session ต่อ
- Auth: ยังคง `HttpSession` + cookie
- ลบ JWT dependency requirement ออกจากแผน M2

### 3.3 ไม่มี Spring Security filter chain → role check ใน service
- ไม่ใช้ `@PreAuthorize`
- ทุก service method ดึง `UserPrincipal` จาก `SessionContext` แล้วเช็ค role เอง (pattern เดิม)
- หรือสร้าง helper `requireRole(principal, "sales", "admin")` ที่โยน 403

### 3.4 Frontend: ไม่มี React Router → ใช้ route state ต่อ
- เพิ่ม ticket routes (`tickets`, `ticket-detail`, `ticket-new`) เข้าใน `allowedRoute()` และ App.jsx switch
- ไม่ติดตั้ง React Router เพิ่ม

### 3.5 Schema สำหรับ ticket
- สร้าง schema `sales` แยกจาก `hr` เพื่อ separation of concerns
- V3 migration: `CREATE SCHEMA IF NOT EXISTS sales;` + tables ทั้งหมด

---

## 4. Roles ที่ต้องเพิ่ม

Seed ใน V3 migration (หรือ seeder):

| Role | ใช้งาน |
|------|-------|
| `sales` | เปิด/ส่ง ticket, generate ใบเสนอราคา |
| `import` | รับเรื่อง, กรอกราคาเสนอ |
| `ceo` | อนุมัติ/ตีกลับราคา |
| `admin` | มีอยู่แล้ว — จัดการทุกอย่าง |

---

## 5. Migration ที่วางแผน (V3)

```sql
-- สร้าง schema
CREATE SCHEMA IF NOT EXISTS sales;

-- tables: tickets, ticket_items, ticket_events, quotations, notifications
-- + seed roles: sales, import, ceo
-- + seed test users 1 คนต่อ role
```

---

## 6. เกณฑ์ M0 ✅

- [x] อ่าน repo และยืนยัน stack จริง
- [x] ระบุ deviation จากแผนครบ (JPA→JDBC, JWT→Session, @PreAuthorize→manual, React Router→state)
- [x] ระบุสิ่งที่ใช้ซ้ำได้
- [x] เสนอ schema `sales` สำหรับ ticket tables
- [x] ยืนยัน assumption ทั้งหมดกับ owner แล้ว (ดูข้อ 7)

---

## 7. คำตอบที่ยืนยันแล้ว (2026-06-19)

| # | คำถาม | คำตอบ |
|---|-------|-------|
| 1 | DB connection | **ใช้ Mock data ก่อน** ยังไม่ต่อ PostgreSQL |
| 2 | Schema | **`sales` schema แยก** จาก `hr` |
| 3 | Role names | **`sales` / `import` / `ceo`** (lowercase) ✅ |
| 4 | Notification | **In-app เท่านั้น** ก่อน — ไม่ส่ง email ใน M5 |
| 5 | Demo users | **Seed test users 1 คน/role** ใน migration ✅ |

---

## 8. สรุปพร้อมไป M1

ขั้นตอนถัดไป (M1):
- เพิ่ม mock data สำหรับ tickets ใน `mockApi.js`
- สร้าง Flyway migration V3 (`sales` schema + tables + seed roles + seed demo users)
- เขียน Java records + repositories สำหรับ `tickets`, `ticket_items`, `ticket_events`, `quotations`, `notifications`
