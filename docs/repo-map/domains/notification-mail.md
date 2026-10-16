---
title: Notification & Mail
layer: Platform
schemas: [hr]
tags: [repo-map, domain/notification-mail]
synced: develop@ef9b17dc
---
# Notification & Mail
> In-app notifications and outbound email (quotations, resets, alerts).

**Layer:** Platform  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.notification` — [notification/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/notification)
- `backend/src/main/java/th/co/glr/hr/notification/AfterCommit.java`
- `backend/src/main/java/th/co/glr/hr/notification/CeoApproverRepository.java`
- `backend/src/main/java/th/co/glr/hr/notification/CeoApproverRule.java`
- `backend/src/main/java/th/co/glr/hr/notification/EmailRecipient.java`
- `backend/src/main/java/th/co/glr/hr/notification/NotificationController.java`
- `backend/src/main/java/th/co/glr/hr/notification/NotificationDto.java`
- `backend/src/main/java/th/co/glr/hr/notification/NotificationEmailService.java`
- `backend/src/main/java/th/co/glr/hr/notification/NotificationRepository.java`
- `backend/src/main/java/th/co/glr/hr/notification/NotificationService.java`
- `backend/src/main/java/th/co/glr/hr/notification/SalesMailRecipientRepository.java`
- `backend/src/main/java/th/co/glr/hr/notification/SalesNotificationMailRouter.java`
- `backend/src/main/java/th/co/glr/hr/notification/SalesNotificationMailer.java`
### `th.co.glr.hr.mail` — [mail/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/mail)
- `backend/src/main/java/th/co/glr/hr/mail/LogMailer.java`
- `backend/src/main/java/th/co/glr/hr/mail/MailOverrideBeanPostProcessor.java`
- `backend/src/main/java/th/co/glr/hr/mail/MailSendException.java`
- `backend/src/main/java/th/co/glr/hr/mail/Mailer.java`
- `backend/src/main/java/th/co/glr/hr/mail/OverrideRedirectingMailer.java`
- `backend/src/main/java/th/co/glr/hr/mail/ResendMailer.java`
- `backend/src/main/java/th/co/glr/hr/mail/SmtpMailer.java`

## Frontend
_(no dedicated frontend feature — UI lives inside a related domain)_

## DB
- Schemas: `hr`

## Related
[[auth-session|Auth & Session]] · [[deposit-billing-finance|Deposit · Billing · Finance]] · [[pricing-flow|Pricing Flow]]

## Gotchas
- SalesNotificationMailer.NO_OP is used in tests / when SMTP is unconfigured — no mail actually sends.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
