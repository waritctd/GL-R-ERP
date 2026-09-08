#!/usr/bin/env python3
"""Regenerate the sales-pipeline map data.

Extracts every Java `record`, controller endpoint, sales.* table writer, migration and file
in the sales/CRM packages, and merges them with the hand-authored service metadata,
role matrix and end-to-end trace below.

    cd backend/src/main/java/th/co/glr/hr
    python3 ../../../../../../../../docs/tools/gen-sales-pipeline-map.py

Writes combined.json next to itself. Sourced by docs/sales-pipeline-forensics.md and
docs/sales-pipeline-dtos.md — regenerate rather than hand-editing those files.
"""
import re, glob, os, json, sys, collections

S = os.path.join(os.path.dirname(os.path.abspath(__file__)), '')

# ── 1. DTOs ──────────────────────────────────────────────────────────────────
PKGS = ['ticket','pricingrequest','factoryquote','pricingcosting','pricingdecision',
        'customerquotation','orderconfirmation','deposit','procurement','commission',
        'customer','pricing','catalog','factory']
CONS = ['NotBlank','NotNull','NotEmpty','Valid','Size','Email','Positive','PositiveOrZero',
        'DecimalMin','DecimalMax','Min','Max','JsonProperty','Pattern']
ANN = re.compile(r'@(?:[\w.]*\.)?(' + '|'.join(CONS) + r')\b(\s*\((?:[^()]|\([^()]*\))*\))?')

def bal(s, i):
    d = 0
    for k in range(i, len(s)):
        if s[k] == '(': d += 1
        elif s[k] == ')':
            d -= 1
            if d == 0: return s[i+1:k]
    return ''

def sp(t):
    o, d, c = [], 0, ''
    for ch in t:
        if ch in '<([': d += 1
        elif ch in '>)]': d -= 1
        if ch == ',' and d == 0: o.append(c.strip()); c = ''
        else: c += ch
    if c.strip(): o.append(c.strip())
    return o

def kind(f, r):
    if r.endswith('Response'): return 'resp'
    if r.endswith('Request'): return 'req'
    if f.endswith(('Repository.java','Service.java','Renderer.java','Engine.java')): return 'int'
    return 'dto'

dtos = []
for pkg in PKGS:
    for f in sorted(glob.glob(f'{pkg}/**/*.java', recursive=True)):
        cl = re.sub(r'/\*.*?\*/', '', open(f, errors='replace').read(), flags=re.S)
        cl = re.sub(r'//[^\n]*', '', cl)
        for m in re.finditer(r'record\s+([A-Za-z0-9_]+)\s*\(', cl):
            flds = []
            for x in sp(re.sub(r'\s+', ' ', bal(cl, m.end()-1))):
                cs = ['@'+a.group(1)+(a.group(2).strip() if a.group(2) else '') for a in ANN.finditer(x)]
                b = ANN.sub('', x).strip()
                b = re.sub(r'^@[\w.]+(\s*\((?:[^()]|\([^()]*\))*\))?\s*', '', b).strip()
                b = re.sub(r'\s+', ' ', b)
                p = b.rsplit(' ', 1)
                t, n = (p[0], p[1]) if len(p) == 2 else (b, '')
                flds.append([n, t.replace('java.math.','').replace('java.time.',''), ' '.join(cs)])
            dtos.append({'pkg': pkg, 'name': m.group(1), 'kind': kind(os.path.basename(f), m.group(1)),
                         'file': os.path.basename(f), 'fields': flds})
assert len(dtos) > 150, f'only {len(dtos)} dtos'

