#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <adb-serial> (find the serial with: adb devices)" >&2
  exit 2
fi

device="$1"
model=$(adb -s "$device" shell getprop ro.product.model | tr -d '\r')
model_lower=$(printf '%s' "$model" | tr '[:upper:]' '[:lower:]')
case "$model_lower" in
  *novaair*|*'nova air'*) ;;
  *)
    echo "Refusing to change screen timeout: expected an ONYX Nova Air, found '$model'." >&2
    exit 1
    ;;
esac

# Set Android's screen-off timeout to its maximum supported integer value.
timeout_ms=2147483647
adb -s "$device" shell settings put system screen_off_timeout "$timeout_ms"
actual=$(adb -s "$device" shell settings get system screen_off_timeout | tr -d '\r')
if [ "$actual" != "$timeout_ms" ]; then
  echo "Could not set screen-off timeout (read back '$actual')." >&2
  exit 1
fi

echo "${model}: screen-off timeout set to maximum (${actual} ms)."
