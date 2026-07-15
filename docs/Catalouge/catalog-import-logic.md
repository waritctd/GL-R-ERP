# Logic: นำเข้า Price List (Excel) → PostgreSQL → หน้า Sales

> วิเคราะห์จากไฟล์จริง 9 โรงงาน (~22,000 แถว) — ทุกข้อมี**หลักฐานจากไฟล์**
> ปลายทาง: GUI ให้ CEO/Import อัปโหลดไฟล์เอง แล้ว Sales ค้นหาสินค้า+ราคาได้

---

## 1. ผลสำรวจไฟล์จริง (ฐานของ logic ทั้งหมด)

| โรงงาน | ชีต | แถว | หัวตาราง | ภาษา | สกุล/หน่วย | หมายเหตุสำคัญ |
|---|---|---|---|---|---|---|
| **Panaria** | 1 (PAN) | 1,421 | **แถว 2** | IT | EUR (คอลัมน์ DIVISA) | มี DATA PRZ (วันที่ราคา) |
| **LEA** | 1 | 1,627 | แถว 1 | IT | EUR / MQ | โครงคล้าย Panaria |
| **Padana** | 1 | **9,077** | แถว 1 | IT | EUR / MQ | ⚠ มี **Scelta (เกรด)** |
| **CDE** | 1 | 2,641 | แถว 1 | EN/IT | EUR (DIVISA) / PC | มี BARCODE, INTRASTAT |
| **CITY** | **2** | 75 | แถว 1 | EN | EUR / mq,ml | 2 ชีต (Contract/Outdoor) |
| **REFIN** | **7** | 1,606 | แถว 1 | EN | EUR / mq | ⚠ **หัวตารางไม่เหมือนกันทุกชีต** |
| **Equipe** | 1 | 2,125 | แถว 1 | ES | EUR / PZ | ⚠ **ราคา 3 คอลัมน์** |
| **Vives** | 1 | **4,622** | แถว 1 | ES | EUR / Pieza,Caja | ⚠ **ราคา 2 คอลัมน์สลับกัน** |
| **Bode** | 1 | 62 | **แถว 11** | EN/CN | **USD** / M2 | ⚠ ไม่ใช่ price list — เป็น **quotation sheet** |

**สรุป: 8/9 เป็นตารางแบน (header + data) → เขียน parser ตัวเดียวใช้ได้หมด. Bode 1 ไฟล์เป็นข้อยกเว้น**

---

## 2. ⚠️ กับดัก 5 ข้อ (ตรวจพบจากข้อมูลจริง — ถ้าไม่แก้ importer พังแน่)

### 2.1 รหัสสินค้า **ไม่ unique** (Padana)
- `Articolo` ซ้ำ **2,543 แถว** จาก 9,076 → เพราะมีคอลัมน์ **`Scelta`** (เกรดสินค้า A01=เกรด1, A02=เกรด2) **ราคาต่างกันคนละครึ่ง**
- ตัวอย่างจริง: `0400012` → A01 = €43 / A02 = €21
- ✅ **unique key ต้องเป็น `(factory, product_code, grade, price_list_version)`** ไม่ใช่ product_code เดี่ยว ๆ
- (คู่ `Articolo+Scelta` → unique 9,076/9,076 ✓)

### 2.2 ราคามีหลายคอลัมน์ ต้องเลือก (Equipe, Vives)
- **Equipe**: `Precio Pallet` / `Precio Picking` / `Precio Sueltas` (3 ราคา = ซื้อยกพาเลท/หยิบ/แยกชิ้น)
  → ต้องให้ CEO **เลือกว่าใช้คอลัมน์ไหนเป็นราคาตั้งต้น** (แนะนำเก็บทั้ง 3 ใน `price_variants` JSONB แล้วชี้ว่าอันไหนคือ `price`)
- **Vives**: `PREPIEZA` (ราคา/ชิ้น) กับ `PREMETRO` (ราคา/ตร.ม.) — **ไม่เคยมีพร้อมกัน (0 แถว)** มีอันใดอันหนึ่ง
  → logic: ถ้ามี PREPIEZA → `price_unit=per_piece` ; ถ้ามี PREMETRO → `price_unit=per_sqm`
  → ⚠ **อย่าเดาหน่วยจากคอลัมน์ `UMEDIDA`** เพราะไม่ตรงกัน (Caja 35 แถวใช้ PREPIEZA, Pieza 151 แถวใช้ PREMETRO) — **ให้ยึดว่าคอลัมน์ไหนมีค่า**

