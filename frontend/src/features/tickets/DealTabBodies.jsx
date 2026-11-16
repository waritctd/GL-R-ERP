// The deal page's five tab bodies (drawings a-deal, a-deal-quote, a-deal-money, a-deal-ship; 2026-11-16) — layout
// only. Each slot is a panel the page has already built and gated, so every permission decision stays at one site
// (TicketDetailPage), unchanged from where the old tabs made it; these components only place the panels as drawn.

/** ภาพรวม: the merged timeline beside ข้อมูลงาน and the bidding contractors, the quiet links at the foot. */
export function DealOverviewTab({ timeline, info, parties = null, quietLinks = null }) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)] lg:items-start">
      <div className="min-w-0">{timeline}</div>
      <div className="flex min-w-0 flex-col gap-4">
        {info}
        {parties}
        {quietLinks}
      </div>
    </div>
  );
}

/** ใบเสนอราคา: this deal's quotations (+ create), the latest quotation's lines, then the items and คำขอราคา. */
export function DealQuoteTab({ quotations, latestItems = null, items, pricingRequests = null }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {quotations}
      {latestItems}
      {items}
      {pricingRequests}
    </div>
  );
}

/** เงิน: the KPI tiles + รับแล้ว / เอกสาร (DealPaymentSection), then เงินที่คาดว่าจะเข้า and the close-lifecycle action. */
export function DealMoneyTab({ payment = null, expected = null, closeActions = null }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {payment}
      {expected}
      {closeActions}
    </div>
  );
}

/** ส่งของ: import tracking (read-only per factory) and delivery to the customer (DealFulfilmentPanel). */
export function DealShipTab({ fulfilment }) {
  return <div className="min-w-0">{fulfilment}</div>;
}

/** ไฟล์: the document register without its quotation list, then the attachments panel. */
export function DealFilesTab({ register, attachments = null }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {register}
      {attachments}
    </div>
  );
}
