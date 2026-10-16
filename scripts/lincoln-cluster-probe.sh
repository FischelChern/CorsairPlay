#!/system/bin/sh
# Probe a Lincoln/Ford head unit's instrument cluster for the extras it accepts.
#
# Run this ON THE HEAD UNIT (adb shell), while CarPlay is connected with an active route in
# Apple Maps. It listens to every broadcast on the cluster channel and tries a series of
# candidate field names for remaining distance/time, then prints what the cluster reacted to.
#
# The cluster app usually logs its own parsing; this script watches that log alongside the
# broadcasts so you can tell which extra names it reads.
#
# Usage:
#   adb shell sh /data/local/tmp/lincoln-cluster-probe.sh            # passive listen (30 s)
#   adb shell sh /data/local/tmp/lincoln-cluster-probe.sh inject     # also inject candidates
#
# Nothing here changes persistent state; it only sends broadcasts a connected CarPlay session
# would send anyway.

CLUSTER="${CLUSTER:-com.baidu.xiaoduos.instrumentcluster}"
MODE="${1:-listen}"

echo "== Lincoln cluster probe =="
echo "cluster package: $CLUSTER"
echo

echo "-- is the cluster installed? --"
pm path "$CLUSTER" 2>&1
echo

echo "-- its signature (SHA-256) --"
APK=$(pm path "$CLUSTER" 2>/dev/null | head -1 | sed 's/^package://')
if [ -n "$APK" ]; then
  # Toybox on Android has sha256sum on newer builds; fall back to openssl if present.
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK"
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$APK"
  else
    echo "(no sha256sum/openssl; skip)"
  fi
fi
echo

echo "-- other likely cluster apps present --"
for p in com.baidu.xiaoduos.instrumentcluster com.baidu.car.navi org.savior.sync \
         com.byd.cluster com.byd.automap com.byd.launchermap; do
  if pm path "$p" >/dev/null 2>&1; then echo "  present: $p"; fi
done
echo

echo "-- recent cluster log (last 200 lines, filtered) --"
logcat -d -t 200 2>/dev/null | grep -iE "instrument|cluster|navi|xiaoduos|remain|eta" | tail -40
echo

if [ "$MODE" != "inject" ]; then
  echo "-- passive listen: watching broadcasts for 30 s --"
  echo "   (set an Apple Maps destination now; press Ctrl-C to stop)"
  timeout 30 logcat -v brief 2>/dev/null | grep -iE "navi|instrument|cluster|PLAY_INFO|remain" | head -60
  echo
  echo "Done. If you saw navi INFO lines, the cluster reads guidance. To try candidate"
  echo "remaining-distance field names, re-run:  sh $0 inject"
  exit 0
fi

echo "-- inject: sending candidate extras one name at a time --"
echo "   watch the cluster screen and the log below; note which name changes the display."
echo

# Confirmed on a MEK-MX8Q head unit: the cluster takes guidance on navi.INFO and the total
# remaining distance/time on its own navi.REMAIN channel (metres / seconds). This still injects
# so you can watch the cluster screen and the log react.
STATE_ON=TRUE
TURN=turn_right
DIST=350
ROAD=Probe

am broadcast -a com.baidu.car.navi.STATE --es state "$STATE_ON" -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.car.navi.INFO --es name "$TURN" --ei distance "$DIST" --es nextRoadName "$ROAD" -p "$CLUSTER" >/dev/null 2>&1
echo "  guidance sent (turn_right, 350 m, $ROAD)"
sleep 1

echo "  sending REMAIN: remainDistance=12300 (m), remainTime=3600 (s)"
am broadcast -a com.baidu.car.navi.REMAIN --ei remainDistance 12300 --ei remainTime 3600 -p "$CLUSTER" >/dev/null 2>&1
sleep 2

echo
echo "-- cluster log during injection --"
logcat -d -t 160 2>/dev/null | grep -iE "NavPresenter|instrument|remain|ManeuverElement|StreetName" | tail -40
echo
echo "Expect mRemainDistance=123 and mRemainHours=1 in the log."
echo
echo "-- clearing guidance --"
am broadcast -a com.baidu.car.navi.STATE --es state FALSE -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.car.navi.REMAIN --ei remainDistance 0 --ei remainTime 0 -p "$CLUSTER" >/dev/null 2>&1
echo "Done."
