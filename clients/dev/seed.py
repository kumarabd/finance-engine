#!/usr/bin/env python3
"""Sample data for the dev engine: categories, tags, merchants, and about a month of spends. Safe to run once per database."""
import json, sys, urllib.request, uuid, datetime

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18091") + "/api/v1/operations/"

def call(op, body):
    req = urllib.request.Request(BASE + op, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req) as r:
        return json.load(r)

def k(): return str(uuid.uuid4())

cats = {n: call("categories_create", {"idempotency_key": k(), "name": n})["id"] for n in ["Coffee", "Groceries", "Transport", "Dining"]}
tags = {n: call("tags_create", {"idempotency_key": k(), "name": n})["id"] for n in ["trip", "work", "gift"]}
merchants = {n: call("merchants_create", {"idempotency_key": k(), "name": n, "aliases": a})["id"]
             for n, a in [("Starbucks", ["SBUX"]), ("Whole Foods", []), ("Uber", []), ("Blue Bottle", [])]}

today = datetime.date.today()
first = today.replace(day=1)
rows = [  # day offset, merchant, category, minor, description, tags
    (0, "Starbucks", "Coffee", 550, "Latte", ["work"]), (0, "Uber", "Transport", 1840, "Ride to airport", ["trip"]),
    (1, "Whole Foods", "Groceries", 6420, "Weekly shop", []), (1, "Blue Bottle", "Coffee", 475, "Flat white", []),
    (2, "Starbucks", "Coffee", 625, "Cold brew", ["work"]), (3, "Uber", "Transport", 920, "Ride home", []),
    (3, None, None, 3000, "Birthday present", ["gift"]), (4, "Whole Foods", "Groceries", 2130, "Snacks", []),
    (5, None, "Dining", 4800, "Dinner with friends", []), (6, "Starbucks", "Coffee", 550, "Latte", ["work"]),
    (7, "Blue Bottle", "Coffee", 500, "Espresso", []), (8, "Uber", "Transport", 1560, "Ride", ["work"]),
    (9, None, None, 1299, "Subscription", []), (10, "Whole Foods", "Groceries", 8845, "Party supplies", ["gift"]),
]
made = []
for i, (off, m, c, minor, desc, tg) in enumerate(rows):
    day = max(first, today - datetime.timedelta(days=off)).isoformat()
    spend = {"occurred_on": day, "kind": "expense", "amount_minor": minor, "currency": "USD", "description": desc,
             "source": "seed", "source_record_id": f"seed-{i}"}
    if m: spend["merchant_id"] = merchants[m]
    if c: spend["allocations"] = [{"category_id": cats[c], "amount_minor": minor}]
    if tg: spend["tag_ids"] = [tags[t] for t in tg]
    made.append(call("spends_create", {"idempotency_key": k(), "spend": spend}))
refund = {"occurred_on": today.isoformat(), "kind": "refund", "amount_minor": 1560, "currency": "USD", "description": "Ride refund",
          "original_spend_id": made[11]["id"], "source": "seed", "source_record_id": "seed-refund"}
call("spends_create", {"idempotency_key": k(), "spend": refund})
print(f"seeded {len(rows) + 1} spends, {len(cats)} categories, {len(tags)} tags, {len(merchants)} merchants")
