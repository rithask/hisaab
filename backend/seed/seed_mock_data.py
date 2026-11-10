#!/usr/bin/env python3
"""Fill an empty Hisaab with realistic mock data through its own API.

    python3 backend/seed/seed_mock_data.py [base-url] [username] [password]

Defaults: http://localhost:8080 admin dev-pass. Uses only the standard library. The data is made up:
account numbers, people and amounts are fictional. Run it against an empty database (see README).
"""
import http.cookiejar
import json
import random
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, time, timedelta, timezone

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "dev-pass"
IST = timezone(timedelta(hours=5, minutes=30))
START = date(2026, 8, 1)
TODAY = datetime.now(IST).date()
rnd = random.Random(7)  # same data every run

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + "/api" + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with opener.open(req) as res:
            text = res.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} failed: {e.code} {e.read().decode()[:300]}")


def at(d, hh, mm=0):
    return datetime.combine(d, time(hh, mm), IST).isoformat()


call("POST", "/auth/login", {"username": USER, "password": PASSWORD})
if call("GET", "/accounts"):
    raise SystemExit("This database already has accounts. Reset it first (see README); refusing to mix mock data in.")

call("PUT", "/settings", {"ownerName": "Arjun Ravi K"})
cat = {c["name"]: c["id"] for c in call("GET", "/categories")}

# ---- accounts (fictional numbers) ------------------------------------------------------------------------
opening = at(START, 0)
def account(name, type_, inst, last4, balance, limit=None, stmt=None, due=None):
    return call("POST", "/accounts", {"name": name, "type": type_, "institution": inst, "last4": last4,
                                      "openingBalance": balance, "openingDate": opening, "creditLimit": limit,
                                      "statementDay": stmt, "dueDay": due})["id"]

AXIS = account("Axis Savings", "BANK", "AXIS", "4821", 42000)
CANARA = account("Canara Savings", "BANK", "CANARA", "3307", 8500)
HDFC = account("HDFC Swiggy", "CREDIT_CARD", "HDFC", "7731", 0, 100000, 1, 21)
AXCARD = account("Axis MY Zone", "CREDIT_CARD", "AXIS", "5590", 0, 50000, 12, 2)
CASH = account("Cash", "CASH", "OTHER", None, 2000)

people = {n: call("POST", "/people", {"name": n, "upiName": u})["id"]
          for n, u in [("Arun", "ARUN KUMAR S"), ("Priya", "PRIYA NAIR"), ("Rahul", "RAHUL MENON")]}

txns = []  # (date, hour, minute, account, direction, amount, payee, category or None, transfer, note)
def add(d, acct, direction, amount, payee, category=None, hour=None, minute=None, transfer=False, note=None):
    if d > TODAY or d < START:
        return
    txns.append((d, hour if hour is not None else rnd.randint(9, 21), minute if minute is not None else rnd.randint(0, 59),
                 acct, direction, round(amount, 2), payee, category, transfer, note))

def months():
    m = START
    while m <= TODAY:
        yield m
        m = date(m.year + (m.month == 12), m.month % 12 + 1, 1)

def day(m, d):
    return date(m.year, m.month, d)