### 2.3 หลายชีต + หัวตารางไม่เหมือนกัน (REFIN)
- REFIN 7 ชีต: `Collections`(1606) `RELIEFS_recap` `Large-Slabs_recap` `OUT2.0_recap` `OUT2.0_accessories` `Trim-Tiles` `Balneo-Project`
- หัวตาราง**ต่างกัน**: `OUT2.0_accessories` ไม่มี Size ; **`Trim-Tiles` ไม่มีคอลัมน์ Code เลย** ; `Balneo-Project` ใช้ COLLECTION/ITEM/SURFACE
- ✅ ต้อง map **ต่อชีต ไม่ใช่ต่อไฟล์** และรองรับกรณี **ไม่มีรหัสสินค้า** (สร้าง surrogate key จาก collection+item+size)

### 2.4 หัวตารางไม่ได้อยู่แถว 1 (Panaria=แถว2, Bode=แถว11)
- ✅ ต้อง **auto-detect แถว header** (สแกน 20 แถวแรก หาแถวที่ match คำที่รู้จักมากสุด) ไม่ใช่ fix ที่แถว 1

### 2.5 Bode ไม่ใช่ price list (เป็น quotation sheet)
- หัว `QUOTATION SHEET`, `To: G.L.&R.`, ราคาเป็น **USD/M2 FOB**
- คอลัมน์ `series` **เว้นว่างแบบ merged** ต้อง **fill-down** (r12 Limestone → r13,r14 ว่าง = Limestone เหมือนกัน)
- คอลัมน์ `code` มี **2 รหัสในเซลล์เดียว** คั่นด้วย comma (`BVLE10426KGA, BVLE20326KGA`)
- ✅ ทำเป็น **profile แยก** (`bode_quotation`) + fill-down + split code เป็นหลายแถว + `currency=USD`

---

## 3. ปัญหา normalize ข้อมูล (จากค่าจริง)

| เรื่อง | ค่าที่เจอจริง | วิธี normalize |
|---|---|---|
| **ขนาด** | `600x1200`(mm), `60x120`(cm), ` 150x600`(เว้นวรรคนำ), `20 x 20`, `120X120`(X ใหญ่), `598X598X18`(3 มิติ), **`15'8X31'6`** (Vives ใช้ `'` เป็นทศนิยม = 15.8×31.6) | parse เป็น `width_mm`,`height_mm`,`thickness_mm` + เก็บ `size_raw` ไว้ด้วย; แปลง cm→mm; `'`→`.` |
| **ตัวเลข** | `55.90000000000005` (float error), `" 14,240"` (ES: comma decimal + เว้นวรรค), `"1.120,00"` | trim → ถ้า ES/IT: comma=decimal, dot=thousands → `Decimal` **(อย่าใช้ float กับราคา — ใช้ NUMERIC ใน PG)** |
| **หน่วย** | `MQ`,`mq`,`M2`,`PC`,`PZ`,`Pieza`,`Caja`,`ml`,`lm` | map → `per_sqm` / `per_piece` / `per_box` / `per_linear_m` |
| **สกุลเงิน** | EUR (8 โรงงาน), **USD** (Bode) | เก็บ `currency` ต่อแถว **ห้าม hardcode EUR** |
| **หัวตารางลวง** | REFIN ไฟล์ปี 2026 แต่หัวเขียน `PRICE 2025` | ❌ **อย่า parse ปีจากชื่อหัวคอลัมน์** — ใช้ปีจาก metadata การอัปโหลดแทน |

---

## 4. สถาปัตยกรรม: Profile-driven Importer (หัวใจของ logic)

**อย่าเขียน parser 9 ตัว** → เขียน **engine ตัวเดียว + "profile" ต่อโรงงาน** (เก็บใน DB แก้ได้ผ่าน GUI ไม่ต้อง deploy ใหม่)

