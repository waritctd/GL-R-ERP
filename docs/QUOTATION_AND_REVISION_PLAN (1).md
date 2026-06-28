# แผนเสริม: Revision Flow + Auto-Generate ใบแจ้งยอดเงินรับมัดจำ (GL-R-ERP)

> addendum ของ `ticket-dashboard-plan.md` — context ให้ Claude Code ทำต่อใน VSCode
> Stack: React + Spring Boot + PostgreSQL | อ้างฟอร์มจริง `quotation_template_source.xlsx` (ชีต `Update`)
>
> เอกสารที่ generate = **ใบแจ้งยอดเงินรับมัดจำ (Deposit Notice)** ไม่ใช่ใบเสนอราคา
> ออกแบบ engine เป็น generic `documents` (มี `docType`) เผื่อ feature #4 เพิ่ม QUOTATION/INVOICE ภายหลัง

---

# ส่วน A — Flow เมื่ออนุมัติราคาแล้ว แต่ลูกค้าขอแก้

## A1. หลักการ
- ราคาที่ CEO อนุมัติ = **immutable** ห้ามแก้ทับ เก็บเป็นประวัติ (audit)
- การแก้ = สร้าง **revision ใหม่** ไม่ใช่แก้ของเดิม
- เอกสารที่ออกทำ **versioning** (Rev 1, 2 …) ฉบับเก่า = SUPERSEDED
- เส้นทางอนุมัติซ้ำ **ขึ้นกับว่าแก้อะไร** (ไม่ใช่ทุกการแก้ต้องวน CEO)

## A2. Decision tree (Sales กด "ขอแก้ไข / Revise" จาก `approved` หรือ `document_issued`)
| สิ่งที่แก้ | ต้องอนุมัติใหม่ไหม | สถานะที่กลับไป |
|---|---|---|
| จำนวน / หมายเหตุ / ข้อมูลลูกค้า / % มัดจำ — **ราคา&ส่วนลดต่อหน่วยเท่าเดิม** | ❌ ไม่ต้อง | คงที่ `approved` → ออกเอกสาร Rev ใหม่ได้เลย |
| ราคา/ส่วนลดต่อหน่วยเปลี่ยน | ✅ CEO อนุมัติเฉพาะรายการที่เปลี่ยน | `price_proposed → approved` |
| เพิ่มสินค้าใหม่ที่ยังไม่ตั้งราคา | ✅ Import เสนอราคา → CEO อนุมัติ | `in_review → price_proposed → approved` |
| ลดรายการออก | ❌ | คงที่ `approved` |

> หลักคิด: **ถ้า "ราคา/ส่วนลดต่อหน่วย" ที่ CEO เคาะ ไม่ถูกแตะ → ไม่ต้องวน CEO** (เปลี่ยน % มัดจำ/จำนวนไม่กระทบราคาต่อหน่วย)

## A3. โครงข้อมูลรองรับ revision
- `tickets.revisionNo` (เริ่ม 1) bump เมื่อ revision ที่ต้องอนุมัติใหม่
- แก้ราคา → **clone item เป็นรอบใหม่** เก็บของเก่าไว้ (ไม่เขียนทับ `approvedPrice`)
- `documents.version` ; ฉบับก่อนหน้า `status=SUPERSEDED`
- ทุก revise เขียน `ticket_events` (`kind=REVISION_REQUESTED`, message=สิ่งที่แก้+เหตุผล)

## A4. action เพิ่ม (รวมไว้ใน `TicketService.transition()` ที่เดียว)
- `requestRevision(scope, reason)` จาก `approved`/`document_issued`
- `scope=QTY_OR_NOTE` → คง `approved`, bump version ตอน generate
- `scope=PRICE_CHANGE` → `price_proposed` (CEO), bump `revisionNo`
- `scope=NEW_ITEM` → `in_review` (Import), bump `revisionNo`

## A5. ✅ เกณฑ์รับงาน (ส่วน A)
- แก้ qty/มัดจำ% อย่างเดียว → ออกเอกสาร Rev2 ได้โดยไม่ต้องให้ CEO กดซ้ำ, Rev1 = superseded
- แก้ราคา → บังคับวนกลับ CEO, ออกเอกสารใหม่ไม่ได้จนกว่าจะ approved รอบใหม่
- timeline แสดงประวัติครบทุก revision

---

# ส่วน B — Auto-Generate ใบแจ้งยอดเงินรับมัดจำ (PDF / Excel)

## B0. สิ่งที่ต้องการ
ปุ่ม "Generate ใบแจ้งยอดมัดจำ" (เปิดใช้เมื่อ status=`approved`) → เปิด **หน้า/overlay สร้างเอกสาร** ที่:
1. ดึงรายการสินค้า+ราคาที่อนุมัติมาเติมอัตโนมัติ
2. เลือก/แก้ **หมายเหตุ** (เช็คลิสต์ + พิมพ์เพิ่ม) + ตั้ง **% มัดจำ**
3. **Preview** ก่อนโหลด
4. ดาวน์โหลดได้ทั้ง **PDF และ Excel** หน้าตาตรงฟอร์มบริษัท

