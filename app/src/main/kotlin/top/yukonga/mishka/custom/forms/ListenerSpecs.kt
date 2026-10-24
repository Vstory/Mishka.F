package top.yukonga.mishka.custom.forms

/**
 * 入站 listeners 的协议类型 / 模板 / 字段表（对齐 mihomo_box `pages-config.js` 的
 * IN_TYPES / IN_TEMPLATES / IN_EXTRA / IN_TLS_FIELDS / IN_REALITY_FIELDS / IN_TRANSPORT_FIELDS /
 * IN_DISGUISE_FIELDS / IN_MUX_FIELDS，见参考实现 1021–1270 行）。
 *
 * 纯数据 + 纯函数，无 Compose 依赖（kotlinc 可直接编译对拍）；渲染仍由 ConfigFormPanel 的
 * SectionsPage 完成。eBPF 有独立字段表（[EBPF_SECTIONS]）与页面，不在这里组合。
 */

/** 「选择监听器类型」的全部选项（参考实现 IN_TYPES；eBPF 仅定制内核可用）。 */
val LISTENER_TYPES: List<FormOption> = listOf(
    FormOption("http", "http"),
    FormOption("socks", "socks"),
    FormOption("mixed", "mixed 混合代理"),
    FormOption("redirect", "redirect 透明代理"),
    FormOption("tproxy", "tproxy 透明代理"),
    FormOption("tun", "tun 接管（高级）"),
    FormOption("ebpf", "eBPF 透明入站（定制内核）"),
    FormOption("shadowsocks", "shadowsocks"),
    FormOption("vmess", "vmess"),
    FormOption("vless", "vless"),
    FormOption("trojan", "trojan"),
    FormOption("anytls", "anytls"),
    FormOption("mieru", "mieru"),
    FormOption("sudoku", "sudoku"),
    FormOption("tuic", "tuic（v4 token / v5 users）"),
    FormOption("shadowquic", "shadowquic"),
    FormOption("hysteria2", "hysteria2"),
    FormOption("hysteria2-realm", "hysteria2-realm"),
    FormOption("trusttunnel", "trusttunnel"),
    FormOption("tunnel", "tunnel 端口转发"),
    FormOption("snell", "snell"),
    FormOption("cns", "cns"),
)

