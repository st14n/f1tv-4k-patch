#!/usr/bin/env bash
set -euo pipefail

# Stores your F1 account in the patched app on the TV, for automatic re-login when F1 TV drops the
# session. Read from a .env file (same keys as race-control-tv) and sent over adb; it is kept on
# the TV only, encrypted with an Android Keystore key, and never printed here.
#
# Usage: scripts/set_login.sh [path/to/.env] [--clear]
#   .env keys: F1_username / F1_password (F1_USERNAME / F1_PASSWORD also work)
#              F1_DEVICE_ID (optional): the F1 device id to keep using. The script prints the id the
#              app has pinned; put it in .env so a reinstall (even with another key) stays the
#              same registered device and never counts against F1's device limit.
#   ADB_SERIAL=<ip:port> selects the TV when several devices are connected.
#   --clear removes the stored account from the TV.

PKG="com.formulaone.production"
RECEIVER="${PKG}/com.f1tvpatch.radio.LoginReceiver"
ACTION="com.f1tvpatch.SET_LOGIN"

ENV_FILE=""
CLEAR=0
for arg in "$@"; do
    case "${arg}" in
        --clear) CLEAR=1 ;;
        *) ENV_FILE="${arg}" ;;
    esac
done
ENV_FILE="${ENV_FILE:-${F1_ENV_FILE:-.env}}"

ADB=(adb)
[[ -n "${ADB_SERIAL:-}" ]] && ADB=(adb -s "${ADB_SERIAL}")

DEVICE_ID_REPORTED=""
send() {
    local out
    out="$("${ADB[@]}" shell am broadcast -n "${RECEIVER}" -a "${ACTION}" "$@" 2>&1)"
    if [[ "${out}" =~ device_id=([A-Za-z0-9._-]+) ]]; then DEVICE_ID_REPORTED="${BASH_REMATCH[1]}"; fi
    case "${out}" in
        *"result=1"*) return 0 ;;
        *"result=2"*) echo "The TV app got an empty username or password." >&2 ;;
        *"result=3"*) echo "The TV app could not store the account (see: adb logcat -s F1Radio)." >&2 ;;
        *) echo "No answer from the patched app. Is it installed (a build with auto-login) and adb connected?" >&2 ;;
    esac
    return 1
}

if [[ "${CLEAR}" == "1" ]]; then
    send --ez clear true && echo "Stored account removed from the TV."
    exit
fi

[[ -f "${ENV_FILE}" ]] || { echo "No .env at ${ENV_FILE} (pass its path as the first argument)" >&2; exit 1; }

# KEY=value lines; surrounding quotes stripped, comments ignored. Nothing is echoed.
read_env() {
    local key value line
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

EMAIL="$(read_env F1_username F1_USERNAME F1_EMAIL || true)"
PASSWORD="$(read_env F1_password F1_PASSWORD || true)"
[[ -n "${EMAIL}" && -n "${PASSWORD}" ]] || { echo "F1_username / F1_password not found in ${ENV_FILE}" >&2; exit 1; }

# base64 keeps any character safe through the adb shell
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }
DEVICE_ID="$(read_env F1_DEVICE_ID || true)"
DEVICE_ARGS=()
[[ -n "${DEVICE_ID}" ]] && DEVICE_ARGS=(--es device_id "${DEVICE_ID}")
send --es email_b64 "$(b64 "${EMAIL}")" --es password_b64 "$(b64 "${PASSWORD}")" --ez login_now true "${DEVICE_ARGS[@]}"
unset EMAIL PASSWORD
echo "Account stored on the TV. If the app is on the sign-in screen it signs in now; otherwise"
echo "it signs back in by itself whenever F1 TV drops the session."
if [[ -n "${DEVICE_ID_REPORTED}" && "${DEVICE_ID_REPORTED}" != "null" ]]; then
    echo "F1 device id in use: ${DEVICE_ID_REPORTED}"
    if [[ -z "${DEVICE_ID}" ]]; then
        echo "Add this line to ${ENV_FILE} to keep it across reinstalls:  F1_DEVICE_ID=${DEVICE_ID_REPORTED}"
    elif [[ "${DEVICE_ID}" != "${DEVICE_ID_REPORTED}" ]]; then
        echo "(F1_DEVICE_ID from .env applies from the next app start)"
    fi
fi