## B1. Field map (ชีต `Update` ของไฟล์จริง)
| ส่วน | เซลล์ | ที่มา |
|---|---|---|
| ชื่อ/ที่อยู่/โทร/เลขภาษีบริษัท | B2–B5 | **คงที่** (config บริษัท: บจก. จี แอล แอนด์ อาร์ฯ, เลขภาษี 0105542026329) |
| หัวเอกสาร "ใบแจ้งยอด / เงินรับมัดจำ" | H2,H3 | คงที่ |
| เรียน (ชื่อลูกค้า+เลขภาษี) | A7,B7 | **customer master** |
| ที่อยู่ลูกค้า | A8,B8 | customer master |
| วันที่ | H7,I7 | วันที่ออก |
| เลขที่เอกสาร | H8,I8 | running `GLRD`+ปีไทย2หลัก+ลำดับ (เดิม GLRD69011) |
| อ้างอิงใบเสนอราคา/ใบสั่งซื้อ | H9 | กรอก/ว่าง |
| หัวตาราง | แถว10 | ลำดับ/รายละเอียด/จำนวน/หน่วย/ราคา/ส่วนลด/คงเหลือ/เป็นเงิน(บาท) |
| Project | B11 | กรอก |
| รายการสินค้า | แถว13+ | **จาก ticket_items ที่อนุมัติ** (ทำซ้ำหลายแถว) |
| หมายเหตุ | B37–B43 | **เลือกได้ + แก้ได้** (B4) |
| **บล็อกสรุปยอด** | แถว44–47 | ดูสูตรด้านล่าง |
| ผู้จัดทำ / ชื่อ / วันที่ | A47,B48,B49 | config (เริ่มต้น: จินตนา หาญมนตรี) |

**สูตรราคา (ยืนยันจากเซลล์จริงในไฟล์):**
```
ต่อแถว:  คงเหลือ(netUnitPrice) = ราคา − ส่วนลด          (ส่วนลด: %/จำนวน/"ราคาพิเศษ")
         เป็นเงิน(amount)      = netUnitPrice × qty       (739.6 × 726 = 536,949.6 ✓)

รวมเป็นเงิน (I44)          = ROUND(Σ amount, 2)              = 536,949.6
ขอรับเงินมัดจำ (I45)       = ROUND(subtotal × depositPct, 2) = 268,474.8   [H45=0.5]
ภาษีมูลค่าเพิ่ม (I46)      = ROUND(deposit × vatPct, 2)      = 18,793.24   [H46=0.07]  ⚠ VAT คิดจาก "มัดจำ"
รวมเป็นเงินที่ต้องชำระ(I47) = deposit + vat                  = 287,268.04
```
> ⚠ จุดพลาดง่าย: VAT 7% **คิดจากยอดมัดจำ ไม่ใช่ยอดรวม** (ถูกต้องตามหลักภาษีไทยที่เงินมัดจำถึง tax point)

## B2. Data model (generic documents)
**customers** (จาก Sheet1 ของไฟล์ = master) — `id`,`name`,`taxId`,`address`,`branch`,`phone`

**document_note_templates** (หมายเหตุ boilerplate) — `id`,`text`,`defaultSelected`,`order`

**documents**
- `id`,`ticketId`,`docType` (`DEPOSIT_NOTICE` ตอนนี้; เผื่อ `QUOTATION`/`INVOICE`),`version`,`docNumber`,`issueDate`
- `status` (`DRAFT`|`ISSUED`|`SUPERSEDED`)
- snapshot ลูกค้า: `customerName`,`customerTaxId`,`customerAddress`
- `projectName`,`reference`,`currency`
- `subtotal`, `depositPercent`, `depositAmount`, `vatPercent`, `vatAmount`, `totalPayable`
- `notes` (list หมายเหตุที่เลือก/แก้)
- `pdfPath`,`xlsxPath` (nullable จน render)

**document_items** (snapshot ตอนออกเอกสาร — แช่แข็ง ไม่ผูกสดกับ ticket)
- `id`,`documentId`,`seq`,`description`,`qty`,`unit`,`unitPrice`,`discountLabel`,`netUnitPrice`,`amount`

**doc number sequence** — ออกเลขรันต่อปีไทย กันชนกันตอนออกพร้อมกัน