```
อัปโหลดไฟล์
   ↓
[1] ตรวจไฟล์ (.xls→.xlsx ด้วย LibreOffice, กัน stylesheet เสีย*)
   ↓
[2] เลือก/เดา profile ของโรงงาน  ──→ ถ้าไม่มี: GUI ให้ map คอลัมน์เอง แล้ว "บันทึกเป็น profile ใหม่"
   ↓
[3] auto-detect แถว header (ต่อชีต)
   ↓
[4] map คอลัมน์ → ฟิลด์กลาง (ตาม profile)
   ↓
[5] normalize (ขนาด/ตัวเลข/หน่วย/สกุลเงิน) + validate ทีละแถว
   ↓
[6] STAGING TABLE + รายงาน: ผ่านกี่แถว / error กี่แถว / ราคาเปลี่ยนกี่รายการ
   ↓
[7] ผู้ใช้กด "ยืนยัน" → commit เข้าตารางจริง (เวอร์ชันใหม่)
```
> *Padana เจอจริง: openpyxl อ่านไม่ได้ (`could not read stylesheet`) → ต้อง re-save ผ่าน LibreOffice ก่อน = ต้องมี fallback นี้ใน pipeline

**Profile หน้าตาแบบนี้** (เก็บเป็น JSONB ในตาราง `import_profiles`):
```json
{
  "factory": "Padana",
  "sheets": [{"name": "Sheet1", "header_row": "auto"}],
  "columns": {
    "product_code": "Articolo", "collection": "Descrizione Serie",
    "product_name": "Descrizione Articolo", "size_raw": "Formato",
    "grade": "Scelta", "price": "Prezzo", "unit": "Unità",
    "pcs_per_box": "PZ/SC", "sqm_per_box": "MQ/SC", "barcode": "EAN"
  },
  "defaults": {"currency": "EUR"},
  "number_format": "eu",
  "price_column_rule": null
}
```
- **Equipe**: `"price_column_rule": {"type": "choose", "options": ["Precio Pallet","Precio Picking","Precio Sueltas"], "selected": "Precio Pallet"}`
- **Vives**: `"price_column_rule": {"type": "first_non_empty", "map": {"PREPIEZA": "per_piece", "PREMETRO": "per_sqm"}}`
- **Bode**: `{"header_row": 11, "fill_down": ["series"], "split_column": {"code": ","}, "defaults": {"currency": "USD", "unit": "per_sqm"}}`

---

## 5. Schema PostgreSQL

```sql
-- โรงงาน
CREATE TABLE factories (
  id BIGSERIAL PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,          -- Panaria, Padana, Vives...
  country TEXT,                        -- IT, ES, CN  (ใช้กับภาษี/สูตรคำนวณของ CEO)
  default_currency TEXT NOT NULL       -- EUR / USD
);

-- profile การ map คอลัมน์ (แก้ผ่าน GUI ได้)
CREATE TABLE import_profiles (
  id BIGSERIAL PRIMARY KEY,
  factory_id BIGINT REFERENCES factories(id),
  config JSONB NOT NULL,
  updated_by BIGINT, updated_at TIMESTAMPTZ DEFAULT now()
);

-- รอบการอัปโหลด (versioning ราคา — ราคาขึ้นทุกปี ต้องเก็บประวัติ)
CREATE TABLE price_list_versions (
  id BIGSERIAL PRIMARY KEY,
  factory_id BIGINT REFERENCES factories(id),
  label TEXT,                          -- "Euro 2026"
  source_file TEXT,
  effective_from DATE,
  status TEXT NOT NULL,                -- DRAFT | ACTIVE | ARCHIVED
  uploaded_by BIGINT, uploaded_at TIMESTAMPTZ DEFAULT now(),
  row_count INT, error_count INT
);

-- staging (ตรวจก่อน commit)
CREATE TABLE product_price_staging (LIKE product_prices INCLUDING ALL);

-- ตารางหลักที่ Sales ใช้
CREATE TABLE product_prices (
  id BIGSERIAL PRIMARY KEY,
  factory_id BIGINT NOT NULL REFERENCES factories(id),
  version_id BIGINT NOT NULL REFERENCES price_list_versions(id),
  product_code TEXT,                   -- อาจ NULL (REFIN Trim-Tiles!)
  grade TEXT,                          -- Padana Scelta (A01/A02)
  collection TEXT,                     -- ซีรีส์/รุ่น
  product_name TEXT,
  color TEXT,
  surface TEXT,                        -- finish/เนื้อผิว
  size_raw TEXT,                       -- เก็บของเดิมไว้เสมอ (audit)
  width_mm NUMERIC, height_mm NUMERIC, thickness_mm NUMERIC,
  price NUMERIC(14,4) NOT NULL,        -- ❗ NUMERIC ไม่ใช่ float
  currency CHAR(3) NOT NULL,
  price_unit TEXT NOT NULL,            -- per_sqm | per_piece | per_box | per_linear_m
  sqm_per_piece NUMERIC,               -- ⭐ ให้ Sales สลับ แผ่น↔ตร.ม. ได้ (ตาม change-request ข้อ 1)
  pcs_per_box NUMERIC, sqm_per_box NUMERIC, kg_per_box NUMERIC,
  price_variants JSONB,                -- Equipe: {pallet, picking, sueltas}
  attributes JSONB,                    -- barcode, intrastat, ฯลฯ
  source_sheet TEXT, source_row INT,   -- ย้อนกลับไปหาต้นทางได้
  CONSTRAINT uq_price UNIQUE (version_id, product_code, grade, size_raw, surface)
);

CREATE INDEX idx_pp_active ON product_prices(factory_id, version_id);
CREATE INDEX idx_pp_search ON product_prices
  USING GIN (to_tsvector('simple', coalesce(collection,'')||' '||coalesce(product_name,'')||' '||coalesce(color,'')));
CREATE INDEX idx_pp_code ON product_prices(product_code);
```

