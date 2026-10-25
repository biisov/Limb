#!/usr/bin/env bash
# Откат телевизора к состоянию из резервной копии (tv/backup.sh).
#   restore.sh <папка копии> <IP телевизора>            ПРОБА: показывает, что вернул бы, и ничего не меняет
#   restore.sh <папка копии> <IP телевизора> --apply    откат по-настоящему
# Возвращает: включённые тогда приложения, ваши приложения (из APK), главный экран, клавиатуру, заставку,
# спецвозможности и доступ к уведомлениям. Ничего не удаляет. Free My TV, если его не было в копии, только
# выключает (pm disable-user), чтобы он не вернул свой экран. Прочие настройки только показывает.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

[ $# -ge 2 ] || { sed -n '2,7p' "$0"; exit 2; }
arg_b=$1; arg_ip=$2
b=$(cd "$1" && pwd)
[ -f "$b/device.txt" ] && [ -f "$b/packages/enabled.txt" ] || { echo "В $1 нет резервной копии."; exit 1; }
connect "$2"
apply=0; [ "${3:-}" = --apply ] && apply=1
now=$(mktemp -d); trap 'rm -rf "$now"' EXIT

changes=0; failed=0
act() {  # act "что делаю" команда…
    changes=$((changes + 1))
    if [ "$apply" = 1 ]; then
        echo "  → $1"
        "${@:2}" </dev/null >/dev/null 2>"$now/err" || { failed=$((failed + 1)); echo "    ✗ не получилось: $(head -c 300 "$now/err")"; }
    else
        echo "  [проба] $1"
        if [ "$2" = tvdo ]; then echo "          adb shell ${*:3}"; else echo "          ${*:2}"; fi
    fi
}
# Команда на телевизоре, после которой надо проверить вывод (pm пишет Error в stdout с кодом 0 на старых Android).
tvdo() { local o; o=$(tvsh "$@" 2>&1) || { echo "$o" >&2; return 1; }; case "$o" in *rror*|*xception*|*ailure*) echo "$o" >&2; return 1 ;; esac; }

[ "$apply" = 1 ] && echo "ОТКАТ по копии $b" || echo "ПРОБА отката по копии $b (на телевизоре ничего не меняется)"

# 0. Та ли это модель.
bm=$(file_val ro.product.model "$b/device.txt"); nm=$(tvsh getprop ro.product.model)
bs=$(file_val ro.serialno "$b/device.txt");     ns=$(tvsh getprop ro.serialno)
if [ "$bm" != "$nm" ] || { [ -n "$bs" ] && [ "$bs" != null ] && [ "$bs" != "$ns" ]; }; then
    echo "Это копия другого телевизора ($bm $bs), а подключён $nm $ns. Остановился."; exit 1
fi

pkgs    > "$now/all.txt"
pkgs -d > "$now/disabled.txt"
pkgs -u > "$now/known.txt"

# 1. Free My TV, если его не было: выключить первым, иначе после перезагрузки он вернёт свой экран.
fmtv=io.freemytv
if grep -qx "$fmtv" "$now/all.txt" && ! grep -qx "$fmtv" "$b/packages/all.txt"; then
    if ! grep -qx "$fmtv" "$now/disabled.txt"; then
        echo "Free My TV:"
        echo "  (проще на телевизоре: Free My TV → Home Screen → Original; тогда этот шаг уже не нужен)"
        act "выключаю $fmtv, не удаляя (вернуть: pm enable $fmtv)" tvdo pm disable-user --user 0 "$fmtv"
    fi
fi

# 2. Удалённые для пользователя системные приложения: вернуть из системы.
only_in "$b/packages/all.txt" "$now/all.txt" > "$now/gone.txt"
both_in "$now/gone.txt" "$now/known.txt" > "$now/existing.txt"
if [ -s "$now/existing.txt" ]; then
    echo "Вернуть удалённые для пользователя:"
    while read -r p; do act "возвращаю $p" tvdo cmd package install-existing --user 0 "$p"; done < "$now/existing.txt"
fi

