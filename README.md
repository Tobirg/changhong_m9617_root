# Root для Tuvio TO55UFGCV5 / CHiQ на MT5889

🇬🇧 [English version](README.en.md)

Пошаговая инструкция получения root с Magisk на телевизорах с загрузчиком **MStar MBOOT (U-Boot 2011.06)**: UART через HDMI, резервное копирование, патч образа прямо на ТВ приложением **RootMate**, изменение состояния загрузчика, сброс данных, запись в eMMC и восстановление.

<img src="docs/img/01-main.png" width="640" alt="RootMate">

## Начать здесь

1. [Полная инструкция по получению root и откат](docs/guide.md)
2. [Скачать RootMate.apk](https://github.com/Tobirg/changhong_m9617_root/releases/latest) (собирается автоматически через [GitHub Actions](https://github.com/Tobirg/changhong_m9617_root/actions))
3. [Восстановление при ошибках](docs/recovery.md)

## RootMate

Приложение для Android TV заменяет всё, что раньше приходилось делать на ПК:

- скачивает и устанавливает последний Magisk;
- патчит оригинальный `BOOT_*.IMG` с флешки (включая фикс `skip_initramfs`), без ПК и без ручной работы в Magisk;
- открывает инженерное меню для включения UART.

Загрузчик (UART/MBOOT) по-прежнему настраивается вручную по инструкции.

## Дополнительно

- [Скрипт исправления skip_initramfs через adb shell](scripts/skipfix.sh) (запасной вариант)
- [Заметки по приложению для доработок](docs/app.md)
