# 0004 Customer pickup (ลูกค้ามารับเอง)

Status: approved by Ploy on 2026-11-27 ("build the pickup flow as drawn"). Designs: the GL&R Portal Prototype canvas, boards `PK1`–`PK5` (row ลูกค้ามารับเอง, Desktop page).

## Why

Some customers collect their goods at the warehouse. Today the only path is a delivery trip, so a pickup order either sits in the trip list or is recorded by hand with no trace of who took the goods.

## What changes

A delivery request round gains one choice, **วิธีรับของ**: ส่งให้ลูกค้า (the default) or ลูกค้ามารับเอง. Documents, the warehouse check and the tax invoice are unchanged. Only the last step differs: no trip, the customer collects at the warehouse.

1. **Sales, request sheet (PK1)**: the choice sits under the quotation. When pickup is chosen, the optional requested date is labelled **วันที่ลูกค้าจะมารับ**. Sales may change it while the round is editable (draft or returned), like the requested date.
2. **Warehouse manager, home (PK2 phone, PK5 desktop)**: a section **ลูกค้ามารับเอง** lists pickup rounds that are INVOICED on a live deal with goods left, by requested date (none last). Pickup rounds never appear in **ได้ใบกำกับแล้ว · รอจัดรอบส่ง** and a trip refuses them (409). Rows in รอคุณตรวจ show ส่งให้ลูกค้า / ลูกค้ามารับเอง.
3. **Handover (PK3)**: **ลูกค้ารับของแล้ว** opens a sheet: collector name (required for a pickup round), phone (optional, kept in the note), date (today by default), photo (optional, the existing delivery-proof upload). Confirm records the whole remaining round through the existing record-delivery path (same gates). Sales people of the order are told (bell + mail) the goods were collected.
4. **Sales, ส่งของ tab (PK4)**: a pickup round shows **ลูกค้ามารับเอง**; once invoiced, **รอลูกค้ามารับ** with the date if given; once recorded, **ลูกค้ารับของแล้ว · [date] · โดย [name]**.
5. **Customer changes their mind after the invoice**: the warehouse manager may switch an INVOICED pickup round with goods left to delivery (it then waits for a trip), or a delivery round on no open trip to pickup. Rep is never stuck.

## Calls made (Claude's)

- Stored as `sales.delivery_request.customer_pickup BOOLEAN NOT NULL DEFAULT FALSE` (V250). Existing rounds are deliveries.
- Who may record a pickup: unchanged record-delivery rule (sales on the order, or warehouse staff). The home button is the warehouse manager's.
- The pre-portal send path and the issuer flow do not change.

## Non-goals

Partial pickups from the home button (record on the order as today), signatures, ID checks, a pickup schedule.
