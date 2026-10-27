#!/usr/bin/env python3
"""Download the benchmark datasets of a huspm-datasets release into datasets/ and verify every file.

    python3 tools/fetch_datasets.py                       # the pinned release
    python3 tools/fetch_datasets.py v1.1.1-seed42-lognormal
    python3 tools/fetch_datasets.py --library <library-root>   # no download: the copy in the shared data library

With --library, the archive and the manifest are read from 02_ChuanSPMF/huspm-datasets-releases/ inside the
library; both must hash to the values of the GitHub release pinned below (RELEASES), so the copy cannot drift.
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
LIBRARY_DIR = "02_ChuanSPMF/huspm-datasets-releases"
# tag -> (archive sha256, MANIFEST.sha256 sha256), as the GitHub release reports them (asset digests)
RELEASES = {
    "v1.1.1-seed42-lognormal": ("a7237a0f1df3bc896b040f90a4ee72db344367800e73de7eca063c02601237f9",
                                "461de41e4c5d013b06087391840cd086a98643d6160f3880c88501bd32b54912"),
}


def get(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def from_library(root, tag):
    d = Path(root) / LIBRARY_DIR
    files = (d / ("huspm-datasets-%s.tar.gz" % tag), d / ("MANIFEST_%s.sha256" % tag))
    if tag not in RELEASES:
        raise SystemExit("REFUSED: no pinned digests for release %s; add them to RELEASES" % tag)
    out = []
    for p, want in zip(files, RELEASES[tag]):
        if not p.exists():
            raise SystemExit("REFUSED: %s does not exist" % p.as_posix())
        b = p.read_bytes()
        got = hashlib.sha256(b).hexdigest()
        if got != want:
            raise SystemExit("REFUSED: %s: sha256 %s, release digest %s" % (p.as_posix(), got, want))
        out.append(b)
    return out


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    global DEST
    library = None
    for flag in ("--library", "--dest"):
        if flag in argv:
            i = argv.index(flag)
            if i + 1 >= len(argv):
                raise SystemExit("REFUSED: %s needs a path" % flag)
            if flag == "--library":
                library = argv[i + 1]
            else:
                DEST = Path(argv[i + 1])
            del argv[i:i + 2]
    tag = argv[0] if argv else PINNED
    DEST.mkdir(exist_ok=True)
    archive = "huspm-datasets-%s.tar.gz" % tag
    if library:
        data, manifest = from_library(library, tag)
        manifest = manifest.decode("utf-8")
    else:
        manifest = get("https://raw.githubusercontent.com/%s/%s/MANIFEST.sha256" % (REPO, tag)).decode("utf-8")
        data = get("https://github.com/%s/releases/download/%s/%s" % (REPO, tag, archive))
    want = {}
    for line in manifest.splitlines():
        parts = line.split()
        if len(parts) == 2:
            want[parts[1].lstrip("*")] = parts[0]
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
