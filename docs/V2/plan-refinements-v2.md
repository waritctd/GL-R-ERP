# แผนแก้ไขเพิ่มเติม v2 — Sales / Import / CEO / เอกสาร / Versioning (GL-R-ERP)

> อ่านคู่กับ `ticket-dashboard-plan.md` + `quotation-and-revision-plan.md` (ไฟล์นี้ = ส่วนแก้ไข/ต่อยอด)
> ทำเป็น milestone R1–R6 ต่อจาก M1–M4 + Q1–Q6 เดิม — ทำทีละสเตป commit ย่อย หยุดรีวิว
>
> ⚠️ **BLOCKER บางส่วน**: field map ของ "ใบเสนอราคา" ต้องอิงไฟล์ **"GL&R ใบเสนอราคา format"** (ยังไม่ส่ง)
> ส่วนที่ไม่พึ่งไฟล์นั้นเริ่มได้เลย; ส่วนที่พึ่ง ให้ทำโครงไว้ + ใส่ placeholder รอไฟล์

---

## 1. Sales — ขยายฟอร์มคำขอราคา (สำหรับ generate ใบเสนอราคา)

### 1.1 ลูกค้า / ผู้ติดต่อ / โครงการ (แยกกัน)
- `customers` (บริษัท): `name`,`taxId`,`address`,`branch`
- **`contacts`** (ผู้ติดต่อ) *(ใหม่)*: `customerId`,`firstName`,`lastName`,`position`,`email`,`phone`
- **`projects`** (โครงการ) *(ใหม่)*: `customerId`,`name` — **1 บริษัทมีได้หลายโครงการ**
- `tickets` เพิ่ม FK: `customerId`,`projectId`,`contactId` (เก็บ snapshot ตอนออกเอกสารด้วย)
- UI: เลือกบริษัท → เลือก/สร้างโครงการ → เลือก/สร้างผู้ติดต่อ (autofill ตำแหน่ง/อีเมล/เบอร์)

### 1.2 รายการสินค้าที่ขอ — ขยาย `ticket_items`
เพิ่มฟิลด์: `brand`(ยี่ห้อ), `collection`(รุ่น), `color`(สี), `surface`(เนื้อผิว), `size`(ขนาด)
- จำนวน: **เก็บทั้ง `qtyPieces`(แผ่น) และ `qtySqm`(ตร.ม.)** — กรอกอันใดอันหนึ่งแล้วคำนวณอีกอันจาก pack (sqm/แผ่น) ในฐาน catalog ถ้ามี
- ให้ autocomplete จากฐานราคา (catalog) เพื่อความสม่ำเสมอของชื่อ brand/รุ่น/สี/ผิว
- ✅ **R1/R2 acceptance**: Sales กรอกลูกค้า+ผู้ติดต่อ+โครงการ(แยกบริษัท)+รายการสินค้าครบทุกฟิลด์ บันทึกเป็น ticket ได้

---

## 2. Import — Import Request + อีเมลถึงโรงงาน (แยกรายโรงงาน)

- ต้องมี mapping **brand → factory** (field `factory` ในฐาน catalog หรือตาราง `brands` แยก)
- เมื่อ Import รับเรื่อง: **generate "Import Request" อัตโนมัติจากสินค้าที่ Sales ขอ โดยจัดกลุ่มตามโรงงาน**
  - สินค้าอยู่คนละโรงงาน → **แยกเอกสาร/แยกอีเมลต่อโรงงาน** (1 โรงงาน = 1 คำขอ)
  - generate **เนื้อความอีเมล** ให้เลย (หัวข้อ + รายการสินค้าของโรงงานนั้น) + แนบ Import Request (PDF/Excel)
  - ส่งผ่าน `JavaMailSender`
- ราคาที่กรอกกลับ = **ราคา raw ตามสกุลเงิน/หน่วยของประเทศโรงงานนั้น** (เช่น Panaria = EUR, €/sqm)
  - เก็บ `rawPrice`,`rawCurrency`,`rawUnit` ต่อ item (อิง normalize เดียวกับ `catalog_extract.py`)
