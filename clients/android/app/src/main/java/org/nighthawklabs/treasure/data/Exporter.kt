package org.nighthawklabs.treasure.data

import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.EngineApi
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.map

/** Every page of an export joined into one CSV (each page repeats the header row; it is kept once). */
object Exporter {
    suspend fun csv(engine: EngineApi, filter: SpendFilter = SpendFilter()): Api<String> {
        val pages = mutableListOf<String>()
        var offset = 0
        while (true) {
            val r = engine.call<SearchInput, ExportResult>("spends_export", filter.input(limit = 200, offset = offset))
            if (r !is Api.Ok) return r.map { it.csv }
            pages += r.value.csv
            offset = r.value.nextOffset ?: return Api.Ok(CSVJoin.join(pages))
        }
    }
}