## B3. วิธี render (ฟรีทั้งหมด — template เดียวได้ทั้ง 2 format)
1. เก็บ `deposit_notice_template.xlsx` ใน `src/main/resources/templates/`
2. **Apache POI** เปิด template → เติมค่า → ได้ `.xlsx`
3. แปลงเป็น PDF ด้วย **JODConverter + LibreOffice headless** → `.pdf` ตรงกับ Excel เป๊ะ
4. ฝัง **ฟอนต์ TH Sarabun New** ในเครื่องที่รัน LibreOffice กัน PDF เพี้ยน
- license: LibreOffice (MPL 2.0) + JODConverter (Apache 2.0) + POI (Apache 2.0) = **ฟรีเชิงพาณิชย์ ไม่มีค่า license** เสียแค่ CPU/RAM
- ทางเลือกถ้าไม่ลง LibreOffice บนเซิร์ฟเวอร์: HTML template → PDF ด้วย OpenHTMLtoPDF (ฝังฟอนต์ไทยเอง), Excel ยังใช้ POI

## B4. หมายเหตุ (default จากไฟล์ — ทำเป็นเช็คลิสต์)
1. ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง
2. จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)
3. กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th
> เก็บใน `document_note_templates` → UI โชว์ checkbox (ติ๊กเริ่มต้นตาม defaultSelected) + ช่องเพิ่มเอง

## B5. API
```
POST /api/tickets/{id}/document/draft?type=DEPOSIT_NOTICE   สร้าง draft จาก items ที่อนุมัติ (เติมอัตโนมัติ)
PUT  /api/documents/{docId}                 อัปเดต (หมายเหตุ/% มัดจำ/project/reference/ลูกค้า)
POST /api/documents/{docId}/preview         render → คืน PDF (โชว์ใน iframe)
POST /api/documents/{docId}/issue           ออกจริง: docNumber+version, status=ISSUED, ticket→document_issued, เก่า→SUPERSEDED
GET  /api/documents/{docId}/file?format=pdf|xlsx
GET  /api/customers?search=                 ค้น master (autofill เรียน/ที่อยู่/เลขภาษี)
```

## B6. UI — หน้าสร้างเอกสาร (React, route เช่น `/tickets/:id/document`)
1. ซ้าย: เลือกลูกค้า (dropdown master, autofill), Project, อ้างอิง, **เช็คลิสต์หมายเหตุ + เพิ่มเอง**, **% มัดจำ**, ตารางรายการ (เติมจาก approved, แก้ qty ได้)
2. ขวา: **Preview** (PDF ใน iframe) — ปุ่ม "อัปเดต preview" เรียก `/preview`
3. ปุ่มล่าง: **ดาวน์โหลด PDF** / **ดาวน์โหลด Excel** / **ออกเอกสารจริง (Issue)**
4. ปุ่ม Generate บนหน้า ticket detail = disabled จนกว่า status=`approved`

## B7. Milestones (ต่อท้าย M4.5 ของแผนหลัก)
- **Q1 – master + note templates**: import Sheet1 → `customers`, seed หมายเหตุ. ✅ ค้น API ได้
- **Q2 – template + POI fill (Excel)**: ทำ `deposit_notice_template.xlsx` (ลบตัวอย่าง ใส่ placeholder) + เติมด้วย POI รวมบล็อก **มัดจำ+VAT-on-deposit+ยอดต้องชำระ**. ✅ ได้ .xlsx ตรง layout + ตัวเลข 4 บรรทัดถูก (268,474.8 / 18,793.24 / 287,268.04)
- **Q3 – PDF render**: JODConverter+LibreOffice + ฟอนต์ไทย. ✅ PDF เหมือน Excel ไทยไม่เพี้ยน
- **Q4 – document API + versioning + docNumber**: draft/update/issue/preview/file + เลขรัน + SUPERSEDED. ✅ ออก Rev1/Rev2 ได้
- **Q5 – หน้าสร้างเอกสาร (React)**: ฟอร์ม+หมายเหตุ+% มัดจำ+preview+โหลด 2 format. ✅ จาก ticket approved → ออกเอกสาร + โหลด PDF/Excel end-to-end
- **Q6 – ผูก revision (ส่วน A)**: ปุ่มขอแก้ไข + decision tree + bump version. ✅ เกณฑ์ A5 ผ่าน

## B8. คำถามที่เหลือ (เล็กน้อย ไม่บล็อก)
1. **เลขที่เอกสาร**: ยืนยันรูปแบบ `GLRD`+ปีไทย+ลำดับ และต้องใช้ชุดเลขแยกต่อ docType ไหม (เผื่ออนาคตมี QUOTATION/INVOICE)
2. **% มัดจำ default**: ใช้ 50% เป็นค่าเริ่มต้นเสมอ หรือกรอกทุกครั้ง
3. ผู้จัดทำ (ชื่อท้ายเอกสาร) fix เป็นคนเดียว หรือใช้ชื่อ Sales ที่ออกเอกสาร