# ---- the regular month ---------------------------------------------------------------------------------
for m in months():
    add(day(m, 1), AXIS, "CREDIT", 85000, "ACME TECHNOLOGIES PVT LTD", "Salary", 10, 5)
    add(day(m, 2), AXIS, "DEBIT", 10000, "ARJUN RAVI K", None, 9, 30, transfer=True, note="To Canara")
    add(day(m, 2), CANARA, "CREDIT", 10000, "ARJUN RAVI K", None, 9, 31, transfer=True, note="From Axis")
    add(day(m, 5), AXIS, "DEBIT", 18000, "SURESH BABU", "Rent", 8, 15, note="House rent")
    add(day(m, 7), AXIS, "DEBIT", 5000, "Zerodha Coin SIP", "Investments", 6, 0)
    add(day(m, 8), CANARA, "DEBIT", rnd.choice([1640, 1810, 1960]), "KSEB Electricity", "Bills & Utilities")
    add(day(m, 10), HDFC, "DEBIT", 599, "Airtel Postpaid", "Bills & Utilities")
    add(day(m, 12), CANARA, "DEBIT", 799, "ACT Fibernet", "Bills & Utilities")
    add(day(m, 14), HDFC, "DEBIT", 649 if m.month < 10 else 799, "Netflix", "Subscriptions", 0, 15)  # price rise in October
    add(day(m, 15), AXCARD, "DEBIT", 119, "Spotify", "Subscriptions", 0, 20)
    add(day(m, 20), HDFC, "DEBIT", 149, "YouTube Premium", "Subscriptions", 0, 30)

    last = (date(m.year + (m.month == 12), m.month % 12 + 1, 1) - timedelta(days=1)).day
    for d in range(1, last + 1):
        dt = day(m, d)
        if dt.weekday() in (2, 6):  # groceries twice a week
            shop = rnd.choice(["Swiggy Instamart", "Blinkit", "BigBasket", "Zepto"])
            add(dt, HDFC, "DEBIT", rnd.randint(380, 1450), shop, None, rnd.randint(17, 21))
        if rnd.random() < 0.42:  # food
            place, acct = rnd.choice([("Swiggy", HDFC), ("Zomato", HDFC), ("Third Wave Coffee", AXCARD), ("Paragon Restaurant", AXCARD),
                                      ("Chai Point", CASH), ("Burger King", AXCARD)])
            add(dt, acct, "DEBIT", rnd.randint(120, 780), place, "Food & Dining", rnd.choice([13, 14, 20, 21]))
        if rnd.random() < 0.18:  # rides
            add(dt, AXCARD, "DEBIT", rnd.randint(90, 420), rnd.choice(["Uber", "Ola", "Kochi Metro"]), "Transport")
    for d in (6, 21):  # fuel
        add(day(m, d), AXCARD, "DEBIT", rnd.choice([1500, 1800, 2000]), "HP Petrol Pump", "Fuel", 18)
    add(day(m, rnd.randint(9, 25)), HDFC, "DEBIT", rnd.randint(900, 3400), rnd.choice(["Amazon", "Myntra", "Decathlon"]), "Shopping")
    add(day(m, rnd.randint(3, 27)), CASH, "DEBIT", rnd.randint(150, 600), "Apollo Pharmacy", "Health")
    add(day(m, 25), HDFC, "CREDIT", round(rnd.uniform(40, 160), 2), "Swiggy CashBack", "Refund & Cashback", 0, 5)
    if m.month in (9, 10):
        add(day(m, 18), CASH, "CREDIT", 3000, "ATM withdrawal", None, 11, transfer=True)
        add(day(m, 18), AXIS, "DEBIT", 3000, "ATM withdrawal", None, 11, transfer=True)

# ---- one-offs ----------------------------------------------------------------------------------------------
add(date(2026, 8, 15), AXCARD, "DEBIT", 1240, "PVR Cinemas", "Entertainment", 19, 30)
add(date(2026, 9, 1), AXIS, "CREDIT", 412.5, "Savings interest", "Interest", 6, 0)
add(date(2026, 9, 9), HDFC, "DEBIT", 6480, "IndiGo Airlines", "Travel", 22, 10)
add(date(2026, 10, 3), HDFC, "DEBIT", 1850, "IRCTC", "Travel", 9, 40)
add(date(2026, 10, 28), AXIS, "CREDIT", 2500, "Amma", "Gift", 12, 0)
# Uncategorised on purpose: small UPI payments to people and shops the app doesn't know.
add(date(2026, 10, 17), CANARA, "DEBIT", 250, "RAJESH S", None, 18, 5)
add(date(2026, 11, 3), CANARA, "DEBIT", 180, "MURUGAN STORES", None, 8, 40)
add(date(2026, 11, 6), CASH, "DEBIT", 120, "Sree Bakery", None, 17, 15)
add(date(2026, 11, 8), CANARA, "DEBIT", 600, "ANIL KUMAR P", None, 20, 10)

