#!/usr/bin/env bash
# Резервная копия телевизора до любых изменений. Телевизор только читается, на нём ничего не меняется.
#   tv/backup.sh <IP телевизора> [папка]            полная копия, с APK приложений, которые вы ставили сами
#   tv/backup.sh <IP телевизора> <папка> --no-apk   только состояние (для сравнения «до» и «после»)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

[ $# -ge 1 ] || { sed -n '2,4p' "$0"; exit 2; }
connect "$1"
out=${2:-backups/$(date +%Y%m%d-%H%M%S)}
with_apk=1; [ "${3:-}" = --no-apk ] && with_apk=0
[ -e "$out" ] && { echo "Папка $out уже есть, выберите другую."; exit 1; }
mkdir -p "$out/settings" "$out/packages" "$out/input" "$out/apk"

echo "Устройство…"
tvsh getprop > "$out/getprop.txt"
for p in ro.product.brand ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release \
         ro.build.version.sdk ro.build.fingerprint ro.serialno ro.config.low_ram; do
    echo "$p=$(tvsh getprop "$p")"
done > "$out/device.txt"

echo "Приложения и их состояние…"
pkgs           > "$out/packages/all.txt"        # установлены для пользователя
pkgs -e        > "$out/packages/enabled.txt"
pkgs -d        > "$out/packages/disabled.txt"   # выключены (в том числе до нас)
pkgs -s        > "$out/packages/system.txt"
pkgs -3        > "$out/packages/user.txt"       # поставлены вами
pkgs -u        > "$out/packages/known.txt"      # плюс удалённые для пользователя, но лежащие в системе
tvsh pm list packages -f > "$out/packages/paths.txt" 2>&1 || true
tvsh dumpsys package > "$out/packages/dumpsys-package.txt" 2>&1 || true

echo "Настройки global, secure, system…"
for ns in global secure system; do
    tvsh settings list "$ns" | sort > "$out/settings/$ns.txt"
done

echo "Главный экран…"
home_component > "$out/home.component"
tvsh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    > "$out/home-candidates.txt" 2>&1 || true
tvsh cmd role get-role-holders android.app.role.HOME > "$out/home-role.txt" 2>&1 || true

echo "Клавиатура…"
tvsh ime list -a -s > "$out/input/ime-all.txt" 2>&1 || true
tvsh ime list -s    > "$out/input/ime-enabled.txt" 2>&1 || true
tvsh ime list -a    > "$out/input/ime-details.txt" 2>&1 || true

echo "Кто что обслуживает (клавиатура, настройки, Wi-Fi, входы HDMI)…"
"$here/deps.sh" "$1" > "$out/deps.txt" 2>&1 || true

echo "Wi-Fi (только имена сетей и кто их сохранил, без паролей)…"
{
    echo "# cmd wifi list-networks"
    tvsh cmd wifi list-networks 2>&1 || true
    echo; echo "# dumpsys wifi: ID, SSID, cname (кто сохранил сеть)"
    tvsh dumpsys wifi 2>/dev/null | grep -E 'ID: [0-9]| SSID: |cname=|cuid=|lname=' \
        | grep -viE 'psk|passw|passphrase|wep|sae' || true
} > "$out/wifi.txt"

echo "Входы и HDMI…"
tvsh dumpsys tv_input     > "$out/tv_input.txt" 2>&1 || true
tvsh dumpsys hdmi_control > "$out/hdmi_control.txt" 2>&1 || true

if [ "$with_apk" = 1 ]; then
    echo "APK ваших приложений ($(wc -l < "$out/packages/user.txt" | tr -d ' ') шт.)…"
    while read -r p; do
        [ -n "$p" ] || continue
        mkdir -p "$out/apk/$p"
        tvsh pm path "$p" | sed -n 's/^package://p' | while read -r f; do
            adb -s "$T" pull "$f" "$(native "$out/apk/$p")/" </dev/null >/dev/null 2>&1 || echo "  не удалось скопировать $p: $f"
        done
    done < "$out/packages/user.txt"
fi

# Копия сама себя откатывает: кладём рядом скрипт отката.
cp "$here/restore.sh" "$here/lib.sh" "$out/"
cat > "$out/КАК-ОТКАТИТЬ.txt" <<EOF
Резервная копия телевизора $(file_val ro.product.model "$out/device.txt"), $(date '+%Y-%m-%d %H:%M').

Проба (ничего не меняет, показывает, что вернётся):
    bash restore.sh . <IP телевизора>
Откат по-настоящему:
    bash restore.sh . <IP телевизора> --apply

Если стоит Free My TV, сначала проще так: на телевизоре Free My TV → Home Screen → Original.
EOF
(cd "$out" && find . -type f ! -name SHA256SUMS -exec bash -c '. ./lib.sh; sha256_all "$@"' _ {} + > SHA256SUMS)

echo
echo "Готово: $out"
echo "  приложений: $(wc -l < "$out/packages/all.txt" | tr -d ' '), выключено до нас: $(wc -l < "$out/packages/disabled.txt" | tr -d ' '), ваших: $(wc -l < "$out/packages/user.txt" | tr -d ' ')"
echo "  главный экран: $(cat "$out/home.component")"
echo "  клавиатура: $(file_val default_input_method "$out/settings/secure.txt")"
[ "$with_apk" = 1 ] && echo "  APK: $(du -sh "$out/apk" | cut -f1)"
exit 0
