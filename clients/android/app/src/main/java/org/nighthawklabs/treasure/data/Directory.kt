package org.nighthawklabs.treasure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.map
import java.util.UUID

/** Merchant and category names by id, so spends can show names rather than UUIDs. */
class Directory(private val engine: EngineApi, private val cache: DiskCache) {
    private val _merchants = MutableStateFlow((cache.load<List<Dimension>>("merchants") ?: emptyList()).associateBy { it.id })
    private val _categories = MutableStateFlow((cache.load<List<Dimension>>("categories") ?: emptyList()).associateBy { it.id })
    val merchants: StateFlow<Map<String, Dimension>> = _merchants.asStateFlow()
    val categories: StateFlow<Map<String, Dimension>> = _categories.asStateFlow()
    private val _tags = MutableStateFlow((cache.load<List<Dimension>>("tags") ?: emptyList()).associateBy { it.id })
    val tags: StateFlow<Map<String, Dimension>> = _tags.asStateFlow()

    fun merchant(id: String?): String? = id?.let { _merchants.value[it]?.name }
    fun category(id: String?): String? = id?.let { _categories.value[it]?.name }
    fun tag(id: String?): String? = id?.let { _tags.value[it]?.name }

    /** Names for a spend's tag ids, in the order stored, skipping any no longer known. */
    fun tagNames(ids: List<String>?): List<String> = ids.orEmpty().mapNotNull { _tags.value[it]?.name }

    /** The id for a typed merchant name: an existing merchant (by name or alias, ignoring case) or a newly created one. */
    suspend fun resolveMerchant(raw: String): Api<String?> {
        val name = raw.trim()
        if (name.isEmpty()) return Api.Ok(null)
        val key = name.lowercase()
        existingMerchant(key)?.let { return Api.Ok(it.id) }
        val r = engine.call<CreateDimensionInput, Dimension>("merchants_create", CreateDimensionInput(UUID.randomUUID().toString(), name))
        // The name is already taken: another device made it, or an earlier attempt succeeded and its reply was lost. Either
        // way the merchant exists, so reload the list and use it rather than failing (and failing a queued spend with it).
        if (r is Api.Failed && (r.code == "duplicate" || (r.code == "conflict" && r.message.contains("already in use")))) {
            refreshMerchants()
            existingMerchant(key)?.let { return Api.Ok(it.id) }
        }
        if (r is Api.Ok) {
            _merchants.value = _merchants.value + (r.value.id to r.value)
            cache.save(_merchants.value.values.toList(), "merchants")
        }
        return r.map { it.id }
    }

    /** A tag by name (ignoring case), created when it doesn't exist yet. Same lost-reply handling as merchants. */
    suspend fun resolveTag(raw: String): Api<Dimension> {
        val name = raw.trim()
        val key = name.lowercase()
        _tags.value.values.firstOrNull { it.name.lowercase() == key }?.let { return Api.Ok(it) }
        val r = engine.call<CreateDimensionInput, Dimension>("tags_create", CreateDimensionInput(UUID.randomUUID().toString(), name))
        if (r is Api.Failed && r.code == "duplicate") {
            refreshTags()
            _tags.value.values.firstOrNull { it.name.lowercase() == key }?.let { return Api.Ok(it) }
        }
        if (r is Api.Ok) { _tags.value = _tags.value + (r.value.id to r.value); cache.save(_tags.value.values.toList(), "tags") }
        return r
    }

    /** Tags ordered by how often the loaded spends use them, then by name. */
    fun tagsByUse(spends: List<Spend>): List<Dimension> {
        val uses = HashMap<String, Int>()
        for (s in spends) for (t in s.tagIds.orEmpty()) uses[t] = (uses[t] ?: 0) + 1
        return _tags.value.values.sortedWith(compareByDescending<Dimension> { uses[it.id] ?: 0 }.thenBy { it.name.lowercase() })
    }

    private suspend fun refreshTags() {
        all("tags_list")?.let { _tags.value = it.associateBy { d -> d.id }; cache.save(it, "tags") }
    }

    private fun existingMerchant(lowercasedName: String): Dimension? =
        _merchants.value.values.firstOrNull { m -> m.name.lowercase() == lowercasedName || m.aliases.orEmpty().any { it.lowercase() == lowercasedName } }

    private suspend fun refreshMerchants() {
        all("merchants_list")?.let { _merchants.value = it.associateBy { d -> d.id }; cache.save(it, "merchants") }
    }

    /** Categories ordered by how often the loaded spends use them, then by name. */
    fun categoriesByUse(spends: List<Spend>): List<Dimension> {
        val uses = HashMap<String, Int>()
        for (s in spends) for (a in s.allocations.orEmpty()) a.categoryId?.let { uses[it] = (uses[it] ?: 0) + 1 }
        return _categories.value.values.sortedWith(compareByDescending<Dimension> { uses[it.id] ?: 0 }.thenBy { it.name.lowercase() })
    }

    suspend fun refresh() {
        all("merchants_list")?.let { _merchants.value = it.associateBy { d -> d.id }; cache.save(it, "merchants") }
        all("categories_list")?.let { _categories.value = it.associateBy { d -> d.id }; cache.save(it, "categories") }
        refreshTags()
    }

    private suspend fun all(op: String): List<Dimension>? {
        val items = mutableListOf<Dimension>()
        var offset = 0
        while (true) {
            val r = engine.call<PageInput, Page<Dimension>>(op, PageInput(200, offset))
            if (r !is Api.Ok) return null
            items += r.value.items
            offset = r.value.nextOffset ?: return items
        }
    }
}