# ---- card bill payments: pay each card's previous cycle in full ---------------------------------------
def card_total(acct, frm, to):
    return round(sum(a if dr == "DEBIT" else -a for (d, _, _, ac, dr, a, *_rest) in txns if ac == acct and frm <= d < to), 2)

for m in months():
    for acct, stmt, due, label in [(HDFC, 1, 18, "HDFC Credit Card"), (AXCARD, 12, 2, "Axis Credit Card")]:
        stmt_day = day(m, stmt)
        prev = date(stmt_day.year - (stmt_day.month == 1), (stmt_day.month - 2) % 12 + 1, stmt)
        amount = card_total(acct, max(prev, START), stmt_day)
        pay_on = stmt_day + timedelta(days=(due - stmt) % 31 or 20)
        if amount > 0 and pay_on <= TODAY:
            add(pay_on, AXIS, "DEBIT", amount, label + " bill", None, 10, 0, transfer=True)
            add(pay_on, acct, "CREDIT", amount, "Payment received", None, 10, 2, transfer=True)

# ---- write them ------------------------------------------------------------------------------------------
txns.sort(key=lambda t: (t[0], t[1], t[2]))
ids = {}
for d, h, mi, acct, direction, amount, payee, category, transfer, note in txns:
    t = call("POST", "/transactions", {"accountId": acct, "direction": direction, "amount": amount, "currency": "INR",
                                       "occurredAt": at(d, h, mi), "counterparty": payee, "notes": note,
                                       "categoryId": cat.get(category) if category else None, "transfer": transfer})
    ids[(d, payee, amount)] = t["id"]

# ---- things for Subscription watch and Unusual activity to notice ----------------------------------------
def post(d, h, mi, acct, direction, amount, payee, category=None, currency="INR"):
    if d > TODAY:
        return None
    return call("POST", "/transactions", {"accountId": acct, "direction": direction, "amount": amount, "currency": currency,
                                          "occurredAt": at(d, h, mi), "counterparty": payee,
                                          "categoryId": cat.get(category) if category else None})["id"]

for d in (date(2026, 9, 22), date(2026, 10, 22)):                        # a new subscription (trial turned paid)
    post(d, 0, 10, HDFC, "DEBIT", 299, "Disney+ Hotstar", "Subscriptions")
post(date(2026, 10, 20), 0, 32, HDFC, "DEBIT", 149, "YouTube Premium", "Subscriptions")  # charged twice, 2 minutes apart
post(date(2026, 11, 2), 11, 5, AXCARD, "DEBIT", 12, "Notion Labs", "Subscriptions", "USD")  # in dollars
late_night = post(date(2026, 10, 30), 1, 40, HDFC, "DEBIT", 3200, "Amazon", "Shopping")  # 1:40 am
post(date(2026, 11, 4), 20, 10, HDFC, "DEBIT", 4850, "Zomato", "Food & Dining")          # far above my usual Zomato order
post(date(2026, 10, 24), 16, 30, AXIS, "DEBIT", 15000, "CROMA RETAIL", "Shopping")       # big, to a new payee
returned = post(date(2026, 10, 26), 18, 0, HDFC, "DEBIT", 2199, "Myntra", "Shopping")
post(date(2026, 11, 1), 9, 0, HDFC, "CREDIT", 2199, "Myntra refund", "Refund & Cashback")
if late_night:
    call("POST", "/refunds", {"txnId": late_night})                     # returned it; waiting for the money
if returned:
    call("POST", "/refunds", {"txnId": returned})                       # already refunded

# ---- budgets and card rewards (made-up card terms) -------------------------------------------------------
for name, limit in [("Food & Dining", 8000), ("Groceries", 6000), ("Transport", 2500)]:
    call("PUT", f"/budgets/{cat[name]}", {"limit": limit})
