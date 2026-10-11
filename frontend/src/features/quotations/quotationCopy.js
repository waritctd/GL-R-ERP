// "คัดลอกเป็นใบใหม่" — a saved quotation as the starting point of a brand-new one, for the job
// several contractors bid on: the same rows and terms, a different customer. See
// quotationCopy.test.jsx for the table of what is copied and what is left for the rep.
//
// PHASE 1 SCAFFOLD: the shape only. Nothing is copied yet, so every test of that table is red.

/**
 * The state `/quotations/new?copyFrom=<id>` opens with, from the source quotation's DTO.
 *
 * @returns {{ groups: object[], items: object[], adjustments: object[], docSettings: object,
 *   terms: object, suggestedProjectName: string }}
 */
export function copyFromQuotation(quotation) {
  void quotation;
  return { groups: [], items: [], adjustments: [], docSettings: {}, terms: {}, suggestedProjectName: '' };
}
