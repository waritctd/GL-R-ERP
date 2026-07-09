# Codex handoff — professional emails across ALL workflows + live tests

Implement on the **`uat` branch**. Goal: every workflow event that notifies a person also **emails**
them; the emails read **professionally** (plain text, not terse machine strings); and a **live test**
fires each through Resend to `fasaiglrhr@gmail.com` for human review.

Decisions already made (do not revisit): **polished plain-text** (NO HTML); **wire the gaps** (the
profile-request flow currently fires no notification at all); do **not** email password resets.

Reuse the existing pattern — commission/OT/leave/ticket already email via
`NotificationService.notify(employeeId, type, subject, body, link, sendEmail=true)` and
`NotificationService.notifyByRole(role, type, subject, body, link, sendEmail=true)`. The email layer is
`th.co.glr.hr.notification.NotificationEmailService` → `th.co.glr.hr.mail.Mailer.send(to, subject, body)`.

---

## 1. Complete flow → email matrix (verify each against the actual services before coding)
✓ already emails · **NEW** = wire it.

| Module (service) | Event (`type`) | Recipient(s) | Status |
|---|---|---|---|
| Commission (`CommissionService`) | SUBMITTED | sales rep | ✓ |
| | PENDING_MANAGER | each sales manager | ✓ |
| | MANAGER_APPROVED | sales rep | ✓ |
| | PENDING_CEO | each CEO approver | ✓ |
| | APPROVED | sales rep + approving manager | ✓ |
| | REJECTED | sales rep | ✓ |
| Overtime (`OvertimeService`) | SUBMITTED | employee + manager | ✓ |
| | MANAGER_APPROVED | employee + each CEO | ✓ |
| | APPROVED | employee + approving manager | ✓ |
| | REJECTED | employee | ✓ |
| Leave (`LeaveService`) | APPROVED / REJECTED (manual) | employee | ✓ |
| | AUTO_APPROVED | employee | ✓ |
| | **AUTO_APPROVED (manager heads-up)** | manager | **NEW — flip `sendEmail=false`→`true`** in `notifyAfterSubmit` (manager branch) |
| | AUTO_REJECTED | employee | ✓ |
| Ticket (`TicketService`) | SUBMITTED (create/submit) | import + ceo roles | ✓ |
| | PRICE_PROPOSED | ceo role | ✓ |
| | APPROVED | ticket creator | ✓ |
| | REJECTED | import role | ✓ |
| Deposit notice (`DepositNoticeService`) | REVISION_REQUESTED | ceo or import role | ✓ |
| Profile request (`ProfileRequestService`) | **PROFILE_REQUEST_SUBMITTED** | HR role | **NEW** |
| | **PROFILE_REQUEST_APPROVED** | requesting employee | **NEW** |
| | **PROFILE_REQUEST_REJECTED** | requesting employee | **NEW** |

Out of scope: password reset (never email the temp password); factory email (separate external channel).

## 2. Central professional plain-text wrapper (one change → every email benefits)
Leave the in-app bell rows and the terse per-event `body` text **unchanged**; only wrap the **email**.
- **Config:** add `app.mail.app-base-url: ${APP_MAIL_APP_BASE_URL:https://demo-glr-git-uat-waritctds-projects.vercel.app}`
  in `application.yml` (portal URL for the CTA link; emails carry no link today).
- **Recipient name:** add `NotificationRepository.findEmployeeContact(employeeId)` → (name, email) in one
  query (today only `findEmployeeEmail` exists). `NotificationService` looks up name+email and threads
  **name + link** into `NotificationEmailService.send(...)` (currently gets only `to/subject/body`).
- **Template** (plain text; assemble in `NotificationEmailService` before `mailer.send`):
  ```
  เรียน คุณ{recipientName},

  {body}

  ดูรายละเอียดในระบบ: {appBaseUrl}{link}

  ขอแสดงความนับถือ
  ระบบบริหารงานบุคคล GL&R (GL&R HR Portal)
  — อีเมลฉบับนี้ส่งจากระบบอัตโนมัติ กรุณาอย่าตอบกลับ
  ```
  Fallbacks: `เรียน ท่านผู้ใช้งาน,` when no name; omit the "ดูรายละเอียด" line when `link` is null. The
  existing UAT "[Redirected for testing … originally for employee #X]" annotation must still append when
  `app.mail.override-to` is set (keep that behavior).
- **Subject:** brand-prefix centrally, e.g. `[GL&R HR] {existing title}` (don't touch call-site titles).
- Transport stays plain text (`ResendMailer`/`SmtpMailer` unchanged). No HTML.

## 3. Wire the profile-request gap (`ProfileRequestService`)
- Inject `NotificationService`. create → `notifyByRole("hr", "PROFILE_REQUEST_SUBMITTED", …, "/requests", true)`;
  approve → `notify(employeeId, "PROFILE_REQUEST_APPROVED", …, "/my-requests", true)`; reject →
  `notify(employeeId, "PROFILE_REQUEST_REJECTED", … + reason, "/my-requests", true)`.
- **Add `"hr"` to `NotificationRepository.findActiveEmployeeIdsByRole`** (`d.source_code ILIKE 'HR%'`) —
  it currently maps only import/ceo/sales.
- Professional Thai messages, e.g. submit → `พนักงาน {name} ยื่นคำขอแก้ไขข้อมูล ({fieldLabel}) รอการตรวจสอบ`;
  approve → `คำขอแก้ไขข้อมูล ({fieldLabel}) ของคุณได้รับการอนุมัติแล้ว`; reject → `… ไม่ได้รับการอนุมัติ: {reason}`.

## 4. Tests
- **Backend unit/IT:** add `NotificationServiceTest` coverage for the name+link threading; add
  `ProfileRequestServiceTest` cases asserting notify/notifyByRole is called on submit/approve/reject.
- **Deterministic (Mailpit, default `./run.sh`):** add `MAIL-07/08/09` for the new profile-request
  emails (captured, from `job@glr.co.th`, right recipient/subject) **and** a
  `test_email_template_structure` that fires one action and asserts the captured body contains the
  greeting (`เรียน`), the CTA link (`{app-base-url}/…`), and the signature (`ระบบบริหารงานบุคคล GL&R`).
- **Live smoke (Resend → `fasaiglrhr@gmail.com`, `./run.sh --live-email`):** extend
  `test_live_email_smoke.py` to fire **one real email per matrix event** (≈21) so every professional
  variant lands in the inbox. Each `@pytest.mark.live_email` (excluded from the default pass/fail run).

## Definition of done
- Verify every request body / endpoint / recipient against the actual services before asserting.
- `cd backend && ./mvnw -B clean verify` green (Jacoco met).
- `cd tools/uat-tests && ./run.sh` green — MAIL-01..09 pass, template-structure assertion passes, all
  69 UAT-IDs still represented; re-runs identical.
- `APP_MAIL_RESEND_API_KEY=re_… LIVE_EMAIL_TO=fasaiglrhr@gmail.com ./run.sh --live-email` delivers ≈21
  professional emails to that inbox (greeting + contextual body + working portal link + GL&R signature).
- `uat`-branch overlay only — never merge to `main`. A red is a real bug; report it, don't weaken it.
