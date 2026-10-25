#!/usr/bin/env bash
# Таблица «до и после» из двух замеров tv/measure.sh. Телевизор не нужен.
#   tv/compare.sh measurements/before-… measurements/after-…
set -euo pipefail
export LC_ALL=C
[ $# = 2 ] || { sed -n '2,3p' "$0"; exit 2; }
v() { grep -m1 "^$2=" "$1/summary.env" | cut -d= -f2-; }
mb() { [ -n "$1" ] && awk -v k="$1" 'BEGIN { printf "%.0f МБ", k / 1024 }' || echo "—"; }
gb() { [ -n "$1" ] && awk -v k="$1" 'BEGIN { printf "%.2f ГБ", k / 1048576 }' || echo "—"; }
delta() {  # delta до после единица(МБ|ГБ|шт)
    [ -n "$1" ] && [ -n "$2" ] || { echo "—"; return; }
    awk -v a="$1" -v b="$2" -v u="$3" 'BEGIN {
        d = b - a; if (u == "МБ") d /= 1024; else if (u == "ГБ") d /= 1048576
        p = (a != 0) ? sprintf(" (%+.0f%%)", (b - a) * 100 / a) : ""
        printf (u == "ГБ" ? "%+.2f %s%s" : "%+.0f %s%s"), d, u, p }'
}
row() {  # row "Показатель" КЛЮЧ формат(mb|gb|n) подсказка
    local a b; a=$(v "$B" "$2"); b=$(v "$A" "$2")
    case $3 in
        mb) printf '| %s | %s | %s | %s |\n' "$1" "$(mb "$a")" "$(mb "$b")" "$(delta "$a" "$b" МБ) $4" ;;
        gb) printf '| %s | %s | %s | %s |\n' "$1" "$(gb "$a")" "$(gb "$b")" "$(delta "$a" "$b" ГБ) $4" ;;
        n)  printf '| %s | %s | %s | %s |\n' "$1" "${a:-—}" "${b:-—}" "$(delta "$a" "$b" шт) $4" ;;
    esac
}
B=$1; A=$2
echo "| Показатель | До | После | Разница |"
echo "|---|---|---|---|"
row "Доступно памяти (MemAvailable)" MEM_AVAILABLE_KB mb "больше = лучше"
row "Совсем свободно (MemFree)" MEM_FREE_KB mb ""
row "Свободно по dumpsys (Free RAM)" FREE_RAM_KB mb "больше = лучше"
row "Занято (Used RAM)" USED_RAM_KB mb "меньше = лучше"
row "Главный экран занимает (PSS)" HOME_PSS_KB mb "меньше = лучше"
row "Процессов в памяти" PROCESSES n "меньше = лучше"
row "Включено приложений" ENABLED_PKGS n ""
row "Свободно места (/data)" DATA_FREE_KB gb ""
echo
echo "Главный экран: до — $(v "$B" HOME), после — $(v "$A" HOME)"
echo "Всего памяти: $(mb "$(v "$B" MEM_TOTAL_KB)"). Замеры: $(v "$B" WHEN) и $(v "$A" WHEN)."