# ── 2. Endpoints ─────────────────────────────────────────────────────────────
MAP = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\b(\s*\((?:[^()]|\([^()]*\))*\))?')
SKIP = {'sessions','session','user','result','p','this'}
eps = []
for c in sorted(glob.glob('*/*Controller.java') + glob.glob('*/*/*Controller.java')):
    pkg = c.split('/')[0]
    if pkg not in PKGS: continue
    src = open(c, errors='replace').read()
    b = re.search(r'@RequestMapping\("([^"]*)"\)', src)
    base = b.group(1) if b else ''
    marks = [(m.start(), m) for m in MAP.finditer(src)]
    for i, (pos, m) in enumerate(marks):
        end = marks[i+1][0] if i+1 < len(marks) else len(src)
        after = src[m.end():end]
        pm = re.search(r'"([^"]*)"', m.group(2) or '')
        sig = re.search(r'^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*([\w<>,\[\]?. ]+?)\s+(\w+)\s*\(', after, re.M)
        ret, hn = (re.sub(r'\s+',' ',sig.group(1)).strip(), sig.group(2)) if sig else ('?','?')
        rb = re.search(r'@RequestBody\s+(?:@Valid\s+)?([\w<>]+)', after)
        calls = [f'{a}.{b2}' for a, b2 in re.findall(r'\b([a-z]\w*)\.(\w+)\s*\(', after) if a not in SKIP]
        svc = next((x for x in calls if 'ervice' in x.split('.')[0]), '')
        eps.append({'pkg': pkg, 'verb': m.group(1).upper(), 'path': base + (pm.group(1) if pm else ''),
                    'handler': hn, 'ret': ret, 'body': rb.group(1) if rb else '', 'call': svc})
assert len(eps) > 140, f'only {len(eps)} endpoints'


# ── 3/4/5. Tables, migrations, files (derived, no pre-built inputs) ──────────
def extract_tables():
    """Every sales.* table the Java touches. `insert`/`update` empty == read-only
    reference data seeded by a migration, not written by any service."""
    w = collections.defaultdict(lambda: {'ins': set(), 'upd': set(), 'sel': set()})
    for f in glob.glob('**/*.java', recursive=True):
        src = open(f, errors='replace').read(); cls = os.path.basename(f)[:-5]
        for t in re.findall(r'INSERT\s+INTO\s+(sales\.\w+)', src, re.I): w[t]['ins'].add(cls)
        for t in re.findall(r'UPDATE\s+(sales\.\w+)', src, re.I):        w[t]['upd'].add(cls)
        for t in re.findall(r'FROM\s+(sales\.\w+)', src, re.I):          w[t]['sel'].add(cls)
    return [{'table': t, 'insert': sorted(w[t]['ins']), 'update': sorted(w[t]['upd']),
             'readers': sorted(w[t]['sel'])} for t in sorted(w)]

def extract_migrations():
    mig = os.path.join('..', '..', '..', '..', '..', 'resources', 'db', 'migration')
    rows = []
    for f in sorted(glob.glob(os.path.join(mig, 'V*.sql')),
                    key=lambda x: int(re.match(r'V(\d+)', os.path.basename(x)).group(1))):
        src = open(f, errors='replace').read()
        tabs = sorted(set(re.findall(r'(?:CREATE TABLE(?: IF NOT EXISTS)?|ALTER TABLE)\s+(sales\.\w+)', src, re.I)))
        if not tabs: continue
        rows.append({'v': int(re.match(r'V(\d+)', os.path.basename(f)).group(1)),
                     'name': re.sub(r'^V\d+__', '', os.path.basename(f))[:-4].replace('_', ' '),
                     'tables': tabs,
                     'created': sorted(set(re.findall(r'CREATE TABLE(?: IF NOT EXISTS)?\s+(sales\.\w+)', src, re.I)))})
    return rows

def extract_files():
    return [{'pkg': p, 'path': f, 'name': os.path.basename(f),
             'loc': sum(1 for _ in open(f, errors='replace'))}
            for p in PKGS for f in sorted(glob.glob(f'{p}/**/*.java', recursive=True))]

tables = extract_tables()
migrations = extract_migrations()
files = extract_files()

