package org.nighthawklabs.treasure

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nighthawklabs.treasure.data.*

class EvidenceTest {
    private inline fun <reified T> obj(v: T) = Json.parseToJsonElement(ApiJson.encodeToString(v)).jsonObject

    @Test fun evidenceIsSentFlatLikeTheEngineExpects() {
        val c = obj(NewEvidenceInput("k", "Receipt", "doc:1", notes = "n"))
        assertEquals("k", c["idempotency_key"]?.jsonPrimitive?.content); assertEquals("doc:1", c["source_ref"]?.jsonPrimitive?.content)
        assertEquals("n", c["notes"]?.jsonPrimitive?.content); assertNull(c["media_type"])
        val u = obj(UpdateEvidenceInput("k", "e1", 3, "T", "r"))
        assertEquals(3, u["expected_version"]?.jsonPrimitive?.int); assertEquals("e1", u["id"]?.jsonPrimitive?.content)
        val l = obj(EvidenceLinkInput("k", "s1", 2, "e1"))
        assertEquals("e1", l["evidence_id"]?.jsonPrimitive?.content); assertEquals("s1", l["id"]?.jsonPrimitive?.content)
        assertEquals(true, obj(DeleteEvidenceInput("k", "e", 1, detach = true))["detach"]?.jsonPrimitive?.boolean)
        assertNull(obj(DeleteEvidenceInput("k", "e", 1))["detach"])
    }
}
