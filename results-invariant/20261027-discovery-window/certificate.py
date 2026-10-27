"""Regularity-aware output-size certificate of a discovery window W (suffix window, regularity bound B):
a pattern X (one itemset) contained in a day-basket A of s1 and a day-basket C of s2 survives the engine's window
pruning when the first occurrence is within B of the window start, consecutive occurrences are within B of each
other, and the last one is within B of the window end (checked on s1, s2 only: more occurrences only shorten the
gaps and add utility). Its utility is at least u(X,s1) + u(X,s2). With Z = A n C and m the fewest items of Z
(largest v1+v2 first) reaching tau, every X with top-m(Z) <= X <= Z qualifies: >= 2^(|Z| - m) distinct patterns.
A single sequence gives the same with s1 = s2 (A = C, utility v1). c(W) = largest such |Z| - m found.
The search keeps it cheap by trying only sequences with u(s) >= tau/8 as partners: any pair found is a valid
lower bound; pairs not tried only make the certificate weaker."""
import csv, io, json, math, re, subprocess, sys
sys.path.insert(0, sys.argv[2]); import rho_lib as ro  # noqa: E402
ROOT = sys.argv[1]
cfg = ro.cfg
TOK = re.compile(r'(\d+)\[(\d+)\]')
cache = {}
def baskets(ds):
    if ds not in cache:
        out = []
        for line in open(ROOT + "/datasets/%s_seq.txt" % ds):
            seq = []
            for part in line.split(" -1 "):
                b = {}
                for it, v in TOK.findall(part):
                    b[it] = max(b.get(it, 0), int(v))
                if b: seq.append(b)
            out.append((sum(sum(b.values()) for b in seq), seq))
        cache[ds] = out
    return cache[ds]
def m_needed(vals, tau):
    s = 0
    for i, v in enumerate(sorted(vals, reverse=True)):
        s += v
        if s >= tau: return i + 1
    return None
def cert(ds, lo, hi, tau, B):
    S = baskets(ds); n = hi - lo; best = 0
    heavy = [p for p in range(n) if S[lo + p][0] >= tau / 8]
    for i, p1 in enumerate(heavy):
        if p1 + 1 > B: break                                   # leading gap from the window start
        u1, s1 = S[lo + p1]
        if n - p1 <= B:                                        # single sequence: trailing gap too
            for A in s1:
                m = m_needed(A.values(), tau)
                if m is not None: best = max(best, len(A) - m)
        for p2 in heavy[i + 1:]:
            if p2 - p1 > B: break
            if n - p2 > B: continue                            # trailing gap from the last occurrence
            u2, s2 = S[lo + p2]
            if u1 + u2 < tau: continue
            for A in s1:
                for C in s2:
                    Z = A.keys() & C.keys()
                    if not Z: continue
                    m = m_needed([A[z] + C[z] for z in Z], tau)
                    if m is not None: best = max(best, len(Z) - m)
    return best
if __name__ == "__main__":
    B_ = "origin/campaign-9950x"
    dirs = [l for l in subprocess.run(["git", "-C", ROOT, "ls-tree", "--name-only", B_, "results/9950x/"], capture_output=True, text=True).stdout.split() if "_E2-S" in l]
    show = lambda p: subprocess.run(["git", "-C", ROOT, "show", "%s:%s" % (B_, p)], capture_output=True, text=True).stdout
    rows = []
    for dpath in dirs:
        ds, sch = dpath.split("_")[2], dpath.split("_E2-")[1][:2]
        ex = list(csv.DictReader(io.StringIO(show(dpath + "/exactness.csv"))))
        tm = list(csv.DictReader(io.StringIO(show(dpath + "/timing_feasibility.csv"))))
        if not ex: continue
        Breg = cfg["datasets"][ds]["B"]
        F = {e["query"]: int(t["query_ms"]) for e, t in zip(ex, tm) if e["policy"] == "remine-nogc"}
        pred = {p["q"]: p for p in ro.series(ds, sch)}
        for e in ex:
            if e["policy"] != "bud3" or e["action"] not in ("discover", "aborted"): continue
            q = int(e["query"]); p = pred[q]; n = p["n_seqs"]; w = int(e["increment_seqs"])
            c = cert(ds, n - w, n, p["tau"], Breg)
            rows.append((ds, sch, q, e["action"], int(e["attempt_ms"]), F[e["query"]], int(e["candidates"] or 0), c))
    rows.sort(key=lambda r: r[7])
    viol = [r for r in rows if r[3] == "discover" and r[6] > 0 and r[7] > math.log2(r[6])]
    print("dataset         sch q  action    attempt   F_ms  candidates  c  log2(cand)")
    for r in rows: print("%-15s %-3s %-2d %-9s %7d %7d %10d %4d  %6.1f" % (r + (math.log2(r[6]) if r[6] else 0,)))
    nd = sum(1 for r in rows if r[3] == "discover")
    print("falsification: %d finished discoveries, %d with 2^c above the candidates the engine returned" % (nd, len(viol)))
