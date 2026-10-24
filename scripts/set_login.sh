#!/usr/bin/env bash
set -euo pipefail

# Stores your F1 account in the patched app on the TV, for automatic re-login when F1 TV drops the
# session. Read from a .env file (same keys as race-control-tv) and sent over adb; it is kept on
# the TV only, encrypted with an Android Keystore key, and never printed here.
#
# Usage: scripts/set_login.sh [path/to/.env] [--clear]
#   .env keys: F1_username / F1_password (F1_USERNAME / F1_PASSWORD also work)
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

send() {
    local out
    out="$("${ADB[@]}" shell am broadcast -n "${RECEIVER}" -a "${ACTION}" "$@" 2>&1)"
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
send --es email_b64 "$(b64 "${EMAIL}")" --es password_b64 "$(b64 "${PASSWORD}")" --ez login_now true
unset EMAIL PASSWORD
echo "Account stored on the TV. If the app is on the sign-in screen it signs in now; otherwise"
echo "it signs back in by itself whenever F1 TV drops the session."