- ✅ **R3 acceptance**: กดจากคำขอที่มีสินค้า 2 โรงงาน → ได้ร่างอีเมล 2 ฉบับแยกโรงงาน + ช่องกรอกราคา raw เป็นสกุลเงินโรงงาน

---

## 3. CEO — Price Calculation Engine (แก้สูตรได้ตลอดเวลา)

### 3.1 Tab ตั้งค่าการคำนวณ (CEO แก้ได้ทุกเมื่อ + versioning)
- ตาราง **`price_calc_config`** *(ใหม่)*: เก็บชุด factor ปัจจุบัน + **เวอร์ชัน** (`version`,`updatedBy`,`updatedAt`,`effectiveFrom`)
- ออกแบบให้ **config-driven** (CEO เพิ่ม/แก้ factor ได้โดยไม่ต้องแก้โค้ด) — เก็บ factor เป็นรายการ component (ชนิด: คงที่/เปอร์เซ็นต์/ต่อหน่วย + ลำดับคำนวณ)

### 3.2 Factor การคำนวณ (แต่ละประเทศไม่เท่ากัน → ผูกกับ country/factory)
- `goodsCost` — ค่าสินค้า (สกุลโรงงาน)
- `fxRate` — อัตราแลกเปลี่ยน → THB
- `freight` — ค่าเรือ/เครื่องบิน
- `insurance` — ค่าประกันภัย
- `inlandFactoryToPort` — ขนส่งโรงงาน → ท่าเรือต้นทาง
- `inlandPortToWarehouse` — ขนส่งท่าเรือปลายทาง → โกดังเรา
- `importDuty` — ภาษีนำเข้า **คิดจาก CIF** (CIF = goodsCost + insurance + freight)
- `taxRate` — อัตราภาษีนำเข้าตามประเทศ (ตั้งต่อ country)

### 3.3 การคำนวณ (ตัวอย่างลำดับ — ให้ CEO ปรับได้)
```
CIF(THB)   = (goodsCost × fxRate) + insurance + freight
importDuty = CIF × taxRate(country)
landedCost = CIF + importDuty + inlandFactoryToPort + inlandPortToWarehouse
sellPrice  = landedCost × (1 + margin)   // margin/วิธีตกลงตาม CEO
```
- แสดง **ทั้งราคา raw (สกุลโรงงาน) และราคาคำนวณ (THB)** เทียบกันให้ CEO เห็น
- เก็บ `calcedCost`,`calcedPrice`,`calcConfigVersion` ต่อ item (รู้ว่าใช้สูตรเวอร์ชันไหนคำนวณ)
- ✅ **R4 acceptance**: CEO แก้ factor → ราคาคำนวณอัปเดต + เก็บประวัติเวอร์ชันสูตร (ย้อนดูได้ว่าใช้สูตรไหนตอนไหน)

---

## 4. เอกสาร — ใบเสนอราคา (primary) + เอกสารอื่น

> 🔄 **เปลี่ยนจากรอบก่อน**: เอกสารหลักกลับมาเป็น **ใบเสนอราคา (QUOTATION)** ไม่ใช่ใบแจ้งยอดมัดจำ
> ใช้ generic `documents` engine เดิม (POI template + LibreOffice→PDF) — แค่ตั้ง `docType=QUOTATION` เป็นหลัก

### 4.1 ใบเสนอราคา (ตาม format "GL&R ใบเสนอราคา format" — ⚠ รอไฟล์)
- ใบเสนอราคา = **ราคาที่ CEO อนุมัติ** (ขึ้นกับวิธีตกลง)
- **Sales แก้ตัวเลขข้อ 1,2,3,7 ได้** = *(ยืนยัน field เป๊ะจากไฟล์ format ตอนได้มา)*
  - วันที่แก้ไข (revisionDate)
  - มัดจำกี่เปอร์เซ็นต์ (depositPercent)
  - ระยะเวลา / validity (validityPeriod)
- Claude Code: ทำโครงฟิลด์เหล่านี้ไว้ + placeholder template, **รอไฟล์ format แล้วค่อย map ตำแหน่งเซลล์จริง**

