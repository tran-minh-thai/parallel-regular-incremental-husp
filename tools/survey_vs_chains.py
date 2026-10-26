#!/usr/bin/env python3
"""Count rows, customers and positive-value rows per chain of the Valued Shoppers transactions file.

Usage: python3 tools/survey_vs_chains.py <transactions.csv.gz> <out.tsv>
Writes one line per chain: chain, rows, positive rows, customers, sorted by rows. Standard library only.
"""
import csv
import gzip
import sys
from collections import defaultdict


def main():
    raw, out = sys.argv[1], sys.argv[2]
    rows = defaultdict(int)
    pos = defaultdict(int)
    custs = defaultdict(set)
    with gzip.open(raw, "rt", newline="") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            c = row[1]
            rows[c] += 1
            if int(row[9]) > 0 and float(row[10]) > 0:
                pos[c] += 1
            custs[c].add(row[0])
    with open(out, "w", newline="\n") as f:
        f.write("chain\trows\tpositive_rows\tcustomers\n")
        for c in sorted(rows, key=lambda k: -rows[k]):
            f.write("%s\t%d\t%d\t%d\n" % (c, rows[c], pos[c], len(custs[c])))


if __name__ == "__main__":
    main()
