"""
import_engine.py — engine ตัวเดียว ขับด้วย profile (ไม่มี logic เฉพาะโรงงานใน code)
พิสูจน์ว่า profile-driven ใช้ได้กับทุก format ก่อนไปเขียน Java

รัน: python3 import_engine.py
"""
import json, re, sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
import openpyxl

TARGETS = ["product_code","grade","collection","product_name","color","surface",
           "size_raw","thickness_mm","price","currency","unit",
           "pcs_per_box","sqm_per_box","kg_per_box","barcode"]

UNIT_MAP = {"mq":"per_sqm","m2":"per_sqm","sqm":"per_sqm","m²":"per_sqm",
            "pc":"per_piece","pz":"per_piece","pieza":"per_piece","pcs":"per_piece",
            "caja":"per_box","box":"per_box","cj":"per_box",
            "ml":"per_linear_m","lm":"per_linear_m"}

def norm_header(s):
    return re.sub(r"\s+", " ", str(s or "")).strip().lower()

def to_decimal(v, fmt="eu"):
    """แปลงตัวเลขทุกทรง: '55.9000005', ' 14,240', '1.120,00', '1,234.56'"""
    if v is None or v == "": return None
    if isinstance(v, (int, float)):
        return Decimal(str(v)).quantize(Decimal("0.0001"))
    s = str(v).strip().replace(" ", "").replace("\xa0", "")
    s = re.sub(r"[^\d.,\-]", "", s)
    if not s: return None
    if "," in s and "." in s:                      # มีทั้งคู่ -> ตัวหลังสุด = decimal
        s = s.replace(".", "").replace(",", ".") if s.rfind(",") > s.rfind(".") else s.replace(",", "")
    elif "," in s:
        s = s.replace(",", ".") if fmt == "eu" else s.replace(",", "")
    try:
        return Decimal(s).quantize(Decimal("0.0001"))
    except (InvalidOperation, ValueError):
        return None

def parse_size(raw, style=None):
    """ '600x1200' '60x120' ' 150x600' '20 x 20' '598X598X18' \"15'8X31'6\" -> (w,h,t) mm"""
    if not raw: return (None, None, None)
    s = str(raw).strip()
    if style == "apostrophe_decimal":              # Vives: 15'8 = 15.8
        s = re.sub(r"(\d)'(\d)", r"\1.\2", s)
    nums = re.findall(r"\d+(?:[.,]\d+)?", s.replace(" ", ""))
    if not nums: return (None, None, None)
    vals = [float(n.replace(",", ".")) for n in nums[:3]]
    # หน่วย: ค่าน้อย (<300) มักเป็น cm -> แปลงเป็น mm
    def to_mm(x): return x * 10 if x < 300 else x
    w = to_mm(vals[0]) if len(vals) > 0 else None
    h = to_mm(vals[1]) if len(vals) > 1 else None
    t = vals[2] if len(vals) > 2 else None         # มิติที่ 3 = ความหนา (mm อยู่แล้ว)
    return (w, h, t)

def canon_unit(u, default=None):
    if not u: return default
    return UNIT_MAP.get(str(u).strip().lower(), default or "unknown")

def load_sheet(path, sheet, header_row):
    wb = openpyxl.load_workbook(path, data_only=True, read_only=True)
    ws = wb[sheet]
    rows = list(ws.iter_rows(min_row=header_row, values_only=True))
    wb.close()
    if not rows: return [], []
    header = [norm_header(c) for c in rows[0]]
    return header, rows[1:]

def build_index(header, prof, sheet_name):
    """map ชื่อฟิลด์กลาง -> ตำแหน่งคอลัมน์ (รองรับ aliases + trim)"""
    idx = {}
    aliases = prof.get("column_aliases", {})
    for tgt, colname in prof.get("columns", {}).items():
        cands = [colname] + aliases.get(tgt, [])
        for c in cands:
            c = norm_header(c)
            if c in header:
                idx[tgt] = header.index(c); break
    for tgt, alist in aliases.items():             # alias-only fields (เช่น surface ของ REFIN)
        if tgt in idx: continue
        for c in alist:
            c = norm_header(c)
            if c in header:
                idx[tgt] = header.index(c); break
    return idx

