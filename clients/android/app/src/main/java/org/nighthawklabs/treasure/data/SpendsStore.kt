package org.nighthawklabs.treasure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.failure
import org.nighthawklabs.treasure.net.problem
import java.util.UUID

data class SpendsState(
    val spends: List<Spend> = emptyList(),
    val loading: Boolean = false,
    val problem: String? = null,
    val hasMore: Boolean = false,
    /** Everything the list is narrowed by. Changing it is followed by `reload()`. */
    val filter: SpendFilter = SpendFilter(),
    /** The last delete (one spend or many), offered for undo for a few seconds. */
    val undo: Undo? = null,
    /** Bumps on every local change so dependent screens (totals, charts) know to refresh. */
    val revision: Int = 0,
) {
    data class Undo(val id: String, val spends: List<Spend>)

    val search: String get() = filter.search
}

class SpendsStore(
    private val engine: EngineApi,
    private val cache: DiskCache,
    val outbox: Outbox,
    private val resolveMerchant: suspend (String) -> Api<String?>,
) {
    private val _state = MutableStateFlow(SpendsState(spends = cache.load<List<Spend>>("spends") ?: emptyList()))
    val state: StateFlow<SpendsState> = _state.asStateFlow()
    private var nextOffset: Int? = null
    private var generation = 0 // drops responses from a superseded query

    fun setSearch(q: String) = _state.update { it.copy(filter = it.filter.copy(search = q)) }
    fun setFilter(f: SpendFilter) = _state.update { it.copy(filter = f) }

    /** Reload from the start (pull to refresh, search change). Keeps the cached list visible until the answer lands. */
    suspend fun reload() {
        val mine = ++generation
        _state.update { it.copy(loading = true) }
        val asked = _state.value.filter
        val r = engine.call<SearchInput, Page<Spend>>("spends_search", asked.input())
        if (mine != generation) return
        if (r is Api.Ok) {
            nextOffset = r.value.nextOffset
            if (asked.isDefault) cache.save(r.value.items, "spends")
            _state.update { it.copy(spends = r.value.items, hasMore = r.value.nextOffset != null, problem = null, loading = false) }
        } else {
            _state.update { it.copy(problem = r.problem, loading = false, spends = if (!asked.isDefault) emptyList() else it.spends) }
        }
    }

    suspend fun loadMore(after: Spend) {
        val s = _state.value
        val offset = nextOffset
        if (!s.hasMore || s.loading || after.id != s.spends.lastOrNull()?.id || offset == null) return
        val mine = ++generation
        _state.update { it.copy(loading = true) }
        val r = engine.call<SearchInput, Page<Spend>>("spends_search", _state.value.filter.input(offset = offset))
        if (mine != generation) return
        if (r is Api.Ok) {
            nextOffset = r.value.nextOffset
            _state.update { it.copy(spends = it.spends + r.value.items, hasMore = r.value.nextOffset != null, loading = false) }
        } else {
            _state.update { it.copy(problem = r.problem, loading = false) }
        }
    }

    suspend fun search(filter: SpendFilter, limit: Int = 30): Api<List<Spend>> =
        engine.call<SearchInput, Page<Spend>>("spends_search", filter.input(limit = limit)).let { r -> if (r is Api.Ok) Api.Ok(r.value.items) else r as Api<List<Spend>> }

    /** Attach or detach one document. The spend gets a new version, which replaces the list copy. */
    suspend fun link(spend: Spend, evidenceId: String, attach: Boolean): Api<Spend> =
        engine.call<EvidenceLinkInput, Spend>(if (attach) "evidence_attach" else "evidence_detach", EvidenceLinkInput(UUID.randomUUID().toString(), spend.id, spend.version, evidenceId))
            .also { if (it is Api.Ok) stored(it.value) }

    /** A spend by id: from the loaded list when present, else from the engine. */
    suspend fun lookup(id: String): Spend? =
        _state.value.spends.firstOrNull { it.id == id } ?: (engine.call<GetInput, Spend>("spends_get", GetInput(id)) as? Api.Ok)?.value

    suspend fun history(id: String): Api<Page<Change>> = engine.call("history_list", HistoryInput("spend", id))

    // region Offline

    fun enqueue(spend: SpendInput, merchantName: String?, key: String) =
        outbox.add(OutboxItem(key, spend, merchantName, System.currentTimeMillis()))

    /** Sends spends saved while offline. Safe to call at any time: it does nothing when the queue is empty or already running. */
    suspend fun flushOutbox() {
        if (outbox.items.value.isEmpty()) return
        val created = outbox.flush { item ->
            var input = item.spend
            if (item.merchantName != null) {
                val m = resolveMerchant(item.merchantName)
                m.failure<Spend>()?.let { return@flush it }
                input = input.copy(merchantId = (m as Api.Ok).value)
            }
            engine.call<CreateSpendInput, Spend>("spends_create", CreateSpendInput(item.id, input))
        }
        created.forEach(::stored)
    }

    // endregion

    // region Writes

    suspend fun create(input: SpendInput, key: String): Api<Spend> =
        engine.call<CreateSpendInput, Spend>("spends_create", CreateSpendInput(key, input)).also { if (it is Api.Ok) stored(it.value) }

    suspend fun update(spend: Spend, input: SpendInput, key: String): Api<Spend> =
        engine.call<UpdateSpendInput, Spend>("spends_update", UpdateSpendInput(key, spend.id, spend.version, input))
            .also { if (it is Api.Ok) stored(it.value) }

    /** The newest server copy, for resolving a version conflict. */
    suspend fun fetch(id: String): Spend? =
        (engine.call<GetInput, Spend>("spends_get", GetInput(id)) as? Api.Ok)?.value?.also(::stored)

    /** Soft delete: the row leaves the list at once and comes back if the server refuses. */
    suspend fun delete(spend: Spend) {
        val before = _state.value.spends
        _state.update { s -> s.copy(spends = s.spends.filter { it.id != spend.id }) }
        val r = engine.call<LifecycleInput, Spend>("spends_delete", LifecycleInput(UUID.randomUUID().toString(), spend.id, spend.version))
        if (r is Api.Ok) {
            _state.update { it.copy(undo = SpendsState.Undo(UUID.randomUUID().toString(), listOf(r.value)), revision = it.revision + 1) }
            persist()
        } else _state.update { it.copy(spends = before, problem = r.problem) }
    }

    suspend fun undoDelete() {
        val u = _state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        val outcome = bulkLifecycle("spends_bulk_restore", u.spends)
        _state.update { it.copy(problem = outcome.failure) }
        if (outcome.failure == null) { outcome.done.forEach(::merge); bump(); persist() }
    }

    // region Bulk

    /**
     * What a bulk call got done. Work is sent in batches of 100 (the engine's limit, each batch all-or-nothing), so a failure
     * partway leaves the earlier batches applied: [done] says which.
     */
    data class BulkOutcome(val done: List<Spend>, val total: Int, val failure: String? = null) { val ok get() = failure == null }

    /** Apply one change to many spends: categorize, retag, set the merchant, and so on. */
    suspend fun bulkUpdate(targets: List<Spend>, patch: SpendPatch): BulkOutcome {
        val done = mutableListOf<Spend>()
        for (chunk in targets.chunked(100)) {
            val r = engine.call<BulkUpdateInput, SpendsResult>("spends_bulk_update", BulkUpdateInput(UUID.randomUUID().toString(), chunk.map { Versioned(it.id, it.version) }, patch))
            if (r !is Api.Ok) return finish(done, targets.size, r)
            done += r.value.items
        }
        done.forEach(::merge); bump(); persist()
        return BulkOutcome(done, targets.size)
    }

    /** Soft-delete many. Refunds go before their original expenses; the engine orders that. Offers Undo. */
    suspend fun bulkDelete(targets: List<Spend>): BulkOutcome {
        val outcome = bulkLifecycle("spends_bulk_delete", targets)
        val gone = outcome.done.map { it.id }.toSet()
        if (gone.isNotEmpty()) {
            _state.update { s -> s.copy(spends = s.spends.filter { it.id !in gone }, undo = SpendsState.Undo(UUID.randomUUID().toString(), outcome.done)) }
            bump(); persist()
        }
        return outcome
    }

    /** Bring deleted spends back (from Trash). */
    suspend fun bulkRestore(targets: List<Spend>): BulkOutcome {
        val outcome = bulkLifecycle("spends_bulk_restore", targets)
        val back = outcome.done.map { it.id }.toSet()
        if (back.isNotEmpty()) {
            if (_state.value.filter.state == "deleted") _state.update { s -> s.copy(spends = s.spends.filter { it.id !in back }) } else outcome.done.forEach(::merge)
            bump(); persist()
        }
        return outcome
    }

    private suspend fun bulkLifecycle(op: String, targets: List<Spend>): BulkOutcome {
        val done = mutableListOf<Spend>()
        for (chunk in targets.chunked(100)) {
            val r = engine.call<BulkLifecycleInput, SpendsResult>(op, BulkLifecycleInput(UUID.randomUUID().toString(), chunk.map { Versioned(it.id, it.version) }))
            if (r !is Api.Ok) return finish(done, targets.size, r)
            done += r.value.items
        }
        return BulkOutcome(done, targets.size)
    }

    private fun finish(done: List<Spend>, total: Int, failed: Api<*>): BulkOutcome {
        var why = failed.problem ?: "Something went wrong."
        if (failed is Api.Failed && failed.code == "conflict") why = "Some of these changed elsewhere. Refresh the list and try again."
        if (done.isNotEmpty()) why = "Changed ${done.size} of $total before a problem. $why"
        done.forEach(::merge)
        if (done.isNotEmpty()) { bump(); persist() }
        return BulkOutcome(done, total, why)
    }

    // endregion

    fun dismissUndo(id: String) = _state.update { if (it.undo?.id == id) it.copy(undo = null) else it }

    private fun stored(s: Spend) { merge(s); bump(); persist() }

    /** Swaps in the newest copy of a spend without announcing a change (callers [bump] once for a batch). */
    private fun merge(s: Spend) { _state.update { it.copy(spends = SpendOrder.upsert(it.spends, s)) } }
    private fun bump() { _state.update { it.copy(revision = it.revision + 1) } }

    private fun persist() { if (_state.value.filter.isDefault) cache.save(_state.value.spends, "spends") }

    // endregion
}
