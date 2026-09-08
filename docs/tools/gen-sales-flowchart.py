#!/usr/bin/env python3
"""Extract every state transition the sales pipeline can actually take, from source.

Two kinds of edge, and the distinction is the whole point:

  DECLARED  - the class ships an explicit transition table that the code checks before
              issuing SQL (only PricingRequestStatus does this). An illegal edge throws.
  INFERRED  - the class is bare constants; the real edges live in guarded UPDATE
              statements in the repository. Nothing validates them centrally, so these
              are "what the code happens to do", not "what the model permits".

An INFERRED edge with no status guard in its WHERE clause is UNGUARDED: that write can
land from ANY prior state. Those are flagged, because they are where a state machine
silently isn't one.

    cd backend/src/main/java/th/co/glr/hr
    python3 ../../../../../../../../docs/tools/gen-sales-flowchart.py

Writes flowchart.json next to itself. Regenerate rather than hand-editing the diagram.
"""
import re, os, json, glob, sys, collections

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '')

# ── 1. DECLARED: PricingRequestStatus.ALLOWED ────────────────────────────────
def declared_pricing_request():
    src = open('pricingrequest/PricingRequestStatus.java', errors='replace').read()
    consts = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', src))
    body = src[src.find('ALLOWED = Map.ofEntries'):]
    edges = []
    for m in re.finditer(r'Map\.entry\(\s*([A-Z_]+)\s*,\s*Set\.(?:<String>)?of\(([^)]*)\)', body):
        frm = consts.get(m.group(1), m.group(1))
        for t in re.findall(r'[A-Z_]{3,}', m.group(2)):
            edges.append({'from': frm, 'to': consts.get(t, t), 'kind': 'declared', 'guard': 'ALLOWED map'})
    return edges

# ── 2. INFERRED: guarded UPDATE statements in repositories ───────────────────
# Each Java text block ("""...""") holds at most one statement, so split on blocks
# FIRST and only then look for UPDATE. Scanning across block boundaries mis-attributes
# the table in any method that issues several UPDATEs (e.g. cancelOpenStep2Children,
# which updates factory_quote AND pricing_costing).
BLOCK = re.compile(r'"""(.*?)"""', re.S)

# The ticket alone carries FIVE parallel tracks in differently-named columns, and the
# quotation uses doc_status. Scanning only `status` would miss exactly the tracks that
# matter most. Each is treated as its own machine, keyed "<table>.<column>".
TRACK_COLS = ['status', 'doc_status', 'fulfillment_status', 'payment_status',
              'lifecycle', 'sales_stage']

def inferred_from_sql():
    out = collections.defaultdict(list)
    for f in glob.glob('*/*Repository.java') + glob.glob('*/*/*Repository.java'):
        src = open(f, errors='replace').read()
        cls = os.path.basename(f)[:-5]
        for blk in BLOCK.finditer(src):
            body = blk.group(1)
            tm = re.search(r'UPDATE\s+(sales\.\w+)', body, re.I)
            if not tm:
                continue
            for col in TRACK_COLS:
                _scan_track(out, body, tm.group(1), col, cls)
    return out

