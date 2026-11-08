# Получение root на MT5889 через UART / MBOOT / Magisk

**Важно:** получение root связано с риском необратимой потери данных и неработоспособности ТВ. Изменение состояния Verified Boot на испытанном устройстве потребовало полного сброса из-за несовместимых ключей шифрования. Инструкция не гарантирует применимость ко всем прошивкам.

## 1. Что потребуется

- USB–UART адаптер **TTL** (не RS-232 ±12 В).
- Разъем HDMI. Под пайку или с клеммами, либо раскромсать ненужный кабель. Можно разобрать ТВ и подключиться к UART на плате, но на мой взгляд даже купить кабель для этой цели будет дешевле, чем случайно разбить матрицу при разборке.
- Терминал UART:  MobaXterm, PuTTY либо аналог. (нужен в котором ввод отправляется сразу, а не по отдельной кнопке)
- ПК с `adb` (нужен для управления ТВ по USB; шаг 6 можно сделать и приложением RootMate прямо на ТВ, без ПК).
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
Fluffy нужен, так как штатный менеджер не умеет выбирать файлы для других приложений, то есть в magisk не сможем выбрать образ для патча.

## 4. Снять оригинальные образы boot с eMMC на USB

Отключите ТВ из розетки.  
Подключите адаптер в **HDMI порт 3** (отсчет снизу вверх) и к ПК.  
Вставьте флешку **в порт ближе к матрице**.  
<img width="2487" height="2503" alt="20261101_111935" src="https://github.com/user-attachments/assets/e6165c5f-8e2b-491a-bfb3-8803b67d4c2e" />


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
Ожидаемый ответ:
~~~text
<< MT5889 >># fatls usb 0:1
            system volume information/
 32505856   boot_a.img
 32505856   boot_b.img
 10679283   magisk-v31.1.apk
  7734485   fluffy-4.5.1-universal.apk

4 file(s), 1 dir(s)
~~~

**Останавливаем работу с USB**:
~~~text
usb stop
~~~
Ответ:
~~~text
<< MT5889 >># usb stop
stopping USB..
~~~

Перезагружаем ТВ в Android:
~~~text
reset
~~~

## 5. Пропатчить boot через Magisk на ТВ

После загрузки через штатный файловый менеджер устанавливаем Magisk и Fluffy с флешки. (можете делать через adb если умеете, отдельно описывать не буду)  
Открываем штатный файловый менеджер на ТВ:  
<img width="332" height="276" alt="image" src="https://github.com/user-attachments/assets/6d52afa9-80ed-493b-a0e8-b74b397f525e" />

Устанавливаем Fluffy и запускаем. Выдаем нужные права.  
Затем ставим Magisk.  
**После установки сразу нажимаем Открыть, потому что ярлыка не будет в штатном лаунчере. Повторно открыть можно через настройки андроид.**    

Потом в Magisk: **Установка → Пропатчить boot-образ**. Через Fluffy выбираем BOOT_A.img Дождитесь сохранения **magisk_patched-....img** в **Download**.  
<img width="1922" height="1112" alt="image" src="https://github.com/user-attachments/assets/547cff17-c3a9-4a48-87a3-a07e23b862d2" />

Идем в Fluffy и переносим сохраненный образ на флешку.  

## 6. Исправить skip_initramfs прямо на ТВ

Патченный `magisk_patched-....img` уже лежит в **Download** на ТВ (шаг 5) — ПК для этого шага больше не нужен, флешку вынимать не надо.

На этой версии MBOOT в реальной командной строке ядра присутствует **skip_initramfs**. Magisk ошибочно выбирает из-за этого Legacy SAR вместо First Stage Init. Помогает замена одного символа в ELF **magiskinit** (файл `init` внутри ramdisk): **skip_initramfs → skip_initramfz** — тогда magiskinit не находит эту подстроку в `/proc/cmdline` и идёт по правильной ветке. Это делает инструмент `magiskboot`, уже встроенный в сам Magisk — отдельный Python больше не нужен.

