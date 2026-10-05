package org.nighthawklabs.treasure.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import org.nighthawklabs.treasure.net.Api

/**
 * A spend created while the engine couldn't be reached. Its id is the idempotency key, so sending it again after a dropped
 * response can never create a second record.
 */
@Serializable
data class OutboxItem(
    val id: String,
    val spend: SpendInput,
    /** A merchant typed by name that didn't exist yet: creating one needs the network, so it is resolved when sent. */
    val merchantName: String? = null,
    val createdAt: Long,
    val failure: String? = null,
    /** Times the engine answered 500 for this spend. Offline and "busy" don't count; a bug that repeats must not block the queue. */
    val attempts: Int? = null,
) {
    /** How a pending item looks in a list. */
    fun asSpend() = Spend(
        id = id, version = 0, occurredOn = spend.occurredOn, kind = spend.kind, amountMinor = spend.amountMinor,
        currency = spend.currency, merchantId = spend.merchantId, description = merchantName ?: spend.description,
        allocations = spend.allocations,
    )
}

class Outbox(private val cache: DiskCache, private val key: String = "outbox") {
    private val state = MutableStateFlow(cache.load<List<OutboxItem>>(key) ?: emptyList())
    val items: StateFlow<List<OutboxItem>> = state.asStateFlow()
    private val running = Mutex()

    fun add(item: OutboxItem) = update { it + item }
    fun discard(id: String) = update { list -> list.filter { it.id != id } }

    /** Puts a spend that gave up back in the queue, with the same key. */
    fun retry(id: String) = update { l -> l.map { if (it.id == id) it.copy(failure = null, attempts = null) else it } }

    /**
     * Sends oldest first and returns what was created. Stops at the first connectivity or sign-in problem so order is kept;
     * the engine refusing one item marks it failed (for the user to see) and carries on with the rest.
     */
    suspend fun flush(send: suspend (OutboxItem) -> Api<Spend>): List<Spend> {
        if (!running.tryLock()) return emptyList()
        try {
            val created = mutableListOf<Spend>()
            for (item in state.value.filter { it.failure == null }) {
                when (val r = send(item)) {
                    is Api.Ok -> { update { l -> l.filter { it.id != item.id } }; created += r.value }
                    is Api.Retry, Api.Unauthorized, Api.NotProvisioned -> return created
                    is Api.ServerError -> {
                        val attempts = (state.value.firstOrNull { it.id == item.id }?.attempts ?: 0) + 1
                        if (attempts < MAX_SERVER_ERRORS) { // try again later, in order
                            update { l -> l.map { if (it.id == item.id) it.copy(attempts = attempts) else it } }
                            return created
                        }
                        update { l -> l.map { if (it.id == item.id) it.copy(attempts = attempts, failure = "Treasure kept failing to save this (${r.message}). Retry it, or discard it.") else it } }
                    }
                    is Api.Failed -> update { l -> l.map { if (it.id == item.id) it.copy(failure = r.message) else it } }
                }
            }
            return created
        } finally {
            running.unlock()
        }
    }

    companion object { const val MAX_SERVER_ERRORS = 5 }

    private fun update(f: (List<OutboxItem>) -> List<OutboxItem>) {
        state.value = f(state.value)
        cache.save(state.value, key)
    }
}
