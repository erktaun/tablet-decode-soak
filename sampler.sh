#!/system/bin/sh
# Runs on the device as the adb shell user (which, unlike apps, may read system-wide CPU and
# thermal-zone data). Started and stopped by run_test.py; appends one block per period:
#   T <epoch s>  /  cpu lines from /proc/stat  /  F <cpu> <cur kHz>  /  Z <zone type> <milli-°C>  /  E
out=$1
period=${2:-5}
echo $$ > "$out.pid"
while true; do
  echo "T $(date +%s)"
  grep '^cpu' /proc/stat
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do
    c=${f#/sys/devices/system/cpu/}; echo "F ${c%%/*} $(cat $f 2>/dev/null)"
  done
  for z in /sys/class/thermal/thermal_zone*; do
    echo "Z $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)"
  done
  echo "E"
  sleep $period
done >> "$out"