### 4.2 การแนบกลับจากลูกค้า (ใหม่ — ตาราง `attachments`)
- ลูกค้าทั่วไป → เซ็นใบเสนอราคากลับ; บริษัทใหญ่ → ส่ง **PO** กลับ
- ต้อง **attach ไฟล์กลับผูกกับ ticket/quotation** (`attachments`: `ticketId`/`documentId`,`fileName`,`filePath`,`type`,`uploadedBy`,`uploadedAt`)

### 4.3 docType อื่น (ใช้ engine เดิม, ทำทีหลัง)
- `DEPOSIT_NOTICE` — ใบแจ้งยอดมัดจำ (ทำไว้แล้วใน Q-plan)
- `INVOICE` — ใบแจ้งหนี้
- `BILLING_NOTE` — ใบวางบิล (ขอเงิน)
- ✅ **R5 acceptance**: จาก ticket approved → ออกใบเสนอราคา (โครงตาม format) + แนบ PO/ใบเซ็นกลับเข้ากับงานได้

---

## 5. Workflow แก้ไข — ต่อราคา / เพิ่มสินค้า (+ versioning + แจ้งเตือน)

ทั้ง **"ต่อราคา"** และ **"ขอเพิ่มสินค้า"** ใช้ flow เดียวกัน (ต่อยอดส่วน A ของ Q-plan):
```
Sales กดแก้ไข ──▶ (ราคาเปลี่ยน/เพิ่มสินค้า) ──▶ CEO อนุมัติ ──▶ Sales แก้ใบเสนอราคาได้
```
- **versioning**: ใบเสนอราคาเก็บเป็นเวอร์ชัน (Rev1/Rev2…), ฉบับเก่า = SUPERSEDED
- **audit**: บันทึก **แก้อะไร / เมื่อไร / โดยใคร** (diff รายการ+ราคา) ใน `ticket_events`
- **แจ้งเตือน** ทุกครั้งที่มีการขอแก้/อนุมัติ (in-app + email ตามแผนหลัก)
- UI: timeline แสดงเวอร์ชัน + สิ่งที่เปลี่ยนแต่ละรอบ
- ✅ **R6 acceptance**: ต่อราคา/เพิ่มสินค้า → วน CEO → อนุมัติ → Sales ออกใบ Rev ใหม่; ดูประวัติได้ว่าแก้อะไรเมื่อไรใครแก้

---

## 6. สรุปผลกระทบ data model (สิ่งที่เพิ่ม/แก้)
- **ใหม่**: `contacts`, `projects`, `attachments`, `price_calc_config`(+version), `brands`(หรือ factory field), template ต่อ docType
- `tickets`: + `customerId`,`projectId`,`contactId`
- `ticket_items`: + `brand`,`collection`,`color`,`surface`,`size`,`qtyPieces`,`qtySqm`,`rawPrice`,`rawCurrency`,`rawUnit`,`calcedCost`,`calcedPrice`,`calcConfigVersion`
- `documents`: `docType` หลัก = `QUOTATION`; + `revisionDate`,`depositPercent`,`validityPeriod`

## 7. ลำดับ Milestone แนะนำ (ต่อจากเดิม)
- **R1** customers/contacts/projects model + ฟอร์ม Sales ส่วนลูกค้า
- **R2** ticket_items ฟิลด์ใหม่ + qty แผ่น/ตร.ม. + autocomplete catalog
- **R3** brand→factory + Import Request + อีเมลแยกโรงงาน + ราคา raw ตามสกุลประเทศ
- **R4** price calc engine + CEO config tab + versioning สูตร
- **R5** ใบเสนอราคา (รอ format file) + attachments (PO/เซ็นกลับ)
- **R6** revision/versioning/audit/แจ้งเตือน (ต่อราคา + เพิ่มสินค้า)

## 8. ⚠️ ต้องเคลียร์ก่อน (ให้ผู้ใช้)
1. **ไฟล์ "GL&R ใบเสนอราคา format"** — จำเป็นต่อ R5 (map ฟิลด์ข้อ 1,2,3,7)
2. **brand→factory** มาจากไหน (ตั้งเอง / จาก field factory ในฐาน catalog)
3. **FX rate** ดึงอัตโนมัติจากแหล่งไหน หรือ CEO กรอกเอง (มีผลต่อ R4)
4. margin/วิธีตกลงราคาขาย — สูตรตายตัว หรือ CEO ใส่ต่อดีล