/** 新建监听器模板（参考实现 IN_TEMPLATES；名字由页面按唯一名覆盖，基础协议端口页面另算）。 */
val LISTENER_TEMPLATES: Map<String, Map<String, Any?>> = linkedMapOf(
    "mixed" to linkedMapOf<String, Any?>("name" to "mixed-in", "type" to "mixed", "port" to 7890L, "listen" to "0.0.0.0"),
    "http" to linkedMapOf<String, Any?>("name" to "http-in", "type" to "http", "port" to 7891L, "listen" to "0.0.0.0"),
    "socks" to linkedMapOf<String, Any?>("name" to "socks-in", "type" to "socks", "port" to 7892L, "listen" to "0.0.0.0"),
    "redirect" to linkedMapOf<String, Any?>("name" to "redirect-in", "type" to "redirect", "port" to 7893L, "listen" to "0.0.0.0"),
    "tproxy" to linkedMapOf<String, Any?>("name" to "tproxy-in", "type" to "tproxy", "port" to 7894L, "listen" to "0.0.0.0"),
    "tunnel" to linkedMapOf<String, Any?>("name" to "tunnel-in", "type" to "tunnel", "port" to 10090L, "listen" to "0.0.0.0", "network" to listOf("tcp", "udp"), "target" to "www.example.com:80"),
    "tun" to linkedMapOf<String, Any?>("name" to "tun-in", "type" to "tun", "stack" to "system", "dns-hijack" to listOf("0.0.0.0:53"), "inet4-address" to listOf("198.19.0.1/30"), "mtu" to 9000L, "auto-route" to true, "auto-detect-interface" to true),
    "ebpf" to linkedMapOf<String, Any?>("name" to "ebpf-in", "type" to "ebpf", "mode" to "local"),
    "shadowsocks" to linkedMapOf<String, Any?>("name" to "ss-in", "type" to "shadowsocks", "port" to 10000L, "listen" to "0.0.0.0", "cipher" to "aes-256-gcm", "password" to "密码"),
    "vmess" to linkedMapOf<String, Any?>("name" to "vmess-in", "type" to "vmess", "port" to 10001L, "listen" to "0.0.0.0", "users" to listOf(linkedMapOf("username" to "user1", "uuid" to "", "alterId" to 0L))),
    "vless" to linkedMapOf<String, Any?>("name" to "vless-in", "type" to "vless", "port" to 10002L, "listen" to "0.0.0.0", "users" to listOf(linkedMapOf("username" to "user1", "uuid" to ""))),
    "trojan" to linkedMapOf<String, Any?>("name" to "trojan-in", "type" to "trojan", "port" to 10003L, "listen" to "0.0.0.0", "users" to listOf(linkedMapOf("username" to "user1", "password" to "")), "certificate" to "./server.crt", "private-key" to "./server.key"),
    "snell" to linkedMapOf<String, Any?>("name" to "snell-in", "type" to "snell", "port" to 10815L, "listen" to "0.0.0.0", "psk" to "snell-psk", "version" to 4L),
    "anytls" to linkedMapOf<String, Any?>("name" to "anytls-in", "type" to "anytls", "port" to 10018L, "listen" to "0.0.0.0", "users" to linkedMapOf("user1" to "password1"), "certificate" to "./server.crt", "private-key" to "./server.key"),
    "mieru" to linkedMapOf<String, Any?>("name" to "mieru-in", "type" to "mieru", "port" to 10019L, "listen" to "0.0.0.0", "transport" to "TCP", "users" to linkedMapOf("user1" to "password1")),
    "sudoku" to linkedMapOf<String, Any?>("name" to "sudoku-in", "type" to "sudoku", "port" to 8443L, "listen" to "0.0.0.0", "key" to "server-public-key-or-uuid", "aead-method" to "chacha20-poly1305"),
    "trusttunnel" to linkedMapOf<String, Any?>("name" to "trusttunnel-in", "type" to "trusttunnel", "port" to 10021L, "listen" to "0.0.0.0", "users" to listOf(linkedMapOf("username" to "user1", "password" to "pass1")), "certificate" to "./server.crt", "private-key" to "./server.key", "congestion-controller" to "bbr"),
    "hysteria2" to linkedMapOf<String, Any?>("name" to "hy2-in", "type" to "hysteria2", "port" to 10004L, "listen" to "0.0.0.0", "users" to linkedMapOf("user1" to "password1"), "certificate" to "./server.crt", "private-key" to "./server.key"),
    "hysteria2-realm" to linkedMapOf<String, Any?>("name" to "hy2realm-in", "type" to "hysteria2-realm", "port" to 10820L, "listen" to "0.0.0.0", "token" to "public"),
    "shadowquic" to linkedMapOf<String, Any?>("name" to "sq-in", "type" to "shadowquic", "port" to 10822L, "listen" to "0.0.0.0", "users" to listOf(linkedMapOf("username" to "user", "password" to "pass")), "jls-upstream" to linkedMapOf("addr" to "www.example.com:443")),
    "tuic" to linkedMapOf<String, Any?>("name" to "tuic-in", "type" to "tuic", "port" to 10005L, "listen" to "0.0.0.0", "users" to linkedMapOf("00000000-0000-0000-0000-000000000000" to "password1"), "certificate" to "./server.crt", "private-key" to "./server.key"),
    "cns" to linkedMapOf<String, Any?>("name" to "cns-in", "type" to "cns", "port" to 23333L, "listen" to "0.0.0.0", "key" to "Meng", "flag" to "httpUDP", "udp" to true),
)

/** 基础监听协议：端口沿用页面「7890 + 序号」的防撞策略，不用模板固定端口。 */
val LISTENER_BASIC_PORT_TYPES: Set<String> = setOf("mixed", "http", "socks", "redirect", "tproxy", "tunnel")

