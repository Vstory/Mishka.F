package top.yukonga.mishka.custom.forms

// 本文件由 tools/forms/gen_specs.py 生成，请勿手改。
// 数据源（都是从 mihomo_box WebUI 参考实现求值提取的）：
//   * tools/forms/fields.json     —— extract_fields.mjs：PER_TYPE / NET_FIELDS / TLS_FIELDS / SMUX_FIELDS / TAIL_FIELDS /
//                                    PROXY_TEMPLATES / EXCLUDE_TYPE_OPTIONS / EXPR_PRESETS（顶层字面量）
//   * tools/forms/fields_p2.json  —— extract_flow.mjs：pages-flow.js / pages-config.js 各编辑器函数体内的字段表、模板、
//                                    默认值与常量（代理组 / 代理集合 / 规则集合 / 规则类型 / 隧道 / 内置策略…）
// 各表 by lazy：避免把几百个 FormField 塞进同一个静态初始化块。
// 流程页的布尔字段全部 boolAs = "pick"（参考实现 boolAsPick：默认（不覆写）/ 开 / 关，默认 = 删键）。

/** 规则类型（参考实现 RULE_TYPES + hintFor 提示语）。multi：匹配值多行输入（AND / OR / NOT / SUB-RULE）；noPayload：MATCH；
 *  ipRule：参考实现 isIpRule（no-resolve 只对它们有意义）；targetKind 是我们的附加项：RULE-SET 的载荷是规则集合名、
 *  SUB-RULE 的目标是子规则名，编辑器据此给候选。 */
data class RuleTypeSpec(
    val value: String,
    val label: String,
    val placeholder: String? = null,
    val multi: Boolean = false,
    val noPayload: Boolean = false,
    val ipRule: Boolean = false,
    val targetKind: String? = null,
)


/** 出站代理协议清单（新建节点时的协议选择，顺序即菜单顺序；参考实现 pages-flow.js:65）。 */
val PROXY_TYPES: List<FormOption> = listOf(
    FormOption("direct", "direct"),
    FormOption("dns", "dns"),
    FormOption("reject", "reject"),
    FormOption("rematch", "rematch"),
    FormOption("http", "http"),
    FormOption("socks5", "socks5"),
    FormOption("ss", "ss"),
    FormOption("ssr", "ssr"),
    FormOption("snell", "snell"),
    FormOption("vmess", "vmess"),
    FormOption("vless", "vless"),
    FormOption("trojan", "trojan"),
    FormOption("anytls", "anytls"),
    FormOption("mieru", "mieru"),
    FormOption("sudoku", "sudoku"),
    FormOption("hysteria", "hysteria"),
    FormOption("hysteria2", "hysteria2"),
    FormOption("tuic", "tuic"),
    FormOption("shadowquic", "shadowquic"),
    FormOption("wireguard", "wireguard"),
    FormOption("tailscale", "tailscale"),
    FormOption("ssh", "ssh"),
    FormOption("masque", "masque"),
    FormOption("trusttunnel", "trusttunnel"),
    FormOption("zerotier", "zerotier"),
    FormOption("openvpn", "openvpn"),
    FormOption("cns", "cns"),
)

/** 每种协议在「协议参数」之外显示哪些小节：server / net / tls / tlsbool / smux / tail（参考实现 pages-flow.js:345–351 的 NO_SERVER / SHOW_NET / SHOW_SMUX / SHOW_TLS / SHOW_HYTLS）。 参考实现流程页没列到、按模板兜底的协议：cns。 */
val PROXY_FEATURES: Map<String, Set<String>> = mapOf(
    "direct" to setOf("tail"),
    "dns" to setOf("tail"),
    "reject" to setOf("tail"),
    "rematch" to setOf("tail"),
    "http" to setOf("server", "tls", "tail"),
    "socks5" to setOf("server", "tls", "tail"),
    "ss" to setOf("server", "smux", "tail"),
    "ssr" to setOf("server", "smux", "tail"),
    "snell" to setOf("server", "tail"),
    "vmess" to setOf("server", "net", "tls", "tlsbool", "smux", "tail"),
    "vless" to setOf("server", "net", "tls", "tlsbool", "smux", "tail"),
    "trojan" to setOf("server", "net", "tls", "tlsbool", "smux", "tail"),
    "anytls" to setOf("server", "tls", "tlsbool", "smux", "tail"),
    "mieru" to setOf("server", "tail"),
    "sudoku" to setOf("server", "tail"),
    "hysteria" to setOf("server", "tls", "smux", "tail"),
    "hysteria2" to setOf("server", "tls", "smux", "tail"),
    "tuic" to setOf("server", "tls", "smux", "tail"),
    "shadowquic" to setOf("server", "tail"),
    "wireguard" to setOf("server", "tail"),
    "tailscale" to setOf("tail"),
    "ssh" to setOf("server", "tail"),
    "masque" to setOf("server", "tail"),
    "trusttunnel" to setOf("server", "tail"),
    "zerotier" to setOf("tail"),
    "openvpn" to setOf("server", "tail"),
    "cns" to setOf("server", "tail"),
)

/** 节点基础字段：服务器 / 端口（参考实现 pages-flow.js:420；NO_SERVER 的协议不显示）。 */
val PROXY_BASE_FIELDS: List<FormField> by lazy {
    listOf(
        FormField(
            path = "server",
            label = "服务器地址",
            type = FormFieldType.TEXT,
            optional = true,
        ),
        FormField(
            path = "port",
            label = "端口",
            type = FormFieldType.NUMBER,
            optional = true,
        ),
    )
}

/** 传输层 network 的取值（参考实现 pages-flow.js:352）。 */
val PROXY_NETWORKS: List<FormOption> = listOf(FormOption("tcp", "tcp（默认）"), FormOption("ws", "ws · WebSocket"), FormOption("h2", "h2 · HTTP/2"), FormOption("grpc", "grpc"), FormOption("http", "http"), FormOption("xhttp", "xhttp · 新版"))

/** 各传输层的 opts 键：切换 network 时删掉其它传输层的 opts（参考实现 pages-flow.js:434）。 */
val NET_OPTS_KEYS: List<String> = listOf("ws-opts", "h2-opts", "grpc-opts", "http-opts", "xhttp-opts")

/** 节点详情页各段标题（editProxySheet 的 group-title）。 */
val PROXY_SECTION_TITLES: Map<String, String> = mapOf("net" to "传输层", "tls" to "TLS / Reality", "smux" to "多路复用 smux", "tail" to "通用链式 / 拨号", "other" to "其他参数（YAML，可选）")

/** 内置策略 + 说明（参考实现 pages-config.js:11）：规则目标 / 代理组成员 / 空组回退里总是可选。 */
val BUILTIN_POLICIES: List<FormOption> = listOf(FormOption("DIRECT", "直连，数据直接出站"), FormOption("REJECT", "拒绝，拦截数据出站"), FormOption("REJECT-DROP", "拒绝，静默抛弃请求，不像 REJECT 那样回应错误"), FormOption("PASS", "绕过，跳过当前命中的规则分支继续匹配；在 SUB-RULE 中会跳出子规则回到主规则"), FormOption("PASS-RULE", "绕过，同 PASS，但在 SUB-RULE 中不跳出，继续在子规则内向后匹配"), FormOption("COMPATIBLE", "兼容，策略组筛选不出节点时出现，等效 DIRECT"))

