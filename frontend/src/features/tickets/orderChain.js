// สั่งเพิ่ม (add-on orders): how a deal's place in its order chain reads, and who may open another order.
// A root deal is order 1 (`<code>-1`); each add-on is a linked deal whose own code is `<root code>-<n>`. A bid-split
// child (`<code>-A`) is a separate customer's deal, so it is the root of its own chain.
import { actsAsOwner } from './dealOwnership.js';

/** The root deal's code: an add-on names its parent, everything else is its own root. */
export function rootCode(summary) {
  // A null parent (the root was hard-deleted: FK ON DELETE SET NULL) makes the add-on its own root, as on the server.
  return summary?.childKind === 'ADD_ON' && summary.parentCode ? summary.parentCode : summary?.code;
}

/** What the order is called on screen: the root shows `<code>-1`, an add-on its own code. */
export function displayOrderCode(summary) {
  if (!summary?.code) return '';
  return summary.childKind === 'ADD_ON' ? summary.code : `${summary.code}-1`;
}

/** The number the next add-on will get, `<root code>-<n>`; the server assigns the real one and its answer wins. */
export function nextAddOnCode(summary) {
  return `${rootCode(summary)}-${(summary?.addOnCount ?? 0) + 2}`;
}

/**
 * Mirrors DealPartyService#createAddOnOrder: the deal's owner/keyer or an all-sales-documents grant holder, on an
 * ACTIVE deal. The server decides; this only decides whether the button is offered.
 */
export function canOpenAddOn(summary, user) {
  return (summary?.lifecycle ?? 'ACTIVE') === 'ACTIVE' && actsAsOwner(summary, user);
}

const isAddOn = (row) => row?.childKind === 'ADD_ON' && row.parentTicketId != null;
const MONEY_FIELDS = ['amountPayable', 'amountPaid', 'amountOutstanding'];
const sumField = (a, b) => (a == null && b == null ? null : (Number(a) || 0) + (Number(b) || 0));

function withOrders(base, orders, codes) {
  const folded = { ...base, orderCount: orders.length + 1, orderCodes: codes };
  for (const field of MONEY_FIELDS) {
    folded[field] = orders.reduce((sum, order) => sumField(sum, order[field]), base[field] ?? null);
  }
  return folded;
}

/**
 * One row per project in a `GET /api/tickets` list (the same rule as SalesBoardRepository#fold): an ADD_ON order never
 * shows as its own row. It folds into its root when the root is listed (the root keeps its place and gains
 * `orderCount`, `orderCodes` and the summed `amountPayable` / `amountPaid` / `amountOutstanding`). When the root is not
 * listed (outside the range or role scope) the project is stood in by its first add-on's row, relabelled with the
 * ROOT's id and code, and counts the orders present. Rows with no add-on are returned as they are, bid-split children
 * included. Not for pickers that need each order (DealPicker).
 */
export function foldAddOnOrders(rows) {
  const list = rows ?? [];
  if (!list.some(isAddOn)) return list;
  const listed = new Set(list.map((row) => row.id));
  const kids = new Map();
  for (const row of list.filter(isAddOn)) {
    kids.set(row.parentTicketId, [...(kids.get(row.parentTicketId) ?? []), row]);
  }
  const out = [];
  const stoodIn = new Set();
  for (const row of list) {
    if (!isAddOn(row)) {
      const orders = kids.get(row.id);
      out.push(orders ? withOrders(row, orders, [row.code, ...orders.map((o) => o.code)]) : row);
    } else if (!listed.has(row.parentTicketId) && !stoodIn.has(row.parentTicketId)) {
      stoodIn.add(row.parentTicketId);
      const [first, ...rest] = kids.get(row.parentTicketId);
      const project = {
        ...first, id: first.parentTicketId, code: first.parentCode ?? first.code,
        childKind: null, parentTicketId: null, parentCode: null,
      };
      const folded = withOrders(project, rest, [project.code, first.code, ...rest.map((o) => o.code)]);
      out.push({ ...folded, orderCount: rest.length + 1 });
    }
  }
  return out;
}