def _scan_track(out, body, table, col, cls):
            # The assignment must be found in the SET clause ONLY. Scanning the whole
            # statement lets a lazy match run past SET into WHERE and read the GUARD as if
            # it were the assignment — which invents a self-loop for every
            # "UPDATE ... SET other_field = ... WHERE status = 'X'" (edit-while-in-state,
            # not a transition). That produced 22 phantom X -> X edges before this fix.
            up = body.upper()
            si, wi = up.find('SET'), up.find('WHERE')
            if si < 0:
                return
            set_clause = body[si:wi] if wi > si else body[si:]
            # A CASE expression carries its own guard, and the naive literal search below reads
            # the WHEN *condition* as if it were the assigned value — inverting the edge. e.g.
            # "status = CASE WHEN status = 'OPEN' THEN 'SHIPPING' ELSE status END" is OPEN ->
            # SHIPPING, but the plain regex reports "-> OPEN". Handle CASE first, and treat the
            # WHEN condition as the from-state since it is a guard the database itself enforces.
            case = re.search(r"\b" + col + r"\s*=\s*CASE\b(.*?)\bEND\b", set_clause, re.I | re.S)
            if case:
                table = f"{table}.{col}"
                for cond, then in re.findall(r"WHEN\s+(.*?)\s+THEN\s+(:?\w+|'[A-Za-z_]+')",
                                             case.group(1), re.I | re.S):
                    if not then.startswith("'"):
                        # THEN :param — the value is supplied by Java, so SQL constrains nothing.
                        out[table].append({'from': '*ANY*', 'to': '*:' + then.lstrip(':') + '*',
                                           'kind': 'inferred', 'guard': 'UNGUARDED (CASE :param)',
                                           'via': cls})
                        continue
                    src = re.search(r"\b" + col + r"\s*=\s*'([A-Za-z_]+)'", cond, re.I)
                    out[table].append({'from': src.group(1) if src else '*ANY*',
                                       'to': then.strip("'"), 'kind': 'inferred',
                                       'guard': 'CASE' if src else 'UNGUARDED (CASE)', 'via': cls})
                return
            to = re.search(r"\b" + col + r"\s*=\s*'([A-Za-z_]+)'", set_clause, re.I)
            if not to:
                return
            table = f"{table}.{col}"
            where = body[wi:] if wi > si else ''
            C = col
            pos = re.findall(r"'([A-Za-z_]+)'", ' '.join(re.findall(C+r"\s+IN\s*\(([^)]*)\)", where, re.I)))
            pos += re.findall(r"\b"+C+r"\s*=\s*'([A-Za-z_]+)'", where, re.I)
            neg = re.findall(r"\b"+C+r"\s*(?:<>|!=)\s*'([A-Za-z_]+)'", where, re.I)
            neg += re.findall(r"'([A-Za-z_]+)'", ' '.join(re.findall(C+r"\s+NOT\s+IN\s*\(([^)]*)\)", where, re.I)))
            # parameterised guard: status = :expected — a compare-and-set whose from-state is
            # supplied at runtime. This is the STRONGEST guard in the codebase (see
            # PricingRequestRepository.transition, which validates against the ALLOWED map
            # before issuing the SQL). Treating it as unguarded defames the best code here.
            param = re.findall(r"\b"+C+r"\s*=\s*:(\w+)", where, re.I)
            if param and not pos:
                out[table].append({'from': '*CAS: :' + param[0] + '*', 'to': to.group(1),
                                   'kind': 'inferred', 'guard': 'compare-and-set', 'via': cls})
                return
            if pos:
                for s in dict.fromkeys(pos):
                    out[table].append({'from': s, 'to': to.group(1), 'kind': 'inferred',
                                       'guard': 'positive', 'via': cls})
            elif neg:
                out[table].append({'from': '*ANY except ' + ', '.join(dict.fromkeys(neg)) + '*',
                                   'to': to.group(1), 'kind': 'inferred',
                                   'guard': 'negative', 'via': cls})
            else:
                out[table].append({'from': '*ANY*', 'to': to.group(1), 'kind': 'inferred',
                                   'guard': 'UNGUARDED', 'via': cls})

# ── 3. Ordered stage list ────────────────────────────────────────────────────
def deal_stages():
    src = open('ticket/DealStage.java', errors='replace').read()
    consts = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', src))
    order_blk = re.search(r'ORDER\s*=\s*List\.of\((.*?)\);', src, re.S)
    order = [consts.get(x, x) for x in re.findall(r'[A-Z_]{3,}', order_blk.group(1))] if order_blk else []
    return order

