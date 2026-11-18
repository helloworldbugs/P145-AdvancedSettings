#!/system/bin/sh
# P145 性能采集（设备端一条命令出全部数据）
#   M <memTotalKb> <memAvailKb>          物理内存总量 / 可用
#   F <dataTotalKb> <dataUsedKb>         /data 分区总量 / 已用
#   T <total_jiffies1> <idle1> <ncpu>    第一次 CPU 总时间
#   A <pid> <user> <jiffies1> <rssPages> <name...>
#   W <upBps> <downBps>                  整机网络速率（B/s）
#   NU <uid> <upBps> <downBps>           每 uid 网络速率（B/s）
#   Z <total_jiffies2> <idle2>           第二次 CPU 总时间
#   J <pid> <jiffies2>                   第二次每进程 jiffies
#   S <pkg> <apkBytes>                   APK 体积
#   D <pkg> <dataKb>                     数据目录体积
#   P <pid> <pssKb>                      可选：PSS（$1 = pss 时输出）
#
# 时序：快照1 → sleep 1 → 快照2（CPU/网络用这 1 秒窗口）→ 存储段放最后
# 注意：toybox awk 不支持 next/exit、&&、|| 和 FILENAME 正则，这里全部绕开。

TERM=dumb
export TERM
WITH_PSS="$1"

D=/data/local/tmp
PC=$D/.pcpu
PP=$D/.pps
PIDF=$D/.pids
QT1=$D/.qt1
QT2=$D/.qt2
DV1=$D/.dv1
DV2=$D/.dv2

get_total() {
  awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s, $5+$6}' /proc/stat
}

NCPU=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
if [ -z "$NCPU" ]; then NCPU=4; fi

# =============== 快照 1 ===============
echo "M $(awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} END{print t, a}' /proc/meminfo)"
echo "F $(df -k /data 2>/dev/null | awk 'NR==2{print $2, $3}')"

T1=$(get_total)
cat /proc/net/xt_qtaguid/stats 2>/dev/null > $QT1
cat /proc/net/dev 2>/dev/null > $DV1

awk '{sub(/^[^)]*\) /,""); split(FILENAME,a,"/"); print a[3], $12+$13, $22}' /proc/[0-9]*/stat 2>/dev/null > $PC
ps -A -o PID,USER,ARGS 2>/dev/null > $PP

APPS=$(awk '
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

echo "T $T1 $NCPU"
echo "$APPS"

# =============== 1 秒窗口 ===============
sleep 1

# =============== 快照 2 ===============
T2=$(get_total)
cat /proc/net/xt_qtaguid/stats 2>/dev/null > $QT2
cat /proc/net/dev 2>/dev/null > $DV2

# ---- 整机网络速率 ----
awk '
{
  if (index(FILENAME, ".dv1") > 0) {
    if (FNR > 2) {
      i = $1; sub(/:/, "", i)
      if (i != "lo") { r1[i] = $2; t1[i] = $10 }
    }
  }
  if (index(FILENAME, ".dv2") > 0) {
    if (FNR > 2) {
      i = $1; sub(/:/, "", i)
      if (i != "lo") { r2[i] = $2; t2[i] = $10 }
    }
  }
}
END {
  up = 0; down = 0
  for (i in r2) {
    if (t1[i] != "") { up += t2[i] - t1[i] }
    if (r1[i] != "") { down += r2[i] - r1[i] }
  }
  if (up < 0) { up = 0 }
  if (down < 0) { down = 0 }
  printf "W %d %d\n", up, down
}
' $DV1 $DV2

# ---- 每个 uid 的网络速率 ----
awk '
{
  if (index(FILENAME, ".qt1") > 0) {
    if (FNR > 1) { u1[$4] += $6; d1[$4] += $8 }
  }
  if (index(FILENAME, ".qt2") > 0) {
    if (FNR > 1) { u2[$4] += $6; d2[$4] += $8 }
  }
}
END {
  for (uid in u2) {
    du = 0; dd = 0
    if (u1[uid] != "") { du = u2[uid] - u1[uid] }
    if (d1[uid] != "") { dd = d2[uid] - d1[uid] }
    if (du < 0) { du = 0 }
    if (dd < 0) { dd = 0 }
    if (du + dd > 0) { printf "NU %s %d %d\n", uid, du, dd }
  }
}
' $QT1 $QT2

echo "Z $T2"

# ---- 第二次 CPU 采样（只取第一次出现过的 pid）----
echo "$APPS" | awk '{print $2}' > $PIDF
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

# =============== 存储占用（放最后，不占采样窗口）===============
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

# =============== 可选 PSS ===============
if [ "$WITH_PSS" = "pss" ]; then
  # 只算 RSS 最大的前 20 个：150 个全算要 22 秒，前 20 个约 3 秒
  for p in $(echo "$APPS" | awk '{print $4, $2}' | sort -rn | head -20 | awk '{print $2}'); do
    v=$(awk '/^Pss:/{s+=$2} END{printf "%d", s+0}' /proc/$p/smaps 2>/dev/null)
    echo "P $p ${v:-0}"
  done
fi

rm -f $PC $PP $PIDF $QT1 $QT2 $DV1 $DV2
