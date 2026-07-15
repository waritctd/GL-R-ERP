# Change Requests v3 — จาก review/ทดสอบจริง (GL-R-ERP)

> อ่านคู่กับแผนเดิมทั้งหมด (`ticket-dashboard-plan.md`, `quotation-and-revision-plan.md`,
> `plan-refinements-v2.md`, `quotation-format-and-decisions.md`)
> ทำ **ทีละข้อ commit ย่อยต่อข้อ** เลขข้อตรงกับที่ผู้ใช้แจ้ง เพื่อ cross-check ง่าย

---

## ⚠️ ไฟล์ที่ต้องมีก่อน (อ้างถึงแต่ยังไม่อยู่ใน repo)
ข้อที่พึ่งไฟล์เหล่านี้ ให้ทำโครง/placeholder ไว้ก่อน แล้วรอไฟล์จริงมาวางใน `docs/templates/`:
- [ ] **sheet สูตรคำนวณราคา** (ข้อ 8)
- [ ] **Template ใบเสนอราคา** + **ตัวอย่าง ใบเสนอราคา** (ข้อ 12) — อาจใช้ `GL_R_ใบเสนอราคา_format.xls` เดิมได้ ตรวจกับผู้ใช้
- [ ] **Template ใบมัดจำ** + **ตัวอย่างใบมัดจำ** (ข้อ 13.1) — อาจใช้ฟอร์มมัดจำเดิมได้
- [ ] **Template Import Request** + **ตัวอย่าง Import Request** (ข้อ 13.2) — ใหม่
- [ ] **Template ใบแจ้งหนี้ส่วนที่เหลือ** + **ตัวอย่าง** (ข้อ 13.5) — ใหม่

## ⚠️ Security (ข้อ 7)
BOT API token **ห้าม hardcode/commit** — เก็บใน env `BOT_API_TOKEN` แล้วอ่านจาก config
(`Authorization: <BOT_API_TOKEN>`); ใส่ `.env`/secret ที่ .gitignore แล้ว

---

# A. Bug fixes

## ข้อ 2 — ช่องจำนวนลบเลข "1" ไม่ได้
- อาการ: input จำนวนมีค่า default/min=1 ทำให้ลบไม่ได้ ต้องพิมพ์ 13 ก่อนค่อยลบ 1
- แก้: controlled input ให้ **ปล่อยว่างชั่วคราวระหว่างพิมพ์ได้** (empty string), อย่าบังคับ min ระหว่างพิมพ์, validate ตอน blur/submit แทน (ว่าง/0 → error ตอน submit)
- ✅ พิมพ์/ลบเลขได้อิสระ, submit ค่าว่างแล้วขึ้น error

## ข้อ 3 — Import ไม่มีสิทธิ์แก้สินค้า → เอาปุ่มแก้ไขออก
- ยืนยัน RBAC: IMPORT แก้ `ticket_items` ไม่ได้ (บังคับที่ backend อยู่แล้ว) → **ซ่อน/ลบปุ่มแก้ไขในหน้า Import**
- ✅ หน้า Import ไม่มีปุ่มแก้ไขรายการสินค้า

## ข้อ 11 — หน้าแก้ไขของ Sales หลัง CEO approve เลย์เอาต์เพี้ยน
- อาการ: size ผิด หน้าเลื่อน ตัวอักษรเบียด radio/วงเลือกใหญ่เกิน
- แก้ CSS/responsive: คุม container width, overflow, spacing, ลดขนาด radio/select ให้พอดี
- ✅ หน้าไม่ล้น ตัวอักษรไม่เบียด ดูปกติบนจอทั่วไป

---

# B. Sales form & UX

## ข้อ 1 — เลือกหน่วยต่อรายการ (แผ่น / ตร.ม.)
- แต่ละโรงงานขายหน่วยไม่เท่ากัน → ให้ **เลือกหน่วยได้ต่อบรรทัด**: `PIECE` (แผ่น) หรือ `SQM` (ตร.ม.)
- ถ้าเลือก PIECE → **โชว์ว่า 1 แผ่น = กี่ ตร.ม.** (จาก size/pack ในฐาน catalog: sqm ต่อแผ่น) และคำนวณ ตร.ม. รวมให้
- เก็บ `unitBasis` (PIECE/SQM) + `qtyPieces` + `qtySqm` (แปลงกันอัตโนมัติเมื่อมี sqm/แผ่น)
- ✅ เลือกหน่วยต่อบรรทัดได้, เลือกแผ่นแล้วเห็น ตร.ม./แผ่น + ยอด ตร.ม. รวม

