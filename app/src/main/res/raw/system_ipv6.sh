#!/system/bin/sh
# Mishka 系统 IPv6 对账脚本（移植自 mihomo_box 的 system_ipv6_sync / apn4 逻辑）
# 由应用复制到私有目录，经 root 执行。用法：
#   sh system_ipv6.sh once  <0|1> <状态目录>   一次性对账（1=恢复，0=禁用）
#   sh system_ipv6.sh watch 0     <状态目录>   常驻对账循环（关态，每 5 秒一拍）
#   sh system_ipv6.sh status 0    <状态目录>   输出首选 APN：id|apn|protocol|roaming|type
MODE="$1"
WANT="$2"          # 0 = 关闭系统 IPv6（默认），1 = 开启
STATE="$3"
# 写入 /proc/.../disable_ipv6 的值：关 → 1（禁用），开 → 0（恢复）
DIS=1
[ "$WANT" = "1" ] && DIS=0
IPV6_CONF=/proc/sys/net/ipv6/conf
IPV6_COOL=60
IPV6_TICK=5
IP=/system/bin/ip
[ -x "$IP" ] || IP=ip
PID_FILE="$STATE/loop.pid"
WANT_FILE="$STATE/want"
NET_FILE="$STATE/net"
LAST_FILE="$STATE/last"
APN_STATE="$STATE/apn.state"
LOG="$STATE/log"

log() {
  echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*" >> "$LOG" 2>/dev/null
  if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 200 ]; then
    tail -n 100 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
  fi
  return 0
}

# 热点 / USB 共享下游口：系统 dnsmasq 与 RA 自己管，绝不写它的 disable_ipv6
is_tether_if() {
  case "$1" in
    br[0-9]*|bridge*|ap[0-9]*|swlan*|softap*|rndis*|usb[0-9]*|bt-pan*|p2p*|wigig*|sipa_usb*) return 0 ;;
    wlan[0-9]*|eth[0-9]*)
      "$IP" -4 addr show dev "$1" 2>/dev/null | grep -Eq 'inet [0-9]+\.[0-9]+\.[0-9]+\.1/' && return 0 ;;
  esac
  return 1
}

# 永久豁免：lo / 模板 / 蜂窝（IMS 依赖 v6，只靠 APN 协议处理）/ 热点下游
is_exempt_if() {
  case "$1" in
    lo|all|default) return 0 ;;
    rmnet*|r_rmnet*|ccmni*|wwan*|pdp*|v4-rmnet*|radio*|cellular*) return 0 ;;
  esac
  is_tether_if "$1"
}

setv() {
  [ -e "$1" ] || return 0
  [ "$(cat "$1" 2>/dev/null)" = "$2" ] || echo "$2" > "$1" 2>/dev/null
  return 0
}