call("PUT", f"/rewards/{HDFC}", {"kind": "CASHBACK", "baseRate": 1, "cycleCap": None, "rules": [
    {"label": "Swiggy", "merchantMatch": "swiggy", "rate": 10, "cycleCap": 1500},
    {"label": "Online shopping", "merchantMatch": "amazon", "rate": 5, "cycleCap": 1500},
    {"label": "Online shopping (Myntra)", "merchantMatch": "myntra", "rate": 5, "cycleCap": 1500},
    {"label": "Fuel", "categoryId": cat["Fuel"], "rate": 0}]})
call("PUT", f"/rewards/{AXCARD}", {"kind": "POINTS", "baseRate": 2, "pointValue": 0.2, "pointsBalance": 1240,
                                   "pointsAsOf": "2026-10-12", "rules": [{"label": "Fuel", "categoryId": cat["Fuel"], "rate": 0}]})

# ---- splits and repayments ----------------------------------------------------------------------------
def shared(d, acct, amount, payee, category, with_people, hour=21):
    t = call("POST", "/transactions", {"accountId": acct, "direction": "DEBIT", "amount": amount, "currency": "INR",
                                       "occurredAt": at(d, hour, 10), "counterparty": payee, "categoryId": cat[category]})
    call("PUT", f"/transactions/{t['id']}/split", {"method": "EQUAL", "includeMe": True,
                                                  "participants": [{"personId": people[p], "value": None} for p in with_people]})

def repaid(d, amount, name, confirm):
    t = call("POST", "/transactions", {"accountId": AXIS, "direction": "CREDIT", "amount": amount, "currency": "INR",
                                       "occurredAt": at(d, 19, 30), "counterparty": name})
    if confirm:
        for s in call("GET", "/settlements/suggested"):
            if s.get("txn", {}).get("id") == t["id"] or s.get("txnId") == t["id"]:
                call("POST", f"/settlements/{s['id']}/confirm", {"amount": amount})

shared(date(2026, 8, 24), HDFC, 3600, "Barbeque Nation", "Food & Dining", ["Arun", "Priya"])
repaid(date(2026, 8, 26), 1200, "ARUN KUMAR S", True)
repaid(date(2026, 8, 30), 1200, "PRIYA NAIR", True)
shared(date(2026, 10, 11), HDFC, 12000, "Airbnb Goa", "Travel", ["Priya", "Rahul"], 13)
repaid(date(2026, 10, 20), 4000, "PRIYA NAIR", True)
shared(date(2026, 11, 7), AXCARD, 900, "PVR Cinemas", "Entertainment", ["Arun"], 22)
repaid(date(2026, 11, 9), 450, "ARUN KUMAR S", False)  # left for you to confirm

# ---- investments ---------------------------------------------------------------------------------------
# Average prices sit near late-2026 market prices; live prices are fetched afterwards, so gains stay modest.
for sym, name, qty, avg in [("RELIANCE", "Reliance Industries", 10, 1250), ("TCS", "Tata Consultancy Services", 5, 2380),
                            ("INFY", "Infosys", 12, 1105), ("HDFCBANK", "HDFC Bank", 8, 742)]:
    call("POST", "/holdings", {"assetType": "STOCK", "symbol": sym, "exchange": "NSE", "name": name, "quantity": qty, "avgPrice": avg})
funds = call("GET", "/mf/search?" + urllib.parse.urlencode({"q": "parag parikh flexi cap direct growth"})) or []
fund = next((f for f in funds if "direct" in f["name"].lower() and "growth" in f["name"].lower()), funds[0] if funds else None)
if fund:
    call("POST", "/holdings", {"assetType": "MF", "symbol": fund["code"], "name": fund["name"], "quantity": 410.25, "avgPrice": 73.1})
call("POST", "/prices/refresh")

print(f"Seeded {len(txns) + 19} transactions across 5 accounts, 3 people, {4 + (1 if fund else 0)} holdings.")