# ── 6. Hand-authored service metadata (verified earlier this session) ────────
SERVICES = [
 {"step":1,"pkg":"ticket","name":"TicketService","tag":"the Deal","api":"/api/tickets",
  "owns":"sales.ticket + 8 satellite tables","loc":1955,"tx":41,"locks":"none","tests":"9 files, 6 real-DB",
  "role_note":"7 role constants — the widest surface in the pipeline",
  "why":"The long-lived customer-facing record. Everything else in the chain hangs off it, but since the V59 redesign it no longer prices anything itself.",
  "calls_out":["PricingRequestService (dead-deal cascade)","PriceCalcService","CustomerRepository","QuotationRenderer"],
  "called_by":["CustomerQuotationService","OrderConfirmationService"]},
 {"step":2,"pkg":"pricingrequest","name":"PricingRequestService","tag":"PCR aggregate","api":"/api/pricing-requests",
  "owns":"sales.pricing_request + 3","loc":962,"tx":12,"locks":"pricing_request_id, root id","tests":"6 files",
  "role_note":"sales writes, import picks up, DRAFT private to owner. V140 removed the ขอข้อมูลเพิ่มเติม round-trip",
  "why":"One deal has 0..N pricing requests — this is the per-revision aggregate. The only rigorous state machine in the pipeline: 12 statuses since V140 collapsed Import's workflow to three user-visible states.",
  "calls_out":["TicketRepository (direct)","ContactRepository","FileStorageService"],"called_by":["TicketService"]},
 {"step":3,"pkg":"factoryquote","name":"FactoryQuoteService","tag":"supplier RFQ","api":"/api/factory-quotes",
  "owns":"sales.factory_quote + 3","loc":819,"tx":14,"locks":"root_factory_quote_id, hashed idempotency key",
  "tests":"1 file — attachments only","role_note":"import writes, ceo reads. Sales NEVER sees a factory quote",
  "why":"The confidentiality boundary of the whole chain: what the factory charges must never reach the customer-facing side.",
  "calls_out":["PricingRequestRepository (direct)","TicketRepository (direct)","FactoryEmailService","FactoryConfigRepository"],"called_by":[]},
 {"step":4,"pkg":"pricingcosting","name":"PricingCostingService","tag":"landed cost","api":"/api/pricing-costings",
  "owns":"sales.pricing_costing + 1","loc":1016,"tx":3,"locks":"none","tests":"ZERO files in package",
  "role_note":"import writes, ceo reads","why":"Turns a raw factory price into a landed THB cost: goods + freight + insurance + duty + inland. Every selling price downstream derives from this number.",
  "calls_out":["FactoryQuoteRepository","PricingRequestRepository","TicketRepository (all direct)","FxRateRepository","PriceCalcConfigRepository"],"called_by":[]},
 {"step":5,"pkg":"pricingdecision","name":"PricingDecisionService","tag":"CEO selling price","api":"/api/pricing-decisions",
  "owns":"sales.pricing_decision + 1","loc":1150,"tx":5,"locks":"pricing_request_id","tests":"17 passed, real transaction",
  "role_note":"every write is CEO-only; /sales-view is the one price-without-cost read",
  "why":"The CEO applies a margin to the frozen landed cost and freezes an approved selling price plus a discount floor. This is where cost becomes price.",
  "calls_out":["PricingCostingRepository","PricingRequestRepository","TicketRepository (all direct)","FxResolver"],"called_by":[]},
 {"step":6,"pkg":"customerquotation","name":"CustomerQuotationService","tag":"customer document","api":"/api/customer-quotations",
  "owns":"sales.quotation + 1","loc":719,"tx":7,"locks":"pricing_request_id","tests":"21 passed",
  "role_note":"sales writes; account excluded end-to-end",
  "why":"Produces the document the customer actually sees. Sales may discount down to — never below — the CEO's approved floor.",
  "calls_out":["TicketService (stage advance)","PricingDecisionRepository","PricingRequestRepository","CustomerRepository"],"called_by":[]},
 {"step":7,"pkg":"orderconfirmation","name":"OrderConfirmationService","tag":"the bridge","api":"/api/pricing-requests/{id}/confirm-order",
  "owns":"no table of its own","loc":517,"tx":3,"locks":"pricing_request_id + replay check","tests":"8 passed",
  "role_note":"sales only","why":"The single point where the PCR aggregate writes back into the Deal. Four writes across three aggregates in one transaction — structurally the riskiest method in the chain.",
  "calls_out":["TicketService","DepositNoticeService","CustomerQuotationRepository","PricingRequestRepository"],"called_by":[]},
 {"step":8,"pkg":"deposit","name":"DepositNoticeService","tag":"ใบแจ้งมัดจำ","api":"/api/deposit-notices",
  "owns":"sales.deposit_notice + 2","loc":1540,"tx":4,"locks":"none","tests":"4 files, none real-DB",
  "role_note":"sales writes; import explicitly 403'd after the role check",
  "why":"Issues the deposit request document. Its issue() is the single action that advances payment_status to DEPOSIT_NOTICE_ISSUED.",
  "calls_out":["CustomerQuotationRepository","TicketRepository (direct)","CustomerRepository"],"called_by":["OrderConfirmationService"]},
 {"step":9,"pkg":"procurement","name":"ProcurementService","tag":"orphaned","api":"/api/factory-purchase-orders",
  "owns":"sales.factory_purchase_order + 1","loc":959,"tx":5,"locks":"pricing_request_id","tests":"13 passed",
  "role_note":"import + ceo","why":"Per-factory purchase orders. Live backend, 13 green tests, and ZERO frontend callers since commit ebaf6888.",
  "calls_out":["PricingRequestRepository","TicketRepository (direct)"],"called_by":[]},
 {"step":10,"pkg":"commission","name":"CommissionService","tag":"rep payout","api":"/api/commissions",
  "owns":"sales.commission_record + 1","loc":1190,"tx":8,"locks":"none","tests":"16 files, 13 real-DB — best covered",
  "role_note":"7 role constants; a genuine two-signature chain",
  "why":"Turns a closed, paid deal into a rep's commission. createFromDeal is the only path that files a tax invoice, and it dual-writes the attachment and the commission in one transaction.",
  "calls_out":["TicketRepository (direct)","AttachmentRepository","AuditService","NotificationService"],"called_by":[]},
]