# ── 4. Constants per machine (to spot unreachable states) ────────────────────
MACHINES = {
    'PricingRequest': 'pricingrequest/PricingRequestStatus.java',
    'DealStage': 'ticket/DealStage.java',
    'DealLifecycle': 'ticket/DealLifecycle.java',
    'Fulfilment': 'ticket/FulfilmentStatus.java',
    'Quotation': 'ticket/QuotationStatus.java',
    'FactoryQuote': 'factoryquote/FactoryQuoteStatus.java',
    'PricingCosting': 'pricingcosting/PricingCostingStatus.java',
    'PricingDecision': 'pricingdecision/PricingDecisionStatus.java',
    'FactoryPurchaseOrder': 'procurement/FactoryPurchaseOrderStatus.java',
    'Commission': 'commission/CommissionStatus.java',
}

def constants():
    out = {}
    for name, path in MACHINES.items():
        if not os.path.exists(path):
            continue
        src = open(path, errors='replace').read()
        vals = [v for _, v in re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', src)]
        out[name] = sorted(dict.fromkeys(vals))
    return out

# ── 5. Dead constants: declared but never referenced outside their own class ──
def dead_constants(consts):
    allsrc = ''
    for f in glob.glob('**/*.java', recursive=True):
        allsrc += open(f, errors='replace').read()
    dead = {}
    for machine, path in MACHINES.items():
        if machine not in consts:
            continue
        cls = os.path.basename(path)[:-5]
        own = open(path, errors='replace').read()
        d = []
        for v in consts[machine]:
            name = re.search(r'String\s+([A-Z_]+)\s*=\s*"' + re.escape(v) + r'"', own)
            if not name:
                continue
            uses = len(re.findall(r'\b' + cls + r'\.' + name.group(1) + r'\b', allsrc))
            lit = len(re.findall(r"'" + re.escape(v) + r"'", allsrc))
            if uses == 0 and lit == 0:
                d.append(v)
        if d:
            dead[machine] = d
    return dead

# ── 1b. DECLARED: PaymentTrack (two policy-dependent paths, added V142) ──────
def declared_payment_track():
    """PaymentTrack is the SECOND declared machine in the pipeline. Unlike
    PricingRequestStatus it carries TWO transition maps, chosen by ticket.deposit_policy,
    so a from-state can have different legal successors depending on the deal."""
    f = 'ticket/PaymentTrack.java'
    if not os.path.exists(f):
        return {}
    src = open(f, errors='replace').read()
    consts = dict(re.findall(r'String\s+([A-Z_]+)\s*=\s*"([^"]+)"', src))
    out = {}
    for label, var in (('REQUIRED', 'REQUIRED_ALLOWED'), ('BYPASS', 'BYPASS_ALLOWED')):
        m = re.search(var + r'\s*=\s*Map\.of\((.*?)\);', src, re.S)
        if not m:
            continue
        edges = []
        for em in re.finditer(r'([A-Z_]{3,})\s*,\s*Set\.(?:<String>)?of\(([^)]*)\)', m.group(1)):
            frm = consts.get(em.group(1), em.group(1))
            for t in re.findall(r'[A-Z_]{3,}', em.group(2)):
                edges.append({'from': frm, 'to': consts.get(t, t),
                              'kind': 'declared', 'guard': var})
        out[label] = edges
    return out

payload = {
    'declared': {'PricingRequest': declared_pricing_request()},
    'declaredPaymentTrack': declared_payment_track(),
    'inferred': {k: v for k, v in inferred_from_sql().items()},
    'dealStageOrder': deal_stages(),
    'constants': constants(),
}
payload['deadConstants'] = dead_constants(payload['constants'])
json.dump(payload, open(OUT + 'flowchart.json', 'w'), indent=1)

d = len(payload['declared']['PricingRequest'])
i = sum(len(v) for v in payload['inferred'].values())
u = sum(1 for v in payload['inferred'].values() for e in v if e['guard'] == 'UNGUARDED')
pt = sum(len(v) for v in payload['declaredPaymentTrack'].values())
print(f"declared={d}+{pt}pt inferred={i} unguarded={u} tables={len(payload['inferred'])} "
      f"dead={sum(len(v) for v in payload['deadConstants'].values())}", file=sys.stderr)
