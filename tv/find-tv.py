#!/usr/bin/env python3
"""Ищет в домашней сети устройства с открытым портом 5555 (ADB по сети). Только пробует подключиться, ничего не шлёт.

    python3 tv/find-tv.py              своя сеть /24 (192.168.1.0–255, если компьютер 192.168.1.x)
    python3 tv/find-tv.py 192.168.0    другая сеть
Работает на macOS, Linux и Windows без дополнительных программ.
"""
import socket
import sys
from concurrent.futures import ThreadPoolExecutor

PORT = 5555


def own_prefix():
    # UDP connect ничего не отправляет: так узнаём, какой адрес у компьютера в локальной сети.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
    finally:
        s.close()
    return ip.rsplit(".", 1)[0], ip


def is_open(host):
    try:
        with socket.create_connection((host, PORT), timeout=0.6):
            return True
    except OSError:
        return False


def main():
    if len(sys.argv) > 1:
        prefix, me = sys.argv[1].rstrip("."), None
    else:
        prefix, me = own_prefix()
    if not prefix.startswith(("192.168.", "10.", "172.")):
        sys.exit(f"Сеть {prefix}.x не похожа на домашнюю, не сканирую. Укажите её: find-tv.py 192.168.1")
    print(f"Ищу порт {PORT} в сети {prefix}.1–254" + (f" (этот компьютер: {me})" if me else "") + "…")
    hosts = [f"{prefix}.{i}" for i in range(1, 255)]
    with ThreadPoolExecutor(64) as pool:
        found = [h for h, ok in zip(hosts, pool.map(is_open, hosts)) if ok]
    if found:
        for h in found:
            print(f"  открыт: {h}:{PORT}")
    else:
        print("  не нашёл. Проверьте, что отладка включена, или посмотрите IP в Настройки → Сеть и Интернет.")


if __name__ == "__main__":
    main()