## ข้อ 5 — Redesign ตัวเลือกสกุลเงิน + หน่วย (เล็ก/ใช้ยาก)
- ออกแบบ control ใหม่ให้ใหญ่/ชัด/ใช้ง่าย (dropdown สกุลเงิน + หน่วย แยกชัด, label กำกับ, ค่าเริ่มต้นตามโรงงาน)
- ✅ เลือกสกุลเงิน/หน่วยได้สะดวก ไม่ต้องเล็งคลิก

## ข้อ 4 — ร่างจดหมาย/อีเมลของ Sales + ส่งจริง
- **เอา "เลขใบขอราคา" และ "ชื่อบริษัทลูกค้า" ออกจากร่างจดหมาย** (ไม่เปิดเผยข้อมูลลูกค้าออกนอก)
- ใช้ **format จดหมายทางการของบริษัท** (⚠ ขอไฟล์ format จากผู้ใช้)
- **ผูกให้ส่งอีเมลได้จริง** ผ่าน JavaMailSender (SMTP บริษัท/Gmail) — ปุ่มส่ง + log การส่ง
- ✅ ร่างไม่มี PR number/ชื่อลูกค้า, กดส่งแล้วอีเมลออกจริง

---

# C. การแก้ไข + Versioning

## ข้อ 6 — หลังกดเสนอราคาแล้วแก้ไม่ได้ → ให้แก้ได้ + versioning + notify
- ทำให้ราคาที่เสนอ **แก้ไขได้** โดยทุกครั้งบันทึกเป็นเวอร์ชัน (เก็บ แก้อะไร/เมื่อไร/ใคร)
- **ก่อนอนุมัติ**: แก้แล้ว **notify CEO** ว่ามีการแก้ไข
- **ถ้าอนุมัติไปแล้ว**: แก้แล้วต้อง **กลับไปอนุมัติใหม่** → เปลี่ยนสถานะกลับเป็น `รอการอนุมัติ` + **notify ทั้ง CEO และ Sales** ว่าสถานะย้อนกลับ
- รวม logic ใน `TicketService.transition()` + versioning table เดิม
- ✅ แก้ราคาได้ทุกช่วง, เวอร์ชัน+ประวัติครบ, อนุมัติแล้วแก้ → เด้งกลับรออนุมัติ + แจ้งทุกฝ่าย

---

# D. CEO Price Config

## ข้อ 7 — Fetch FX จาก BOT อัตโนมัติทุกวัน 18:00
- **Scheduled job** (Spring `@Scheduled(cron = "0 0 18 * * *")`) ดึงอัตราแลกเปลี่ยนรายวันจาก BOT
- endpoint: `GET https://gateway.api.bot.or.th/Stat-ExchangeRate/v2/DAILY_AVG_EXG_RATE/?start_period={date}&end_period={date}&currency={CUR}`
  - header: `Authorization: {BOT_API_TOKEN}` (จาก env — ดู Security)
  - วน currency ที่ระบบใช้ (USD, EUR, …), parse JSON → upsert `fx_rates(currency, date, rate, source='BOT', fetchedAt)`
  - จัดการกรณี BOT ยังไม่ประกาศของวันนั้น (ผลว่าง) → fallback ใช้ค่าล่าสุดที่มี + log
- ยังให้ CEO **override/กรอกเองได้** (source='MANUAL')
- ✅ 18:00 ดึงอัตโนมัติเข้าตาราง, ราคาคำนวณใช้อัตราล่าสุด, override มือได้

## ข้อ 8 — อ่านสูตรคำนวณจาก sheet ที่แนบ
- ให้หน้า CEO price config **โหลด/แปลงสูตรจาก sheet** (⚠ รอไฟล์) เป็นชุด factor/สูตรใน `price_calc_config`
- ระหว่างรอไฟล์: ทำโครง config ตาม factor เดิม (goodsCost/fx/freight/insurance/inland×2/importDuty(CIF)/taxRate) ไว้ก่อน
- ✅ เปิดไฟล์สูตรแล้วระบบตั้ง factor/สูตรตามได้

## ข้อ 9 — โชว์สูตรตอนกดคำนวณแต่ละสินค้า
- หน้าคำนวณราคาต่อสินค้า **แสดง breakdown สูตร** (แต่ละ factor + ขั้นการคิด CIF→duty→landed→sell) ไม่ใช่แค่ตัวเลขสุดท้าย
- ✅ กดคำนวณแล้วเห็นที่มาของราคาเป็นขั้น ๆ