/** 新建节点模板（参考实现 pages-flow.js:65）。新节点名按参考实现取 `<协议>-out`。 */
val PROXY_TEMPLATES: Map<String, Map<String, Any?>> by lazy {
    linkedMapOf(
        "direct" to linkedMapOf<String, Any?>("name" to "新的直连", "type" to "direct", "udp" to true),
        "dns" to linkedMapOf<String, Any?>("name" to "DNS 出站", "type" to "dns"),
        "reject" to linkedMapOf<String, Any?>("name" to "新的拒绝出站", "type" to "reject"),
        "rematch" to linkedMapOf<String, Any?>("name" to "新的REMATCH", "type" to "rematch", "target-rematch-name" to "mark1"),
        "http" to linkedMapOf<String, Any?>("name" to "新的HTTP代理", "type" to "http", "server" to "1.2.3.4", "port" to 8080),
        "socks5" to linkedMapOf<String, Any?>("name" to "新的Socks5", "type" to "socks5", "server" to "1.2.3.4", "port" to 1080, "udp" to true),
        "ss" to linkedMapOf<String, Any?>("name" to "新的SS节点", "type" to "ss", "server" to "1.2.3.4", "port" to 8388, "cipher" to "aes-256-gcm", "password" to "密码", "udp" to true),
        "ssr" to linkedMapOf<String, Any?>("name" to "新的SSR节点", "type" to "ssr", "server" to "1.2.3.4", "port" to 8388, "cipher" to "aes-256-cfb", "password" to "密码", "protocol" to "origin", "obfs" to "http_simple", "udp" to true),
        "snell" to linkedMapOf<String, Any?>("name" to "新的Snell节点", "type" to "snell", "server" to "1.2.3.4", "port" to 8388, "psk" to "密码", "version" to 4, "udp" to true),
        "vmess" to linkedMapOf<String, Any?>("name" to "新的VMess节点", "type" to "vmess", "server" to "1.2.3.4", "port" to 443, "uuid" to "uuid", "alterId" to 0, "cipher" to "auto", "udp" to true),
        "vless" to linkedMapOf<String, Any?>("name" to "新的VLESS节点", "type" to "vless", "server" to "1.2.3.4", "port" to 443, "uuid" to "uuid", "udp" to true, "client-fingerprint" to "chrome"),
        "trojan" to linkedMapOf<String, Any?>("name" to "新的Trojan节点", "type" to "trojan", "server" to "1.2.3.4", "port" to 443, "password" to "密码", "sni" to "example.com", "udp" to true),
        "anytls" to linkedMapOf<String, Any?>("name" to "新的AnyTLS节点", "type" to "anytls", "server" to "1.2.3.4", "port" to 443, "password" to "密码", "sni" to "example.com", "skip-cert-verify" to false, "udp" to true),
        "mieru" to linkedMapOf<String, Any?>("name" to "新的Mieru节点", "type" to "mieru", "server" to "1.2.3.4", "port" to 2999, "username" to "user", "password" to "密码", "transport" to "TCP"),
        "sudoku" to linkedMapOf<String, Any?>("name" to "新的Sudoku节点", "type" to "sudoku", "server" to "1.2.3.4", "port" to 8080, "key" to "密钥", "aead" to true),
        "hysteria" to linkedMapOf<String, Any?>("name" to "新的Hy1节点", "type" to "hysteria", "server" to "1.2.3.4", "port" to 443, "auth-str" to "密码", "protocol" to "udp", "up" to 30, "down" to 100, "sni" to "example.com"),
        "hysteria2" to linkedMapOf<String, Any?>("name" to "新的Hy2节点", "type" to "hysteria2", "server" to "1.2.3.4", "port" to 443, "password" to "密码", "sni" to "example.com", "skip-cert-verify" to false),
        "tuic" to linkedMapOf<String, Any?>("name" to "新的Tuic节点", "type" to "tuic", "server" to "1.2.3.4", "port" to 443, "uuid" to "uuid", "password" to "密码", "congestion-controller" to "cubic", "udp-relay-mode" to "native"),
        "shadowquic" to linkedMapOf<String, Any?>("name" to "新的ShadowQUIC", "type" to "shadowquic", "server" to "www.example.com", "port" to 10443, "username" to "user", "password" to "pass"),
        "wireguard" to linkedMapOf<String, Any?>("name" to "新的WireGuard", "type" to "wireguard", "server" to "1.2.3.4", "port" to 51820, "ip" to "172.16.0.2/32", "private-key" to "本机私钥", "public-key" to "对端公钥", "udp" to true),
        "tailscale" to linkedMapOf<String, Any?>("name" to "新的Tailscale", "type" to "tailscale", "hostname" to "mihomo", "auth-key" to "tskey-auth-xxxx", "udp" to true, "accept-routes" to true),
        "ssh" to linkedMapOf<String, Any?>("name" to "新的SSH节点", "type" to "ssh", "server" to "1.2.3.4", "port" to 22, "username" to "root", "password" to "密码"),
        "masque" to linkedMapOf<String, Any?>("name" to "新的MASQUE", "type" to "masque", "server" to "server.com", "port" to 443, "private-key" to "", "public-key" to "", "ip" to "172.16.0.2/32", "mtu" to 1280, "udp" to true),
        "trusttunnel" to linkedMapOf<String, Any?>("name" to "新的TrustTunnel", "type" to "trusttunnel", "server" to "1.2.3.4", "port" to 443, "username" to "user", "password" to "pass", "health-check" to true, "udp" to true),
        "zerotier" to linkedMapOf<String, Any?>("name" to "新的ZeroTier", "type" to "zerotier", "network" to "0123456789abcdef", "udp" to true),
        "openvpn" to linkedMapOf<String, Any?>("name" to "新的OpenVPN", "type" to "openvpn", "server" to "vpn.example.com", "port" to 1194, "proto" to "udp", "username" to "user", "password" to "pass", "ca" to "-----BEGIN CERTIFICATE-----\n请替换为 CA 根证书内容\n-----END CERTIFICATE-----", "udp" to true),
        "cns" to linkedMapOf<String, Any?>("name" to "新的CNS节点", "type" to "cns", "server" to "1.2.3.4", "port" to 23333, "key" to "Meng", "password" to "", "flag" to "httpUDP", "udp" to true),
    )
}

