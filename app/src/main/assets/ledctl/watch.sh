#!/system/bin/sh
# Tells ledctl when the screen turns on or off (started by service.sh, one instance). The "while on" LED mode runs
# only with the screen on; the TV service reprograms the LED ~1 s after screen-on, so "on" is sent a few times.
# logcat blocks while idle, so this costs nothing between events.
MODDIR=${0%/*}
P=/sys/module/ledctl/parameters
echo $$ > "$MODDIR/watch.pid"
logcat -b events -T 1 -s screen_toggled 2>/dev/null | while read -r l; do
  case "$l" in
    *"screen_toggled: 1"*)
      for d in 0.5 0.5 1 1 2 3; do
        sleep $d
        [ -w "$P/screen_on" ] || break
        echo 1 > "$P/screen_on"
      done ;;
    *"screen_toggled: 0"*)
      [ -w "$P/screen_on" ] && echo 0 > "$P/screen_on" ;;
  esac
done
