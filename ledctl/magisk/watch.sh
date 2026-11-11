#!/system/bin/sh
# Re-applies the ledctl "while on" mode after each screen-on (started by service.sh, one instance).
# logcat blocks while idle, so this costs nothing between events.
MODDIR=${0%/*}
P=/sys/module/ledctl/parameters
echo $$ > "$MODDIR/watch.pid"
logcat -b events -T 1 -s screen_toggled 2>/dev/null | while read -r l; do
  case "$l" in
    *"screen_toggled: 1"*)
      for d in 0.5 0.5 1 1 2 3; do
        sleep $d
        [ -w "$P/on_led" ] || break
        v=$(cat "$P/on_led")
        [ "$v" = "-1" ] && break
        echo "$v" > "$P/on_led"
      done ;;
  esac
done