/** 无监听端口的接管型（参考实现 IN_NO_PORT；ebpf 走独立页面）。 */
val LISTENER_NO_PORT: Set<String> = setOf("tun", "ebpf")

/** 需要 TLS 证书字段的类型（参考实现 IN_TLS_TYPES）。 */
val LISTENER_TLS_TYPES: Set<String> = setOf("vmess", "vless", "trojan", "hysteria2", "tuic", "anytls", "trusttunnel", "hysteria2-realm")

/** 支持 REALITY / 传输层 / TLS 伪装层的类型（参考实现 IN_REALITY_TYPES / IN_TRANSPORT_TYPES / IN_DISGUISE_TYPES）。 */
val LISTENER_REALITY_TYPES: Set<String> = setOf("vmess", "vless", "trojan")

/** 支持 mux-option 的类型（参考实现 IN_MUX_TYPES）。 */
val LISTENER_MUX_TYPES: Set<String> = setOf("vmess", "vless", "trojan", "hysteria2", "tuic", "shadowsocks")

/** `users` 是「对象列表」的协议 → 简版映射列表编辑器的列（其余协议的 users 是 MAPTEXT 映射）。 */
val LISTENER_USERS_COLUMNS: Map<String, List<Pair<String, String>>> = linkedMapOf(
    "http" to listOf("username" to "用户名", "password" to "密码"),
    "socks" to listOf("username" to "用户名", "password" to "密码"),
    "mixed" to listOf("username" to "用户名", "password" to "密码"),
    "vmess" to listOf("username" to "用户名", "uuid" to "UUID", "alterId" to "alterId"),
    "vless" to listOf("username" to "用户名", "uuid" to "UUID", "flow" to "flow 流控"),
    "trojan" to listOf("username" to "用户名", "password" to "密码"),
    "trusttunnel" to listOf("username" to "用户名", "password" to "密码"),
    "shadowquic" to listOf("username" to "用户名", "password" to "密码"),
)

/** users 必填的协议（内核结构体无 omitempty；页面在说明里提示，不做硬校验）。 */
val LISTENER_USERS_REQUIRED: Set<String> = setOf("vmess", "vless", "trojan")

private fun ccOptions() = listOf(FormOption("cubic", "cubic"), FormOption("bbr", "bbr"), FormOption("new_reno", "new_reno"))

private fun bbrOptions() = listOf(FormOption("standard", "standard"), FormOption("conservative", "conservative"), FormOption("aggressive", "aggressive"))

