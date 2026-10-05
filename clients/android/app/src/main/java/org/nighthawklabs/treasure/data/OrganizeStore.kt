package org.nighthawklabs.treasure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.problem
import java.util.UUID

data class OrganizeState(
    val items: List<Dimension> = emptyList(),
    val loading: Boolean = true,
    val problem: String? = null,
    /** A delete the engine refused because spends still use the record: the user must choose a replacement. */
    val needsReplacement: Dimension? = null,
    val showDeleted: Boolean = false,
)

class OrganizeStore(
    private val kind: DimensionKind,
    private val engine: EngineApi,
    private val afterChange: suspend () -> Unit,
) {
    private val _state = MutableStateFlow(OrganizeState())
    val state: StateFlow<OrganizeState> = _state.asStateFlow()
    private fun key() = UUID.randomUUID().toString()

    suspend fun load(showDeleted: Boolean = _state.value.showDeleted) {
        _state.update { it.copy(showDeleted = showDeleted) }
        val all = mutableListOf<Dimension>()
        var offset = 0
        while (true) {
            val r = engine.call<PageInput, Page<Dimension>>("${kind.plural}_list", PageInput(200, offset, if (showDeleted) "deleted" else null))
            if (r !is Api.Ok) { _state.update { it.copy(problem = r.problem, loading = false) }; return }
            all += r.value.items
            offset = r.value.nextOffset ?: break
        }
        _state.update { it.copy(items = all, problem = null, loading = false) }
    }

    suspend fun create(name: String) =
        finish(engine.call<CreateDimensionInput, Dimension>("${kind.plural}_create", CreateDimensionInput(key(), name)).problem)

    suspend fun rename(d: Dimension, name: String) = finish(
        engine.call<UpdateDimensionInput, Dimension>("${kind.plural}_update", UpdateDimensionInput(key(), d.id, d.version ?: 0, name, d.aliases)).problem,
    )

    /** Replace a merchant's alternative names (what the importer and search also match on). */
    suspend fun setAliases(d: Dimension, aliases: List<String>) = finish(
        engine.call<UpdateDimensionInput, Dimension>("${kind.plural}_update", UpdateDimensionInput(key(), d.id, d.version ?: 0, d.name, aliases)).problem,
    )

    /** Bring back a deleted record. The engine refuses if its name (or an alias) has since been taken. */
    suspend fun restore(d: Dimension) = finish(
        engine.call<LifecycleInput, Dimension>("${kind.plural}_restore", LifecycleInput(key(), d.id, d.version ?: 0)).problem,
    )

    suspend fun history(d: Dimension): Api<Page<Change>> = engine.call("history_list", HistoryInput(kind.singular, d.id))

    suspend fun merge(source: Dimension, target: Dimension) = finish(
        engine.call<MergeDimensionInput, MergeResult>("${kind.plural}_merge", MergeDimensionInput(key(), source.id, source.version ?: 0, target.id, target.version ?: 0)).problem,
    )

    /** Plain delete. If spends still use it the engine answers `conflict`, and we ask for a replacement instead. */
    suspend fun delete(d: Dimension, replacement: Dimension? = null) {
        val r = engine.call<DeleteDimensionInput, Dimension>(
            "${kind.plural}_delete",
            DeleteDimensionInput(key(), d.id, d.version ?: 0, replacement?.id, replacement?.version),
        )
        if (r is Api.Failed && r.code == "conflict" && replacement == null && r.message.contains("replacement")) {
            _state.update { it.copy(needsReplacement = d) }
            return
        }
        finish(r.problem)
    }

    fun dismissReplacement() = _state.update { it.copy(needsReplacement = null) }
    fun clearProblem() = _state.update { it.copy(problem = null) }

    private suspend fun finish(failure: String?) {
        if (failure != null) { load(); _state.update { it.copy(problem = failure) }; return }
        load()
        afterChange() // names and references changed: refresh the directory and spends
    }
}
