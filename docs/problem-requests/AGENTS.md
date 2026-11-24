# Problem requests

How we handle what staff send through **แจ้งปัญหา / ขอฟีเจอร์**, from the first report to the release mail. The root [AGENTS.md](../../AGENTS.md) still applies. This file adds only what is specific to requests.

**This repository is public.** Never copy a request's text, a person's name or email, a customer, or a real document number into a commit, PR, spec, test or this file. Refer to a request by its number only.

## Where things live

| What | Where |
|---|---|
| Staff form and their own list | มีอะไรใหม่ (`/whats-new`), `frontend/src/features/whatsNew/FeedbackTab.jsx` |
| Admin inbox (แท็บ เรื่องที่แจ้งเข้ามา) | `frontend/src/features/whatsNew/FeedbackInbox.jsx` |
| Rules, statuses and notifications | `backend/src/main/java/th/co/glr/hr/feedback/FeedbackService.java` |
| Data | `hr.feedback_request`, `hr.feedback_requester` |

## Statuses

| Status | Portal label | Who moves it there | Needs |
|---|---|---|---|
| `PENDING` | รอพิจารณา | The system, on submit | |
| `DENIED` | ไม่รับ | Admin | A reason of 5 to 2000 characters |
| `ACCEPTED` | รับเรื่อง | Admin | A due date |
| `IN_PROGRESS` | กำลังทำ | Admin | |
| `TESTING` | ทดสอบ | Admin | |
| `RELEASED` | ใช้งานได้แล้ว | Deployer | The fix is live on production and checked |

A request must be accepted before it can move along the progress steps.

## The flow

1. **Read it and reproduce it.** Read the request, its page path and its impact. Reproduce on demo data or read production without changing it. Check whether another request or open branch already covers it.
2. **Prioritize.** `BLOCKED` comes first, then `WORKAROUND`, then `LOW`. For features: `DAILY`, `SOMETIMES`, `NICE`. Anything that affects money, customer documents or a rep stuck mid-deal moves up.
3. **Get Ploy's decision.** Ploy decides whether to accept or deny, and rules on anything that changes a business rule. Quote Ploy's exact words when passing a ruling to another session.
4. **Assign one owner.** One session owns one request. Check the deployer's alignment note before starting, and ask the deployer for a migration number.
5. **Build the smallest full fix.** Fix the root cause, end to end, following the root workflow. Show it working in the real app, run the review, and push to `develop`.
6. **Hand over to the deployer.** Give the SHA, patch or minor, whether it holds backend changes or a migration, what was verified, what was not, and any production data changes. Draft the mail and PDF if the change needs them. Do not send them.
7. **Release.** The deployer follows [versioning](../versioning.md) and `ops/onprem/README.md`: all CI green on that exact SHA, gate review, test site, Ploy's go, backup, production, `main`, tag.
8. **Tell people.** Choose one way to notify, below. Each person gets one release message.
9. **Close it.** Move the request to `RELEASED` only after the fix is live and checked.

## Notifying people

The portal sends these to everyone listed as a requester:

| Action | Bell | Email |
|---|---|---|
| Accept or deny | Yes | Yes |
| Change a step or the due date | Yes | No |
| **First** move to `RELEASED` | Yes | Yes |
| Release again after moving back | Yes | No |
| Any of these with **ไม่ต้องแจ้งผู้ขอ (ไม่ส่งอีเมล)** ticked | No | No |

Pick one:

- **Portal mail**, for a fix that concerns only the people who asked. Leave the box unticked and let the portal send it.
- **Team mail**, for a change a whole team should know about. Tick the box **before** changing the status, then send the team mail with its PDF.

**A silent first release uses up the release email.** Moving the request back and releasing it again sends only a bell notice. If the requester should still hear about it, someone has to send that mail by hand.

Tick the box on the right card. It belongs to one card and is not remembered after a reload.

## Team mail and PDF

- Use the patch format for a fix: ปัญหาที่พบ, สิ่งที่แก้ไข, and สิ่งที่ขอให้ดำเนินการ when people must act.
- Use the feature format for something new: สรุปสิ่งที่เพิ่ม and รายละเอียด.
- Attach one PDF named `Patch-<พ.ศ. date>-<topic>.pdf` or `Feature-<พ.ศ. date>-<topic>.pdf`, with a screenshot of each step at phone width.
- Use made up data in every mail and screenshot. Never name a real deal, customer or document.
- List only what is live when the mail goes out. Say plainly what is still not fixed.
- Ploy approves the body and PDF of **each** mail. Any later change to a claim goes back to Ploy.
- Only the deployer sends, after a real UI walk confirms every claim. The standing bcc address is kept outside this repository.
- A มีอะไรใหม่ entry needs Ploy's word.

## Production data

Production is read only. If a request needs a data fix, get Ploy's approval for the exact change, take a backup, rehearse on a private copy, and report what changed to the deployer. Never put production data on the test site.