/** 协议专属字段（参考实现 IN_EXTRA；`users` 对象列表在页面里走专门控件行，不在此列）。 */
val LISTENER_EXTRA: Map<String, List<FormField>> = linkedMapOf(
    "shadowsocks" to listOf(
        FormField("cipher", "加密方式 cipher", FormFieldType.TEXT, optional = true, placeholder = "aes-256-gcm / 2022-blake3-aes-256-gcm"),
        FormField("password", "密码", FormFieldType.TEXT, optional = true, desc = "2022 系列加密要求 base64 形式的密钥"),
    ),
    "vmess" to listOf(
        FormField("mkcp-config.enable", "启用 mKCP", FormFieldType.BOOL, optional = true, desc = "v2ray 兼容的 mKCP 传输层"),
        FormField("mkcp-config.mtu", "mKCP MTU", FormFieldType.NUMBER, optional = true, placeholder = "1350"),
        FormField("mkcp-config.tti", "mKCP 传输间隔(ms)", FormFieldType.NUMBER, optional = true, placeholder = "50"),
        FormField("mkcp-config.uplink-capacity", "mKCP 上行(MB/s)", FormFieldType.NUMBER, optional = true, placeholder = "5"),
        FormField("mkcp-config.downlink-capacity", "mKCP 下行(MB/s)", FormFieldType.NUMBER, optional = true, placeholder = "20"),
        FormField("mkcp-config.congestion", "mKCP 拥塞控制", FormFieldType.BOOL, optional = true),
        FormField("mkcp-config.seed", "mKCP 混淆种子", FormFieldType.TEXT, optional = true),
        FormField(
            "mkcp-config.header", "mKCP 伪装包头", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("none", "none"), FormOption("srtp", "srtp"), FormOption("utp", "utp"), FormOption("wechat-video", "wechat-video"), FormOption("dtls", "dtls"), FormOption("wireguard", "wireguard")),
        ),
    ),
    "vless" to listOf(
        FormField(
            "decryption", "VLESS encryption 服务端串", FormFieldType.TEXTAREA, optional = true,
            placeholder = "mlkem768x25519plus.native/xorpub/random.600s/0s.(私钥)…",
            desc = "填写后可免 TLS。由 mihomo generate vless-x25519 / vless-mlkem768 生成",
        ),
        FormField("allow-insecure", "允许不加密 allow-insecure", FormFieldType.BOOL, optional = true, desc = "仅用于前置 nginx/caddy 的场景；否则证书、REALITY、decryption 至少配一项"),
        FormField("xhttp-config.path", "XHTTP 路径", FormFieldType.TEXT, optional = true, placeholder = "/", desc = "填写后启用 XHTTP 传输层"),
        FormField("xhttp-config.host", "XHTTP Host", FormFieldType.TEXT, optional = true),
        FormField(
            "xhttp-config.mode", "XHTTP 模式", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("auto", "auto"), FormOption("stream-one", "stream-one"), FormOption("stream-up", "stream-up"), FormOption("packet-up", "packet-up")),
        ),
    ),
    "trojan" to listOf(
        FormField("ss-option.enabled", "启用 Shadowsocks 套壳", FormFieldType.BOOL, optional = true, desc = "等价 trojan-go 的 shadowsocks 配置"),
        FormField(
            "ss-option.method", "SS 加密方式", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("aes-128-gcm", "aes-128-gcm"), FormOption("aes-256-gcm", "aes-256-gcm"), FormOption("chacha20-ietf-poly1305", "chacha20-ietf-poly1305")),
        ),
        FormField("ss-option.password", "SS 密码", FormFieldType.TEXT, optional = true),
        FormField("allow-insecure", "允许不加密 allow-insecure", FormFieldType.BOOL, optional = true, desc = "仅用于前置 nginx/caddy 的场景；否则证书、REALITY、ss-option 至少配一项"),
    ),
    "hysteria2" to listOf(
        FormField("users", "用户（每行「用户名: 密码」）", FormFieldType.MAPTEXT, optional = true, placeholder = "user1: password1", desc = "映射形式；需同时配置 TLS 证书"),
        FormField("masquerade", "伪装站点 masquerade", FormFieldType.TEXT, optional = true, placeholder = "https://bing.com"),
        FormField("obfs", "混淆 obfs", FormFieldType.TEXT, optional = true, placeholder = "salamander"),
        FormField("obfs-password", "混淆密码", FormFieldType.TEXT, optional = true),
        FormField("up", "上行速率 up", FormFieldType.TEXT, optional = true, placeholder = "1000（默认 Mbps）"),
        FormField("down", "下行速率 down", FormFieldType.TEXT, optional = true, placeholder = "1000（默认 Mbps）"),
        FormField("ignore-client-bandwidth", "忽略客户端带宽", FormFieldType.BOOL, optional = true, desc = "开启后固定使用 BBR"),
        FormField("alpn", "ALPN", FormFieldType.LIST, optional = true, hint = "h3"),
        FormField("bbr-profile", "BBR 策略", FormFieldType.SELECT, allowEmpty = true, options = bbrOptions()),
        FormField("max-idle-time", "最大空闲(ms)", FormFieldType.NUMBER, optional = true),
        FormField("cwnd", "拥塞窗口 cwnd", FormFieldType.NUMBER, optional = true),
    ),
    "tuic" to listOf(
        FormField(
            "users", "v5 用户（每行「UUID: 密码」）", FormFieldType.MAPTEXT, optional = true,
            placeholder = "00000000-0000-0000-0000-000000000000: password1",
            desc = "键必须是 UUID、值是密码；与 token(v4) 至少填一项",
        ),
        FormField("token", "token v4（与 users 二选一）", FormFieldType.LIST, optional = true, hint = "TOKEN"),
        FormField("congestion-controller", "拥塞控制", FormFieldType.SELECT, allowEmpty = true, options = ccOptions()),
        FormField("max-idle-time", "最大空闲（ms）", FormFieldType.NUMBER, optional = true),
        FormField("max-udp-relay-packet-size", "最大 UDP 中继包", FormFieldType.NUMBER, optional = true),
        FormField("authentication-timeout", "认证超时(ms)", FormFieldType.NUMBER, optional = true, placeholder = "1000"),
        FormField("alpn", "ALPN", FormFieldType.LIST, optional = true, hint = "h3"),
        FormField("cwnd", "拥塞窗口 cwnd", FormFieldType.NUMBER, optional = true),
    ),
    "anytls" to listOf(
        FormField("users", "用户（每行「用户名: 密码」）", FormFieldType.MAPTEXT, optional = true, placeholder = "user1: password1", desc = "映射形式；certificate 与 private-key 必填"),
        FormField("padding-scheme", "填充策略 padding-scheme", FormFieldType.LIST, optional = true, hint = "如 stop=8"),
    ),
    "mieru" to listOf(
        FormField("transport", "传输 transport", FormFieldType.SELECT, allowEmpty = true, options = listOf(FormOption("TCP", "TCP"), FormOption("UDP", "UDP"))),
        FormField("users", "用户（每行「用户名: 密码」）", FormFieldType.MAPTEXT, optional = true, placeholder = "user1: password1"),
        FormField("traffic-pattern", "流量模式 traffic-pattern", FormFieldType.TEXT, optional = true),
        FormField("user-hint-is-mandatory", "强制 user-hint", FormFieldType.BOOL, optional = true),
    ),
    "sudoku" to listOf(
        FormField("key", "密钥 key", FormFieldType.TEXT, optional = true),
        FormField(
            "aead-method", "AEAD 算法", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("chacha20-poly1305", "chacha20-poly1305"), FormOption("aes-128-gcm", "aes-128-gcm"), FormOption("none", "none")),
        ),
        FormField("padding-min", "最小填充率", FormFieldType.NUMBER, optional = true),
        FormField("padding-max", "最大填充率", FormFieldType.NUMBER, optional = true),
        FormField(
            "table-type", "表类型 table-type", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("prefer_ascii", "prefer_ascii"), FormOption("prefer_entropy", "prefer_entropy"), FormOption("up_ascii_down_entropy", "up_ascii_down_entropy"), FormOption("up_entropy_down_ascii", "up_entropy_down_ascii")),
        ),
        FormField("enable-pure-downlink", "纯 Sudoku 下行", FormFieldType.BOOL, optional = true),
        FormField("handshake-timeout", "握手超时（秒）", FormFieldType.NUMBER, optional = true),
        FormField("httpmask.disable", "禁用 HTTP 伪装", FormFieldType.BOOL, optional = true),
        FormField(
            "httpmask.mode", "HTTP 伪装模式", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("legacy", "legacy"), FormOption("stream", "stream"), FormOption("poll", "poll"), FormOption("auto", "auto"), FormOption("ws", "ws")),
        ),
        FormField("httpmask.path-root", "伪装路径前缀", FormFieldType.TEXT, optional = true),
        FormField("fallback", "HTTP 回退地址", FormFieldType.TEXT, optional = true),
    ),
    "trusttunnel" to listOf(
        FormField("network", "网络（http2+http3）", FormFieldType.LIST, optional = true, hint = "tcp, udp"),
        FormField("congestion-controller", "拥塞控制", FormFieldType.SELECT, allowEmpty = true, options = ccOptions()),
        FormField("bbr-profile", "BBR 策略", FormFieldType.SELECT, allowEmpty = true, options = bbrOptions()),
    ),
    "hysteria2-realm" to listOf(
        FormField("token", "Bearer token", FormFieldType.TEXT, optional = true),
        FormField("max-realms", "最大 realm 总数", FormFieldType.NUMBER, optional = true),
        FormField("max-realms-per-ip", "单 IP 最大 realm 数", FormFieldType.NUMBER, optional = true),
        FormField("trusted-proxy-header", "可信代理头", FormFieldType.TEXT, optional = true, placeholder = "X-Forwarded-For"),
        FormField("realm-name-pattern", "realm 名称正则", FormFieldType.TEXT, optional = true),
        FormField("alpn", "ALPN", FormFieldType.LIST, optional = true, hint = "h2, http/1.1"),
    ),
    "shadowquic" to listOf(
        FormField("jls-upstream.addr", "JLS 上游 jls-upstream.addr", FormFieldType.TEXT, optional = true, placeholder = "www.example.com:443"),
        FormField("jls-upstream.sni", "JLS 上游 SNI", FormFieldType.TEXT, optional = true),
        FormField("alpn", "ALPN", FormFieldType.LIST, optional = true, hint = "h3"),
        FormField("quic-versions", "QUIC 版本", FormFieldType.TEXT, optional = true, placeholder = "v1"),
        FormField("zero-rtt", "0-RTT", FormFieldType.BOOL, optional = true),
        FormField("congestion-controller", "拥塞控制", FormFieldType.SELECT, allowEmpty = true, options = ccOptions()),
        FormField("ignore-client-bandwidth", "忽略客户端带宽声明", FormFieldType.BOOL, optional = true),
    ),
    "tunnel" to listOf(
        FormField("target", "转发目标 target", FormFieldType.TEXT, optional = true, placeholder = "www.example.com:80"),
        FormField("network", "转发网络", FormFieldType.LIST, optional = true, hint = "tcp, udp"),
    ),
    "snell" to listOf(
        FormField("psk", "PSK 密钥", FormFieldType.TEXT, optional = true),
        FormField(
            "version", "版本 version", FormFieldType.SELECT, allowEmpty = true, numeric = true,
            options = listOf(FormOption("1", "v1"), FormOption("2", "v2"), FormOption("3", "v3"), FormOption("4", "v4"), FormOption("5", "v5")),
        ),
        FormField("obfs-opts.mode", "混淆模式", FormFieldType.SELECT, allowEmpty = true, options = listOf(FormOption("http", "http"), FormOption("tls", "tls"))),
        FormField("obfs-opts.host", "混淆 Host", FormFieldType.TEXT, optional = true),
    ),
    "cns" to listOf(
        FormField("key", "密钥 key", FormFieldType.TEXT, optional = true, placeholder = "Meng", desc = "对应 Proxy_key，省略默认 Meng"),
        FormField("password", "密码 password", FormFieldType.TEXT, optional = true, desc = "对应 Encrypt_password，空则不 XOR"),
        FormField("flag", "UDP 标志 flag", FormFieldType.TEXT, optional = true, placeholder = "httpUDP", desc = "对应 Udp_flag；握手里出现该行即走 UDP-over-TCP"),
    ),
    "tun" to listOf(
        FormField(
            "stack", "协议栈 stack", FormFieldType.SELECT, allowEmpty = true,
            options = listOf(FormOption("system", "system"), FormOption("gvisor", "gvisor"), FormOption("mixed", "mixed"), FormOption("mips", "mips")),
            desc = "mips = mihomo 自研 IP 协议栈，需内核 ≥ v1.19.31",
        ),
        FormField("dns-hijack", "DNS 劫持", FormFieldType.LIST, optional = true, hint = "0.0.0.0:53"),
        FormField("inet4-address", "IPv4 地址", FormFieldType.LIST, optional = true, hint = "198.19.0.1/30"),
        FormField("inet6-address", "IPv6 地址", FormFieldType.LIST, optional = true),
        FormField("mtu", "MTU", FormFieldType.NUMBER, optional = true),
        FormField("auto-route", "自动路由", FormFieldType.BOOL, optional = true),
        FormField("auto-detect-interface", "自动探测出口网卡", FormFieldType.BOOL, optional = true),
        FormField("strict-route", "严格路由", FormFieldType.BOOL, optional = true),
        FormField("endpoint-independent-nat", "全锥形 NAT", FormFieldType.BOOL, optional = true),
    ),
)

