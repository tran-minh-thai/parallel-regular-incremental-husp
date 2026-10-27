"""rho = (largest sequence utility in the discovery window) / (discovery threshold), from the data and the definitions
alone: batches as ExpUtil.split(fineRatios(k, dOld)); window = every sequence after the old part (no re-anchor can happen
before the last query of S1/S2/S3); tau = ceil(d*U(D_t)) - ceil(d*U(D_0)) + 1 (integer discovery threshold, theta0 = d*U(D_0)).
Validates tau against the value implied by the artifacts (r = tau*|W|/U(W)), then predicts rho per query everywhere."""
import csv, io, json, math, re, subprocess, sys
from fractions import Fraction
ROOT, OUT = sys.argv[1], sys.argv[2]
cfg = json.load(open(ROOT + "/experiments/campaign_9950x.json"))
SCHED = {"S1": (0.5, 21, "every"), "S2": (0.9, 11, "every"), "S3": (0.5, 21, "every:4")}
def jround(x): return math.floor(x + 0.5)
def split(n, dold, k):
    ratios = [dold] + [(1.0 - dold) / (k - 1)] * (k - 1); b = [0]
    for i, r in enumerate(ratios):
        cnt = n - b[-1] if i == k - 1 else jround(n * r); b.append(b[-1] + max(0, min(cnt, n - b[-1])))
    return b
def queries(spec, k):
    if spec == "every": return list(range(1, k))
    m = int(spec.split(":")[1]); return [b for b in range(1, k) if b % m == 0 or b == k - 1]
cache = {}
def utils(ds):
    if ds not in cache:
        cache[ds] = [sum(int(v) for v in re.findall(r'\[(\d+)\]', l)) for l in open(ROOT + "/datasets/%s_seq.txt" % ds)]
    return cache[ds]
def series(ds, sch):
    U = utils(ds); d = Fraction(cfg["datasets"][ds]["delta"]); dold, k, spec = SCHED[sch]
    b = split(len(U), dold, k); t0 = math.ceil(d * sum(U[:b[1]]))
    pref = sum(U[:b[1]]); umax = 0; out = []; qs = set(queries(spec, k)); q = 0
    for i in range(1, k):
        seg = U[b[i]:b[i + 1]]; pref += sum(seg); umax = max([umax] + seg)
        if i in qs:
            tau = math.ceil(d * pref) - t0 + 1
            out.append({"q": q, "n_seqs": b[i + 1], "tau": tau, "u_max": umax, "rho": umax / tau}); q += 1
    return out
# 1. validation against the artifacts
B = "origin/campaign-9950x"
dirs = [l for l in subprocess.run(["git", "-C", ROOT, "ls-tree", "--name-only", B, "results/9950x/"], capture_output=True, text=True).stdout.split() if "_E2-S" in l]
show = lambda p: subprocess.run(["git", "-C", ROOT, "show", "%s:%s" % (B, p)], capture_output=True, text=True).stdout
worst = 0; nval = 0; nseq_mismatch = 0
for dpath in dirs:
    ds, sch = dpath.split("_")[2], dpath.split("_E2-")[1][:2]
    ex = [e for e in csv.DictReader(io.StringIO(show(dpath + "/exactness.csv"))) if e["policy"] == "bud3"]
    if not ex: continue
    pred = {p["q"]: p for p in series(ds, sch)}
    U = utils(ds)
    for e in ex:
        q = int(e["query"]); p = pred[q]
        if int(e["n_seqs"]) != p["n_seqs"]: nseq_mismatch += 1
        if e["action"] in ("discover",):            # discover rows carry the discovery threshold itself
            nval += 1; worst = max(worst, abs(int(e["threshold"]) - p["tau"]) / p["tau"])
print("validation: %d discover rows, worst relative gap between artifact threshold and recomputed tau %.2e; n_seqs mismatches %d"
      % (nval, worst, nseq_mismatch))
# 2. prediction everywhere
rows = []
for ds in cfg["datasets"]:
    try: utils(ds)
    except FileNotFoundError: print("missing locally:", ds); continue
    for sch in SCHED:
        s = [p for p in series(ds, sch) if p["q"] >= 1]
        rows.append([ds, sch, len(s), "%.3f" % max(p["rho"] for p in s)] + [sum(1 for p in s if p["rho"] >= kap) for kap in (1, 2, 4, 8)])
with open(OUT, "w", newline="") as f:
    w = csv.writer(f, delimiter="\t"); w.writerow(["dataset", "schedule", "attempt_queries", "max_rho", "rho>=1", "rho>=2", "rho>=4", "rho>=8"]); w.writerows(rows)
for r in rows: print("\t".join(str(x) for x in r))
