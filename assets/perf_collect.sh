#!/system/bin/sh
# 性能采集 v12
# 设备端只做采集 + 轻度过滤（不用 && / || ，toybox awk 对它们有解析 bug）
#   T <total_jiffies1> <ncpu>
#   A <pid> <user> <cpuJiffies> <rssPages> <name...>
#   I <pid> <ioBytes>
#   N <uid> <netBytes>
#   Z <total_jiffies2>
#   J <pid> <cpuJiffies2>
TERM=dumb
export TERM
WITH_PSS="$1"

D=/data/local/tmp
PC=$D/.pcpu
PP=$D/.pps

get_total() {
  awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s, $5+$6}' /proc/stat
}

NCPU=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
[ -z "$NCPU" ] && NCPU=4

awk '{sub(/^[^)]*\) /,""); split(FILENAME,a,"/"); print a[3], $12+$13, $22}' /proc/[0-9]*/stat 2>/dev/null > $PC
ps -A -o PID,USER,ARGS 2>/dev/null > $PP

echo "M $(awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} END{print t, a}' /proc/meminfo)"
echo "T $(get_total) $NCPU"

AL=$(awk '
{
  if (index(FILENAME, ".pps") > 0) {
    if ($1 ~ /^[0-9]+$/) {
      user[$1] = $2
      s = $0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "", s)
      name[$1] = s
    }
  }
  if (index(FILENAME, ".pcpu") > 0) {
    c[$1] = $2
    r[$1] = $3
  }
}
END {
  for (p in c) {
    nm = name[p]
    ok = 0
    if (nm ~ /[.]/) { ok = 1 }
    if (nm == "system_server") { ok = 1 }
    if (ok == 1) {
      print "A", p, user[p], c[p], r[p], nm
    }
  }
}
' $PP $PC)

echo "$AL"

for p in $(echo "$AL" | awk '{print $2}'); do
  io=$(awk '/^read_bytes:/{rr=$2} /^write_bytes:/{ww=$2} END{print rr+ww}' /proc/$p/io 2>/dev/null)
  echo "I $p ${io:-0}"
done

awk 'NR>1{s[$4]+=$6+$8} END{for(u in s) if (s[u]>0) print "N", u, s[u]}' /proc/net/xt_qtaguid/stats 2>/dev/null

sleep 1

echo "Z $(get_total)"

echo "$AL" | awk '{print $2}' > $D/.pids
awk '
{
  if (index(FILENAME, ".pids") > 0) {
    keep[$1] = 1
  } else {
    sub(/^[^)]*\) /,"")
    split(FILENAME,a,"/")
    if (keep[a[3]] != "") { print "J", a[3], $12+$13 }
  }
}
' $D/.pids /proc/[0-9]*/stat 2>/dev/null
rm -f $D/.pids

rm -f $PC $PP


if [ "$WITH_PSS" = "pss" ]; then
  for p in $(echo "$AL" | awk '{print $2}'); do
    v=$(awk '/^Pss:/{s+=$2} END{printf "%d", s+0}' /proc/$p/smaps 2>/dev/null)
    echo "P $p ${v:-0}"
  done
fi
