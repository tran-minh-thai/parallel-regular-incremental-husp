#!/usr/bin/env python3
"""Build every measured dataset of the campaign from the raw files and prove the build is byte-identical.

    python3 tools/build_all_datasets.py <library-root> [--only NAME[,NAME...]] [--verify-only]

<library-root> is the data root holding the raw files in the layout below; each raw file is
read at its path inside it (RAW below). --verify-only checks the raw files and stops. Each raw file must hash to
the value recorded in the committed manifests; then every dataset is built with tools/build_stream_datasets.py
into datasets/, which rewrites its manifest under tools/stream_datasets/manifests/. The build is accepted only
if every field of each new manifest except the builder's own hash equals the committed one (so every output file
has the committed SHA-256 and every count is the same); the committed manifest is then restored.
Exit status is non-zero on a missing or changed raw file, a failed build, or any manifest difference.
Standard library only; runs on Windows and macOS.
"""
import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# raw file -> (path inside the library, SHA-256)
RAW = {
    "transactions.csv.gz": ("01_BanLe_GioHang/valued-shopper",
                            "75cc4fa0d0006b9c43454e3dc1dea502b297ed5c75f3d679aae1df1acf633303"),
    "transactions_train.csv": ("01_BanLe_GioHang/hm-fashion",
                               "fff5e3b35c1b26016aa0bf2231be9525bc766eed32c94f1653420b465aef84fa"),
    "articles.csv": ("01_BanLe_GioHang/hm-fashion",
                     "25f21882053517f7684ea43f524225a9481a7bb9aab9cf0abfc7fe8c4576a72b"),
    "ecommerce-behavior-data-from-multi-category-store.zip": ("01_BanLe_GioHang/rees46",
                                                              "0d23bc5116a31f34ee97e459bcd1ebfff5a4d262391ae82a4ddaab830314eecb"),
    "ecommerce-events-history-in-cosmetics-shop.zip": ("01_BanLe_GioHang/rees46",
                                                       "9e6412743b279180e025d0d4491033dc693867ae92a7851264ea2dbbf5568922"),
    "transaction_data.csv": ("01_BanLe_GioHang/dunnhumby-complete-journey",
                             "3a685c0729cef664d634486189f774518b84f53cde7cbf701a5963238692b476"),
    "online_retail_II.xlsx": ("01_BanLe_GioHang/online-retail",
                              "bcbe73b35f5b7babf197fb0cb983a11f5d9ff929078d4aa53d171b1f2df2e980"),
    "ta_feng_all_months_merged.csv": ("01_BanLe_GioHang/ta-feng",
                                      "1d575e5d0b7207d7706d22ca56c7535886fff8175ca5537a310333a4ab7a7b67"),
}
# (datasets produced, builder arguments after the kind; {raw:NAME} is replaced by the raw file path)
BUILDS = [
    (["VS-CHAIN96-CW", "VS-CHAIN101-CW", "VS-CHAIN152-CW", "VS-CHAIN46-CW", "VS-CHAIN360-CW"],
     ["vs", "{raw:transactions.csv.gz}", "96,101,152,46,360"]),
    (["VS-CHAIN152-CW-NOACC", "VS-CHAIN46-CW-NOACC"], ["vs", "{raw:transactions.csv.gz}", "152,46", "1000"]),
    (["HM-CW-TYPE"], ["hmtype", "{raw:transactions_train.csv}", "{raw:articles.csv}"]),
    (["HM-CW-TYPE-12W"], ["hmtype", "{raw:transactions_train.csv}", "{raw:articles.csv}", "12"]),
    (["REES46-MULTI-CW"], ["rees46", "{raw:ecommerce-behavior-data-from-multi-category-store.zip}", "REES46-MULTI-CW"]),
    (["REES46-COSMETICS-CW"], ["rees46", "{raw:ecommerce-events-history-in-cosmetics-shop.zip}", "REES46-COSMETICS-CW"]),
    (["DUNNHUMBY-H"], ["dunnhumby", "{raw:transaction_data.csv}"]),
    (["DUNNHUMBY-HW"], ["dunnhumbyhw", "{raw:transaction_data.csv}"]),
    (["ONLINE-RETAIL-II-H"], ["retail", "{raw:online_retail_II.xlsx}"]),
    (["TAFENG-CW"], ["tafeng", "{raw:ta_feng_all_months_merged.csv}"]),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
    return h.hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("library_root")
    ap.add_argument("--only")
    ap.add_argument("--verify-only", action="store_true")
    args = ap.parse_args(argv)
    lib = Path(args.library_root)

    def raw_path(name):
        return lib / RAW[name][0] / name
    only = set(args.only.split(",")) if args.only else None
    builds = [b for b in BUILDS if not only or only & set(b[0])]
    needed = sorted({a[5:-1] for _, argl in builds for a in argl if a.startswith("{raw:")})
    problems = []
    for name in needed:
        p = raw_path(name)
        if not p.exists():
            problems.append("raw file %s does not exist" % p.as_posix())
            continue
        got = sha256(p)
        if got != RAW[name][1]:
            problems.append("raw file %s: sha256 %s, expected %s" % (p.as_posix(), got, RAW[name][1]))
        else:
            print("raw ok  %s" % p.as_posix(), flush=True)
    if problems:
        for x in problems:
            print("REFUSED: " + x)
        return 2
    if args.verify_only:
        print("all %d raw file(s) present with the expected SHA-256" % len(needed))
        return 0
    for names, argl in builds:
        cmd = [sys.executable, "tools/build_stream_datasets.py", argl[0]] + \
              [str(raw_path(a[5:-1])) if a.startswith("{raw:") else a for a in argl[1:2]] + \
              ["datasets", "tools/stream_datasets/manifests"] + \
              [str(raw_path(a[5:-1])) if a.startswith("{raw:") else a for a in argl[2:]]
        print("build   %s" % ",".join(names), flush=True)
        r = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True)
        if r.returncode != 0:
            print("FAILED: %s\n%s" % (" ".join(cmd), r.stderr[-2000:]))
            return 1
    import json
    bad, builder_changed = [], []
    for names, _ in builds:
        for n in names:
            path = "tools/stream_datasets/manifests/%s.manifest.json" % n
            committed = subprocess.run(["git", "show", "HEAD:" + path], cwd=str(ROOT), capture_output=True, text=True)
            if committed.returncode != 0:
                bad.append("%s: not in the commit" % path)
                continue
            old, new = json.loads(committed.stdout), json.loads((ROOT / path).read_text(encoding="utf-8"))
            diff = [k for k in set(old) | set(new) if k != "builder_sha256" and old.get(k) != new.get(k)]
            if diff:
                bad.append("%s: differs in %s" % (path, ", ".join(sorted(diff))))
            elif old.get("builder_sha256") != new.get("builder_sha256"):
                builder_changed.append(n)
            # the committed manifest stays the record, so the tracked tree is clean for the campaign
            subprocess.run(["git", "checkout", "--", path], cwd=str(ROOT))
    if bad:
        for x in bad:
            print("MISMATCH: " + x)
        return 1
    print("all %d dataset(s) built byte-identical to the committed manifests%s"
          % (sum(len(n) for n, _ in builds),
             "" if not builder_changed else " (builder file changed since the committed build of %s; outputs identical)"
             % ",".join(builder_changed)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