/** 协议专属字段（参考实现 pages-flow.js:131）。 */
val PROXY_PER_TYPE: Map<String, List<FormField>> by lazy {
    linkedMapOf(
        "direct" to listOf(
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "ip-version",
                label = "IP 版本",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("dual", "dual(双栈)"), FormOption("ipv4", "ipv4"), FormOption("ipv6", "ipv6"), FormOption("ipv4-prefer", "ipv4 优先"), FormOption("ipv6-prefer", "ipv6 优先")),
                optional = true,
                allowEmpty = true,
            ),
        ),
        "dns" to listOf(
        ),
        "reject" to listOf(
        ),
        "rematch" to listOf(
            FormField(
                path = "target-rematch-name",
                label = "重匹配名称 REMATCH-NAME",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "target-sub-rule",
                label = "跳转子规则 sub-rule",
                type = FormFieldType.TEXT,
                optional = true,
            ),
        ),
        "http" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "tls",
                label = "TLS (https)",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "socks5" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "tls",
                label = "TLS 加密",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "ss" to listOf(
            FormField(
                path = "cipher",
                label = "加密方式 cipher",
                type = FormFieldType.TEXT,
                placeholder = "aes-256-gcm / 2022-blake3-aes-128-gcm",
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "plugin",
                label = "插件 plugin",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("obfs", "obfs"), FormOption("v2ray-plugin", "v2ray-plugin"), FormOption("shadow-tls", "shadow-tls"), FormOption("restls", "restls")),
                allowEmpty = true,
            ),
            FormField(
                path = "plugin-opts",
                label = "插件参数 plugin-opts",
                type = FormFieldType.MAPTEXT,
                desc = "如 mode: tls / host: bing.com / password: xxx",
                optional = true,
            ),
            FormField(
                path = "udp-over-tcp",
                label = "UDP over TCP",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "ssr" to listOf(
            FormField(
                path = "cipher",
                label = "加密方式 cipher",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "protocol",
                label = "协议 protocol",
                type = FormFieldType.TEXT,
                placeholder = "origin / auth_aes128_md5",
                optional = true,
            ),
            FormField(
                path = "obfs",
                label = "混淆 obfs",
                type = FormFieldType.TEXT,
                placeholder = "plain / http_simple",
                optional = true,
            ),
            FormField(
                path = "protocol-param",
                label = "协议参数",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "obfs-param",
                label = "混淆参数",
                type = FormFieldType.TEXT,
                optional = true,
            ),
        ),
        "snell" to listOf(
            FormField(
                path = "psk",
                label = "PSK 密钥",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "version",
                label = "协议版本",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("1", "v1"), FormOption("2", "v2"), FormOption("3", "v3"), FormOption("4", "v4（推荐）"), FormOption("5", "v5（最新，需服务端支持）")),
                allowEmpty = true,
                numeric = true,
            ),
            FormField(
                path = "obfs-opts.mode",
                label = "混淆模式",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("http", "http"), FormOption("tls", "tls")),
                allowEmpty = true,
            ),
            FormField(
                path = "obfs-opts.host",
                label = "混淆 Host",
                type = FormFieldType.TEXT,
                placeholder = "如 itunes.apple.com",
                optional = true,
            ),
        ),
        "vmess" to listOf(
            FormField(
                path = "uuid",
                label = "UUID",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "alterId",
                label = "alterId",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "cipher",
                label = "加密方式",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("auto", "auto"), FormOption("aes-128-gcm", "aes-128-gcm"), FormOption("chacha20-poly1305", "chacha20-poly1305"), FormOption("none", "none")),
                allowEmpty = true,
            ),
            FormField(
                path = "xudp",
                label = "XUDP",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "vless" to listOf(
            FormField(
                path = "uuid",
                label = "UUID",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "flow",
                label = "flow 流控",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("xtls-rprx-vision", "xtls-rprx-vision")),
                allowEmpty = true,
            ),
            FormField(
                path = "packet-encoding",
                label = "包编码",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("packetaddr", "packetaddr"), FormOption("xudp", "xudp")),
                allowEmpty = true,
            ),
            FormField(
                path = "encryption",
                label = "encryption（ML-KEM 等）",
                type = FormFieldType.TEXT,
                placeholder = "none / mlkem768x25519plus.…",
                tag = "新版",
                optional = true,
            ),
        ),
        "trojan" to listOf(
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
        ),
        "anytls" to listOf(
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "idle-session-check-interval",
                label = "空闲检查间隔(秒)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "idle-session-timeout",
                label = "空闲会话超时(秒)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "min-idle-session",
                label = "最小空闲会话数",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "mieru" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "transport",
                label = "传输 transport",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("TCP", "TCP")),
                allowEmpty = true,
            ),
            FormField(
                path = "multiplexing",
                label = "多路复用",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("MULTIPLEXING_OFF", "关闭"), FormOption("MULTIPLEXING_LOW", "低"), FormOption("MULTIPLEXING_MIDDLE", "中"), FormOption("MULTIPLEXING_HIGH", "高")),
                allowEmpty = true,
            ),
        ),
        "sudoku" to listOf(
            FormField(
                path = "key",
                label = "密钥 key",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "aead",
                label = "AEAD 加密",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "table-type",
                label = "映射表类型",
                type = FormFieldType.TEXT,
                placeholder = "prefer_ascii / prefer_entropy",
                optional = true,
            ),
            FormField(
                path = "padding-min",
                label = "最小填充(%)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "padding-max",
                label = "最大填充(%)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "hysteria" to listOf(
            FormField(
                path = "auth-str",
                label = "认证串 auth-str",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "protocol",
                label = "协议",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("udp", "udp"), FormOption("wechat-video", "wechat-video"), FormOption("faketcp", "faketcp")),
                allowEmpty = true,
            ),
            FormField(
                path = "up",
                label = "上行带宽",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "down",
                label = "下行带宽",
                type = FormFieldType.TEXT,
                optional = true,
            ),
        ),
        "hysteria2" to listOf(
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "up",
                label = "上行带宽",
                type = FormFieldType.TEXT,
                placeholder = "30 Mbps / 数字",
                optional = true,
            ),
            FormField(
                path = "down",
                label = "下行带宽",
                type = FormFieldType.TEXT,
                placeholder = "200 Mbps",
                optional = true,
            ),
            FormField(
                path = "obfs",
                label = "混淆 obfs",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("salamander", "salamander")),
                allowEmpty = true,
            ),
            FormField(
                path = "obfs-password",
                label = "混淆密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "ports",
                label = "端口跳跃 ports",
                type = FormFieldType.TEXT,
                placeholder = "10000-20000",
                desc = "范围或逗号分隔多端口；留空=单端口",
                optional = true,
            ),
            FormField(
                path = "hop-interval",
                label = "跳跃间隔 hop-interval（秒）",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "tuic" to listOf(
            FormField(
                path = "uuid",
                label = "UUID",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "congestion-controller",
                label = "拥塞控制",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("cubic", "cubic"), FormOption("bbr", "bbr"), FormOption("new_reno", "new_reno")),
                allowEmpty = true,
            ),
            FormField(
                path = "udp-relay-mode",
                label = "UDP 中继模式",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("native", "native"), FormOption("quic", "quic")),
                allowEmpty = true,
            ),
            FormField(
                path = "reduce-rtt",
                label = "0-RTT 握手",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "heartbeat-interval",
                label = "心跳间隔(ms)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "shadowquic" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "sni",
                label = "SNI",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "alpn",
                label = "ALPN",
                type = FormFieldType.LIST,
                hint = "h3",
                optional = true,
            ),
            FormField(
                path = "quic-versions",
                label = "QUIC 版本",
                type = FormFieldType.TEXT,
                placeholder = "v1",
                optional = true,
            ),
            FormField(
                path = "udp-over-stream",
                label = "UDP over Stream",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "zero-rtt",
                label = "0-RTT",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "keep-alive-interval",
                label = "保活间隔（ms）",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "congestion-controller",
                label = "拥塞控制",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("cubic", "cubic"), FormOption("bbr", "bbr"), FormOption("new_reno", "new_reno")),
                allowEmpty = true,
            ),
            FormField(
                path = "bbr-profile",
                label = "BBR 策略",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("standard", "standard"), FormOption("conservative", "conservative"), FormOption("aggressive", "aggressive")),
                allowEmpty = true,
            ),
            FormField(
                path = "max-datagram-frame-size",
                label = "最大 Datagram 帧",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "wireguard" to listOf(
            FormField(
                path = "ip",
                label = "本机 IPv4（CIDR）",
                type = FormFieldType.TEXT,
                placeholder = "172.16.0.2/32",
                optional = true,
            ),
            FormField(
                path = "ipv6",
                label = "本机 IPv6（CIDR）",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "private-key",
                label = "本机私钥",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "public-key",
                label = "对端公钥",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "pre-shared-key",
                label = "预共享密钥 PSK",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "allowed-ips",
                label = "AllowedIPs",
                type = FormFieldType.LIST,
                hint = "0.0.0.0/0, ::/0",
                optional = true,
            ),
            FormField(
                path = "dns",
                label = "DNS 服务器",
                type = FormFieldType.LIST,
                optional = true,
            ),
            FormField(
                path = "mtu",
                label = "MTU",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "remote-dns-resolve",
                label = "远端 DNS 解析",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "fwmark",
                label = "fwmark 路由标记",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "tailscale" to listOf(
            FormField(
                path = "hostname",
                label = "设备名 hostname",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "auth-key",
                label = "登录密钥 auth-key",
                type = FormFieldType.TEXT,
                placeholder = "tskey-auth-xxxx",
                optional = true,
            ),
            FormField(
                path = "control-url",
                label = "控制服务地址",
                type = FormFieldType.TEXT,
                placeholder = "https://controlplane.tailscale.com",
                optional = true,
            ),
            FormField(
                path = "state-dir",
                label = "状态目录 state-dir",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "ephemeral",
                label = "临时节点 ephemeral",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "accept-routes",
                label = "接受路由 accept-routes",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "exit-node",
                label = "出口节点 exit-node",
                type = FormFieldType.TEXT,
                placeholder = "100.64.0.1",
                optional = true,
            ),
            FormField(
                path = "exit-node-allow-lan-access",
                label = "出口节点允许 LAN 访问",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "ssh" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码（与私钥二选一）",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "private-key",
                label = "私钥（路径或内容）",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "host-key-algorithms",
                label = "主机公钥算法",
                type = FormFieldType.LIST,
                optional = true,
            ),
            FormField(
                path = "host-key",
                label = "固定主机公钥",
                type = FormFieldType.LIST,
                optional = true,
            ),
        ),
        "masque" to listOf(
            FormField(
                path = "private-key",
                label = "私钥 private-key (Base64)",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "public-key",
                label = "公钥 public-key (Base64)",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "ip",
                label = "本机 IPv4（CIDR）",
                type = FormFieldType.TEXT,
                placeholder = "172.16.0.2/32",
                optional = true,
            ),
            FormField(
                path = "ipv6",
                label = "本机 IPv6（CIDR）",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "network",
                label = "工作模式 network",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("", "h3（默认·TUN 出站）"), FormOption("h3-l4proxy", "h3-l4proxy（L4 代理）"), FormOption("h2", "h2")),
                allowEmpty = true,
            ),
            FormField(
                path = "sni",
                label = "SNI",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "mtu",
                label = "MTU",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "congestion-controller",
                label = "拥塞控制",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("cubic", "cubic"), FormOption("bbr", "bbr"), FormOption("new_reno", "new_reno")),
                allowEmpty = true,
            ),
            FormField(
                path = "bbr-profile",
                label = "BBR 策略",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("standard", "standard"), FormOption("conservative", "conservative"), FormOption("aggressive", "aggressive")),
                allowEmpty = true,
            ),
            FormField(
                path = "handshake-timeout",
                label = "握手超时（秒）",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "trusttunnel" to listOf(
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "health-check",
                label = "健康检查",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "quic",
                label = "QUIC 传输",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "sni",
                label = "SNI",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "alpn",
                label = "ALPN",
                type = FormFieldType.LIST,
                hint = "h2",
                optional = true,
            ),
            FormField(
                path = "client-fingerprint",
                label = "TLS 指纹",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("chrome", "chrome"), FormOption("firefox", "firefox"), FormOption("safari", "safari"), FormOption("ios", "ios"), FormOption("android", "android"), FormOption("edge", "edge"), FormOption("random", "random"), FormOption("randomized", "randomized")),
                allowEmpty = true,
            ),
            FormField(
                path = "congestion-controller",
                label = "拥塞控制",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("cubic", "cubic"), FormOption("bbr", "bbr"), FormOption("new_reno", "new_reno")),
                allowEmpty = true,
            ),
            FormField(
                path = "bbr-profile",
                label = "BBR 策略",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("standard", "standard"), FormOption("conservative", "conservative"), FormOption("aggressive", "aggressive")),
                allowEmpty = true,
            ),
            FormField(
                path = "skip-cert-verify",
                label = "跳过证书校验",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "max-connections",
                label = "最大连接数",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "min-streams",
                label = "最小复用流",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "max-streams",
                label = "最大复用流",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
        ),
        "zerotier" to listOf(
            FormField(
                path = "network",
                label = "网络 ID (16 位 hex)",
                type = FormFieldType.TEXT,
                placeholder = "0123456789abcdef",
                optional = true,
            ),
            FormField(
                path = "state-dir",
                label = "状态目录",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "planet",
                label = "私有 Planet 文件",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "mtu",
                label = "MTU",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "physical-mtu",
                label = "物理 MTU",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "primary-port",
                label = "主端口",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "secondary-port",
                label = "次端口",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "low-bandwidth",
                label = "低带宽模式",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "encrypted-hello",
                label = "加密握手",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "openvpn" to listOf(
            FormField(
                path = "proto",
                label = "传输协议 proto",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("udp", "udp（默认）"), FormOption("tcp", "tcp")),
                desc = "仅支持 udp / tcp",
                allowEmpty = true,
            ),
            FormField(
                path = "dev",
                label = "虚拟网卡类型 dev",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("tun", "tun（默认·内核仅支持）")),
                desc = "内核校验：非 tun 直接报错",
                allowEmpty = true,
            ),
            FormField(
                path = "ca",
                label = "CA 根证书（PEM）",
                type = FormFieldType.TEXTAREA,
                placeholder = "-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----",
                desc = "必填。用于校验服务端证书，缺失会导致节点不可用",
                tag = "必填",
            ),
            FormField(
                path = "cert",
                label = "客户端证书（PEM）",
                type = FormFieldType.TEXTAREA,
                placeholder = "-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----",
                desc = "证书认证时填；与下方用户名密码二选一",
                optional = true,
            ),
            FormField(
                path = "key",
                label = "客户端私钥（PEM）",
                type = FormFieldType.TEXTAREA,
                placeholder = "-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----",
                optional = true,
            ),
            FormField(
                path = "username",
                label = "用户名",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码",
                type = FormFieldType.PASSWORD,
                optional = true,
            ),
            FormField(
                path = "tls-crypt",
                label = "tls-crypt 静态密钥",
                type = FormFieldType.TEXTAREA,
                placeholder = "-----BEGIN OpenVPN Static key V1-----\n…\n-----END OpenVPN Static key V1-----",
                desc = "控制通道加密密钥（可与服务端 tls-crypt 配套）",
                optional = true,
            ),
            FormField(
                path = "cipher",
                label = "加密算法 cipher",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("AES-128-GCM", "AES-128-GCM（默认）"), FormOption("AES-192-GCM", "AES-192-GCM"), FormOption("AES-256-GCM", "AES-256-GCM"), FormOption("CHACHA20-POLY1305", "CHACHA20-POLY1305"), FormOption("AES-128-CBC", "AES-128-CBC"), FormOption("AES-192-CBC", "AES-192-CBC"), FormOption("AES-256-CBC", "AES-256-CBC")),
                desc = "内核校验：仅接受列出的套件，留空= AES-128-GCM",
                allowEmpty = true,
            ),
            FormField(
                path = "auth",
                label = "HMAC 校验 auth",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("SHA256", "SHA256（默认）"), FormOption("SHA1", "SHA1"), FormOption("SHA384", "SHA384"), FormOption("SHA512", "SHA512"), FormOption("MD5", "MD5")),
                desc = "留空= SHA256",
                allowEmpty = true,
            ),
            FormField(
                path = "comp-lzo",
                label = "LZO 压缩 comp-lzo",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("yes", "yes"), FormOption("adaptive", "adaptive"), FormOption("no", "no")),
                desc = "adaptive 等同 yes",
                allowEmpty = true,
            ),
            FormField(
                path = "mtu",
                label = "MTU",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "ping",
                label = "ping 间隔（秒）",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "ping-restart",
                label = "ping-restart（秒）",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "udp",
                label = "UDP 支持",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "remote-dns-resolve",
                label = "远端 DNS 解析",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "dns",
                label = "远端 DNS 服务器",
                type = FormFieldType.LIST,
                optional = true,
            ),
        ),
        "cns" to listOf(
            FormField(
                path = "key",
                label = "密钥 key",
                type = FormFieldType.TEXT,
                placeholder = "Meng",
                desc = "对应服务端 Proxy_key，省略默认 Meng",
                optional = true,
            ),
            FormField(
                path = "password",
                label = "密码 password",
                type = FormFieldType.TEXT,
                desc = "对应 Encrypt_password，空则不 XOR",
                optional = true,
            ),
            FormField(
                path = "flag",
                label = "UDP 标志 flag",
                type = FormFieldType.TEXT,
                placeholder = "httpUDP",
                optional = true,
            ),
            FormField(
                path = "headers",
                label = "请求头 headers",
                type = FormFieldType.HEADERS,
                desc = "如 Host 伪装",
                optional = true,
            ),
        ),
    )
}

