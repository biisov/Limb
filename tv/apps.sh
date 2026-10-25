#!/usr/bin/env bash
# Список приложений телевизора: пакет, включено ли, системное или ваше, название на экране и что о нём знает
# Free My TV (выключит сам / защищено). Только читает телевизор.
#   tv/apps.sh <IP телевизора> [файл, по умолчанию apps.tsv]
# Названия берутся из APK через aapt2 (Android SDK build-tools); без него колонка пустая.
# APK больше 80 МБ не копируются (это Google Play Services, WebView и т. п.: их и так никто не трогает).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

[ $# -ge 1 ] || { sed -n '2,6p' "$0"; exit 2; }
connect "$1"
out=${2:-apps.tsv}
src=$here/../free-my-tv/app/src/io/freemytv/HomeMode.java

aapt2=$(command -v aapt2 || ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/*/aapt2 2>/dev/null | tail -n 1 || true)
[ -n "$aapt2" ] || echo "aapt2 не найден: названия будут пустыми."

list_of() { [ -f "$src" ] && awk -v s="$1" 'index($0, s){f=1} f{print} f&&/\)\);|\};/{exit}' "$src" | grep -oE '"[A-Za-z0-9_.]+"' | tr -d '"' | sort -u || true; }
debloat=$(list_of 'DEBLOAT = {'); protected=$(list_of 'PROTECTED = new')

pkgs -d > "${TMPDIR:-/tmp}/fmtv-disabled.$$"; pkgs -3 > "${TMPDIR:-/tmp}/fmtv-user.$$"
cache=$(mktemp -d); trap 'rm -rf "$cache" "${TMPDIR:-/tmp}"/fmtv-*.$$' EXIT

printf 'package\tstate\tkind\tlabel\tfree_my_tv\n' > "$out"
n=0; total=$(pkgs | wc -l | tr -d ' ')
pkgs | while read -r p; do
    n=$((n + 1)); printf '\r%s/%s %-60s' "$n" "$total" "$p" >&2
    state=enabled; grep -qx "$p" "${TMPDIR:-/tmp}/fmtv-disabled.$$" && state=disabled
    kind=system;   grep -qx "$p" "${TMPDIR:-/tmp}/fmtv-user.$$" && kind=user
    fm=""
    echo "$debloat"   | grep -qx "$p" && fm="выключит сам"
    echo "$protected" | grep -qx "$p" && fm="защищено"
    label=""
    if [ -n "$aapt2" ]; then
        apk=$(tvsh pm path "$p" | sed -n 's/^package://p' | grep -m1 'base.apk$\|\.apk$' || true)
        size=$( [ -n "$apk" ] && tvsh stat -c %s "$apk" 2>/dev/null || echo 0 )
        if [ -n "$apk" ] && [ "${size:-0}" -lt 83886080 ] 2>/dev/null; then
            if adb -s "$T" pull "$apk" "$(native "$cache/a.apk")" </dev/null >/dev/null 2>&1; then
                badging=$("$aapt2" dump badging "$(native "$cache/a.apk")" 2>/dev/null || true)
                label=$(echo "$badging" | sed -n "s/^application-label-ru:'\(.*\)'$/\1/p" | head -n 1)
                [ -n "$label" ] || label=$(echo "$badging" | sed -n "s/^application-label:'\(.*\)'$/\1/p" | head -n 1)
                rm -f "$cache/a.apk"
            fi
        fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$p" "$state" "$kind" "$label" "$fm" >> "$out"
done
echo >&2
echo "Готово: $out ($(($(wc -l < "$out") - 1)) приложений)"