SUPPORTING = [
 {"name":"CustomerService","api":"/api/customers","roles":"aliases TicketAccessPolicy.VIEWER_ROLES","note":"customer, contact, project"},
 {"name":"PriceCalcService","api":"—","roles":"no gate of its own","note":"legacy per-item price calc, called by TicketService under ceo"},
 {"name":"FxRateController","api":"/api/fx-rates","roles":"read {ceo, import, sales} · write {ceo}","note":"read widened by owner ruling"},
 {"name":"PriceCalcConfigController","api":"/api/price-calc-configs","roles":"read {ceo, import} · write {ceo}","note":"legacy per-sqm cost config"},
 {"name":"PricingFormulaConfigController","api":"/api/pricing-formula-config","roles":"read {ceo, import} · write {ceo}","note":"freight / duty / clearance-fee tables (V109)"},
 {"name":"DealEstimateMarkupController","api":"/api/deal-estimate-markup","roles":"write {ceo}","note":"V112"},
 {"name":"CatalogController","api":"/api/catalog","roles":"{ceo, import}","note":"product_prices, price_list_versions"},
 {"name":"PriceImportService","api":"/api/price-import","roles":"{ceo, import}","note":"staging → validate → commit"},
 {"name":"FactoryConfigController","api":"/api/factory-configs","roles":"{ceo, import}","note":"factory email + currency defaults"},
 {"name":"FactoryEmailService","api":"—","roles":"—","note":"wraps Mailer; missing file skipped with a warning"},
 {"name":"DashboardService","api":"/api/dashboard","roles":"tickets all {import,ceo} / own {sales}","note":"commissions all {sales_manager,ceo} / own {sales}"},
 {"name":"BotFxFetchService","api":"—","roles":"system","note":"@Scheduled cron 18:00 Asia/Bangkok"},
 {"name":"FactoryQuoteEmailDispatchWorker","api":"—","roles":"system","note":"@Scheduled 5s poll"},
 {"name":"QuotationExpiryWorker","api":"—","roles":"system","note":"@Scheduled hourly sweep"},
]

