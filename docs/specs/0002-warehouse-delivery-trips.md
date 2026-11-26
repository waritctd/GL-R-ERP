# 0002 Warehouse delivery trips (รอบส่งของ) and the warehouse home

Status: approved by Ploy on 2026-11-26 (canvas WH1-WH6) and built. Sales may set a requested delivery date on the round (V249, optional, Ploy 11-26); no place is stored; a trip pressed early records today as the delivery date.

## Why

The warehouse manager opens the portal and sees an HR self-service home with nothing about her work. Her only way in
is the bell, one order at a time. She also plans deliveries outside the portal, so sales cannot tell a customer when
goods will arrive.

## Who

- **Warehouse manager**: the only warehouse person who works deliveries. She checks sales' documents (already built),
  plans trips, and records deliveries. Other warehouse staff keep today's behaviour; nothing new for them.
- **Sales** (order owner, keyer, round requester): read the trip plan on their order and get told about it.
- Today's rule stays: the warehouse never reads the deal or its prices.

## What exists today (built, on main)

Sales drafts a delivery request round on an order and sends it (SENT). The warehouse forwards it to the tax invoice
issuer (FORWARDED) or returns it (RETURNED). The issuer confirms the invoice (INVOICED). Then sales or the warehouse
records the delivery against the round. The round already carries the date and place sales asked for.

## Goals

1. **Warehouse home** at `/` for the warehouse manager, two sections, in this order:
   - **Waiting to be delivered**: rounds that are INVOICED, still have goods to deliver, and are on no open trip
     (delayed ones included). Each row: order code, customer, the date and place sales asked for, delayed tag if any.
     She can tick several and make a trip from them.
   - **My trips this week**: date, route, number of orders, done or not. Tap opens the trip.
   - Above those, the invoice pipeline so she sees what is coming (Ploy 11-26; wording open to revision):
     **ขอออกใบกำกับแล้ว · รอคุณตรวจ** (SENT, she forwards or returns) and **รอออกใบกำกับ** (FORWARDED, read only).
     **ได้ใบกำกับแล้ว** is the "waiting to be delivered" section above.
   - ส่งของแล้ว with a blocked order (Ploy: "she can pick anything"): she may put any waiting round on a trip; the
     button records every order that can leave and marks the blocked ones with the reason, so she can mark them
     delayed. Nothing stops the whole trip.
2. **Trip**: the manager creates it whenever she has time (no repeating schedule; built from what is waiting).
   - Required: delivery date, route (a short text label she types, e.g. "สายบางนา–สมุทรปราการ").
   - Optional: anything else (time slot, truck/driver, note). Free text, nothing depends on it.
   - Orders: she adds or removes waiting rounds any time until the trip is done.
   - **Delayed (เลื่อนส่ง)**: per order on the trip. It stays undelivered and goes back to "waiting".
   - **One "ส่งของแล้ว" button**: records every non-delayed order on the trip as delivered in full, through the
     existing record-delivery path (same rules: stock/received-goods gates, etc.). The trip is then done.
3. **Sales sees it**: on the order's delivery tab, "จะส่ง [date] · [route]" plus the optional details.
4. **Sales is told**: bell + email to the order's sales people when their order is put on a trip, when its date
   changes, and when it is marked delayed. *(Email on create is Ploy's ask; the date-change and delayed mails are my
   guess.)*

## Calls made (Claude's, from the builder check; Ploy to check)

- The unit on a trip is a **round** ("order" is display wording). A round sits on at most one open trip. Delayed or
  removed → back to waiting. A delivered round has nothing left, so it never comes back.
- ส่งของแล้ว has no form: delivered date = the trip date, no recipient name, source derived by the server (as today
  for the warehouse). Proof photos stay optional on the round, as today.
- Trips are shared by all warehouse managers ("My trips" = the warehouse's trips); edits lock the trip row.
- Trip date: today or later when created or changed.
- A trip with nothing delivered can be cancelled: its rounds go back to waiting and sales is told.
- A done trip is read-only history, delayed rows kept with their tag. Fixing a wrong delivery goes through the
  order's existing void/record rules, not the trip.
- Sales view of a delayed or removed round: "รอจัดรอบส่งใหม่", no date, until it is on a new trip.

## Non-goals

- Maps, stop order optimisation, driver app, GPS.
- Partial delivery from the trip button (a partly shipped order is marked delayed, or recorded on the order as today).
- Other warehouse staff planning trips.
- Repeating weekly routes.

## Anti-goals

- The warehouse seeing prices or the deal page.
- A trip button that marks something delivered that did not leave (that is why delayed exists).
- Sales having to ask the warehouse "when?" after this ships.

## Constraints

- Thai UI, formal wording; mobile first (the manager may use a phone at the dock).
- Backend decides access: trip endpoints are warehouse-manager only (`WarehouseStaff.isManager`); sales read is the
  existing deal-read rule.
- New tables → one forward-only migration, next number after the highest on `origin/develop`.
- API change → `hrApi.js` + `mockApi.js` + regenerated `docs/api/` digests.
- The self-service lock on prod: the warehouse home and trip pages must be added to the warehouse's released paths
  (like `RELEASED_PATHS_BY_ROLE` for import/account) and get its own landing (like `OWN_LANDING_LOCKED_ROLES`).
- Dates shown in Buddhist era, stored ISO, Asia/Bangkok.

## Left to the builder

Table shape, endpoint names, whether a trip page is a sheet or a route, exact Thai copy (formal), email template
reuse (existing mail package), sorting inside sections.

## Design first

Before code: mock the warehouse home, the trip page (create/edit, delayed, ส่งของแล้ว), and the sales view of the plan
on the Claude Design canvas with the GL&R ERP design system, mock data only. Ploy approves the mocks, then build.
