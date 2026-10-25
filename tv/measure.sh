#!/usr/bin/env bash
# Замер памяти и места на телевизоре. Только читает, ничего не меняет.
#   tv/measure.sh <IP телевизора> <метка: before или after> [папка, по умолчанию measurements]
# Честно сравнивать замеры в одинаковых условиях: телевизор перезагружен, на главном экране, 3 минуты покоя.
# Память берём три раза с паузой 20 секунд, в итог идёт среднее из трёх (медиана).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

[ $# -ge 2 ] || { sed -n '2,5p' "$0"; exit 2; }
connect "$1"
out=${3:-measurements}/$2-$(date +%Y%m%d-%H%M%S)
mkdir -p "$out"

for i in 1 2 3; do
    echo "Замер памяти $i из 3…"
    tvsh cat /proc/meminfo > "$out/meminfo-$i.txt"
    [ "$i" = 3 ] || sleep 20
done
echo "dumpsys meminfo (секунд десять)…"
tvsh dumpsys meminfo > "$out/dumpsys-meminfo.txt" 2>&1 || true
tvsh df -k /data > "$out/df-data.txt" 2>&1 || true
tvsh df -h > "$out/df-all.txt" 2>&1 || true
tvsh uptime > "$out/uptime.txt" 2>&1 || true
home=$(home_component); echo "$home" > "$out/home.component"
home_pkg=${home%%/*}

med() {  # медиана поля /proc/meminfo по трём замерам, в КБ
    for i in 1 2 3; do awk -v k="$1:" '$1 == k {print $2}' "$out/meminfo-$i.txt"; done | sort -n | sed -n 2p
}
kb() { sed -n "s/^ *$1: *\([0-9,]*\)K.*/\1/p" "$out/dumpsys-meminfo.txt" | head -n 1 | tr -d ,; }
pss_section() { awk '/^Total PSS by process:/{f=1; next} f && /^[[:space:]]*$/{exit} f' "$out/dumpsys-meminfo.txt"; }

{
    echo "LABEL=$2"
    echo "WHEN=$(date '+%Y-%m-%d %H:%M')"
    echo "UPTIME=$(cat "$out/uptime.txt")"
    echo "MEM_TOTAL_KB=$(med MemTotal)"
    echo "MEM_AVAILABLE_KB=$(med MemAvailable)"
    echo "MEM_FREE_KB=$(med MemFree)"
    echo "CACHED_KB=$(med Cached)"
    echo "SWAP_TOTAL_KB=$(med SwapTotal)"
    echo "SWAP_FREE_KB=$(med SwapFree)"
    echo "FREE_RAM_KB=$(kb 'Free RAM')"
    echo "USED_RAM_KB=$(kb 'Used RAM')"
    echo "PROCESSES=$(pss_section | grep -c 'K: ' || true)"
    echo "HOME=$home"
    echo "HOME_PSS_KB=$(pss_section | awk -v p="$home_pkg" 'index($0, "K: " p " ") || index($0, "K: " p ":") {
        v = $1; gsub(/[,K:]/, "", v); s += v } END { print s + 0 }')"
    echo "DATA_SIZE_KB=$(awk 'NR == 2 {print $2}' "$out/df-data.txt")"
    echo "DATA_FREE_KB=$(awk 'NR == 2 {print $4}' "$out/df-data.txt")"
    echo "ENABLED_PKGS=$(pkgs -e | wc -l | tr -d ' ')"
} > "$out/summary.env"

echo
echo "Готово: $out"
sed 's/^/  /' "$out/summary.env"