/** TLS 证书（参考实现 IN_TLS_FIELDS）。 */
val LISTENER_TLS_FIELDS: List<FormField> = listOf(
    FormField("certificate", "证书 certificate", FormFieldType.TEXT, optional = true, placeholder = "./server.crt", desc = "PEM 内容或证书路径"),
    FormField("private-key", "私钥 private-key", FormFieldType.TEXT, optional = true, placeholder = "./server.key", desc = "PEM 内容或私钥路径，需与证书同时填写"),
    FormField(
        "client-auth-type", "mTLS 客户端校验", FormFieldType.SELECT, allowEmpty = true, emptyLabel = "不校验（默认）",
        options = listOf(FormOption("request", "request"), FormOption("require-any", "require-any"), FormOption("verify-if-given", "verify-if-given"), FormOption("require-and-verify", "require-and-verify")),
        desc = "选 verify-if-given / require-and-verify 时，下方客户端证书必填",
    ),
    FormField("client-auth-cert", "mTLS 客户端证书", FormFieldType.TEXT, optional = true, desc = "PEM 内容或证书路径"),
    FormField(
        "ech-key", "ECH 密钥 ech-key", FormFieldType.TEXTAREA, optional = true,
        placeholder = "-----BEGIN ECH KEYS-----\n…\n-----END ECH KEYS-----",
        desc = "可由 mihomo generate ech-keypair <域名> 生成",
    ),
)

