#!/system/bin/sh
# P145 性能采集（设备端一条命令出全部数据）
#   M <memTotalKb> <memAvailKb>          物理内存总量 / 可用
#   F <dataTotalKb> <dataUsedKb>         /data 分区总量 / 已用
#   T <total_jiffies1> <idle1> <ncpu>    第一次 CPU 总时间
#   A <pid> <user> <jiffies1> <rssPages> <name...>
#   N <uid> <netBytes>                   按 uid 的网络累计
#   Z <total_jiffies2> <idle2>           第二次 CPU 总时间
#   J <pid> <jiffies2>                   第二次每进程 jiffies
#   S <pkg> <apkBytes>                   APK 体积
#   D <pkg> <dataKb>                     数据目录体积
#   P <pid> <pssKb>                      可选：PSS（$1 = pss 时输出）
#
# 注意：toybox awk 不支持 next/exit、&&、|| 和 FILENAME 正则，这里全部绕开。

TERM=dumb
export TERM
WITH_PSS="$1"

D=/data/local/tmp
PC=$D/.pcpu
PP=$D/.pps
PIDF=$D/.pids

get_total() {
  awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s, $5+$6}' /proc/stat
}

NCPU=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
if [ -z "$NCPU" ]; then NCPU=4; fi

# ---- 物理内存 / 存储总量 ----
echo "M $(awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} END{print t, a}' /proc/meminfo)"
echo "F $(df -k /data 2>/dev/null | awk 'NR==2{print $2, $3}')"

# ---- 每进程 CPU / 内存（第一次采样）----
awk '{sub(/^[^)]*\) /,""); split(FILENAME,a,"/"); print a[3], $12+$13, $22}' /proc/[0-9]*/stat 2>/dev/null > $PC
ps -A -o PID,USER,ARGS 2>/dev/null > $PP

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
    if (ok == 1) { print "A", p, user[p], c[p], r[p], nm }
  }
}
' $PP $PC)

echo "$AL"

# ---- 网络（按 uid 累计）----
awk 'NR>1{s[$4]+=$6+$8} END{for(u in s) if (s[u]>0) print "N", u, s[u]}' /proc/net/xt_qtaguid/stats 2>/dev/null

# ---- 存储：APK + 数据目录 ----
pm list packages -f 2>/dev/null | while read line; do
  apk=${line#package:}
  pkg=${apk##*=}
  apk=${apk%=*}
  sz=$(stat -c %s "$apk" 2>/dev/null)
  echo "S $pkg ${sz:-0}"
done

du -s -k /data/data/* 2>/dev/null | while read kb path; do
  echo "D ${path##*/} $kb"
done

# ---- 第二次采样（只取第一次出现过的 pid）----
sleep 1

echo "Z $(get_total)"

echo "$AL" | awk '{print $2}' > $PIDF
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
' $PIDF /proc/[0-9]*/stat 2>/dev/null

# ---- 可选：PSS（约 3 秒）----
if [ "$WITH_PSS" = "pss" ]; then
  for p in $(echo "$AL" | awk '{print $2}'); do
    v=$(awk '/^Pss:/{s+=$2} END{printf "%d", s+0}' /proc/$p/smaps 2>/dev/null)
    echo "P $p ${v:-0}"
  done
fi

rm -f $PC $PP $PIDF
