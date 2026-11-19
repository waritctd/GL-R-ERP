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