ROLES = ["ceo","sales_manager","sales","import","account","hr","warehouse","qc","employee"]
# W=write R=read B=blocked-on-purpose (documented) -=no grant
MATRIX = {
 "1 Deal":            {"ceo":"W","sales_manager":"R","sales":"W","import":"W","account":"W","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "2 PricingRequest":  {"ceo":"W","sales_manager":"R","sales":"W","import":"W","account":"R","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "3 FactoryQuote":    {"ceo":"R","sales_manager":"-","sales":"B","import":"W","account":"-","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "4 PricingCosting":  {"ceo":"R","sales_manager":"-","sales":"B","import":"W","account":"-","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "5 PricingDecision": {"ceo":"W","sales_manager":"R","sales":"R","import":"R","account":"-","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "6 CustomerQuote":   {"ceo":"R","sales_manager":"R","sales":"W","import":"R","account":"B","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "7 OrderConfirm":    {"ceo":"-","sales_manager":"-","sales":"W","import":"-","account":"-","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "8 DepositNotice":   {"ceo":"R","sales_manager":"R","sales":"W","import":"B","account":"R","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "9 Procurement":     {"ceo":"W","sales_manager":"-","sales":"-","import":"W","account":"-","hr":"-","warehouse":"-","qc":"-","employee":"-"},
 "10 Commission":     {"ceo":"W","sales_manager":"W","sales":"R","import":"-","account":"W","hr":"R","warehouse":"-","qc":"-","employee":"-"},
}
MATRIX_NOTES = {
 "3 FactoryQuote/sales":"Sales must never see what the factory charges.",
 "4 PricingCosting/sales":"Same confidentiality boundary as step 3.",
 "6 CustomerQuote/account":"Brief says no quotation editing; step 5 already excludes account from every raw-pricing view, so it stays forbidden for reads too.",
 "8 DepositNotice/import":"Passes the role check, then gets an explicit 403 — deposit notices are customer financial documents.",
 "1 Deal/sales":"Own deals only.",
 "2 PricingRequest/sales":"Own deals only; DRAFT is private to the owning rep plus CEO/sales_manager.",
 "1 Deal/import":"Fulfilment writes only; quotations, payment ledger and quotation files are stripped or 403'd.",
 "10 Commission/sales":"Own rows only.",
 "10 Commission/hr":"Payroll-ready feed only.",
}

TRACE = [
 {"n":1,"role":"sales","title":"Create the deal","ep":"POST /api/tickets","svc":"TicketService.create",
  "dto":"CreateTicketRequest → TicketDetailResponse","writes":["sales.ticket","sales.ticket_item","sales.ticket_event"],
  "state":"status=draft · lifecycle=ACTIVE · salesStage=LEAD_APPROACH","note":"A deal starts as a lightweight lead. Items can be edited until it is priced."},
 {"n":2,"role":"sales","title":"Ask Import for a price","ep":"POST /api/tickets/{id}/pricing-requests → /submit","svc":"PricingRequestService.createDraft → submit",
  "dto":"CreatePricingRequestRequest → PricingRequestDetailResponse","writes":["sales.pricing_request","sales.pricing_request_item","sales.pricing_request_event"],
  "state":"PCR: DRAFT → SUBMITTED","note":"DRAFT is the rep's private scratchpad — no notification, no ticket change. submit() is what makes it visible."},
 {"n":3,"role":"import","title":"Pick it up and quote the factories","ep":"POST /api/pricing-requests/{id}/pickup, then /factory-quotes/{id}/send","svc":"PricingRequestService.pickup → FactoryQuoteService.send",
  "dto":"SendFactoryQuoteRequest → FactoryQuoteDto","writes":["sales.factory_quote","sales.factory_quote_email_dispatch"],
  "state":"PCR: SUBMITTED → IMPORT_REVIEWING → AWAITING_FACTORY_RESPONSE","note":"The email is queued, not sent inline. A worker polls every 5s; attachment inclusion is resolved at send time, not queue time."},
 {"n":4,"role":"import","title":"Record what the factory answered","ep":"POST /api/factory-quotes/{id}/receive → /mark-ready-for-costing","svc":"FactoryQuoteService.receive",
  "dto":"ReceiveFactoryQuoteRequest → FactoryQuoteDto","writes":["sales.factory_quote","sales.factory_quote_item","sales.factory_quote_response_receipt"],
  "state":"Quote: REQUESTED → RESPONSE_RECEIVED → READY_FOR_COSTING","note":"clientRequestId makes the write idempotent — a retried request returns the first result instead of duplicating."},
 {"n":5,"role":"import","title":"Compute the landed cost","ep":"POST /api/pricing-requests/{id}/costings → /submit","svc":"PricingCostingService.createDraft → submit",
  "dto":"CreateCostingRequest → PricingCostingDto (37-field items)","writes":["sales.pricing_costing","sales.pricing_costing_item"],
  "state":"PCR: AWAITING_FACTORY_RESPONSE → READY_FOR_CEO_REVIEW","note":"Goods + freight + insurance + duty + inland → CIF → landed cost per unit, with the FX rate frozen into the row. Since V140 costing no longer owns a status — Import clicks one button that chains markReady → createCosting → recalculate → submit. This is the number the whole price rests on, and the package with zero tests."},
 {"n":6,"role":"ceo","title":"Turn cost into price","ep":"POST /api/pricing-requests/{id}/pricing-decisions → /approve","svc":"PricingDecisionService.startReview → approve",
  "dto":"ApprovePricingDecisionRequest → PricingDecisionDto","writes":["sales.pricing_decision","sales.pricing_decision_item"],
  "state":"PCR: READY_FOR_CEO_REVIEW → CEO_REVIEWING → APPROVED_FOR_QUOTATION","note":"approve() never trusts a supplied price — it recomputes from the frozen cost and the margin. It also freezes a discount floor Sales cannot go under."},
 {"n":7,"role":"sales","title":"Quote the customer","ep":"POST /api/pricing-requests/{id}/quotations → /issue","svc":"CustomerQuotationService.create → issue",
  "dto":"CreateCustomerQuotationRequest → CustomerQuotationDto","writes":["sales.quotation","sales.quotation_item"],
  "state":"PCR: APPROVED_FOR_QUOTATION → QUOTATION_ISSUED · deal stage advances","note":"The one place the chain calls TicketService rather than TicketRepository — to reuse the existing stage transition instead of inventing a second one. Only the FIRST issue moves the PCR."},
 {"n":8,"role":"sales","title":"Customer accepts","ep":"POST /api/customer-quotations/{id}/outcome","svc":"CustomerQuotationService.recordOutcome",
  "dto":"RecordQuotationOutcomeRequest → CustomerQuotationDto","writes":["sales.quotation"],
  "state":"PCR: QUOTATION_ISSUED → QUOTATION_ACCEPTED (terminal)","note":"There is deliberately no QUOTATION_REJECTED status — rejection lives only on the document, and Sales decides what happens next."},
 {"n":9,"role":"sales","title":"Confirm the order — the bridge","ep":"POST /api/pricing-requests/{id}/confirm-order","svc":"OrderConfirmationService.confirmOrder",
  "dto":"ConfirmOrderRequest → OrderConfirmationResultDto","writes":["sales.ticket","sales.ticket_item","sales.ticket_event","sales.deposit_notice"],
  "state":"ticket.status draft → quotation_issued · payment_status=CUSTOMER_CONFIRMED","note":"THE critical hop. The PCR aggregate writes back into the Deal: reconciles ticket_item.qty to what this PCR settled on, because the delivery machinery reads quantities from there."},
 {"n":10,"role":"sales → account","title":"Deposit issued, then paid","ep":"POST /api/deposit-notices/{id}/issue → POST /api/tickets/{id}/deposit-paid","svc":"DepositNoticeService.issue → TicketService.confirmDepositPaid",
  "dto":"DepositNoticeDto → TicketDetailResponse","writes":["sales.deposit_notice","sales.payment_receipt","sales.ticket"],
  "state":"payment_status: DEPOSIT_NOTICE_ISSUED → DEPOSIT_PAID","note":"Handoff from Sales to Accounting. issue() is the single writer of DEPOSIT_NOTICE_ISSUED."},
 {"n":11,"role":"import","title":"Import and deliver","ep":"POST /api/tickets/{id}/import-request → /shipping → /goods-received → /deliveries/complete","svc":"TicketService.issueImportRequest … completeDelivery",
  "dto":"RecordDeliveryRequest → TicketDetailResponse","writes":["sales.ticket","sales.delivery_record","sales.delivery_record_item"],
  "state":"fulfillment_status: IR_ISSUED → SHIPPING → GOODS_RECEIVED → FULLY_DELIVERED","note":"Runs on ticket_item.qty — the quantities step 9 reconciled. Get that reconciliation wrong and delivery silently works off the wrong numbers."},
 {"n":12,"role":"account → ceo","title":"Final payment, then a two-signature close","ep":"POST /api/tickets/{id}/final-payment → /close/confirm → /close/verify","svc":"TicketService.confirmFinalPayment → confirmCloseReady → verifyClose",
  "dto":"→ TicketDetailResponse","writes":["sales.payment_receipt","sales.ticket","sales.ticket_event"],
  "state":"payment_status=FULLY_PAID · salesStage=CLOSED_PAID · lifecycle=COMPLETED","note":"confirmCloseReady is account-only and deliberately excludes CEO, because the CEO signs verifyClose. Two people, two signatures."},
 {"n":13,"role":"account → sales_manager → ceo","title":"Commission","ep":"POST /api/commissions/from-deal → /approve ×2","svc":"CommissionService.createFromDeal → approve",
  "dto":"SubmitCommissionRequest → CommissionDetailResponse","writes":["sales.commission_record","sales.invoice_details","sales.attachment"],
  "state":"SUBMITTED → MANAGER_APPROVED → APPROVED","note":"createFromDeal dual-writes the tax invoice attachment AND the commission in one transaction — which is exactly why no second upload path is allowed to exist."},
]

READING_ORDER = [
 {"n":1,"f":"ticket/TicketAccessPolicy.java","loc":130,"why":"Shortest file that teaches the domain: who may reach a deal and its documents, and why each exclusion exists. Start here."},
 {"n":2,"f":"pricingrequest/PricingRequestStatus.java","loc":110,"why":"The one rigorous state machine. Its ALLOWED map is the spine of the whole redesigned chain."},
 {"n":3,"f":"ticket/DealStage.java + DealLifecycle.java","loc":90,"why":"The two ticket tracks that actually have guards. Everything else on the ticket is looser than these."},
 {"n":4,"f":"orderconfirmation/OrderConfirmationService.java","loc":517,"why":"The bridge. Read it once and you understand how the PCR aggregate and the Deal aggregate meet — and the failure mode when they don't."},
 {"n":5,"f":"pricingcosting/PricingCostingService.java","loc":1016,"why":"The money math. Untested, so read it carefully rather than trusting it."},
 {"n":6,"f":"ticket/TicketService.java","loc":1955,"why":"The big one. Read it LAST — it makes far more sense once you know the chain has taken pricing away from it."},
]

payload = {
 "meta": {"branch":"uat","date":"2026-08-12","files":len(files),"loc":sum(x['loc'] for x in files),
          "dtos":len(dtos),"fields":sum(len(d['fields']) for d in dtos),"endpoints":len(eps),
          "tables":len(tables),"tablesWritten":sum(1 for t in tables if t["insert"] or t["update"]),"migrations":len(migrations)},
 "services": SERVICES, "supporting": SUPPORTING, "dtos": dtos, "endpoints": eps,
 "tables": tables, "migrations": migrations, "files": files,
 "roles": ROLES, "matrix": MATRIX, "matrixNotes": MATRIX_NOTES,
 "trace": TRACE, "reading": READING_ORDER,
}
json.dump(payload, open(S + 'combined.json', 'w'), separators=(',', ':'))
print(f"OK dtos={len(dtos)} eps={len(eps)} tables={len(tables)} migrations={len(migrations)} "
      f"files={len(files)} loc={payload['meta']['loc']}", file=sys.stderr)
