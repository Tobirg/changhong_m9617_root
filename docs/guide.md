# Получение root на MT5889 через UART / MBOOT / Magisk

**Важно:** получение root связано с риском необратимой потери данных и неработоспособности ТВ. Изменение состояния Verified Boot на испытанном устройстве потребовало полного сброса из-за несовместимых ключей шифрования. Инструкция не гарантирует применимость ко всем прошивкам.

## 1. Что потребуется

- USB–UART адаптер **TTL** (не RS-232 ±12 В).
- Разъем HDMI. Под пайку или с клеммами, либо раскромсать ненужный кабель. Можно разобрать ТВ и подключиться к UART на плате, но на мой взгляд даже купить кабель для этой цели будет дешевле, чем случайно разбить матрицу при разборке.
- Терминал UART:  MobaXterm, PuTTY либо аналог. (нужен в котором ввод отправляется сразу, а не по отдельной кнопке)
- ПК на котором можно запустить питон скрипт
- FAT32-флешка.


## 2. Собираем адаптер UART через HDMI

<img width="1702" height="1702" alt="photo_2026-10-31_20-34-52" src="https://github.com/user-attachments/assets/2ae974fb-9402-445b-80c8-4b52a521ba75" />

| Контакт HDMI телевизора | Назначение относительно ТВ | Подключение USB–UART |
| --- | --- | --- |
| **2** | **TX** ТВ | **RX** адаптера |
| **8** | GND | GND адаптера |
| **14** | **RX** ТВ | **TX** адаптера |

Параметры терминала: **115200, 8N1, без flow control**.


## 3. Подготовка флешки и ПК

