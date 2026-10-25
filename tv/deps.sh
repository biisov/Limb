#!/usr/bin/env bash
# Какие приложения обслуживают клавиатуру, настройки, Wi-Fi, Bluetooth, входы HDMI и главный экран.
# Их нельзя выключать: если среди них есть пакет оболочки производителя, он идёт в PROTECTED.
# Только читает телевизор.
#   tv/deps.sh <IP телевизора> [путь к HomeMode.java из Free My TV]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

[ $# -ge 1 ] || { sed -n '2,5p' "$0"; exit 2; }
connect "$1"
src=${2:-$here/../free-my-tv/app/src/io/freemytv/HomeMode.java}

# Пакеты из списка DEBLOAT в Free My TV (то, что он выключит сам).
debloat=""
if [ -f "$src" ]; then
    debloat=$(awk '/DEBLOAT = \{/{f=1} f{print} f&&/\};/{exit}' "$src" | grep -oE '"[A-Za-z0-9_.]+"' | tr -d '"' | sort -u)
fi

show() {  # show "Что" команда…
    local title=$1; shift
    echo "== $title"
    local list
    list=$("$@" 2>/dev/null | components | sed 's#/.*##' | sort -u || true)
    [ -n "$list" ] || { echo "   (не найдено)"; return; }
    echo "$list" | while read -r p; do
        if [ -n "$debloat" ] && echo "$debloat" | grep -qx "$p"; then
            echo "   $p   <-- ВНИМАНИЕ: в DEBLOAT Free My TV, нужно в PROTECTED"
        else
            echo "   $p"
        fi
    done
}
act() { tvsh cmd package resolve-activity --brief -a "$1"; }
svc() { tvsh cmd package query-services --brief -a "$1"; }

show "Клавиатуры (InputMethod)"       svc android.view.InputMethod
echo "   по умолчанию: $(tvsh settings get secure default_input_method)"
show "Настройки"                      act android.settings.SETTINGS
show "Настройки Wi-Fi"                act android.settings.WIFI_SETTINGS
show "Сеть"                           act android.settings.WIRELESS_SETTINGS
show "Bluetooth и пульт"              act android.settings.BLUETOOTH_SETTINGS
show "Выбор клавиатуры"               act android.settings.INPUT_METHOD_SETTINGS
show "Входы ТВ и HDMI (TvInputService)" svc android.media.tv.TvInputService
show "Главные экраны"                 tvsh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
show "Заставки (DreamService)"        svc android.service.dreams.DreamService
