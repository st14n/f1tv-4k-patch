#!/usr/bin/env bash
set -euo pipefail

# PRIVATE build of the patched app with your F1 account inside, for your own devices only.
# Never publish its output: the account is stored in the APK. Releases are built by CI without it.
#
# Usage: scripts/build_private.sh <original.apkm|.xapk> <output-dir> [path/to/.env]
#
# Reads from the .env (default ./.env), never printing the values:
#   F1_username / F1_password   the account stored on first start (encrypted on the device)
#   F1_DEVICE_ID                optional: the F1 device id to keep (see set_login.sh)
#   CUSTOM_RADIO_URL            the radio stream (empty = build without radio)
# and from the environment:
#   F1TV_LANGUAGE (default nl)  language of the patch UI: auto | nl | en
#   F1TV_UHD_PATCHES (default 0) 0 = stock playback (the official app already serves 4K where supported)
#   KEYSTORE_PATH / KEYSTORE_PASS / KEYSTORE_ALIAS   signing key (same key = installs over earlier builds)
#   F1TV_UPDATE_REPO            GitHub owner/repo for the in-app updater (empty = no updater)

BUNDLE="${1:?original .apkm/.xapk required}"
OUT_DIR="${2:?output dir required}"
ENV_FILE="${3:-.env}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ -f "${ENV_FILE}" ]] || { echo "No .env at ${ENV_FILE}" >&2; exit 1; }

read_env() {   # <key>...  -> value of the first key found
    local key value line wanted
    while IFS= read -r line || [[ -n "${line}" ]]; do
        line="${line%$'\r'}"
        [[ -z "${line}" || "${line}" == \#* || "${line}" != *=* ]] && continue
        key="${line%%=*}"; key="${key// /}"
        for wanted in "$@"; do
            if [[ "${key}" == "${wanted}" ]]; then
                value="${line#*=}"
                value="${value#"${value%%[![:space:]]*}"}"; value="${value%"${value##*[![:space:]]}"}"
                value="${value#\"}"; value="${value%\"}"
                printf '%s' "${value}"
                return 0
            fi
        done
    done < "${ENV_FILE}"
    return 1
}

F1TV_LOGIN_EMAIL="$(read_env F1_username F1_USERNAME || true)"
F1TV_LOGIN_PASSWORD="$(read_env F1_password F1_PASSWORD || true)"
[[ -n "${F1TV_LOGIN_EMAIL}" && -n "${F1TV_LOGIN_PASSWORD}" ]] || { echo "F1_username / F1_password not found in ${ENV_FILE}" >&2; exit 1; }
F1TV_DEVICE_ID="$(read_env F1_DEVICE_ID || true)"
F1TV_CUSTOM_AUDIO_URL="$(read_env CUSTOM_RADIO_URL || true)"
export F1TV_LOGIN_EMAIL F1TV_LOGIN_PASSWORD F1TV_DEVICE_ID F1TV_CUSTOM_AUDIO_URL
export F1TV_EXTENSION=1
export F1TV_LANGUAGE="${F1TV_LANGUAGE:-nl}"
export F1TV_UHD_PATCHES="${F1TV_UHD_PATCHES:-0}"
export F1TV_HLG_BYPASS="${F1TV_HLG_BYPASS:-1}" F1TV_DISPLAY_HDR_SPOOF="${F1TV_DISPLAY_HDR_SPOOF:-1}"
export F1TV_RELEASE_TAG="${F1TV_RELEASE_TAG:-private}"

# patch.sh wants an .apkm: an .xapk is the same zip of split APKs
case "${BUNDLE}" in
    *.xapk) TMP_BUNDLE="$(mktemp -d)/bundle.apkm"; cp "${BUNDLE}" "${TMP_BUNDLE}"; BUNDLE="${TMP_BUNDLE}" ;;
esac

bash "${HERE}/patch.sh" "${BUNDLE}" "${OUT_DIR}"
echo "Private build in ${OUT_DIR} (contains your account: do not publish it)."