# 每行：id|apn|protocol|roaming|type（同一 id 只输出一次）
apn_rows() {
  _d="$STATE/apnq.$$"
  mkdir -p "$_d" 2>/dev/null || return 0
  _proj="_id:apn:protocol:roaming_protocol:type"
  content query --uri content://telephony/carriers/preferapn --projection "$_proj" > "$_d/0" 2>/dev/null &
  for _n in 1 2 3 4; do
    content query --uri "content://telephony/carriers/preferapn/subId/$_n" --projection "$_proj" > "$_d/$_n" 2>/dev/null &
  done
  wait
  cat "$_d"/* 2>/dev/null | awk '
    /Row:/ && /_id=/ {
      id=""; apn=""; p=""; r=""; t=""
      for (i = 1; i <= NF; i++) {
        v = $i; sub(/,$/, "", v)
        if (v ~ /^_id=/) id = substr(v, 5)
        else if (v ~ /^apn=/) apn = substr(v, 5)
        else if (v ~ /^protocol=/) p = substr(v, 10)
        else if (v ~ /^roaming_protocol=/) r = substr(v, 18)
        else if (v ~ /^type=/) t = substr(v, 6)
      }
      if (id != "" && !(id in seen)) { seen[id] = 1; print id "|" apn "|" p "|" r "|" t }
    }'
  rm -rf "$_d" 2>/dev/null
  return 0
}

# 改协议后需重新附着数据才生效：后台自动开关一次移动数据
apn_reattach() {
  command -v svc >/dev/null 2>&1 || { log "WARN 无 svc，请手动开关一次飞行模式使 APN 协议生效"; return 0; }
  log "重新附着数据（svc data off/on）"
  ( svc data disable >/dev/null 2>&1; sleep 2; svc data enable >/dev/null 2>&1 ) </dev/null >/dev/null 2>&1 &
  return 0
}

# 「系统 IPv6=关」：上网 APN 协议改为 IP（幂等；只写漂移的行；跳过 IMS）
# 写入后不信任 content 的输出文字，而是重新查询同一行，以实际值为准。
apn_apply() {
  _rows=$(apn_rows)
  if [ -z "$_rows" ]; then
    log "WARN 未查询到首选 APN，未改写（可手动把 APN 协议设为 IPv4，效果相同）"
    return 1
  fi
  _changed=0; _fail=0
  while IFS='|' read -r _id _apn _p _r _t; do
    [ -n "$_id" ] || continue
    case "$_t" in *ims*) log "跳过 IMS 承载 id=$_id apn=$_apn"; continue ;; esac
    if [ "$_p" = "IP" ] && [ "$_r" = "IP" ]; then continue; fi
    grep -q "^$_id|" "$APN_STATE" 2>/dev/null || echo "$_id|$_apn|$_p|$_r" >> "$APN_STATE"
    _res=$(content update --uri content://telephony/carriers --where "_id=$_id" --bind protocol:s:IP --bind roaming_protocol:s:IP 2>&1)
    _now=$(apn_rows | grep "^$_id|" | head -1)
    case "$_now" in
      *"|IP|IP|"*) log "id=$_id apn=$_apn: $_p/$_r -> IP/IP（已校验）"; _changed=1 ;;
      *) log "WARN id=$_id apn=$_apn 写入后仍为 [$_now]，content 输出：$_res"; _fail=1 ;;
    esac
  done <<APN_ROWS_END
$_rows
APN_ROWS_END
  [ "$_changed" = "1" ] && apn_reattach
  [ "$_fail" = "0" ]
}

# 关态周期校验：首选 APN 若被系统或运营商写回 IPv4/IPv6，则重新改写（每 60 秒一次，见 watch 循环）
apn_drift_check() {
  _rows=$(apn_rows)
  [ -n "$_rows" ] || return 0
  _bad=$(echo "$_rows" | awk -F'|' '$1 != "" && $5 !~ /ims/ && ($3 != "IP" || $4 != "IP") {print $1}')
  if [ -n "$_bad" ]; then
    log "APN 协议漂移（id=$(echo $_bad | tr '\n' ' ')），重新改写"
    apn_apply
  fi
  return 0
}

# 「系统 IPv6=开」：按状态文件还原 protocol / roaming_protocol
apn_restore() {
  [ -f "$APN_STATE" ] || return 0
  _fail=0; _changed=0
  while IFS='|' read -r _id _apn _p _r; do
    [ -n "$_id" ] || continue
    _res=$(content update --uri content://telephony/carriers --where "_id=$_id" --bind "protocol:s:$_p" --bind "roaming_protocol:s:$_r" 2>&1)
    # 同 apn_apply：以重新查询到的实际值为准，不解析 content 的输出文字
    _now=$(apn_rows | grep "^$_id|" | head -1)
    case "$_now" in
      "$_id|$_apn|$_p|$_r|"*) log "还原 id=$_id apn=$_apn: $_p/$_r（已校验）"; _changed=1 ;;
      *) log "WARN 还原 id=$_id apn=$_apn 后仍为 [$_now]，content 输出：$_res"; _fail=1 ;;
    esac
  done < "$APN_STATE"
  [ "$_fail" = "0" ] && rm -f "$APN_STATE" 2>/dev/null
  [ "$_changed" = "1" ] && apn_reattach
  return 0
}

# 网络指纹 = 路由表 + 网卡集合（切网 / 飞行模式 / 换卡时变化）
fingerprint() {
  _cur=""
  while IFS= read -r _l || [ -n "$_l" ]; do _cur="$_cur$_l "; done < /proc/net/route 2>/dev/null
  for _f in "$IPV6_CONF"/*/disable_ipv6; do
    [ -e "$_f" ] || continue
    _i=${_f%/disable_ipv6}; _cur="$_cur${_i##*/} "
  done
  printf '%s' "$_cur"
}

