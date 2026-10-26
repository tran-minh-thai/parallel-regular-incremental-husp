#!/usr/bin/env python3
"""Download the benchmark datasets of a huspm-datasets release into datasets/ and verify every file.

    python3 tools/fetch_datasets.py                       # the pinned release
    python3 tools/fetch_datasets.py v1.1.1-seed42-lognormal

The manifest is read from the same release tag as the archive (never from a moving branch), written to
datasets/MANIFEST.sha256, and every listed file must hash to it; any mismatch exits non-zero and names the
file. Standard library only, so it runs on Windows (fetch_datasets.sh needs bash and curl).
"""
import hashlib
import io
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "datasets"
REPO = "tran-minh-thai/huspm-datasets"
PINNED = "v1.1.1-seed42-lognormal"


def get(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    tag = argv[0] if argv else PINNED
    DEST.mkdir(exist_ok=True)
    manifest = get("https://raw.githubusercontent.com/%s/%s/MANIFEST.sha256" % (REPO, tag)).decode("utf-8")
    want = {}
    for line in manifest.splitlines():
        parts = line.split()
        if len(parts) == 2:
            want[parts[1].lstrip("*")] = parts[0]
    archive = "huspm-datasets-%s.tar.gz" % tag
    data = get("https://github.com/%s/releases/download/%s/%s" % (REPO, tag, archive))
    print("release %s: %s, %d bytes, sha256 %s" % (tag, archive, len(data), hashlib.sha256(data).hexdigest()))
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for m in tar.getmembers():
            name = Path(m.name).name
            if not m.isfile() or name not in want:
                continue
            (DEST / name).write_bytes(tar.extractfile(m).read())
    with open(DEST / "MANIFEST.sha256", "w", encoding="utf-8", newline="\n") as f:
        f.write(manifest if manifest.endswith("\n") else manifest + "\n")
    bad = []
    for name, h in sorted(want.items()):
        p = DEST / name
        got = hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else "missing"
        if got != h:
            bad.append("%s: %s, manifest %s" % (p.as_posix(), got, h))
    print("%d/%d files verified against the manifest of %s" % (len(want) - len(bad), len(want), tag))
    for b in bad:
        print("  MISMATCH " + b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