def import_file(path, prof):
    fmt = prof.get("number_format", "eu")
    defaults = prof.get("defaults", {})
    out, errors = [], []
    for sh in prof["sheets"]:
        try:
            header, rows = load_sheet(path, sh["name"], sh["header_row"])
        except Exception as e:
            errors.append(f"[{sh['name']}] เปิดชีตไม่ได้: {e}"); continue
        idx = build_index(header, prof, sh["name"])

        # ราคา: rule พิเศษ (Equipe เลือกคอลัมน์ / Vives first-non-empty)
        rule = prof.get("price_column_rule")
        price_cols = {}
        if rule:
            keys = rule.get("options") or list(rule.get("map", {}).keys())
            for k in keys:
                if norm_header(k) in header: price_cols[k] = header.index(norm_header(k))

        last = {}
        for rno, row in enumerate(rows, start=sh["header_row"] + 1):
            if not any(c not in (None, "") for c in row): continue
            def get(f):
                i = idx.get(f)
                return row[i] if i is not None and i < len(row) else None

            rec = {t: get(t) for t in TARGETS}

            # fill-down (Bode series)
            for f in prof.get("fill_down", []):
                tgt = next((t for t, c in prof["columns"].items() if c == f), None)
                if tgt:
                    if rec.get(tgt) in (None, ""): rec[tgt] = last.get(tgt)
                    else: last[tgt] = rec[tgt]

            # ราคาตาม rule
            unit_override = None
            if rule and rule["type"] == "choose":
                col = rule["selected"]
                rec["price"] = row[price_cols[col]] if col in price_cols else None
                rec["price_variants"] = {k: str(row[i]) for k, i in price_cols.items() if row[i] not in (None, "")}
            elif rule and rule["type"] == "first_non_empty":
                for col, u in rule["map"].items():
                    if col in price_cols and row[price_cols[col]] not in (None, ""):
                        rec["price"] = row[price_cols[col]]; unit_override = u; break

            price = to_decimal(rec.get("price"), fmt)
            if price is None or price <= 0:
                errors.append(f"[{sh['name']}] r{rno}: ไม่มีราคา"); continue

            code = rec.get("product_code")
            code = str(code).strip() if code not in (None, "") else None
            if not code and not prof.get("allow_missing_code"):
                errors.append(f"[{sh['name']}] r{rno}: ไม่มีรหัสสินค้า"); continue

            # split code (Bode: 2 รหัสใน 1 เซลล์) -> แตกหลายแถว
            codes = [code]
            sp = prof.get("split_column", {})
            if code and "code" in sp:
                codes = [c.strip() for c in code.split(sp["code"]) if c.strip()]

            size_raw = rec.get("size_raw")
            if not size_raw and prof.get("size_from"):
                size_raw = rec.get(prof["size_from"])
            w, h, t = parse_size(size_raw, prof.get("size_format"))

            unit = unit_override or canon_unit(rec.get("unit"), defaults.get("unit"))
            cur = rec.get("currency") or defaults.get("currency") or prof["default_currency"]
            pcs = to_decimal(rec.get("pcs_per_box"), fmt)
            sqm = to_decimal(rec.get("sqm_per_box"), fmt)
            sqm_per_piece = (sqm / pcs) if (pcs and sqm and pcs > 0) else None

            for c in codes:
                out.append({
                    "factory": prof["factory"],
                    "product_code": c,
                    "grade": (str(rec["grade"]).strip() if rec.get("grade") else None),
                    "collection": (str(rec["collection"]).strip() if rec.get("collection") else None),
                    "product_name": (str(rec["product_name"]).strip() if rec.get("product_name") else None),
                    "color": (str(rec["color"]).strip() if rec.get("color") else None),
                    "surface": (str(rec["surface"]).strip() if rec.get("surface") else None),
                    "size_raw": (str(size_raw).strip() if size_raw else None),
                    "width_mm": w, "height_mm": h,
                    "thickness_mm": t or float(to_decimal(rec.get("thickness_mm"), fmt) or 0) or None,
                    "price": price, "currency": str(cur).strip().upper()[:3], "price_unit": unit,
                    "pcs_per_box": pcs, "sqm_per_box": sqm,
                    "sqm_per_piece": round(sqm_per_piece, 4) if sqm_per_piece else None,
                    "kg_per_box": to_decimal(rec.get("kg_per_box"), fmt),
                    "barcode": (str(rec["barcode"]).strip() if rec.get("barcode") else None),
                    "price_variants": rec.get("price_variants"),
                    "source_sheet": sh["name"], "source_row": rno,
                })
    return out, errors


FILES = {
    "Panaria": "Panaria_Price_List_Euro_2026.xlsx",
    "LEA": "LEA_price_list_EURO_2026.xlsx",
    "Padana": "Padana_fixed.xlsx",
    "CDE": "CDE_price_list_Euro_2026.xlsx",
    "CITY": "CITY_Price-List_EURO_2026.xlsx",
    "REFIN": "REFIN_Price-List_EUR_2026.xlsx",
    "Equipe": "Equipe_EXTRACOMUNITARIOS.xlsx",
    "Vives": "Vives_articulosVives_7A_febrero2026.xlsx",
    "Bode": "Bode_new_quotation_GLR_260623.xlsx",
}

if __name__ == "__main__":
    profiles = {p["factory"]: p for p in json.load(open("factory_profiles.json"))["profiles"]}
    all_rows, summary = [], []
    for fac, fn in FILES.items():
        rows, errs = import_file(fn, profiles[fac])
        all_rows += rows
        cur = {r["currency"] for r in rows}
        units = {r["price_unit"] for r in rows}
        summary.append((fac, len(rows), len(errs), ",".join(sorted(cur)), len(units)))

    print(f"{'โรงงาน':<10} {'เข้า DB':>8} {'ข้าม':>6}  {'สกุล':<6} หน่วย")
    print("-" * 52)
    for f, n, e, c, u in summary:
        print(f"{f:<10} {n:>8} {e:>6}  {c:<6} {u}")
    print("-" * 52)
    print(f"{'รวม':<10} {sum(s[1] for s in summary):>8} {sum(s[2] for s in summary):>6}")

    # เขียน CSV ให้ดูของจริง
    import csv
    cols = ["factory","product_code","grade","collection","product_name","color","surface",
            "size_raw","width_mm","height_mm","thickness_mm","price","currency","price_unit",
            "pcs_per_box","sqm_per_box","sqm_per_piece","kg_per_box","barcode","source_sheet","source_row"]
    with open("/mnt/user-data/outputs/catalog_import_result.csv", "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in all_rows: w.writerow(r)
    print("\n-> /mnt/user-data/outputs/catalog_import_result.csv")
