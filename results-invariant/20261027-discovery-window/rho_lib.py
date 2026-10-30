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
