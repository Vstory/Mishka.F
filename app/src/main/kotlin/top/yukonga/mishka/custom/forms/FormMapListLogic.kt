package top.yukonga.mishka.custom.forms

/**
 * Build child-level YAML operations for a map-list edit. Existing nodes are staged
 * under temporary keys before final renames, so cycles (A↔B) do not overwrite a
 * value or lose the source key. Unchanged rows produce no operations.
 *
 * Returns null for duplicate keys or unsupported source rows; callers can show a
 * user-facing explanation without ever attempting a partial write.
 */
internal fun buildMapListEditOps(
    path: YPath,
    oldRows: List<FormMapListRow>,
    edits: List<FormMapListEdit>,
): List<BatchOp>? {
    if (edits.any { it.source?.supported == false }) return null
    if (edits.map { it.key }.toSet().size != edits.size) return null
    if (edits.isEmpty()) return emptyList()

    val oldKeys = oldRows.map { it.key }.toSet()
    val newKeys = edits.map { it.key }.toSet()
    val retainedSources = edits.mapNotNull { it.source?.key }.toSet()
    val renames = edits.filter { edit -> edit.source != null && edit.source.key != edit.key }
    val ops = ArrayList<BatchOp>()

    // Delete source rows explicitly removed from the dialog, even if another row
    // is being renamed to the same final key. Retained rename sources survive until
    // the staging phase below.
    oldKeys.filter { it !in retainedSources }
        .forEach { ops += BatchOp.Remove(path + it) }

    val tempNames = renames.mapIndexed { index, _ ->
        var suffix = index
        var candidate: String
        do {
            candidate = "__mishka_p3_tmp_$suffix"
            suffix++
        } while (candidate in oldKeys || candidate in newKeys)
        candidate
    }
    renames.forEachIndexed { index, edit ->
        ops += BatchOp.Rename(path + edit.source!!.key, tempNames[index])
    }
    renames.forEachIndexed { index, _ ->
        ops += BatchOp.Rename(path + tempNames[index], renames[index].key)
    }

    for (edit in edits) {
        val source = edit.source
        val unchanged = source != null && source.key == edit.key &&
            source.values == edit.values && source.isSequence == edit.isSequence
        if (unchanged) continue
        val value: Any? = when {
            edit.values.size == 1 && !edit.isSequence -> edit.values.single()
            else -> edit.values
        }
        ops += BatchOp.Set(path + edit.key, value)
    }
    return ops
}
