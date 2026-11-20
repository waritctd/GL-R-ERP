// The deal page's five tab bodies (drawings a-deal, a-deal-quote, a-deal-money, a-deal-ship; 2026-11-16) — layout
// only. Each slot is a panel the page has already built and gated, so every permission decision stays at one site
// (TicketDetailPage), unchanged from where the old tabs made it; these components only place the panels as drawn.

/** ภาพรวม: the merged timeline beside ออเดอร์ในโครงการนี้ (when there is more than one order, or an add-on may be opened), ข้อมูลงาน and the bidding contractors, the quiet links at the foot. */
export function DealOverviewTab({ timeline, projectOrders = null, info, parties = null, quietLinks = null }) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)] lg:items-start">
      <div className="min-w-0">{timeline}</div>
      <div className="flex min-w-0 flex-col gap-4">
        {projectOrders}
        {info}
        {parties}
        {quietLinks}
      </div>
    </div>
  );
}

/** ใบเสนอราคา: the สั่งเพิ่ม entry (owner only), this deal's quotations (+ create), the latest quotation's lines, then the items and คำขอราคา. */
export function DealQuoteTab({
  addOn = null, orderQuotations = null, quotations, latestItems = null, items, pricingRequests = null,
}) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {orderQuotations}
      {addOn}
      {quotations}
      {latestItems}
      {items}
      {pricingRequests}
    </div>
  );
}

/** เงิน: the KPI tiles + รับแล้ว / เอกสาร (DealPaymentSection), then เงินที่คาดว่าจะเข้า and the close-lifecycle action. */
export function DealMoneyTab({
  orderSwitcher = null, projectTotals = null, payment = null, expected = null, closeActions = null,
}) {
  // projectTotals (รวมโครงการ) replaces this order's own money, so the page never shows one order's figures as the project's.
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {orderSwitcher}
      {projectTotals ?? (
        <>
          {payment}
          {expected}
          {closeActions}
        </>
      )}
    </div>
  );
}

/** ส่งของ: import tracking (read-only per factory) and delivery to the customer (DealFulfilmentPanel). */
export function DealShipTab({ orderSwitcher = null, fulfilment }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {orderSwitcher}
      {fulfilment}
    </div>
  );
}

/** ไฟล์: the order chips, the document register without its quotation list, one block per delivery round, then the other files.
 * On ทั้งโครงการ only `rounds` (each order's round files, stacked) shows. */
export function DealFilesTab({ orderSwitcher = null, register = null, rounds = null, attachments = null }) {
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {orderSwitcher}
      {register}
      {rounds}
      {attachments}
    </div>
  );
}
