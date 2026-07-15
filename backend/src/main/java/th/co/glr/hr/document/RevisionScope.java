package th.co.glr.hr.document;

public enum RevisionScope {
    QTY_OR_NOTE,   // qty/note/customer/deposit% change only — no re-approval needed
    PRICE_CHANGE,  // unit price or discount changed — CEO must re-approve
    NEW_ITEM       // new product added — Import must price, then CEO approves
}