sync_once() {
  [ -d "$IPV6_CONF" ] || return 0
  # 热点下游若被旧版写成 disable_ipv6=1，交还系统
  for _f in "$IPV6_CONF"/*/disable_ipv6; do
    [ -e "$_f" ] || continue
    _i=${_f%/disable_ipv6}; _i=${_i##*/}
    is_tether_if "$_i" || continue
    setv "$_f" 0
  done

  _prev=$(cat "$WANT_FILE" 2>/dev/null)
  _cur=$(fingerprint)
  _netprev=$(cat "$NET_FILE" 2>/dev/null)
  _need=0; _flip=0; _netchg=0; _drift=0
  if [ "$_prev" != "$WANT" ]; then
    _need=1; _flip=1
  elif [ "$WANT" = "1" ]; then
    # 开态：只更新指纹，不写任何东西
    [ "$_cur" = "$_netprev" ] || printf '%s' "$_cur" > "$NET_FILE"
    return 0
  else
    # 关态：切网 → 必修；网络未变 → 扫描是否有网卡被放回 0，有则按 60 秒冷却追打
    if [ "$_cur" != "$_netprev" ] || [ -z "$_netprev" ]; then
      _need=1; _netchg=1
    else
      for _f in "$IPV6_CONF"/*/disable_ipv6; do
        [ -e "$_f" ] || continue
        _i=${_f%/disable_ipv6}; _i=${_i##*/}
        is_exempt_if "$_i" && continue
        if [ "$(cat "$_f" 2>/dev/null)" != "$DIS" ]; then _need=1; _drift=1; break; fi
      done
      if [ "$_need" = "1" ]; then
        _now=$(date +%s 2>/dev/null); _last=$(cat "$LAST_FILE" 2>/dev/null)
        case "$_now" in ''|*[!0-9]*) _now=0 ;; esac
        case "$_last" in ''|*[!0-9]*) _last=0 ;; esac
        if [ "$_now" -gt 0 ] && [ "$_last" -gt 0 ] && [ $((_now - _last)) -lt "$IPV6_COOL" ]; then
          _need=0
        fi
      fi
    fi
  fi
  [ "$_need" = "1" ] || return 0

  # 蜂窝侧：关 → 改写 APN（翻转或网络变化都复查）；开 → 翻转时还原
  if [ "$WANT" = "0" ]; then
    if [ "$_flip" = "1" ] || [ "$_netchg" = "1" ]; then apn_apply; fi
  else
    if [ "$_flip" = "1" ]; then apn_restore; fi
  fi

  # 非蜂窝、非豁免网卡：只写值不对的（lo 跳过）
  _n=0; _changed=""
  for _f in "$IPV6_CONF"/*/disable_ipv6; do
    [ -e "$_f" ] || continue
    _i=${_f%/disable_ipv6}; _i=${_i##*/}
    is_exempt_if "$_i" && continue
    [ "$(cat "$_f" 2>/dev/null)" = "$DIS" ] && continue
    if echo "$DIS" > "$_f" 2>/dev/null; then
      _n=$((_n + 1)); _changed="$_changed $_i"
      if [ "$WANT" = "0" ]; then
        setv "$IPV6_CONF/$_i/accept_ra" 0
        setv "$IPV6_CONF/$_i/autoconf" 0
        setv "$IPV6_CONF/$_i/accept_ra_defrtr" 0
        setv "$IPV6_CONF/$_i/accept_ra_pinfo" 0
      else
        setv "$IPV6_CONF/$_i/accept_ra" 2
        setv "$IPV6_CONF/$_i/autoconf" 1
        setv "$IPV6_CONF/$_i/accept_ra_defrtr" 1
        setv "$IPV6_CONF/$_i/accept_ra_pinfo" 1
        setv "$IPV6_CONF/$_i/accept_ra_rtr_pref" 1
        setv "$IPV6_CONF/$_i/disable_tempaddr" 0
        setv "$IPV6_CONF/$_i/router_solicitations" 3
      fi
    fi
  done

  # 开启立即生效：ndc 通知 netd；蜂窝口 down/up（wlan 不动，免得断 Wi-Fi）
  if [ "$WANT" = "1" ] && [ -n "$_changed" ]; then
    if command -v ndc >/dev/null 2>&1; then
      for _i in $_changed; do ndc interface ipv6 "$_i" enable >/dev/null 2>&1; done
    fi
    for _i in $_changed; do
      case "$_i" in rmnet*|ccmni*|wwan*|r_rmnet*) "$IP" link set "$_i" down 2>/dev/null ;; esac
    done
    for _i in $_changed; do
      case "$_i" in rmnet*|ccmni*|wwan*|r_rmnet*) "$IP" link set "$_i" up 2>/dev/null ;; esac
    done
  fi

  printf '%s' "$WANT" > "$WANT_FILE"
  printf '%s' "$_cur" > "$NET_FILE"
  if [ "$_drift" = "1" ]; then
    _now=$(date +%s 2>/dev/null)
    case "$_now" in ''|*[!0-9]*) ;; *) printf '%s' "$_now" > "$LAST_FILE" ;; esac
  fi
  if [ "$_n" -gt 0 ]; then
    if [ "$WANT" = "1" ]; then _w=开; else _w=关; fi
    log "系统 IPv6 对账（$_w）：应用 $_n 块网卡"
  fi
  return 0
}

kill_loop() {
  _old=$(cat "$PID_FILE" 2>/dev/null)
  if [ -n "$_old" ] && [ "$_old" != "$$" ]; then kill "$_old" 2>/dev/null; fi
  rm -f "$PID_FILE" 2>/dev/null
  return 0
}

mkdir -p "$STATE" 2>/dev/null
case "$MODE" in
  once)
    kill_loop
    sync_once
    ;;
  watch)
    kill_loop
    echo "$$" > "$PID_FILE"
    log "常驻对账启动（pid $$，${IPV6_TICK}s 一拍）"
    # 被新的 watch / once 替换（PID 文件变化）时退出
    _tick=0
    while [ "$(cat "$PID_FILE" 2>/dev/null)" = "$$" ]; do
      sync_once
      _tick=$((_tick + 1))
      # 关态每 12 拍（约 60 秒）校验一次 APN；开态不做
      if [ "$WANT" = "0" ] && [ $((_tick % 12)) -eq 0 ]; then apn_drift_check; fi
      sleep "$IPV6_TICK"
    done
    ;;
  status)
    apn_rows
    ;;
esac
exit 0