/** REALITY（vmess / vless / trojan 共用；参考实现 IN_REALITY_FIELDS）。 */
val LISTENER_REALITY_FIELDS: List<FormField> = listOf(
    FormField("reality-config.dest", "REALITY 目标 dest", FormFieldType.TEXT, optional = true, placeholder = "test.com:443", desc = "偷取证书的真实站点；填写后即启用 REALITY（不可与证书同时使用）"),
    FormField("reality-config.private-key", "REALITY 私钥", FormFieldType.TEXT, optional = true, desc = "由 mihomo generate reality-keypair 生成"),
    FormField("reality-config.short-id", "REALITY short-id", FormFieldType.LIST, optional = true, hint = "0123456789abcdef"),
    FormField("reality-config.server-names", "REALITY server-names", FormFieldType.LIST, optional = true, hint = "test.com"),
    FormField("reality-config.max-time-difference", "最大时间偏差(μs)", FormFieldType.NUMBER, optional = true),
    FormField("reality-config.proxy", "REALITY 回落出口", FormFieldType.TEXT, optional = true),
)

/** 传输层（vmess / vless / trojan 共用；参考实现 IN_TRANSPORT_FIELDS）。 */
val LISTENER_TRANSPORT_FIELDS: List<FormField> = listOf(
    FormField("ws-path", "WebSocket 路径 ws-path", FormFieldType.TEXT, optional = true, placeholder = "/", desc = "非空即开启 WebSocket 传输层"),
    FormField("grpc-service-name", "gRPC 服务名", FormFieldType.TEXT, optional = true, placeholder = "GunService", desc = "非空即开启 gRPC 传输层"),
)