/** 传输层字段，按 network 分（参考实现 pages-flow.js:361）。 */
val PROXY_NET_FIELDS: Map<String, List<FormField>> by lazy {
    linkedMapOf(
        "ws" to listOf(
            FormField(
                path = "ws-opts.path",
                label = "WS 路径",
                type = FormFieldType.TEXT,
                placeholder = "/",
                optional = true,
            ),
            FormField(
                path = "ws-opts.headers",
                label = "WS 请求头",
                type = FormFieldType.HEADERS,
                desc = "自定义 WebSocket 请求头：左边填请求头名，右边填值",
                optional = true,
            ),
            FormField(
                path = "ws-opts.max-early-data",
                label = "max-early-data",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "ws-opts.early-data-header-name",
                label = "early-data 头名",
                type = FormFieldType.TEXT,
                placeholder = "Sec-WebSocket-Protocol",
                optional = true,
            ),
            FormField(
                path = "ws-opts.v2ray-http-upgrade",
                label = "HTTP Upgrade 模式",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "ws-opts.v2ray-http-upgrade-fast-open",
                label = "HTTP Upgrade Fast Open",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
        "http" to listOf(
            FormField(
                path = "http-opts.method",
                label = "HTTP 方法",
                type = FormFieldType.TEXT,
                placeholder = "GET",
                optional = true,
            ),
            FormField(
                path = "http-opts.path",
                label = "HTTP 路径列表",
                type = FormFieldType.LIST,
                hint = "如 /",
                optional = true,
            ),
            FormField(
                path = "http-opts.headers",
                label = "HTTP 请求头",
                type = FormFieldType.HEADERS,
                desc = "自定义 HTTP 请求头：左边填请求头名，右边填值；同一个请求头可添加多行作为候选值（每次请求随机取一个）",
                optional = true,
                arrayValues = true,
            ),
        ),
        "h2" to listOf(
            FormField(
                path = "h2-opts.host",
                label = "H2 Host 列表",
                type = FormFieldType.LIST,
                optional = true,
            ),
            FormField(
                path = "h2-opts.path",
                label = "H2 路径",
                type = FormFieldType.TEXT,
                placeholder = "/",
                optional = true,
            ),
        ),
        "grpc" to listOf(
            FormField(
                path = "grpc-opts.grpc-service-name",
                label = "gRPC ServiceName",
                type = FormFieldType.TEXT,
                optional = true,
            ),
        ),
        "xhttp" to listOf(
            FormField(
                path = "xhttp-opts.path",
                label = "XHTTP 路径",
                type = FormFieldType.TEXT,
                placeholder = "/",
                optional = true,
            ),
            FormField(
                path = "xhttp-opts.host",
                label = "XHTTP Host",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "xhttp-opts.mode",
                label = "XHTTP 模式",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("auto", "auto"), FormOption("packet-up", "packet-up"), FormOption("stream-up", "stream-up"), FormOption("stream-one", "stream-one")),
                allowEmpty = true,
            ),
            FormField(
                path = "xhttp-opts.headers",
                label = "XHTTP 请求头",
                type = FormFieldType.HEADERS,
                desc = "自定义 XHTTP 请求头：左边填请求头名，右边填值",
                optional = true,
            ),
            FormField(
                path = "xhttp-opts.no-grpc-header",
                label = "禁用 gRPC 头",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
        ),
    )
}

/** TLS 字段（SHOW_HYTLS 的协议去掉 `tls` 开关 = 参考实现 HY_TLS_FIELDS）（参考实现 pages-flow.js:388）。 */
val PROXY_TLS_FIELDS: List<FormField> by lazy {
    listOf(
        FormField(
            path = "tls",
            label = "启用 TLS",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "servername",
            label = "SNI（servername）",
            type = FormFieldType.TEXT,
            placeholder = "同义：sni",
            optional = true,
        ),
        FormField(
            path = "skip-cert-verify",
            label = "跳过证书校验",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "alpn",
            label = "ALPN",
            type = FormFieldType.LIST,
            hint = "h2, http/1.1",
            optional = true,
        ),
        FormField(
            path = "client-fingerprint",
            label = "uTLS 指纹",
            type = FormFieldType.SELECT,
            options = listOf(FormOption("chrome", "chrome"), FormOption("firefox", "firefox"), FormOption("safari", "safari"), FormOption("ios", "ios"), FormOption("android", "android"), FormOption("edge", "edge"), FormOption("random", "random"), FormOption("randomized", "randomized")),
            allowEmpty = true,
        ),
        FormField(
            path = "fingerprint",
            label = "证书指纹(HEX)",
            type = FormFieldType.TEXT,
            optional = true,
        ),
        FormField(
            path = "reality-opts.public-key",
            label = "Reality public-key",
            type = FormFieldType.TEXT,
            optional = true,
        ),
        FormField(
            path = "reality-opts.short-id",
            label = "Reality short-id",
            type = FormFieldType.TEXT,
            optional = true,
        ),
    )
}

/** 多路复用字段（参考实现 pages-flow.js:399）。 */
val PROXY_SMUX_FIELDS: List<FormField> by lazy {
    listOf(
        FormField(
            path = "smux.enabled",
            label = "启用多路复用 smux",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "smux.protocol",
            label = "复用协议",
            type = FormFieldType.SELECT,
            options = listOf(FormOption("smux", "smux"), FormOption("yamux", "yamux"), FormOption("h2mux", "h2mux")),
            allowEmpty = true,
        ),
        FormField(
            path = "smux.max-connections",
            label = "最大连接数",
            type = FormFieldType.NUMBER,
            optional = true,
        ),
        FormField(
            path = "smux.min-streams",
            label = "最小流数",
            type = FormFieldType.NUMBER,
            optional = true,
        ),
        FormField(
            path = "smux.max-streams",
            label = "最大流数",
            type = FormFieldType.NUMBER,
            optional = true,
        ),
        FormField(
            path = "smux.padding",
            label = "填充 padding",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
    )
}

/** 通用字段（UDP / TFO / 链式出口 / 网卡 / IP 版本）（参考实现 pages-flow.js:407）。 */
val PROXY_TAIL_FIELDS: List<FormField> by lazy {
    listOf(
        FormField(
            path = "udp",
            label = "UDP",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "tfo",
            label = "TFO (TCP Fast Open)",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "mptcp",
            label = "MPTCP",
            type = FormFieldType.BOOL,
            optional = true,
            boolAs = "pick",
        ),
        FormField(
            path = "dialer-proxy",
            label = "链式出口 dialer-proxy",
            type = FormFieldType.SELECT,
            desc = "本节点经此出口建立连接（代理名/代理组）",
            allowEmpty = true,
            emptyLabel = "默认（不覆写）",
        ),
        FormField(
            path = "interface-name",
            label = "绑定出口网卡",
            type = FormFieldType.TEXT,
            optional = true,
        ),
        FormField(
            path = "routing-mark",
            label = "路由标记 routing-mark",
            type = FormFieldType.NUMBER,
            optional = true,
        ),
        FormField(
            path = "ip-version",
            label = "IP 版本偏好",
            type = FormFieldType.SELECT,
            options = listOf(FormOption("dual", "dual"), FormOption("ipv4", "ipv4"), FormOption("ipv6", "ipv6"), FormOption("ipv4-prefer", "ipv4-prefer"), FormOption("ipv6-prefer", "ipv6-prefer")),
            allowEmpty = true,
        ),
    )
}

/** exclude-type 的候选（参考实现 pages-flow.js:1251）。 */
val EXCLUDE_TYPE_OPTIONS: List<String> = listOf("Shadowsocks", "ShadowsocksR", "Snell", "Socks5", "Http", "Vmess", "Vless", "Trojan", "Hysteria", "Hysteria2", "WireGuard", "Tuic", "Ssh", "Mieru", "AnyTLS", "ShadowQuic", "OpenVPN", "Tailscale", "ZeroTier", "Sudoku", "Masque", "TrustTunnel", "GostRelay")

/** override-expr 预设（参考实现 pages-flow.js:1142）。 */
val EXPR_PRESETS: List<Pair<String, List<String>>> = listOf(
    "前缀改名" to listOf(".name = \"[机场] \" + .name"),
    "后缀改名" to listOf(".name = .name + \" | 专线\""),
    "开启 UDP" to listOf(".udp = true"),
    "删除跳过证书校验" to listOf("del(.skip-cert-verify)"),
    "仅保留 TLS" to listOf("(select(.port == 443) | .tls) = true"),
    "混淆覆写" to listOf(".servername = \"填免流混淆\"", ".sni = .servername", "(select(.network == \"ws\") | .[\"ws-opts\"].headers.Host) = .servername", "(select(.network == \"http\") | .[\"http-opts\"].headers.Host) = [.servername]", "(select(.network == \"h2\") | .[\"h2-opts\"].headers.Host) = [.servername]", "(select(.network == \"xhttp\") | .[\"xhttp-opts\"].headers.Host) = .servername", "(select(.type == \"cns\") | .headers.Host) = .servername"),
)

/** 代理组类型（参考实现 pages-flow.js:1238）。 */
val GROUP_TYPES: List<FormOption> = listOf(FormOption("select", "select 手动选择"), FormOption("url-test", "url-test 自动测速"), FormOption("fallback", "fallback 自动回退"), FormOption("load-balance", "load-balance 负载均衡"), FormOption("smart", "smart 智能(Smart内核)"), FormOption("relay", "relay 链式"))

/** 新建代理组模板（参考实现 pages-flow.js:1486；名字重复时加序号）。 */
val GROUP_TEMPLATE: Map<String, Any?> by lazy { linkedMapOf<String, Any?>("name" to "手动选择", "type" to "select", "proxies" to emptyList<Any?>(), "use" to listOf<Any?>(), "url" to "https://cp.cloudflare.com/generate_204", "interval" to 300, "timeout" to 5000, "lazy" to true) }

/** 代理集合的来源类型（参考实现 pages-flow.js:840）。 */
val PROVIDER_TYPES: List<FormOption> = listOf(FormOption("http", "http 远程下载"), FormOption("file", "file 本地文件"), FormOption("inline", "inline 内联节点"))

/** 规则集合的来源类型（参考实现 pages-flow.js:2083）。 */
val RULE_PROVIDER_TYPES: List<FormOption> = listOf(FormOption("http", "http"), FormOption("file", "file"), FormOption("inline", "inline"))

/** 新建代理集合的默认值（参考实现 pages-flow.js:831）。不预置 interval：新建时不写「更新间隔」，交给内核默认值。 */
val PROVIDER_DEFAULT: Map<String, Any?> by lazy { linkedMapOf<String, Any?>("type" to "http", "url" to "", "path" to "") }

/** 新建规则集合的默认值（参考实现 pages-flow.js:2068）。不预置 interval，理由同 [PROVIDER_DEFAULT]。 */
val RULE_PROVIDER_DEFAULT: Map<String, Any?> by lazy { linkedMapOf<String, Any?>("type" to "http", "behavior" to "domain", "format" to "yaml", "url" to "") }

/** file 类型集合不该落盘的远程字段（参考实现 pages-flow.js:702）：sub = 代理集合，ep = 规则集合。 */
val FILE_HIDDEN_KEYS: Map<String, List<String>> = mapOf("sub" to listOf("url", "interval", "size-limit", "proxy", "age-secret-key", "header"), "ep" to listOf("url", "interval", "proxy"))

/** 开启健康检查时自动补的默认值（参考实现 pages-flow.js:921）。 */
val HC_DEFAULTS: Map<String, Any?> by lazy { linkedMapOf<String, Any?>("url" to "https://cp.cloudflare.com/generate_204", "interval" to 300) }

/** 新建隧道（映射写法）的默认值（参考实现 pages-config.js:1574）。 */
val TUNNEL_TEMPLATE: Map<String, Any?> by lazy { linkedMapOf<String, Any?>("network" to "tcp", "address" to "", "target" to "") }

/** 代理组：proxy-groups（序列，按下标编辑；带 only 的字段按组的 type 显隐）（fields_p2.json，editGroupSheet：GROUP_COMMON / GROUP_HEALTH / GROUP_TOLERANCE / GROUP_STRATEGY / GROUP_DEFAULT_SELECTED / GROUP_EMPTY_FALLBACK / GROUP_OTHERS / GROUP_SMART / GROUP_PROXIES / GROUP_USE） */
val GROUP_SECTIONS: List<FormSection> by lazy {
    listOf(
        FormSection("成员", listOf(
            FormField(
                path = "proxies",
                label = "成员(顺序生效)",
                type = FormFieldType.PICKLIST,
                optionsDynamic = "policies",
                optional = true,
            ),
            FormField(
                path = "use",
                label = "使用订阅(use)",
                type = FormFieldType.PICKLIST,
                optionsDynamic = "providers",
                optional = true,
            ),
        )),
        FormSection("通用参数（按类型自动显示）", listOf(
            FormField(
                path = "include-all",
                label = "包含全部 (代理+订阅)",
                type = FormFieldType.BOOL,
                desc = "自动包含全部 proxies 和 providers（按名称排序）",
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "include-all-proxies",
                label = "包含全部代理",
                type = FormFieldType.BOOL,
                desc = "仅包含全部 proxies",
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "include-all-providers",
                label = "包含全部订阅",
                type = FormFieldType.BOOL,
                desc = "仅包含全部 providers",
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "filter",
                label = "节点名称过滤(正则)",
                type = FormFieldType.TEXT,
                placeholder = "如 (?i)港|hk|hongkong",
                desc = "仅对 include-all/use 引入的节点生效，多正则用 ` 分隔",
                optional = true,
            ),
            FormField(
                path = "exclude-filter",
                label = "排除节点(正则)",
                type = FormFieldType.TEXT,
                desc = "排除匹配的节点",
                optional = true,
            ),
            FormField(
                path = "exclude-type",
                label = "排除节点类型",
                type = FormFieldType.PICKLIST,
                options = listOf(FormOption("Shadowsocks", "Shadowsocks"), FormOption("ShadowsocksR", "ShadowsocksR"), FormOption("Snell", "Snell"), FormOption("Socks5", "Socks5"), FormOption("Http", "Http"), FormOption("Vmess", "Vmess"), FormOption("Vless", "Vless"), FormOption("Trojan", "Trojan"), FormOption("Hysteria", "Hysteria"), FormOption("Hysteria2", "Hysteria2"), FormOption("WireGuard", "WireGuard"), FormOption("Tuic", "Tuic"), FormOption("Ssh", "Ssh"), FormOption("Mieru", "Mieru"), FormOption("AnyTLS", "AnyTLS"), FormOption("ShadowQuic", "ShadowQuic"), FormOption("OpenVPN", "OpenVPN"), FormOption("Tailscale", "Tailscale"), FormOption("ZeroTier", "ZeroTier"), FormOption("Sudoku", "Sudoku"), FormOption("Masque", "Masque"), FormOption("TrustTunnel", "TrustTunnel"), FormOption("GostRelay", "GostRelay")),
                desc = "按类型排除，仅对 proxies 引入生效，无视大小写",
                optional = true,
                join = "|",
            ),
            FormField(
                path = "url",
                label = "测速 URL",
                type = FormFieldType.TEXT,
                placeholder = "https://www.gstatic.com/generate_204",
                desc = "url-test/fallback/load-balance 必填",
                optional = true,
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "interval",
                label = "测速间隔(秒)",
                type = FormFieldType.NUMBER,
                placeholder = "300",
                optional = true,
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "timeout",
                label = "测速超时(ms)",
                type = FormFieldType.NUMBER,
                placeholder = "5000",
                optional = true,
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "lazy",
                label = "懒加载",
                type = FormFieldType.BOOL,
                desc = "未被选中时不测速（默认 true）",
                optional = true,
                boolAs = "pick",
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "tolerance",
                label = "容差(ms)",
                type = FormFieldType.NUMBER,
                placeholder = "50",
                desc = "仅 url-test/smart：新节点快于当前多少才切换",
                optional = true,
                only = listOf("url-test", "smart"),
            ),
            FormField(
                path = "max-failed-times",
                label = "最大失败次数",
                type = FormFieldType.NUMBER,
                placeholder = "5",
                desc = "超过则强制健康检查",
                optional = true,
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "expected-status",
                label = "期望状态码",
                type = FormFieldType.TEXT,
                placeholder = "204 或 2xx 或 200/302/400-503",
                desc = "支持 / - 组合，默认 *",
                optional = true,
                only = listOf("url-test", "fallback", "load-balance", "smart"),
            ),
            FormField(
                path = "strategy",
                label = "负载策略",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("consistent-hashing", "consistent-hashing（一致性哈希）"), FormOption("round-robin", "round-robin（轮询）"), FormOption("sticky-sessions", "sticky-sessions（粘性会话）")),
                desc = "load-balance 专属，默认 consistent-hashing",
                allowEmpty = true,
                only = listOf("load-balance"),
            ),
            FormField(
                path = "default-selected",
                label = "默认选中",
                type = FormFieldType.SELECT,
                optionsDynamic = "group-members",
                desc = "组的默认选择项；留空或填了不存在的名字则用第一个成员",
                allowEmpty = true,
                only = listOf("select"),
            ),
            FormField(
                path = "empty-fallback",
                label = "空组回退",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("DIRECT", "DIRECT（直连，数据直接出站）"), FormOption("REJECT", "REJECT（拒绝，拦截数据出站）"), FormOption("REJECT-DROP", "REJECT-DROP（拒绝，静默抛弃请求，不像 REJECT 那样回应错误）"), FormOption("PASS", "PASS（绕过，跳过当前命中的规则分支继续匹配；在 SUB-RULE 中会跳出子规则回到主规则）"), FormOption("PASS-RULE", "PASS-RULE（绕过，同 PASS，但在 SUB-RULE 中不跳出，继续在子规则内向后匹配）"), FormOption("COMPATIBLE", "COMPATIBLE（兼容，策略组筛选不出节点时出现，等效 DIRECT）")),
                desc = "组内一个可用节点都没有时走哪条内置策略，默认 COMPATIBLE",
                allowEmpty = true,
            ),
            FormField(
                path = "disable-udp",
                label = "禁用 UDP",
                type = FormFieldType.BOOL,
                desc = "该组禁用 UDP 转发",
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "interface-name",
                label = "绑定出口网卡",
                type = FormFieldType.TEXT,
                placeholder = "如 en0 / eth0",
                desc = "已废弃，建议在节点上配置；优先级 节点>组>全局",
                optional = true,
            ),
            FormField(
                path = "routing-mark",
                label = "路由标记",
                type = FormFieldType.NUMBER,
                desc = "已废弃，建议在节点上配置",
                optional = true,
            ),
            FormField(
                path = "hidden",
                label = "隐藏代理组",
                type = FormFieldType.BOOL,
                desc = "在仪表盘隐藏",
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "icon",
                label = "图标 URL",
                type = FormFieldType.TEXT,
                placeholder = "https://...",
                desc = "仪表盘显示图标",
                optional = true,
            ),
        )),
        FormSection("Smart 专属参数", listOf(
            FormField(
                path = "uselightgbm",
                label = "使用 LightGBM 预测权重",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
                only = listOf("smart"),
            ),
            FormField(
                path = "collectdata",
                label = "采样数据统计",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
                only = listOf("smart"),
            ),
            FormField(
                path = "sample-rate",
                label = "采样率 (0~1)",
                type = FormFieldType.NUMBER,
                placeholder = "默认 1",
                optional = true,
                only = listOf("smart"),
            ),
            FormField(
                path = "prefer-asn",
                label = "ASN 粒度权重",
                type = FormFieldType.BOOL,
                desc = "按目标 ASN 训练/选择",
                optional = true,
                boolAs = "pick",
                only = listOf("smart"),
            ),
            FormField(
                path = "tolerance",
                label = "切换容差(ms)",
                type = FormFieldType.NUMBER,
                desc = "延迟差小于该值不切换",
                optional = true,
                only = listOf("smart"),
            ),
            FormField(
                path = "policy-priority",
                label = "节点权重策略",
                type = FormFieldType.TEXT,
                placeholder = "如 Premium:0.9;SG:1.3",
                desc = "<1 降权 >1 加权，正则/串匹配",
                optional = true,
                only = listOf("smart"),
            ),
        )),
    )
}

/** 代理集合：proxy-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）（fields_p2.json，editSubSheet：SUB_BASIC / SUB_filter / SUB_exclude-filter / SUB_exclude-type / HC_SUB / SUB_OVERRIDE） */
val PROXY_PROVIDER_SECTIONS: List<FormSection> by lazy {
    listOf(
        FormSection("基础", listOf(
            FormField(
                path = "type",
                label = "类型",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("http", "http 远程下载"), FormOption("file", "file 本地文件"), FormOption("inline", "inline 内联节点")),
            ),
            FormField(
                path = "url",
                label = "订阅链接",
                type = FormFieldType.TEXT,
                desc = "http 类型必填",
                only = listOf("http", "inline"),
            ),
            FormField(
                path = "path",
                label = "保存路径",
                type = FormFieldType.TEXT,
                placeholder = "默认 ./proxies/ 目录",
                optional = true,
            ),
            FormCustomRow("provider-file-ops"),
            FormField(
                path = "interval",
                label = "自动更新间隔(秒)",
                type = FormFieldType.NUMBER,
                optional = true,
                only = listOf("http", "inline"),
            ),
            FormField(
                path = "size-limit",
                label = "订阅大小限制(字节)",
                type = FormFieldType.NUMBER,
                desc = "0 为不限制",
                optional = true,
                only = listOf("http", "inline"),
            ),
            FormField(
                path = "proxy",
                label = "下载出口",
                type = FormFieldType.SELECT,
                optionsDynamic = "outbound",
                desc = "下载订阅使用的出口",
                allowEmpty = true,
                only = listOf("http", "inline"),
                emptyLabel = "默认（不覆写）",
            ),
            FormField(
                path = "age-secret-key",
                label = "AGE 解密密钥",
                type = FormFieldType.TEXT,
                desc = "加密订阅内容自动解密",
                tag = "新版",
                optional = true,
                only = listOf("http", "inline"),
            ),
            FormCustomRow("provider-payload"),
        )),
        FormSection("请求 / 过滤", listOf(
            FormField(
                path = "header",
                label = "请求头 header",
                type = FormFieldType.HEADERS,
                desc = "如 UA / Authorization，每行一条",
                optional = true,
                only = listOf("http", "inline"),
                arrayValues = true,
            ),
            FormField(
                path = "filter",
                label = "筛选节点 filter",
                type = FormFieldType.TEXT,
                placeholder = "(?i)港|hk|hongkong",
                desc = "筛选满足关键词或正则表达式的节点",
                optional = true,
            ),
            FormField(
                path = "exclude-filter",
                label = "排除节点 exclude-filter",
                type = FormFieldType.TEXT,
                desc = "排除匹配的节点",
                optional = true,
            ),
            FormField(
                path = "exclude-type",
                label = "排除协议类型",
                type = FormFieldType.PICKLIST,
                options = listOf(FormOption("Shadowsocks", "Shadowsocks"), FormOption("ShadowsocksR", "ShadowsocksR"), FormOption("Snell", "Snell"), FormOption("Socks5", "Socks5"), FormOption("Http", "Http"), FormOption("Vmess", "Vmess"), FormOption("Vless", "Vless"), FormOption("Trojan", "Trojan"), FormOption("Hysteria", "Hysteria"), FormOption("Hysteria2", "Hysteria2"), FormOption("WireGuard", "WireGuard"), FormOption("Tuic", "Tuic"), FormOption("Ssh", "Ssh"), FormOption("Mieru", "Mieru"), FormOption("AnyTLS", "AnyTLS"), FormOption("ShadowQuic", "ShadowQuic"), FormOption("OpenVPN", "OpenVPN"), FormOption("Tailscale", "Tailscale"), FormOption("ZeroTier", "ZeroTier"), FormOption("Sudoku", "Sudoku"), FormOption("Masque", "Masque"), FormOption("TrustTunnel", "TrustTunnel"), FormOption("GostRelay", "GostRelay")),
                desc = "按类型排除节点；无视大小写",
                optional = true,
                join = "|",
            ),
        )),
        FormSection("健康检查", listOf(
            FormCustomRow("provider-health-enable"),
            FormField(
                path = "health-check.url",
                label = "测速 URL",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "health-check.interval",
                label = "测速间隔(秒)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "health-check.timeout",
                label = "测速超时(ms)",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "health-check.lazy",
                label = "懒加载",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "health-check.expected-status",
                label = "期望状态码",
                type = FormFieldType.TEXT,
                placeholder = "204 或 2xx",
                optional = true,
            ),
        )),
        FormSection("覆写 override（对该订阅全部节点生效）", listOf(
            FormField(
                path = "override.additional-prefix",
                label = "节点名前缀",
                type = FormFieldType.TEXT,
                placeholder = "[机场名] ",
                optional = true,
            ),
            FormField(
                path = "override.additional-suffix",
                label = "节点名后缀",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "override.tfo",
                label = "TFO (TCP Fast Open)",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "override.mptcp",
                label = "MPTCP",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "override.udp",
                label = "UDP",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "override.udp-over-tcp",
                label = "UDP over TCP (UoT)",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "override.up",
                label = "上行带宽",
                type = FormFieldType.TEXT,
                placeholder = "10 Mbps",
                optional = true,
            ),
            FormField(
                path = "override.down",
                label = "下行带宽",
                type = FormFieldType.TEXT,
                placeholder = "50 Mbps",
                optional = true,
            ),
            FormField(
                path = "override.skip-cert-verify",
                label = "跳过证书校验",
                type = FormFieldType.BOOL,
                optional = true,
                boolAs = "pick",
            ),
            FormField(
                path = "override.name-cert-verify",
                label = "证书 DNSName 校验目标",
                type = FormFieldType.TEXT,
                desc = "不改 SNI，只改校验对象",
                optional = true,
            ),
            FormField(
                path = "override.dialer-proxy",
                label = "链式出口 dialer-proxy",
                type = FormFieldType.SELECT,
                optionsDynamic = "outbound",
                desc = "订阅内全部节点经此出口建立连接",
                allowEmpty = true,
                emptyLabel = "默认（不覆写）",
            ),
            FormField(
                path = "override.interface-name",
                label = "绑定出口网卡",
                type = FormFieldType.TEXT,
                optional = true,
            ),
            FormField(
                path = "override.routing-mark",
                label = "路由标记 routing-mark",
                type = FormFieldType.NUMBER,
                optional = true,
            ),
            FormField(
                path = "override.ip-version",
                label = "IP 版本偏好",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("dual", "dual"), FormOption("ipv4", "ipv4"), FormOption("ipv6", "ipv6"), FormOption("ipv4-prefer", "ipv4-prefer"), FormOption("ipv6-prefer", "ipv6-prefer")),
                allowEmpty = true,
            ),
            FormField(
                path = "override.proxy-name",
                label = "proxy-name 批量重命名",
                type = FormFieldType.MAPLIST,
                desc = "正则 pattern→target，支持 \$1 引用",
                optional = true,
            ),
        )),
        FormSection("override-expr（按表达式批量修改节点 · 新版）", listOf(
            FormCustomRow("override-expr"),
        )),
    )
}