## ข้อ 10 — CEO กรอกราคา manual ได้ (แก้ตรงหน้าใบขอราคา)
- ถ้าคำนวณแล้วไม่พอใจ → CEO **override ราคาเองได้ตรงหน้าใบขอราคา** (เก็บ `manualPrice`, flag ว่าเป็น override + เหตุผล)
- ### ข้อ 10.1 — ราคา override/ตีกลับของ CEO ไม่โชว์ในหน้า Import
  - เมื่อ CEO ตีกลับ/กรอกราคาใหม่เอง → **หน้า Import ไม่แสดงราคาใหม่ที่ CEO เสนอ** (Import เห็นแค่ราคา raw ที่ตัวเองกรอก)
- ✅ CEO override ได้, Import มองไม่เห็นราคาที่ CEO ตั้ง/ตีกลับ

---

# E. เอกสารใบเสนอราคา

## ข้อ 12 — ไฟล์ที่โหลดมา "ไม่ตรง template ทั้งใบ" (ไม่ใช่ฟอนต์เพี้ยน)
- อาการจริง: ดาวน์โหลดมาแล้ว **เลย์เอาต์ไม่ตรง template** (โครงทั้งใบผิด ไม่ใช่แค่ตัวอักษร)
- สาเหตุที่พบบ่อยสุด: โค้ด **สร้าง workbook/ตารางขึ้นใหม่เอง** แทนที่จะ **เปิดไฟล์ template จริงแล้วเติมเฉพาะช่อง** → merged cells / เส้น / ความกว้างคอลัมน์ / ตำแหน่งหมายเหตุ-สรุปยอด-ลายเซ็น หายหมด
- วิธีแก้ (POI) — **เติมลง template ห้ามวาดใหม่**:
  1. วางไฟล์ template จริง (`GL_R_ใบเสนอราคา_format.xls` เดิม หรือ "Template ใบเสนอราคา" ที่ยืนยันแล้ว) ที่ `src/main/resources/templates/quotation_template.xlsx` แล้ว **โหลดด้วย `WorkbookFactory.create(templateStream)`** — **ห้าม `new XSSFWorkbook()` เปล่า**
  2. **เติมเฉพาะเซลล์ dynamic ตาม field map** ใน `quotation-format-and-decisions.md` (B4 วันที่, I4 เลขที่, B5 เรียน, B6 โทร/อีเมล, B8 Project, แถว10+ รายการ, หมายเหตุข้อ 1/2/3/7, I38/I39/I40 สรุปยอด) — ที่เหลือ (หัวบริษัท/หัวตาราง/หมายเหตุคงที่/บล็อกลายเซ็น/form control) **ปล่อยไว้ตาม template ห้ามแตะ**
  3. **reuse cell style เดิม** — set ค่าลงเซลล์เฉย ๆ อย่าสร้าง `CellStyle` ใหม่ทับ ไม่งั้นเส้น/ฟอนต์/การจัดวางหาย
  4. **หลายรายการสินค้า**: insert row ในโซนรายการโดย **copy style จากแถว template** (หรือเตรียม template ให้มีแถวว่างพอ) และ **รักษา merged ranges เดิม** อย่าให้ดันบล็อกหมายเหตุ/สรุปยอด/ลายเซ็นด้านล่างเลื่อนเพี้ยน
  5. **ตรวจเทียบกับ "ตัวอย่าง ใบเสนอราคา"** (ไฟล์ที่กรอกแล้ว) ทุกช่องต้องตรงตำแหน่ง+หน้าตา
- (ฟอนต์ไทยเป็นคนละเรื่อง — ถ้า **แปลงเป็น PDF แล้ว** ตัวอักษรไทยหาย ค่อยฝัง TH Sarabun New แยกต่างหาก ไม่เกี่ยวกับอาการนี้)
- ✅ ไฟล์ที่ออก **หน้าตาตรงกับ template/ตัวอย่างทุกช่อง** (merged/เส้น/ตำแหน่งครบ ไม่ใช่แค่อ่านออก)

---

# F. Flow หลังใบเสนอราคา (ข้อ 13) — Dual-track status

## โมเดลสถานะ 2 แทร็กใน 1 ticket (13.3, 13.6)
**Track P — เอกสาร/การเงิน (`paymentStatus`)**
`QUOTATION_ISSUED`(ทำใบเสนอราคาแล้ว) → `CUSTOMER_CONFIRMED`(ลูกค้ายืนยันการสั่งซื้อ) →
`DEPOSIT_NOTICE_ISSUED`(ออกใบมัดจำแล้ว) → `DEPOSIT_PAID`(จ่ายมัดจำแล้ว) →
`AWAITING_FINAL_PAYMENT`(รอชำระส่วนที่เหลือ) → `FULLY_PAID`(ชำระเงินเรียบร้อย)

