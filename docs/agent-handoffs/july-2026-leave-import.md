# July 2026 Leave Import — GL-R-ERP prod

**Date:** 2026-07-25 · **Branch:** `data/july-2026-leave-import` (off `origin/main`) · **Target:** prod Supabase `tdyzcqzxmhtxpbouewud`, `hr.leave_request`

## What was done
Populated **30 leave records** (15 employees) from manual paper/PDF/image/xls leave forms
(ใบลาหยุด F-HR-020) into prod `hr.leave_request`. Table was previously **empty (0 rows)**.
All rows: `status=APPROVED`, `quota_year=2026`, `reviewer_note='บันทึกย้อนหลังจากใบลากระดาษ (July 2026 import)'`,
`requested_by_id=employee_id`, `reviewed_by_id=NULL`. Guarded with `WHERE NOT EXISTS` on
`(employee_id, leave_type_code, start_date, end_date)` — safely re-runnable.

## Pay decision — ALL PAID (฿0 deduction)
Applied the **company rule** `ประกาศกฏระเบียบเรื่องวันเวลาทำงาน (2561)`, which is authoritative over the app:
- SICK ≤ **30**/yr paid · PERSONAL ≤ **7**/yr paid · VACATION ≤ **6**/yr paid (+1yr carryover)
- Absence *beyond* entitlement → deducted daily.
Every record is within quota → `paid_days=total_days`, `unpaid_days=0`. No one's July pay is docked.

⚠️ **System quota divergence:** app `hr.leave_type.PERSONAL.annual_quota_days = 3`, but company policy = **7**.
The app's balance display will show wrong (possibly negative→0) personal balances until the seed is
corrected to 7. Pay is unaffected (import stored explicit paid/unpaid; payroll reads the row, not the quota).

## ⚠️ July payroll already PROCESSED
`hr.payroll_period` period_id=1 (2026-07) status=`PROCESSED` since 2026-07-05 (before these leaves).
Inserting leave does **not** retro-change processed pay. Since all leave is paid, a re-run would show
**no change** anyway. If a re-run is ever done, unpaid-leave is an HR-reviewed *suggested* input only.

## Sub-day leaves recorded as fractional days
10 of 30 are hour/half-day leaves, stored as fractions (1h=0.13, 2h=0.25, 45min=0.09, half=0.50).
The app engine (`LeaveDayMath.countWorkingDays`) only models WHOLE weekdays and cannot *create* these —
faithful for display/records, but **a follow-up feature is requested** to let employees file sub-day
leave in backend + frontend (separate branch, not done here).

## Flags / judgement calls (pay-neutral)
- **สุริณีย์ 10039, 1–4 Jul:** recorded as VACATION (history-box note "พักร้อน 4 วัน ใช้สิทธิ์ปี 68") though
  checkbox said ลากิจ; 3 working days (Sat 4/7 off for Support Mon–Fri) vs form's "4 วัน".
- **วรรณิภา 10057, 15 Jul SICK:** form ambiguous (time 08:30–09:30 vs "รวม 1 วัน"); recorded 0.13 (1h) per time window. Has hospital appt card.
- **ชนิดา 10040, 21 Jul SICK:** form date box wrote range 21–27/07 but "รวม 1 วัน" → recorded single day 21/7.
- **10027:** form name looked like "รุ่งรัตน์ สิงห์เพชร" (dept Support) but code 10027 = **ชัยวัตน์ สันเพาะ**;
  user confirmed code authoritative. Also his leave is a Saturday (Support = Mon–Fri) — odd but paid ½-day.
- **พัฒวุฒิ 10060:** DB salary 450 (looks daily-rate); leave is paid SICK so pay basis is moot here.
- Saturdays counted as working days for 6-day depts (แม่บ้าน/cleaning=จำเนียร, คลังสินค้า/warehouse=สนั่น).
- 2 evidence attachments NOT entered as leave: med cert (จำเนียร flu), hospital appt card (วรรณิภา 15/7).

## Verification
`SELECT COUNT(*)…` → 30 rows / 30 APPROVED / 0 unpaid / 15 employees / July 21.60 days / sum_unpaid 0.00. ✓

## Artifacts
Scratch: `…/scratchpad/leave-extract/` (records.json, insert_leave.sql, gen_sql.py, converted images).

## Next
1. (Optional) Correct app `PERSONAL` quota seed 3→7 to fix in-app balance display.
2. Sub-day leave feature (backend + frontend) — separate branch, scoping pending.
3. If deductions are ever needed, re-run July payroll in-app (currently ฿0).