/** 规则集合：rule-providers（映射，按名字编辑；only = http / inline 的字段在 file 类型隐藏）（fields_p2.json，editEpSheet：EP_FIELDS） */
val RULE_PROVIDER_SECTIONS: List<FormSection> by lazy {
    listOf(
        FormSection("基础", listOf(
            FormField(
                path = "type",
                label = "类型",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("http", "http"), FormOption("file", "file"), FormOption("inline", "inline")),
            ),
            FormField(
                path = "behavior",
                label = "规则类型",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("domain", "domain 域名"), FormOption("ipcidr", "ipcidr IP段"), FormOption("classical", "classical 经典")),
            ),
            FormField(
                path = "format",
                label = "格式",
                type = FormFieldType.SELECT,
                options = listOf(FormOption("yaml", "yaml"), FormOption("text", "text"), FormOption("mrs", "mrs(二进制)")),
                allowEmpty = true,
            ),
            FormField(
                path = "url",
                label = "下载链接",
                type = FormFieldType.TEXT,
                optional = true,
                only = listOf("http", "inline"),
            ),
            FormField(
                path = "path",
                label = "本地路径",
                type = FormFieldType.TEXT,
                placeholder = "默认 ./rules/ 目录",
                optional = true,
            ),
            FormCustomRow("rule-provider-file-ops"),
            FormField(
                path = "interval",
                label = "更新间隔(秒)",
                type = FormFieldType.NUMBER,
                optional = true,
                only = listOf("http", "inline"),
            ),
            FormField(
                path = "proxy",
                label = "下载出口",
                type = FormFieldType.SELECT,
                optionsDynamic = "outbound",
                desc = "下载规则集使用的出口",
                allowEmpty = true,
                only = listOf("http", "inline"),
                emptyLabel = "默认（不覆写）",
            ),
            FormCustomRow("rule-provider-payload"),
        )),
    )
}