/** TLS 伪装层：shadow-tls / res-tls / jls-config（参考实现 IN_DISGUISE_FIELDS）。 */
val LISTENER_DISGUISE_FIELDS: List<FormField> = listOf(
    FormField("shadow-tls.enable", "启用 ShadowTLS", FormFieldType.BOOL, optional = true),
    FormField("shadow-tls.version", "ShadowTLS 版本", FormFieldType.SELECT, allowEmpty = true, numeric = true, options = listOf(FormOption("1", "v1"), FormOption("2", "v2"), FormOption("3", "v3"))),
    FormField("shadow-tls.password", "ShadowTLS 密码(v2)", FormFieldType.TEXT, optional = true),
    FormField("shadow-tls.handshake.dest", "ShadowTLS 握手目标", FormFieldType.TEXT, optional = true, placeholder = "www.example.com:443"),
    FormField("res-tls.enable", "启用 RestTLS", FormFieldType.BOOL, optional = true),
    FormField("res-tls.dest", "RestTLS 目标", FormFieldType.TEXT, optional = true, placeholder = "www.example.com:443"),
    FormField("res-tls.password", "RestTLS 密码", FormFieldType.TEXT, optional = true),
    FormField("jls-config.enable", "启用 JLS", FormFieldType.BOOL, optional = true),
    FormField("jls-config.dest", "JLS 回落目标", FormFieldType.TEXT, optional = true, placeholder = "www.example.com:443"),
    FormField("jls-config.sni", "JLS SNI", FormFieldType.TEXT, optional = true, desc = "留空时从 dest 推导"),
)

