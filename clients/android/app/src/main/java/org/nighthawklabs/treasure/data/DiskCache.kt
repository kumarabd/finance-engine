package org.nighthawklabs.treasure.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Last good response per key, so lists still open offline.
 * ponytail: JSON files hold only the first page; move to Room once the cache must be queried or edited offline.
 */
class DiskCache(private val dir: File) {
    private fun file(key: String) = File(dir, "treasure-$key.json")

    fun write(key: String, text: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "treasure-$key.tmp")
            tmp.writeText(text)
            tmp.renameTo(file(key)) // atomic swap, so a crash mid-write never leaves half a file
        }
    }

    fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(key: String) { file(key).delete() }

    inline fun <reified T> save(value: T, key: String) = write(key, ApiJson.encodeToString(value))
    inline fun <reified T> load(key: String): T? = read(key)?.let { runCatching { ApiJson.decodeFromString<T>(it) }.getOrNull() }
}