/** 流量隧道：tunnels（序列；单行写法 `tcp/udp,地址,目标[,策略]` 整行改，映射写法按字段表）（fields_p2.json，renderTunnels：TUNNEL_FIELDS） */
val TUNNEL_SECTIONS: List<FormSection> by lazy {
    listOf(
        FormSection("隧道", listOf(
            FormField(
                path = "network",
                label = "协议 network",
                type = FormFieldType.PICKLIST,
                options = listOf(FormOption("tcp", "tcp"), FormOption("udp", "udp")),
                desc = "映射写法里是列表（内核 Tunnel.network []string）",
            ),
            FormField(
                path = "address",
                label = "监听地址 address",
                type = FormFieldType.TEXT,
                placeholder = "如 0.0.0.0:8080",
            ),
            FormField(
                path = "target",
                label = "目标地址 target",
                type = FormFieldType.TEXT,
                placeholder = "如 example.com:80",
            ),
            FormField(
                path = "proxy",
                label = "出口代理 proxy（留空直连）",
                type = FormFieldType.SELECT,
                optionsDynamic = "policies",
                allowEmpty = true,
            ),
        )),
    )
}

/** 规则类型（参考实现 pages-flow.js:1732 的 RULE_TYPES；提示语 参考实现 pages-flow.js:1906 的 hintFor）。 */
val RULE_TYPES: List<RuleTypeSpec> by lazy {
    listOf(
        RuleTypeSpec("DOMAIN", "DOMAIN", placeholder = "完整域名，如 www.google.com"),
        RuleTypeSpec("DOMAIN-SUFFIX", "DOMAIN-SUFFIX", placeholder = "域名后缀，如 google.com / .google.com"),
        RuleTypeSpec("DOMAIN-KEYWORD", "DOMAIN-KEYWORD", placeholder = "域名关键词，如 google"),
        RuleTypeSpec("DOMAIN-REGEX", "DOMAIN-REGEX", placeholder = "正则，如 ^.*google.*"),
        RuleTypeSpec("DOMAIN-WILDCARD", "DOMAIN-WILDCARD", placeholder = "通配，如 *.google.com"),
        RuleTypeSpec("GEOSITE", "GEOSITE", placeholder = "geosite 类别，如 cn / google / category-ads-all"),
        RuleTypeSpec("GEOIP", "GEOIP", placeholder = "国家码，如 CN / 私有地址 private", ipRule = true),
        RuleTypeSpec("IP-CIDR", "IP-CIDR", placeholder = "IPv4 段，如 91.108.4.0/22", ipRule = true),
        RuleTypeSpec("IP-CIDR6", "IP-CIDR6", placeholder = "IPv6 段，如 2001:db8::/32", ipRule = true),
        RuleTypeSpec("IP-SUFFIX", "IP-SUFFIX", placeholder = "IP 后缀，如 8.8.8.8/24", ipRule = true),
        RuleTypeSpec("IP-ASN", "IP-ASN", placeholder = "ASN 号，如 13335", ipRule = true),
        RuleTypeSpec("SRC-GEOIP", "SRC-GEOIP", placeholder = "源 GeoIP，如 CN", ipRule = true),
        RuleTypeSpec("SRC-IP-ASN", "SRC-IP-ASN", placeholder = "源 ASN，如 9808", ipRule = true),
        RuleTypeSpec("SRC-IP-CIDR", "SRC-IP-CIDR", placeholder = "源 IP 段，如 192.168.1.0/24", ipRule = true),
        RuleTypeSpec("SRC-IP-SUFFIX", "SRC-IP-SUFFIX", placeholder = "源 IP 后缀", ipRule = true),
        RuleTypeSpec("DST-PORT", "DST-PORT", placeholder = "目标端口，如 443 / 80-443"),
        RuleTypeSpec("SRC-PORT", "SRC-PORT", placeholder = "源端口"),
        RuleTypeSpec("IN-PORT", "IN-PORT", placeholder = "入站端口，如 7890"),
        RuleTypeSpec("IN-TYPE", "IN-TYPE", placeholder = "入站类型，如 SOCKS / HTTP"),
        RuleTypeSpec("IN-USER", "IN-USER", placeholder = "入站用户，如 mihomo"),
        RuleTypeSpec("IN-NAME", "IN-NAME", placeholder = "入站名称，如 ss"),
        RuleTypeSpec("REMATCH-NAME", "REMATCH-NAME", placeholder = "Rematch 名称"),
        RuleTypeSpec("PROCESS-NAME", "PROCESS-NAME", placeholder = "进程名，如 chrome.exe"),
        RuleTypeSpec("PROCESS-NAME-REGEX", "PROCESS-NAME-REGEX", placeholder = "进程名正则"),
        RuleTypeSpec("PROCESS-NAME-WILDCARD", "PROCESS-NAME-WILDCARD", placeholder = "进程名通配，如 *telegram*"),
        RuleTypeSpec("PROCESS-PATH", "PROCESS-PATH", placeholder = "进程路径，如 /usr/bin/curl"),
        RuleTypeSpec("PROCESS-PATH-REGEX", "PROCESS-PATH-REGEX", placeholder = "路径正则"),
        RuleTypeSpec("PROCESS-PATH-WILDCARD", "PROCESS-PATH-WILDCARD", placeholder = "路径通配"),
        RuleTypeSpec("UID", "UID", placeholder = "用户 ID"),
        RuleTypeSpec("NETWORK", "NETWORK", placeholder = "TCP / UDP"),
        RuleTypeSpec("DSCP", "DSCP", placeholder = "DSCP 值，如 4"),
        RuleTypeSpec("RULE-SET", "RULE-SET", placeholder = "规则集名称（需在规则集页面创建）", ipRule = true, targetKind = "rule-providers"),
        RuleTypeSpec("AND", "AND", placeholder = "逻辑与，如 ((DOMAIN,example.com),(NETWORK,UDP))", multi = true),
        RuleTypeSpec("OR", "OR", placeholder = "逻辑或", multi = true),
        RuleTypeSpec("NOT", "NOT", placeholder = "逻辑非，如 ((DOMAIN,example.com))", multi = true),
        RuleTypeSpec("SUB-RULE", "SUB-RULE", placeholder = "子规则名称", multi = true, targetKind = "sub-rules"),
        RuleTypeSpec("MATCH", "MATCH", placeholder = "兜底规则，无需匹配值", noPayload = true),
    )
}
