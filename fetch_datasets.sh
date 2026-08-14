#!/usr/bin/env bash
#
# fetch_datasets.sh - Download the benchmark datasets into datasets/ and verify them.
#
# The datasets are shared by several HUSPM projects, so they live in their own repository
# rather than in this one:  https://github.com/tran-minh-thai/huspm-datasets
#
# Usage:
#   bash fetch_datasets.sh                      # pinned release (what the paper reports)
#   bash fetch_datasets.sh v1.1-seed42-lognormal  # a different release
#   bash fetch_datasets.sh --list               # show available releases
#
# Re-running is safe: files are overwritten with identical bytes and re-verified.

set -euo pipefail

VERSION="${1:-v1.1.1-seed42-lognormal}"
REPO="tran-minh-thai/huspm-datasets"
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$PROJ/datasets"

if [[ "$VERSION" == "--list" || "$VERSION" == "-l" ]]; then
    echo "Releases of $REPO:"
    curl -fsSL "https://api.github.com/repos/$REPO/releases" \
        | grep '"tag_name"' | sed 's/.*: *"/  /; s/",*$//'
    exit 0
fi

command -v curl >/dev/null || { echo "curl not found on PATH" >&2; exit 1; }
# macOS ships shasum, most Linux distributions ship sha256sum. Either will do.
if command -v shasum >/dev/null; then SHA=(shasum -a 256 -c)
elif command -v sha256sum >/dev/null; then SHA=(sha256sum -c)
else echo "neither shasum nor sha256sum found on PATH" >&2; exit 1; fi

TARBALL="huspm-datasets-$VERSION.tar.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$DEST"
echo "Release   : $VERSION"
echo "Target    : $DEST"

echo "Downloading $TARBALL ..."
curl -fL --progress-bar -o "$TMP/$TARBALL" \
    "https://github.com/$REPO/releases/download/$VERSION/$TARBALL"

echo "Extracting ..."
tar xzf "$TMP/$TARBALL" -C "$DEST"

# The manifest is kept in the repository, not in the tarball, and lists bare filenames --
# hence the separate download and the check from inside datasets/.
echo "Verifying checksums ..."
curl -fsSL -o "$DEST/MANIFEST.sha256" \
    "https://github.com/$REPO/releases/download/$VERSION/MANIFEST.sha256" \
  || curl -fsSL -o "$DEST/MANIFEST.sha256" \
    "https://raw.githubusercontent.com/$REPO/main/MANIFEST.sha256"
( cd "$DEST" && "${SHA[@]}" MANIFEST.sha256 )

echo
echo "Done. $(find "$DEST" -maxdepth 1 -name '*_seq.txt' | wc -l | tr -d ' ') sequence files in $DEST"
echo "Next:  bash run_experiments.sh --no-maven --test     # smoke test"
