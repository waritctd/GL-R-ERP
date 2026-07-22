// Thin re-export: the page body moved to OvertimePanel.jsx (now hosted inside
// RequestsPage.jsx's tab bar alongside SpecialMoneyPanel). This wrapper keeps
// OvertimePage's export name/props stable so OvertimePage.test.jsx — which
// imports `{ OvertimePage }` from this exact path — keeps passing unchanged,
// and so any other existing import of OvertimePage still resolves.
export { OvertimePanel as OvertimePage } from './OvertimePanel.jsx';