**Track F — จัดหา/ขนส่ง (`fulfillmentStatus`)**
`IR_ISSUED`(ออกใบ IR) → `IR_SENT`(ส่ง IR ให้โรงงาน) → `SHIPPING`(กำลังจัดส่งสินค้า) → `GOODS_RECEIVED`(ได้รับสินค้าเรียบร้อย)

> ticket เก็บ 2 field สถานะแยกกัน + เดินคนละจังหวะ

## ขั้นตอน + การกระทำ
- **13.1** หลัง CEO ยืนยันราคา → ออกใบเสนอราคา (`QUOTATION_ISSUED`). ลูกค้าเซ็นกลับ/ส่ง PO → Sales **อัพโหลดไฟล์เซ็น/PO** (`attachments`) → เปลี่ยนเป็น `CUSTOMER_CONFIRMED` → ปลดล็อกปุ่ม **Generate ใบมัดจำ** (template ใบมัดจำ) → `DEPOSIT_NOTICE_ISSUED`
- **13.2** หลังออกใบมัดจำ → ปลดล็อกให้ Import **Generate Import Request แยกรายโรงงาน** (template IR) → `IR_ISSUED` → กดส่งให้โรงงาน → `IR_SENT`
- **13.3** ลูกค้าโอนมัดจำ → Sales **อัพสลิป** → `DEPOSIT_PAID` (แทร็ก P เดินคู่กับแทร็ก F ของ Import)
- **13.4** โรงงาน shipping → Import กด → `SHIPPING`
- **13.5** ลูกค้าได้รับของ → Sales กด `GOODS_RECEIVED` → **Generate ใบแจ้งหนี้ส่วนที่เหลือ** (template) → `AWAITING_FINAL_PAYMENT`
- **13.6** ลูกค้าจ่ายส่วนที่เหลือ → Sales **อัพสลิป** → `FULLY_PAID` → ขึ้นปุ่ม **Generate ข้อความอีเมลถึงฝ่ายบัญชี** เพื่อขอออกใบกำกับภาษีให้ลูกค้า (JavaMailSender)
- **13.7 (ปิดงาน)** เปลี่ยน ticket เป็น `CLOSED` **ได้ก็ต่อเมื่อ** `paymentStatus=FULLY_PAID` **และ** `fulfillmentStatus=GOODS_RECEIVED`

## เอกสารในเฟสนี้ (ใช้ generic `documents` engine เดิม + docType)
- `DEPOSIT_NOTICE` (มี template แล้ว) / `IMPORT_REQUEST` (ใหม่, แยกโรงงาน) / `REMAINING_INVOICE` (ใหม่)
- อีเมลถึงบัญชี = ข้อความ generate + JavaMailSender (ไม่ใช่เอกสารฟอร์ม)

## Attachments ที่เพิ่ม (ใช้ตาราง `attachments` เดิม + `type`)
- `SIGNED_QUOTATION`/`PO` (13.1), `DEPOSIT_SLIP` (13.3), `FINAL_SLIP` (13.6)

- ✅ **13 acceptance**: เดินครบลูป ใบเสนอราคา→ยืนยัน→ใบมัดจำ→IR แยกโรงงาน→จ่ายมัดจำ→shipping→รับของ→ใบแจ้งหนี้ส่วนที่เหลือ→จ่ายครบ→อีเมลบัญชี→ปิดงาน (ปิดได้เมื่อ 2 แทร็กครบ)

---

## สรุปการเปลี่ยน data model
- `ticket_items`: + `unitBasis`(PIECE/SQM), `sqmPerPiece`, `manualPrice`, `manualReason`
- `tickets`: + `paymentStatus`, `fulfillmentStatus` (2 แทร็ก)
- `documents`: docType + `IMPORT_REQUEST`, `REMAINING_INVOICE`
- `attachments`: + type (SIGNED_QUOTATION/PO/DEPOSIT_SLIP/FINAL_SLIP)
- `fx_rates`: + source(BOT/MANUAL), fetchedAt ; job ดึง 18:00
- price proposal: + versioning (แก้ได้/ประวัติ/notify)

## ลำดับแนะนำ
Bug (2,3,11) → UX (1,5,4) → versioning (6) → CEO config (7,8,9,10) → quotation fix (12) → lifecycle (13) ทีละสเตป