Два равноценных способа:

### Вариант A — приложение RootMate (проще, без adb)

Собирается автоматически через GitHub Actions этого репозитория — возьми готовый APK во вкладке **Releases** либо в артефактах последнего прогона **Actions** (вкладка Actions → последний успешный workflow → Artifacts → `RootMate`).  
Установи APK на ТВ (разреши установку из неизвестных источников) и открой.

1. **Выбрать magisk_patched-\*.img** → укажи файл из Download.
2. **Выбрать папку (флешку) для сохранения** → укажи примонтированную USB-флешку.
3. **Пропатчить skip_initramfs** → дождись лога, в конце появится путь к сохранённому `magisk_skipfix.img`.

В приложении кроме патчинга есть ещё две кнопки:

- **Включить UART** — пытается включить UART напрямую. По коду самого фабричного приложения (`PageData5` → `FactorySetting.setUartOnOff` → `factoryPage5.setUartOnOff`) тумблер "Design Mode → Uart → On" в итоге дёргает `MtkTvFApiSystem.setUartEnvironment(true)`, который пишет `UARTOnOff=on` в тот же U-Boot env, что `setenv`/`saveenv` в MBOOT — приложение вызывает именно этот метод по рефлексии прямо из установленного `com.homwee.factory`, без копирования чужого кода.

  На практике SELinux на этой прошивке это режет: `avc: denied { find } for interface=vendor.mediatek.tv.mtktvfactory::IMtkTvFApiManager ... scontext=u:r:untrusted_app`, то есть обычное (сторонне установленное) приложение к этому HIDL-сервису достучаться не может — только системные/привилегированные пакеты вроде самого `com.homwee.factory`. Поэтому кнопка откатывается на проверенный способ: открывает страницу 4 инженерного меню и тостом/в логе показывает путь — **Index 5 → Design Mode → Uart → On**, включать приходится руками.
- **Открыть инженерное меню** — то же самое открытие страницы 4 (`PageIndex=4`, аналог `adb shell am start -W -n com.homwee.factory/.FactoryActivity --ei PageIndex 4`), но отдельной кнопкой, без попытки авто-включения — для тех, кому нужно само меню (Index 5 → Design Mode → любая другая настройка).

Статус загрузчика (`devicestate`, активный boot-слот) и его разблокировка из приложения оказались невозможны на этой прошивке: единственный экспортированный AIDL-сервис `com.homwee.factory` (`KeyService`/`IKey`) заточен строго под DRM-ключи и не даёт доступа к произвольным env-переменным, а в самом инженерном меню нигде не отображается ни `devicestate`, ни активный раздел (только версия MBOOT на первой странице). Поэтому разблокировка загрузчика остаётся только через MBOOT-консоль (шаг 7): `setenv devicestate unlock; saveenv`.

### Вариант B — вручную через adb shell

```bash
adb push scripts/skipfix.sh /data/local/tmp/skipfix.sh
adb shell sh /data/local/tmp/skipfix.sh magisk_patched-....img
```

Скрипт сам находит `magiskboot` внутри установленного Magisk (под нужный ABI), патчит и копирует готовый `magisk_skipfix.img` на флешку в `/storage/`.

Оба варианта делают одно и то же: `magiskboot unpack` → достают `init` из `ramdisk.cpio` → меняют в нём строку → кладут обратно → `magiskboot repack` (он сам пересчитывает контрольные поля заголовка, в старом питон-скрипте это называлось «legacy boot ID»).

После патча — вынимаем флешку из порта ТВ и переставляем в MBOOT-порт, тот же, что ближе к матрице.  

## 7. Разблокировка загрузчика, прошивка magisk и factory reset  

**Снова получаем доступ к консоли как в пункте 4.**  
~~~text
<< MT5889 >>#
~~~

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

Следующая команда удаляет пользовательские данные. После сброса ТВ придётся настраивать заново, включая разрешение отладки/ADB и переустановку Magisk APK.  
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

