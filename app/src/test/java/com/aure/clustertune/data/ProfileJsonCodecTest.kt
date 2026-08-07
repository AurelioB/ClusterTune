package com.aure.clustertune.data

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonCodecTest {

    @Test
    fun `share export omits app internal profile fields`() {
        val json = ProfileJsonCodec.encodeShareFile(
            profiles = listOf(
                PerformanceProfile(
                    id = "bundled_cq8725s_small",
                    name = "Small Underclock",
                    maxFrequencies = mapOf(0 to 2_745_600, 6 to 3_072_000),
                    source = ProfileSource.BUNDLED,
                    order = 7,
                    isEditable = false,
                    isDeletable = false,
                ),
            ),
            socModel = "CQ8725S",
        )

        assertTrue(json.contains("\"profiles\""))
        assertTrue(json.contains("\"id\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"maxFrequencies\""))
        assertFalse(json.contains("\"source\""))
        assertFalse(json.contains("\"isEditable\""))
        assertFalse(json.contains("\"isDeletable\""))
        assertFalse(json.contains("\"order\""))
    }

    @Test
    fun `share import parses public profile schema with array order`() {
        val profiles = ProfileJsonCodec.parseShareProfiles(
            """
                {
                  "schemaVersion": 1,
                  "socModel": "CQ8725S",
                  "profiles": [
                    {
                      "id": "bundled_cq8725s_small",
                      "name": "Small Underclock",
                      "maxFrequencies": {
                        "0": 2745600,
                        "6": 3072000
                      }
                    },
                    {
                      "id": "custom",
                      "name": "Custom",
                      "maxFrequencies": {
                        "0": 2227200
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("bundled_cq8725s_small", "custom"), profiles.map { it.id })
        assertEquals(listOf(0, 1), profiles.map { it.order })
        assertEquals(mapOf(0 to 2_745_600, 6 to 3_072_000), profiles.first().maxFrequencies)
        assertEquals(ProfileSource.USER, profiles.first().source)
    }

    @Test
    fun `share codec decodes v1 legacy and round trips v2 gpu cap`() {
        val legacy = ProfileJsonCodec.parseShareProfiles(
            """{"schemaVersion":1,"profiles":[{"id":"legacy","name":"Legacy","maxFrequencies":{}}]}""",
        ).single()
        assertEquals(null, legacy.gpuMaxFrequencyHz)

        val profile = PerformanceProfile("gpu", "GPU", emptyMap(), ProfileSource.USER, gpuMaxFrequencyHz = 700_000_000)
        val parsed = ProfileJsonCodec.parseShareProfiles(ProfileJsonCodec.encodeShareFile(listOf(profile), "soc")).single()
        assertEquals(700_000_000, parsed.gpuMaxFrequencyHz)
    }

    @Test
    fun `share import ignores non-positive frequency values`() {
        val parsed = ProfileJsonCodec.parseShareProfiles(
            """{"schemaVersion":1,"profiles":[{"id":"bad","name":"Bad","maxFrequencies":{"0":-1},"gpuMaxFrequencyHz":0}]}""",
        ).single()
        assertEquals(emptyMap<Int, Int>(), parsed.maxFrequencies)
        assertEquals(null, parsed.gpuMaxFrequencyHz)
    }
}
