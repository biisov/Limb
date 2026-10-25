# Общие функции для скриптов в tv/. Подключаются через ". lib.sh", сами по себе ничего не делают.
# Пишем под bash 3.2 (macOS) и Git Bash (Windows): без declare -A, mapfile и ${x,,}.

export LC_ALL=C  # одинаковая сортировка для sort и comm
# Git Bash на Windows переделывает аргументы вида /data/... в C:/Program Files/Git/data/...: для путей на
# телевизоре это выключаем, а пути на компьютере для adb переводим сами (native).
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# "192.168.1.50" -> "192.168.1.50:5555"
target() { case "$1" in *:*) echo "$1" ;; *) echo "$1:5555" ;; esac; }

# Подключается к телевизору и проверяет, что он разрешил отладку. Задаёт T.
connect() {
    command -v adb >/dev/null 2>&1 || { echo "Не нашёл adb. Установите Android platform-tools."; exit 1; }
    T=$(target "$1")
    adb connect "$T" >/dev/null 2>&1 || true
    local state
    state=$(adb -s "$T" get-state 2>&1 | tr -d '\r' || true)
    case "$state" in
        device) ;;
        *unauthorized*)
            echo "Телевизор $T ждёт разрешения: на экране «Разрешить отладку?» отметьте «Всегда» и нажмите «Разрешить»."
            exit 1 ;;
        *)
            echo "Телевизор $T не отвечает ($state). Проверьте IP и что отладка включена."
            exit 1 ;;
    esac
}

# Команда на телевизоре. stdin закрыт, чтобы не съедать ввод циклов while read.
tvsh() { adb -s "$T" shell "$@" </dev/null | tr -d '\r'; }

# Пакеты из "pm list packages <флаги>", по одному на строку, по алфавиту.
pkgs() { tvsh pm list packages "$@" 2>/dev/null | sed -n 's/^package://p' | sort -u; }

# Строки, похожие на компонент "пакет/класс", из вывода resolve-/query-activities.
components() { grep -E '^[[:space:]]*[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+[[:space:]]*$' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'; }

# Текущий главный экран (компонент), пусто если не выбран.
home_component() {
    tvsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
        | components | tail -n 1 | grep -v 'ResolverActivity' || true
}

# Значение key из файла "key=value" (как settings list). "null", если ключа нет.
file_val() { local v; v=$(grep -m1 "^$1=" "$2" 2>/dev/null) || { echo null; return; }; echo "${v#*=}"; }

# Строка в одинарных кавычках для shell телевизора.
q() { printf "'%s'" "$(printf %s "$1" | sed "s/'/'\\\\''/g")"; }

sha256_all() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

# Строки, которые есть в файле A и нет в B (оба отсортированы).
only_in() { comm -23 "$1" "$2"; }
both_in() { comm -12 "$1" "$2"; }