/** Multiplex（参考实现 IN_MUX_FIELDS）。 */
val LISTENER_MUX_FIELDS: List<FormField> = listOf(
    FormField("mux-option.padding", "Mux 填充", FormFieldType.BOOL, optional = true),
    FormField("mux-option.brutal.enabled", "Brutal 拥塞控制", FormFieldType.BOOL, optional = true),
    FormField("mux-option.brutal.up", "Brutal 上行(Mbps)", FormFieldType.NUMBER, optional = true),
    FormField("mux-option.brutal.down", "Brutal 下行(Mbps)", FormFieldType.NUMBER, optional = true),
)

/**
 * 已知协议的详情页小节组合（参考实现 renderListener 的分组顺序）。
 * eBPF / 未知协议返回 null，由页面用 [EBPF_SECTIONS] 或通用小节兜底。
 * `users` 对象列表协议的第一行是专门控件行（页面里接简版映射列表编辑器）。
 */
fun listenerSections(type: String): List<FormSection>? {
    if (type !in LISTENER_TEMPLATES || type == "ebpf") return null
    val out = ArrayList<FormSection>()
    val common = ArrayList<FormRow>()
    common.add(FormField("name", "名称", FormFieldType.TEXT, optional = true))
    if (type !in LISTENER_NO_PORT) {
        common.add(FormField("listen", "监听地址", FormFieldType.TEXT, placeholder = "0.0.0.0", optional = true))
        common.add(FormField("port", "监听端口", FormFieldType.NUMBER, optional = true))
        common.add(FormField("ports", "端口范围", FormFieldType.LIST, optional = true, desc = "多端口监听；与端口二选一"))
        common.add(FormField("udp", "监听 UDP", FormFieldType.BOOL, optional = true, default = true, desc = "内核默认开启"))
    }
    common.add(FormField("proxy", "转发到代理/代理组", FormFieldType.SELECT, optionsDynamic = "outbound", optional = true, allowEmpty = true, emptyLabel = "默认（走路由规则）", desc = "入站流量不走路由，直接交给指定代理"))
    common.add(FormField("rule", "子规则 sub-rules", FormFieldType.TEXT, optional = true, desc = "指定 sub-rules 中的规则组名"))
    common.add(FormField("routing-mark", "routing-mark", FormFieldType.NUMBER, optional = true, desc = "为监听 socket 设置 routing-mark，仅 Linux 有效"))
    out.add(FormSection("基础", common))

    val extra = ArrayList<FormRow>()
    if (type in LISTENER_USERS_COLUMNS) extra.add(FormCustomRow("in-users"))
    extra.addAll(LISTENER_EXTRA[type].orEmpty())
    if (extra.isNotEmpty()) out.add(FormSection("协议参数", extra))
    if (type in LISTENER_REALITY_TYPES) out.add(FormSection("传输层", LISTENER_TRANSPORT_FIELDS))
    if (type in LISTENER_TLS_TYPES) out.add(FormSection("TLS 证书", LISTENER_TLS_FIELDS))
    if (type in LISTENER_REALITY_TYPES) out.add(FormSection("REALITY", LISTENER_REALITY_FIELDS))
    if (type in LISTENER_REALITY_TYPES) out.add(FormSection("TLS 伪装（ShadowTLS / RestTLS / JLS）", LISTENER_DISGUISE_FIELDS))
    if (type in LISTENER_MUX_TYPES) out.add(FormSection("Multiplex", LISTENER_MUX_FIELDS))
    return out
}