**หลักการสำคัญ:**
- **`sqm_per_piece`** คำนวณตอน import (จาก `sqm_per_box / pcs_per_box`) → หน้า Sales สลับหน่วยแผ่น↔ตร.ม. ได้ทันที (แก้ change-request ข้อ 1)
- **versioning ราคา**: อัปไฟล์ใหม่ = version ใหม่ (`DRAFT`→`ACTIVE`, ตัวเก่า→`ARCHIVED`) ไม่ทับของเก่า → ใบเสนอราคาเก่ายังอ้างราคาเดิมได้
- **`price` เป็น NUMERIC** ห้าม float (เจอ `55.90000000000005` ในไฟล์จริง)

---

## 6. GUI อัปโหลด (CEO / Import)
1. เลือกโรงงาน + อัปโหลดไฟล์ (.xls/.xlsx)
2. ระบบ preview: ตรวจ header ที่เจอ + **ตาราง map คอลัมน์** (เติมอัตโนมัติจาก profile เดิม, แก้ได้)
3. ถ้ามีหลายชีต → เลือกชีตที่จะ import (REFIN 7 ชีต / CITY 2 ชีต)
4. ถ้ามีราคาหลายคอลัมน์ (Equipe) → เลือกว่าใช้คอลัมน์ไหน
5. กด **Validate** → เข้า staging → รายงาน: ผ่าน X / error Y (ดู error รายแถวได้) / **ราคาเปลี่ยนจากเวอร์ชันก่อน Z รายการ**
6. กด **Commit** → เป็น version `ACTIVE`
7. บันทึก mapping กลับเข้า `import_profiles` → ปีหน้าอัปไฟล์เดิม **ไม่ต้อง map ใหม่**

## 7. หน้า Sales ใช้ยังไง
- ค้นหา: โรงงาน / รุ่น(collection) / สี / ผิว / ขนาด / รหัส (ใช้ GIN index)
- เลือกสินค้า → ได้ `factory` อัตโนมัติ (→ ใช้แยก Import Request รายโรงงาน ตาม change-request ข้อ 13.2)
- แสดงราคา raw + สกุลเงินโรงงาน → ส่งเข้าสูตรคำนวณของ CEO (FX/freight/duty) ต่อ
- แสดง `sqm_per_piece` เพื่อสลับหน่วยแผ่น↔ตร.ม.

## 8. ลำดับทำ (milestones)
- **C1** schema + factories/profiles/versions + seed 9 โรงงาน
- **C2** import engine (detect header, map, normalize ขนาด/ตัวเลข/หน่วย) + fallback LibreOffice re-save
- **C3** staging + validate + รายงาน error/diff → commit + versioning
- **C4** profile 9 โรงงาน (รวมเคสพิเศษ: Padana grade, Equipe 3 ราคา, Vives 2 คอลัมน์, REFIN 7 ชีต, Bode quotation)
- **C5** GUI อัปโหลด/map/preview (CEO+Import)
- **C6** หน้าค้นหาสินค้าของ Sales + ผูกเข้าฟอร์มคำขอราคา
- ✅ acceptance: อัปครบ 9 ไฟล์ → ~22,000 แถวเข้า DB, Padana ได้ 9,076 แถว (ไม่ชนกัน), Bode ราคาเป็น USD, Sales ค้นเจอ+รู้โรงงาน

