#!/usr/bin/env python3
"""Build time-ordered quantitative sequence databases from raw retail and session logs.

Each output is a pair <NAME>_seq.txt / <NAME>_eui.txt in the format QSeqDatabase reads: one
sequence per line, events separated by -1, each item written as item[q]. Every external utility
is 1, so the utility of an item in an event is q, the MEASURED line value in the smallest
currency unit (summed when an item occurs twice in one event). Sequences are ordered by time, so
appending batches at the tail is the natural stream of the source.

Role table (one role per raw column):

  YOOCHOOSE-BUYS      sequence = a buying session, ordered by its first purchase timestamp;
                      event = the items bought at one timestamp; item = item id;
                      utility = price * quantity.
  ONLINE-RETAIL-II-H  sequence = one calendar hour with at least one invoice, in time order;
                      event = one invoice, ordered by invoice time; item = StockCode;
                      utility = round(quantity * price * 100). Each cancellation line
                      (customer c, code s, quantity -q) removes the latest earlier purchase
                      line with the same (c, s, q); unmatched cancellations are dropped.
  DUNNHUMBY-H         sequence = one (DAY, hour of TRANS_TIME) slot with at least one basket;
                      event = one basket (BASKET_ID), ordered by TRANS_TIME;
                      item = PRODUCT_ID; utility = round(SALES_VALUE * 100).

Rows with a non-positive price, quantity or value are dropped and counted. Item ids are remapped
to 1..n in order of first appearance. A manifest JSON records input and output SHA-256, every
count, and the regularity bound B derived from the declared rule (7 days of sequences for hourly
data, 1 day of sessions for session data).

Usage:
  python3 tools/build_stream_datasets.py yoochoose  <yoochoose-buys.dat>       <out-dir> <manifest-dir>
  python3 tools/build_stream_datasets.py retail     <online_retail_II.xlsx>    <out-dir> <manifest-dir>
  python3 tools/build_stream_datasets.py dunnhumby  <transaction_data.csv>     <out-dir> <manifest-dir>
  python3 tools/build_stream_datasets.py hm         <transactions_train.csv>   <out-dir> <manifest-dir> [max_weeks]
  python3 tools/build_stream_datasets.py vs         <transactions.csv.gz>      <out-dir> <manifest-dir> <chain>

  HM-CW               sequence = (customer, ISO week), ordered by week then customer id; event = one
                      purchase day; item = article_id; utility = round(price * 1e6) per row.
  VS-CHAIN<c>-CW      one chain; same construction; item = category|company|brand;
                      utility = round(purchaseamount * 100).
  For both, the order of customers inside a week is arbitrary (by id) and B spans two weeks.

Standard library only, so it runs unchanged on Windows.
"""
import csv
import datetime
import hashlib
import json
import math
import os
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import OrderedDict, defaultdict


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_db(name, sequences, out_dir, manifest_dir, raw_path, counts, b_rule, b_value, roles):
    """sequences: iterable of lists of events; an event is an OrderedDict raw_item -> utility (int > 0).
    b_value: the regularity bound, or a function (n_sequences, counts) -> bound evaluated after writing,
    so a generator can stream sequences without holding them all."""
    item_ids = {}
    seq_path = os.path.join(out_dir, name + "_seq.txt")
    eui_path = os.path.join(out_dir, name + "_eui.txt")
    total_utility = 0
    n_events = 0
    n_items = 0
    n_seqs = 0
    with open(seq_path, "w", newline="\n") as f:
        for seq in sequences:
            n_seqs += 1
            parts = []
            for ev in seq:
                toks = []
                for raw, u in ev.items():
                    if raw not in item_ids:
                        item_ids[raw] = len(item_ids) + 1
                    toks.append((item_ids[raw], u))
                toks.sort()
                parts.append(" ".join("%d[%d]" % (i, u) for i, u in toks) + " -1")
                total_utility += sum(u for _, u in toks)
                n_events += 1
                n_items += len(toks)
            f.write(" ".join(parts) + " -2\n")
    with open(eui_path, "w", newline="\n") as f:
        f.write("# ItemID:Profit (all 1: the item quantity field carries the measured line value)\n")
        for i in range(1, len(item_ids) + 1):
            f.write("%d:1\n" % i)
    if callable(b_value):
        b_value = b_value(n_seqs, counts)
    manifest = OrderedDict([
        ("name", name),
        ("builder", "tools/build_stream_datasets.py"),
        ("builder_sha256", sha256(os.path.abspath(__file__))),
        ("raw_file", os.path.basename(raw_path)),
        ("raw_sha256", sha256(raw_path)),
        ("roles", roles),
        ("values", "measured"),
        ("sequences", n_seqs),
        ("events", n_events),
        ("item_occurrences", n_items),
        ("distinct_items", len(item_ids)),
        ("total_utility", total_utility),
        ("counts", counts),
        ("regularity_bound_rule", b_rule),
        ("regularity_bound_B", b_value),
        ("seq_sha256", sha256(seq_path)),
        ("eui_sha256", sha256(eui_path)),
    ])
    os.makedirs(manifest_dir, exist_ok=True)
    with open(os.path.join(manifest_dir, name + ".manifest.json"), "w", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(json.dumps(manifest, indent=2))


def add(ev, item, u):
    ev[item] = ev.get(item, 0) + u


def build_yoochoose(raw, out_dir, manifest_dir):
    counts = defaultdict(int)
    sessions = defaultdict(lambda: defaultdict(OrderedDict))   # session -> timestamp -> event
    with open(raw, newline="") as f:
        for row in csv.reader(f):
            counts["rows"] += 1
            sid, ts, item, price, qty = row[0], row[1], row[2], int(row[3]), int(row[4])
            if price <= 0 or qty <= 0:
                counts["dropped_nonpositive_price_or_quantity"] += 1
                continue
            add(sessions[sid][ts], item, price * qty)
    order = sorted(sessions, key=lambda s: (min(sessions[s]), s))
    seqs = [[sessions[s][ts] for ts in sorted(sessions[s])] for s in order]
    days = len({min(sessions[s])[:10] for s in order})
    counts["days"] = days
    b = math.ceil(len(seqs) / days)
    write_db("YOOCHOOSE-BUYS", seqs, out_dir, manifest_dir, raw, counts,
             "sessions per day, ceil(N / days)", b,
             "sequence=session ordered by first purchase time; event=items bought at one timestamp; "
             "item=item id; utility=price*quantity")


# ---------------------------------------------------------------- Online Retail II (xlsx)

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def xlsx_rows(path):
    """Yield the cell values of every row of every sheet, header rows included, as lists of str."""
    with zipfile.ZipFile(path) as z:
        strings = []
        with z.open("xl/sharedStrings.xml") as f:
            for _, el in ET.iterparse(f):
                if el.tag == NS + "si":
                    strings.append("".join(t.text or "" for t in el.iter(NS + "t")))
                    el.clear()
        sheets = sorted(n for n in z.namelist() if n.startswith("xl/worksheets/sheet"))
        for sheet in sheets:
            with z.open(sheet) as f:
                for _, el in ET.iterparse(f):
                    if el.tag != NS + "row":
                        continue
                    cells = {}
                    for c in el.findall(NS + "c"):
                        ref = c.get("r")
                        col = "".join(ch for ch in ref if ch.isalpha())
                        v = c.find(NS + "v")
                        if v is None:
                            is_ = c.find(NS + "is")
                            val = "".join(t.text or "" for t in is_.iter(NS + "t")) if is_ is not None else ""
                        else:
                            val = strings[int(v.text)] if c.get("t") == "s" else v.text
                        cells[col] = val
                    yield [cells.get(k, "") for k in "ABCDEFGH"]
                    el.clear()


def excel_time(serial):
    return datetime.datetime(1899, 12, 30) + datetime.timedelta(days=float(serial))


def build_retail(raw, out_dir, manifest_dir):
    counts = defaultdict(int)
    lines = []        # [time, invoice, code, qty, price, customer, alive]
    last_by_key = defaultdict(list)
    for r in xlsx_rows(raw):
        if r[0] == "Invoice":
            continue
        counts["rows"] += 1
        invoice, code, qty, when, price, cust = r[0], r[1], r[3], r[4], r[5], r[6]
        try:
            qty = int(float(qty)); price = float(price); t = excel_time(when)
        except ValueError:
            counts["dropped_unparsable"] += 1
            continue
        if invoice.startswith("C"):
            counts["cancellation_lines"] += 1
            stack = last_by_key.get((cust, code, -qty))
            while stack and not lines[stack[-1]][6]:
                stack.pop()
            if stack:
                lines[stack.pop()][6] = False
                counts["cancellations_matched"] += 1
            continue
        lines.append([t, invoice, code, qty, price, cust, True])
        last_by_key[(cust, code, qty)].append(len(lines) - 1)
    slots = OrderedDict()
    for t, invoice, code, qty, price, cust, alive in sorted(lines, key=lambda x: (x[0], x[1])):
        if not alive:
            counts["purchase_lines_removed_by_cancellation"] += 1
            continue
        if qty <= 0 or price <= 0:
            counts["dropped_nonpositive_price_or_quantity"] += 1
            continue
        u = int(round(qty * price * 100))
        if u <= 0:
            counts["dropped_zero_value"] += 1
            continue
        slot = t.strftime("%Y-%m-%d %H")
        events = slots.setdefault(slot, OrderedDict())
        add(events.setdefault(invoice, OrderedDict()), code, u)
    seqs = [list(evs.values()) for evs in slots.values()]
    days = len({s[:10] for s in slots})
    counts["days"] = days
    b = math.ceil(7 * len(seqs) / days)
    write_db("ONLINE-RETAIL-II-H", seqs, out_dir, manifest_dir, raw, counts,
             "7 days of sequences, ceil(7 * N / days)", b,
             "sequence=calendar hour with an invoice, time order; event=invoice, by invoice time; "
             "item=StockCode; utility=round(quantity*price*100); cancellations netted against the latest "
             "earlier purchase line with the same customer, code and quantity")


def build_dunnhumby(raw, out_dir, manifest_dir):
    counts = defaultdict(int)
    slots = defaultdict(lambda: defaultdict(OrderedDict))    # (day, hour) -> (time, basket) -> event
    with open(raw, newline="") as f:
        for row in csv.DictReader(f):
            counts["rows"] += 1
            qty = int(row["QUANTITY"]); value = float(row["SALES_VALUE"])
            if qty <= 0 or value <= 0:
                counts["dropped_nonpositive_quantity_or_value"] += 1
                continue
            u = int(round(value * 100))
            if u <= 0:
                counts["dropped_zero_value"] += 1
                continue
            day, tt = int(row["DAY"]), int(row["TRANS_TIME"])
            add(slots[(day, tt // 100)][(tt, row["BASKET_ID"])], row["PRODUCT_ID"], u)
    keys = sorted(slots)
    seqs = [[slots[k][e] for e in sorted(slots[k])] for k in keys]
    days = len({k[0] for k in keys})
    counts["days"] = days
    b = math.ceil(7 * len(seqs) / days)
    write_db("DUNNHUMBY-H", seqs, out_dir, manifest_dir, raw, counts,
             "7 days of sequences, ceil(7 * N / days)", b,
             "sequence=(DAY, hour of TRANS_TIME) slot with a basket, time order; event=basket, by TRANS_TIME; "
             "item=PRODUCT_ID; utility=round(SALES_VALUE*100)")


def iso_week(date_str):
    y, m, d = map(int, date_str[:10].split("-"))
    iy, iw, _ = datetime.date(y, m, d).isocalendar()
    return "%04d-W%02d" % (iy, iw)


def week_sequences(week_rows):
    """week_rows: customer -> day -> event; yields one sequence per customer, customers in id order."""
    for cust in sorted(week_rows):
        days = week_rows[cust]
        yield [days[d] for d in sorted(days)]


def build_hm(raw, out_dir, manifest_dir, max_weeks=0):
    """H&M transactions_train.csv (t_dat, customer_id, article_id, price, sales_channel_id), sorted by date."""
    counts = defaultdict(int)

    def stream():
        week_rows, cur, last_date, weeks = defaultdict(lambda: defaultdict(OrderedDict)), None, "", 0
        with open(raw, newline="") as f:
            r = csv.reader(f)
            next(r)
            for row in r:
                counts["rows"] += 1
                d = row[0]
                if d < last_date:
                    sys.exit("input is not sorted by date at row %d" % counts["rows"])
                last_date = d
                wk = iso_week(d)
                if wk != cur:
                    if cur is not None:
                        yield from week_sequences(week_rows)
                        week_rows = defaultdict(lambda: defaultdict(OrderedDict))
                        weeks += 1
                        if max_weeks and weeks >= max_weeks:
                            counts["stopped_after_weeks"] = weeks
                            counts["weeks"] = weeks
                            return
                    cur = wk
                u = int(round(float(row[3]) * 1_000_000))
                if u <= 0:
                    counts["dropped_nonpositive_price"] += 1
                    continue
                add(week_rows[row[1]][d], row[2], u)
        if cur is not None:
            yield from week_sequences(week_rows)
            weeks += 1
        counts["weeks"] = weeks

    name = "HM-CW" if not max_weeks else "HM-CW-%dW" % max_weeks
    write_db(name, stream(), out_dir, manifest_dir, raw, counts,
             "2 weeks of sequences, ceil(2 * N / weeks)", lambda n, c: math.ceil(2 * n / c["weeks"]),
             "sequence=(customer, ISO week), ordered by week then customer id; event=one purchase day of that "
             "customer in the week; item=article_id; utility=round(price*1e6) per row (one row = one unit), "
             "summed per item and day; the price column is scaled by an undisclosed constant")


def build_vs(raw, out_dir, manifest_dir, chain):
    """Valued Shoppers transactions.csv.gz restricted to one chain; item = category|company|brand."""
    import gzip
    counts = defaultdict(int)
    rows = defaultdict(lambda: defaultdict(OrderedDict))     # (week, customer) -> day -> event
    with gzip.open(raw, "rt", newline="") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            counts["rows"] += 1
            if row[1] != chain:
                continue
            counts["rows_in_chain"] += 1
            qty, amount = int(row[9]), float(row[10])
            if qty <= 0 or amount <= 0:
                counts["dropped_nonpositive_quantity_or_amount"] += 1
                continue
            u = int(round(amount * 100))
            if u <= 0:
                counts["dropped_zero_value"] += 1
                continue
            d = row[6]
            add(rows[(iso_week(d), row[0])][d], row[3] + "|" + row[4] + "|" + row[5], u)
    keys = sorted(rows)
    seqs = [[rows[k][d] for d in sorted(rows[k])] for k in keys]
    weeks = len({k[0] for k in keys})
    counts["weeks"] = weeks
    b = math.ceil(2 * len(seqs) / weeks)
    write_db("VS-CHAIN%s-CW" % chain, seqs, out_dir, manifest_dir, raw, counts,
             "2 weeks of sequences, ceil(2 * N / weeks)", b,
             "one chain (%s); sequence=(customer, ISO week), ordered by week then customer id; event=one purchase "
             "day; item=category|company|brand; utility=round(purchaseamount*100)" % chain)


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    kind, raw, out_dir, manifest_dir = sys.argv[1:5]
    extra = sys.argv[5:]
    if kind == "hm":
        build_hm(raw, out_dir, manifest_dir, int(extra[0]) if extra else 0)
    elif kind == "vs":
        build_vs(raw, out_dir, manifest_dir, extra[0])
    else:
        {"yoochoose": build_yoochoose, "retail": build_retail, "dunnhumby": build_dunnhumby}[kind](
            raw, out_dir, manifest_dir)
