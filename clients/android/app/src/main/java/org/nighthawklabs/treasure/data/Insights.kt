package org.nighthawklabs.treasure.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.failure

/** Aggregates cross the API as exact integer strings; parse them as Long (never Double). */
@Serializable
data class Bucket(
    val currency: String,
    val key: String,
    val label: String,
    @SerialName("expense_minor") val expenseMinor: String,
    @SerialName("refund_minor") val refundMinor: String,
    @SerialName("net_minor") val netMinor: String,
    val count: Long = 0,
) {
    val net: Long get() = netMinor.toLongOrNull() ?: 0
    val expense: Long get() = expenseMinor.toLongOrNull() ?: 0
}

@Serializable
data class Analysis(val current: List<Bucket> = emptyList(), val comparison: List<Bucket> = emptyList())

@Serializable
data class AnalysisInput(
    val from: String,
    val to: String,
    @SerialName("group_by") val groupBy: String,
    @SerialName("compare_from") val compareFrom: String? = null,
    @SerialName("compare_to") val compareTo: String? = null,
)

data class Snapshot(
    val period: Period,
    val window: Period.Window,
    val trend: Analysis,
    val categories: Analysis,
    val merchants: Analysis,
) {
    /** Currencies present in the window, biggest spend first. They are never combined. */
    val currencies: List<String>
        get() = trend.current.groupBy { it.currency }.mapValues { (_, v) -> v.sumOf { it.expense } }
            .entries.sortedByDescending { it.value }.map { it.key }

    fun total(currency: String): Pair<Long, Long?> {
        val cur = trend.current.filter { it.currency == currency }.sumOf { it.net }
        val prev = trend.comparison.filter { it.currency == currency }
        return cur to if (prev.isEmpty()) null else prev.sumOf { it.net }
    }

    fun series(currency: String) = Trend.series(trend.current, currency, period.trendGroup, window.from, window.to)
    fun previousSeries(currency: String) = Trend.series(trend.comparison, currency, period.trendGroup, window.compareFrom, window.compareTo)
}

class Insights(private val engine: EngineApi) {
    suspend fun snapshot(period: Period): Api<Snapshot> = coroutineScope {
        val w = period.window()
        val trend = async { engine.call<AnalysisInput, Analysis>("spending_analyze", AnalysisInput(w.from, w.to, period.trendGroup, w.compareFrom, w.compareTo)) }
        val cats = async { engine.call<AnalysisInput, Analysis>("spending_analyze", AnalysisInput(w.from, w.to, "category")) }
        val merchants = async { engine.call<AnalysisInput, Analysis>("spending_analyze", AnalysisInput(w.from, w.to, "merchant")) }
        val (t, c, m) = Triple(trend.await(), cats.await(), merchants.await())
        if (t !is Api.Ok) return@coroutineScope t.failure()!!
        if (c !is Api.Ok) return@coroutineScope c.failure()!!
        if (m !is Api.Ok) return@coroutineScope m.failure()!!
        Api.Ok(Snapshot(period, w, t.value, c.value, m.value))
    }
}