---

# 9. ✅ ผลพิสูจน์จริง (รัน import ทั้ง 9 ไฟล์ด้วย engine ตัวเดียว + profile)

**engine ไม่มี logic เฉพาะโรงงานเลย — ทุกอย่างมาจาก profile JSON** (`factory_profiles.json`)

| โรงงาน | เข้า DB | ข้าม | สกุลเงิน | หมายเหตุ |
|---|---:|---:|---|---|
| Panaria | 1,419 | 0 | EUR | header แถว 2 ✓ |
| LEA | 1,626 | 0 | EUR | |
| **Padana** | **9,076** | 0 | EUR | **เกรด A01/A02 แยกแถวถูกต้อง (0400012: €43 / €21)** ✓ |
| CDE | 1,640 | 0 | EUR | (ไฟล์มี 2,640 แถว แต่ 1,000 แถวท้ายเป็นแถวว่าง = ถูกต้อง) |
| CITY | 83 | 0 | EUR | 2 ชีตรวมกัน |
| REFIN | 1,862 | 5 | EUR | 7 ชีต, Trim-Tiles ไม่มี code ก็เข้าได้ ✓ |
| Equipe | 2,124 | 0 | EUR | ใช้ Precio Pallet, เก็บอีก 2 ราคาไว้ |
| Vives | 4,617 | 1 | EUR | **ขนาด 15'8X31'6 → 158×316 mm** ✓ |
| **Bode** | **215** | 4 | **USD** | fill-down + แตก code หลายรหัส/เซลล์ ✓ |
| **รวม** | **22,662** | **10** | | **error rate 0.04%** |

**ตรวจแล้วผ่านทุกเคสยาก:**
- Padana เกรด A01/A02 → คนละแถว ราคาต่างกันถูกต้อง (ไม่ชนกัน)
- Vives `15'8X31'6` → parse เป็น 158×316 mm, หน่วยราคาอ่านจากคอลัมน์ที่มีค่า (ไม่ใช่ UMEDIDA)
- REFIN Trim-Tiles (ไม่มีคอลัมน์ Code) → เข้าได้ด้วย `allow_missing_code`
- Bode → USD, fill-down series, แตก 2 รหัสใน 1 เซลล์เป็นหลายแถว
- **`sqm_per_piece` คำนวณได้ 98%** (22,251/22,662) → Sales สลับหน่วยแผ่น↔ตร.ม. ได้จริง
- หน่วยราคา: per_sqm 13,852 / per_piece 8,357 / per_linear_m 288 / unknown 165

## ⚠️ ข้อจำกัดที่เจอ (ต้องบอก Claude Code)
1. **fill-down ต้องทำระดับชีต ไม่ใช่ระดับไฟล์** — REFIN ชีต `Balneo-Project` มี COLLECTION/ITEM/SIZE เว้นว่างแบบ merged (ดู `fill_down_per_sheet` ใน profile) แต่ชีตอื่นของ REFIN ไม่ต้อง fill-down
2. **165 แถว unit = unknown** → ต้องมีหน้าให้ผู้ใช้ระบุหน่วยเอง หรือ map เพิ่มใน GUI
3. **Equipe ไม่มีคอลัมน์ขนาด** → parse จาก Descripción (`"1,2X20 JOLLY ALTEA ASH"`) ความแม่นยำต่ำกว่าโรงงานอื่น ควรให้ตรวจใน staging
4. **Padana เปิดด้วย openpyxl ไม่ได้** (stylesheet เสีย) → pipeline ต้อง re-save ผ่าน LibreOffice ก่อนเสมอ

## ไฟล์ที่ให้มา
- `factory_profiles.json` — profile 9 โรงงาน (seed ลง `import_profiles`) **แก้ผ่าน GUI ได้ ไม่ต้องแตะโค้ด**
- `import_engine.py` — engine อ้างอิง (Python) ให้ Claude Code แปลงเป็น Java ตาม logic นี้
- `catalog_import_result.csv` — ผลลัพธ์จริง 22,662 แถว (ใช้เป็น expected output เทียบงาน Claude Code)
