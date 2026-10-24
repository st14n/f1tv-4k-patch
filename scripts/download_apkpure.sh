#!/usr/bin/env bash
set -euo pipefail

# Downloads one F1TV TV version from APKPure and prints the path of an .apkm bundle.
# Usage: scripts/download_apkpure.sh <full-version> <output-dir>
#   e.g. scripts/download_apkpure.sh 3.0.49.4-SP166.4.1-release-R54.2-tv download

TV_VERSION="$1"
OUT_DIR="$2"
mkdir -p "${OUT_DIR}"

echo "Downloading F1TV TV version from APKPure: ${TV_VERSION}" >&2
apkeep -a "com.formulaone.production@${TV_VERSION}" -d apk-pure "${OUT_DIR}/" >&2

echo "Download directory contents:" >&2
ls -la "${OUT_DIR}/" >&2
DOWNLOADED=$(find "${OUT_DIR}/" -maxdepth 1 -name 'com.formulaone.production*' -print -quit)
if [[ -z "${DOWNLOADED}" ]]; then
    echo "::error::APKPure download failed" >&2
    exit 1
fi
echo "Downloaded: ${DOWNLOADED}" >&2

# XAPK is a zip of split APKs (same as APKM)
if [[ "${DOWNLOADED}" == *.xapk ]]; then
    mv "${DOWNLOADED}" "${DOWNLOADED%.xapk}.apkm"
    DOWNLOADED="${DOWNLOADED%.xapk}.apkm"
fi

# Single APK: wrap it into an APKM bundle so patch.sh can handle it
if [[ "${DOWNLOADED}" == *.apk ]]; then
    echo "Single APK detected, wrapping as APKM bundle..." >&2
    mkdir -p "${OUT_DIR}/bundle"
    cp "${DOWNLOADED}" "${OUT_DIR}/bundle/base.apk"
    (cd "${OUT_DIR}/bundle" && zip -q ../f1tv-apkpure.apkm base.apk)
    DOWNLOADED="${OUT_DIR}/f1tv-apkpure.apkm"
fi

printf '%s\n' "${DOWNLOADED}"