# 3. Ваши приложения, которых больше нет: поставить из копии.
only_in "$now/gone.txt" "$now/known.txt" > "$now/missing.txt"
if [ -s "$now/missing.txt" ]; then
    echo "Поставить заново из копии:"
    while read -r p; do
        set -- "$b/apk/$p"/*.apk
        if [ ! -f "$1" ]; then echo "  ! $p: нет APK в копии (системное или не скопировалось)"; continue; fi
        apks=(); for f in "$@"; do apks+=("$(native "$f")"); done
        if [ $# = 1 ]; then act "ставлю $p" adb -s "$T" install -r "${apks[0]}"
        else act "ставлю $p ($# частей)" adb -s "$T" install-multiple -r "${apks[@]}"; fi
    done < "$now/missing.txt"
fi

# 4. Включить то, что было включено. Настройку Google (setupwraith) последней: с ней включённой и выключенным
#    главным экраном Home перехватывает её экран восстановления.
both_in "$b/packages/enabled.txt" "$now/disabled.txt" > "$now/enable.txt"
if [ -s "$now/enable.txt" ]; then
    echo "Включить обратно:"
    { grep -v setupwraith "$now/enable.txt" || true; grep setupwraith "$now/enable.txt" || true; } > "$now/enable-ordered.txt"
    while read -r p; do act "включаю $p" tvdo pm enable --user 0 "$p"; done < "$now/enable-ordered.txt"
fi

# 5. Главный экран.
bh=$(cat "$b/home.component" 2>/dev/null || true)
if [ "$apply" = 1 ] && [ "$changes" -gt 0 ]; then sleep 2; fi
nh=$(home_component)
if [ -n "$bh" ] && [ "$bh" != "$nh" ]; then
    echo "Главный экран (сейчас: ${nh:-не выбран}):"
    act "возвращаю $bh" tvdo cmd package set-home-activity --user 0 "$bh"
fi

# 6. Клавиатура, заставка, спецвозможности (там живёт сервис кнопок пульта Free My TV).
hdr=""
put_back() {  # put_back secure ключ
    local was is; was=$(file_val "$2" "$b/settings/$1.txt"); is=$(tvsh settings get "$1" "$2")
    [ "$was" = "$is" ] && return 0
    [ -n "$hdr" ] || { hdr=1; echo "Заставка и спецвозможности:"; }
    if [ "$was" = null ]; then act "$1 $2: убираю «$is»" tvdo settings delete "$1" "$2"
    else act "$1 $2: «$is» → «$was»" tvdo settings put "$1" "$2" "$(q "$was")"; fi
}
was_ime=$(file_val enabled_input_methods "$b/settings/secure.txt")
is_ime=$(tvsh settings get secure enabled_input_methods)
if [ "$was_ime" != "$is_ime" ] && [ "$was_ime" != null ]; then
    echo "Клавиатуры:"
    echo "$was_ime" | tr ':' '\n' | sed 's/;.*//' > "$now/ime.txt"
    while read -r id; do
        [ -n "$id" ] || continue
        case ":$is_ime:" in *":$id:"* | *":$id;"*) ;; *) act "включаю клавиатуру $id" tvdo ime enable "$id" ;; esac
    done < "$now/ime.txt"
fi
was_def=$(file_val default_input_method "$b/settings/secure.txt")
is_def=$(tvsh settings get secure default_input_method)
if [ "$was_def" != null ] && [ "$was_def" != "$is_def" ]; then
    act "клавиатура по умолчанию: $is_def → $was_def" tvdo ime set "$was_def"
fi
for k in screensaver_components screensaver_default_component screensaver_enabled \
         screensaver_activate_on_sleep screensaver_activate_on_dock \
         enabled_accessibility_services accessibility_enabled; do
    put_back secure "$k"
done

# 7. Доступ к уведомлениям (виджет «Сейчас играет» в Free My TV).
was_nl=$(file_val enabled_notification_listeners "$b/settings/secure.txt" | tr ':' '\n' | grep -v '^null$' | grep . | sort -u || true)
is_nl=$(tvsh settings get secure enabled_notification_listeners | tr ':' '\n' | grep -v '^null$' | grep . | sort -u || true)
[ "$was_nl" = "$is_nl" ] || echo "Доступ к уведомлениям:"
for c in $(comm -13 <(echo "$was_nl") <(echo "$is_nl")); do act "забираю доступ к уведомлениям у $c" tvdo cmd notification disallow_listener "$c"; done
for c in $(comm -23 <(echo "$was_nl") <(echo "$is_nl")); do act "возвращаю доступ к уведомлениям $c" tvdo cmd notification allow_listener "$c"; done

# 8. Прочие настройки: только показываем, что изменилось с момента копии.
handled='^(screensaver_|enabled_accessibility_services|accessibility_enabled|enabled_input_methods|default_input_method|enabled_notification_listeners|selected_input_method_subtype|input_methods_subtype_history)'
for ns in global secure system; do
    tvsh settings list "$ns" | sort > "$now/$ns.txt"
    comm -3 "$b/settings/$ns.txt" "$now/$ns.txt" | sed 's/^[[:space:]]*//; s/=.*//' | sort -u | grep -vE "$handled" \
        | sed "s/^/$ns /" || true
done > "$now/other.txt"
if [ -s "$now/other.txt" ]; then
    echo "Для сведения, другие настройки отличаются от копии ($(wc -l < "$now/other.txt" | tr -d ' ')), их не трогаю:"
    head -n 40 "$now/other.txt" | sed 's/^/    /'
fi

echo
if [ "$changes" = 0 ]; then
    echo "Отличий от копии нет: откатывать нечего."
elif [ "$apply" = 1 ]; then
    echo "Готово: действий $changes, не получилось $failed. Перезагрузите телевизор (выключить из розетки на 30 секунд)."
else
    echo "Проба: при откате будет действий: $changes. На телевизоре ничего не изменено."
    echo "Откатить по-настоящему: bash $0 $arg_b $arg_ip --apply"
fi
[ "$failed" = 0 ]
