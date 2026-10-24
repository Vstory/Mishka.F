package top.yukonga.mishka.custom.forms

/** Effective role state for an eBPF listener, scoped to one listeners[index] path. */
internal fun ebpfRoleState(doc: YamlDoc, base: YPath): Pair<Boolean, Boolean> {
    fun flag(path: YPath): Boolean? {
        val node = doc.get(path) ?: return null
        if (doc.isNullText(node)) return null
        val raw = FormValues.scalarText(doc, node)?.trim()?.lowercase() ?: return null
        return when (raw) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            "", "null", "~" -> null
            else -> null
        }
    }

    val localFlag = flag(base + listOf("local", "enable"))
        ?: flag(base + listOf("local", "enabled"))
    val sharedFlag = flag(base + listOf("shared", "enable"))
        ?: flag(base + listOf("shared", "enabled"))
    if (localFlag != null || sharedFlag != null) {
        return (localFlag == true) to (sharedFlag == true)
    }
    return when (FormValues.readRaw(doc, base + "mode")?.lowercase().orEmpty()) {
        "shared" -> false to true
        "hybrid" -> true to true
        "local", "" -> true to false // eBPF's default mode is local.
        else -> false to false
    }
}

/** Does shared have at least one concrete downstream interface configured? */
internal fun ebpfHasSharedInterface(doc: YamlDoc, base: YPath): Boolean {
    val path = base + listOf("shared", "interface")
    val node = doc.get(path)
    return if (node?.kind == YamlNode.Kind.SEQ) {
        FormValues.readList(doc, path).any { it.isNotBlank() }
    } else {
        FormValues.readRaw(doc, path)?.split(',')?.any { it.isNotBlank() } == true
    }
}

/** Does the listener have a live TC hook that can answer FakeIP ICMP Echo? */
internal fun ebpfHasFakeIpIcmpHook(doc: YamlDoc, base: YPath, localOn: Boolean, sharedOn: Boolean): Boolean {
    val localTc = localOn && FormValues.readRaw(doc, base + listOf("local", "data-plane")) == "tc"
    return localTc || (sharedOn && ebpfHasSharedInterface(doc, base))
}

/** Replace legacy mode/enable fields with explicit role flags, preserving other listener settings. */
internal fun ebpfRoleUpdateOps(base: YPath, localOn: Boolean, sharedOn: Boolean): List<BatchOp> = listOf(
    BatchOp.Remove(base + "mode"),
    BatchOp.Remove(base + listOf("local", "enabled")),
    BatchOp.Remove(base + listOf("shared", "enabled")),
    BatchOp.Set(base + listOf("local", "enable"), localOn),
    BatchOp.Set(base + listOf("shared", "enable"), sharedOn),
)