Устанавливаем [python3](https://www.python.org/downloads/)  
[Скачиваем скрипт](../scripts/magisk_skipfix.py)

На ПК форматируйте флешку в **FAT32**, один раздел.
Скачиваем последний [Magisk](https://github.com/topjohnwu/Magisk) и [Fluffy](https://github.com/mlm-games/Fluffy) и закидываем на флешку.

## 4. Снять оригинальные образы boot с eMMC на USB

Отключите ТВ из розетки.  
Подключите адаптер в **HDMI порт 3** (отсчет снизу вверх) и к ПК.  
Вставьте флешку **в порт ближе к матрице**.  

Зажмите Enter на клавиатуре в активном окне терминала и подключите питание ТВ.

**Держите клавишу** до появления приглашения: 
~~~text
<< MT5889 >>#
~~~

В MBOOT консоли:
~~~text
usb start 1
~~~

Должен быть ответ вида:
~~~text
(Re)start USB 1...
Check USB port[1]:
[USB] config miu select [F0] [EF] [EF] ][EF]
[USB] enable miu lower bound address subtraction
scanning bus for devices... New Device 0

[USB] interface[0] conf:1 value 8:
1 USB Device(s) found
       scanning bus for storage devices... max lun:0
1 Storage Device(s) found
~~~

Затем для проверки:
~~~text
fatls usb 0:1
~~~

Ожидаемый ответ:
~~~text
            system volume information/
 10679283   magisk-v31.1.apk
  7734485   fluffy-4.5.1-universal.apk

2 file(s), 1 dir(s)
~~~

Смотрим куда система загружает boot в RAM и размер загрузчика:
~~~text
printenv bootcmd
~~~

Ожидаемый ответ:  
0x1F00000 — размер читаемого boot-образа, а 0x25000000 — адрес RAM.
~~~text
bootcmd=mmc read.p 0x25000000 boot 0x1F00000; bootm 0x25000000
~~~

Если значения совпадают, используем команды ниже, **иначе подставляем в них свои значения**.  

Выполните команды **по очереди**:
~~~text
mmc read.p 0x25000000 boot_a 0x1F00000
~~~
~~~text
fatwrite usb 0:1 0x25000000 BOOT_A.IMG 0x1F00000
~~~
~~~text
mmc read.p 0x25000000 boot_b 0x1F00000
~~~
~~~text
fatwrite usb 0:1 0x25000000 BOOT_B.IMG 0x1F00000
~~~

Ожидаемый ответ (# будет больше):
~~~text
<< MT5889 >># mmc read.p 0x25000000 boot_a 0x1F00000
<< MT5889 >># fatwrite usb 0:1 0x25000000 BOOT_A.IMG 0x1F00000
file BOOT_A.IMG not found
now create file BOOT_A.IMG
#################################################################
<< MT5889 >># mmc read.p 0x25000000 boot_b 0x1F00000
<< MT5889 >># fatwrite usb 0:1 0x25000000 BOOT_B.IMG 0x1F00000
file BOOT_B.IMG not found
now create file BOOT_B.IMG
#################################################################
~~~

Проверяем наличие файлов и размер: 
~~~text
fatls usb 0:1
~~~
**Останавливаем работу с USB**:
~~~text
usb stop
~~~

Ожидаемый ответ:
~~~text
<< MT5889 >># fatls usb 0:1
            system volume information/
 32505856   boot_a.img
 32505856   boot_b.img
 10679283   magisk-v31.1.apk
  7734485   fluffy-4.5.1-universal.apk

4 file(s), 1 dir(s)
<< MT5889 >># usb stop
stopping USB..
~~~

## 5. Пропатчить boot через Magisk на ТВ

В MBOOT вводим и ждем загрузки ТВ:
~~~text
reset
~~~

После загрузки через штатный файловый менеджер устанавливаем Magisk и Fluffy с флешки. (можете делать через adb если умеете, отдельно описывать не буду)  
Открываем штатный файловый менеджер на ТВ:  
<img width="332" height="276" alt="image" src="https://github.com/user-attachments/assets/6d52afa9-80ed-493b-a0e8-b74b397f525e" />

Устанавливаем Fluffy и запускаем. Выдаем нужные права.  
Затем ставим Magisk.  
**После установки сразу нажимаем Открыть, потому что ярлыка не будет в штатном лаунчере. Повторно открыть можно через настройки андроид.**    

Потом в Magisk: **Установка → Пропатчить boot-образ**. Через Fluffy выбираем BOOT_A.img Дождитесь сохранения **magisk_patched-....img** в **Download**.  
<img width="1922" height="1112" alt="image" src="https://github.com/user-attachments/assets/547cff17-c3a9-4a48-87a3-a07e23b862d2" />

Идем в Fluffy и переносим сохраненный образ на флешку.  

## 6. Исправить skip_initramfs на ПК

Извлекаем флешку из ТВ и вставляем в ПК.

Скопируйте **BOOT_A.IMG**, **BOOT_B.IMG** и патченный образ с флешки на ПК в отдельную папку для еще одного патча и бэкапа.  
Переименовываем образ magisk в magisk.img.

На этой версии MBOOT в командной строке ядра присутствует **skip_initramfs**. Magisk может ошибочно выбрать Legacy SAR вместо First Stage Init. На исследованной конфигурации помогла замена одного символа в ELF **magiskinit**: **skip_initramfs → skip_initramfz**.
В репозитории есть самостоятельный скрипт **scripts/magisk_skipfix.py**. Он использует **только Python 3 (stdlib)**, читает уже пропатченный Magisk образ, изменяет строку внутри ELF в gzip/cpio ramdisk, пересчитывает legacy boot ID и сохраняет новый образ **той же длины**, без доступа к ТВ.

Скрипт из шапки кладем в эту же папку.  
Открываем в ней терминал и запускаем скрипт.  

~~~powershell
py .\magisk_skipfix.py .\magisk.img
~~~

Linux:
~~~sh
python3 scripts/magisk_skipfix.py magisk.img
~~~

Скрипт рассчитан на **Android boot header v0–v2 + gzip/newc cpio** (целевая платформа — v2); он не является универсальным патчером Magisk для любых телефонов или версий.  

Файл **magisk_skipfix.img** закидываем на флешку.  

Флешку отключаем от ПК и возвращаем в телевизор, тот же порт ближе к матрице.  

## 7. Разблокировка загрузчика, прошивка magisk и factory reset

В MBOOT:
~~~text
setenv devicestate unlock
~~~
~~~text
saveenv
~~~

Проверим запись состояния:
~~~text
printenv devicestate
~~~
Должно быть:
~~~text
devicestate=unlock
~~~

Меняем оригинальный образ на патченный:
~~~text
usb start 1
~~~
~~~text
fatload usb 0:1 0x25000000 magisk_skipfix.img
~~~
~~~text
mmc write.p 0x25000000 boot_a 0x1F00000 0
~~~

Это удаляет пользовательские данные. После сброса ТВ придётся настраивать заново, включая разрешение отладки/ADB и переустановку Magisk APK.  
**Если ТВ нормально не загружается, скорее всего пропустили этот шаг.**
~~~text
recovery_wipe_partition data
~~~

Перезагружаем
~~~text
reset
~~~

Заново ставим apk magisk.  
При запросе дополнительной установки соглашаемся.

## ОТКАТ

Возвращаемся в MBOOT и вводим:
~~~text
setenv devicestate lock
~~~
~~~text
saveenv
~~~
~~~text
usb start 1
~~~
~~~text
fatload usb 0:1 0x25000000 boot_a.img
~~~

~~~text
mmc write.p 0x25000000 boot_a 0x1F00000 0
~~~
~~~text
recovery_wipe_partition data
~~~
~~~text
reset
~~~

**Если совсем все плохо и оригинальный boot a потерян**, то прошиваем из boot b вместо загрузки boot a с флешки. Остальные шаги идентичны.  
Переключение слота лично на моем ТВ не работает, зависает. Но прошивка работает.
~~~text
mmc read.p 0x25000000 boot_b 0x1F00000
~~~

