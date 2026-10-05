package org.nighthawklabs.treasure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.problem
import java.util.UUID

/** A reference to a supporting document (receipt, statement). The engine stores the reference and never fetches the file. */
@Serializable
data class Evidence(
    val id: String,
    val title: String,
    @SerialName("source_ref") val sourceRef: String,
    @SerialName("media_type") val mediaType: String? = null,
    val checksum: String? = null,
    val notes: String? = null,
    val version: Long,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** The editable fields. Updates replace all of them, so the ones the editor doesn't show (media type, checksum) are sent back. */
data class EvidenceFields(val title: String, val sourceRef: String, val mediaType: String? = null, val checksum: String? = null, val notes: String? = null)

@Serializable
data class NewEvidenceInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val title: String,
    @SerialName("source_ref") val sourceRef: String,
    @SerialName("media_type") val mediaType: String? = null,
    val checksum: String? = null,
    val notes: String? = null,
)

@Serializable
data class UpdateEvidenceInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    val title: String,
    @SerialName("source_ref") val sourceRef: String,
    @SerialName("media_type") val mediaType: String? = null,
    val checksum: String? = null,
    val notes: String? = null,
)

@Serializable
data class DeleteEvidenceInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    val detach: Boolean = false,
)

/** `id` and `expected_version` are the spend's; the document goes in `evidence_id`. */
@Serializable
data class EvidenceLinkInput(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val id: String,
    @SerialName("expected_version") val expectedVersion: Long,
    @SerialName("evidence_id") val evidenceId: String,
)

@Serializable
data class EvidencePageInput(val limit: Int = 200, val offset: Int = 0, val state: String? = null)

data class EvidenceState(
    val items: List<Evidence> = emptyList(),
    val loading: Boolean = true,
    val problem: String? = null,
    val showDeleted: Boolean = false,
    /** A delete the engine refused because spends still use the document: the user must confirm unlinking them. */
    val needsDetach: Evidence? = null,
)

class EvidenceStore(private val engine: EngineApi, private val afterChange: suspend () -> Unit = {}) {
    private val _state = MutableStateFlow(EvidenceState())
    val state: StateFlow<EvidenceState> = _state.asStateFlow()

    suspend fun load(showDeleted: Boolean = _state.value.showDeleted) {
        _state.update { it.copy(showDeleted = showDeleted, loading = true) }
        val all = mutableListOf<Evidence>()
        var offset = 0
        while (true) {
            val r = engine.call<EvidencePageInput, Page<Evidence>>("evidence_list", EvidencePageInput(offset = offset, state = if (showDeleted) "deleted" else null))
            if (r !is Api.Ok) { _state.update { it.copy(problem = r.problem, loading = false) }; return }
            all += r.value.items
            offset = r.value.nextOffset ?: break
        }
        _state.update { it.copy(items = all, problem = null, loading = false) }
    }

    suspend fun create(f: EvidenceFields, key: String = UUID.randomUUID().toString()): Evidence? {
        val r = engine.call<NewEvidenceInput, Evidence>("evidence_create", NewEvidenceInput(key, f.title, f.sourceRef, f.mediaType, f.checksum, f.notes))
        if (r !is Api.Ok) { _state.update { it.copy(problem = r.problem) }; return null }
        _state.update { it.copy(items = listOf(r.value) + it.items, problem = null) }
        return r.value
    }

    suspend fun update(e: Evidence, f: EvidenceFields): Boolean {
        val r = engine.call<UpdateEvidenceInput, Evidence>("evidence_update", UpdateEvidenceInput(UUID.randomUUID().toString(), e.id, e.version, f.title, f.sourceRef, f.mediaType, f.checksum, f.notes))
        if (r !is Api.Ok) { _state.update { it.copy(problem = r.problem) }; return false }
        _state.update { s -> s.copy(items = s.items.map { if (it.id == e.id) r.value else it }, problem = null) }
        return true
    }

    /** `detach` unlinks it from every spend first; without it the engine refuses while any spend still uses the document. */
    suspend fun delete(e: Evidence, detach: Boolean = false) {
        val r = engine.call<DeleteEvidenceInput, Evidence>("evidence_delete", DeleteEvidenceInput(UUID.randomUUID().toString(), e.id, e.version, detach))
        when {
            r is Api.Ok -> { _state.update { s -> s.copy(items = s.items.filter { it.id != e.id }, problem = null, needsDetach = null) }; if (detach) afterChange() }
            r is Api.Failed && r.code == "conflict" && !detach -> _state.update { it.copy(needsDetach = e) }
            else -> _state.update { it.copy(problem = r.problem) }
        }
    }

    suspend fun restore(e: Evidence) {
        val r = engine.call<LifecycleInput, Evidence>("evidence_restore", LifecycleInput(UUID.randomUUID().toString(), e.id, e.version))
        if (r is Api.Ok) _state.update { s -> s.copy(items = s.items.filter { it.id != e.id }, problem = null) } else _state.update { it.copy(problem = r.problem) }
    }

    fun dismissDetach() = _state.update { it.copy(needsDetach = null) }

    suspend fun get(id: String): Evidence? = (engine.call<GetInput, Evidence>("evidence_get", GetInput(id)) as? Api.Ok)?.value
}
