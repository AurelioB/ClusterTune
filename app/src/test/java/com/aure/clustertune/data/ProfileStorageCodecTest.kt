package com.aure.clustertune.data

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileStorageCodecTest {

    @Test
    fun `app assignments preserve named and custom targets`() {
        val assignments = listOf(
            AppProfileAssignment("named.app", "Named", profileId = "small"),
            AppProfileAssignment("custom.app", "Custom", customMaxFrequencies = mapOf(0 to 2_000_000), customGpuMaxFrequencyHz = 500_000_000),
        )

        val parsed = ProfileStorageCodec.parseAppProfileAssignments(
            ProfileStorageCodec.encodeAppProfileAssignments(assignments),
        )

        assertEquals(assignments.associateBy { it.packageName }, parsed.associateBy { it.packageName })
        assertEquals(false, parsed.first { it.packageName == "named.app" }.isCustom)
        assertEquals(true, parsed.first { it.packageName == "custom.app" }.isCustom)
    }

    @Test
    fun `app assignments remain compatible with legacy json`() {
        val parsed = ProfileStorageCodec.parseAppProfileAssignments(
            """[{"packageName":"legacy.app","appLabel":"Legacy","profileId":"small"}]""",
        )

        assertEquals(listOf(AppProfileAssignment("legacy.app", "Legacy", profileId = "small")), parsed)
    }

    @Test
    fun `profile storage round trips app internal fields`() {
        val original = listOf(
            PerformanceProfile(
                id = "bundled_cq8725s_small",
                name = "Small Underclock",
                maxFrequencies = mapOf(0 to 2_745_600, 6 to 3_072_000),
                source = ProfileSource.BUNDLED,
                order = 1,
                isEditable = true,
                isDeletable = false,
            ),
            PerformanceProfile(
                id = "stock",
                name = "Stock",
                maxFrequencies = emptyMap(),
                source = ProfileSource.VIRTUAL,
                order = 0,
                isEditable = true,
                isDeletable = false,
            ),
        )

        val parsed = ProfileStorageCodec.parseProfiles(ProfileStorageCodec.encodeProfiles(original))

        assertEquals(listOf("stock", "bundled_cq8725s_small"), parsed.map { it.id })
        assertEquals(ProfileSource.VIRTUAL, parsed.first().source)
        assertEquals(false, parsed.first().isDeletable)
        assertEquals(mapOf(0 to 2_745_600, 6 to 3_072_000), parsed.last().maxFrequencies)
    }

    @Test
    fun `profile storage reads json array format`() {
        val parsed = ProfileStorageCodec.parseProfiles(
            """
                [
                  {
                    "id": "custom",
                    "name": "Custom",
                    "source": "USER",
                    "isResetProfile": false,
                    "order": 3,
                    "isEditable": true,
                    "isDeletable": true,
                    "maxFrequencies": {
                      "0": 2227200,
                      "6": 3072000
                    }
                  }
                ]
            """.trimIndent(),
        )

        assertEquals("custom", parsed.single().id)
        assertEquals(3, parsed.single().order)
        assertEquals(mapOf(0 to 2_227_200, 6 to 3_072_000), parsed.single().maxFrequencies)
        assertEquals(null, parsed.single().gpuMaxFrequencyHz)
    }

    @Test
    fun `profile storage v2 round trips gpu cap while decoding v1 legacy`() {
        val legacy = ProfileStorageCodec.parseProfiles(
            "[{\"id\":\"legacy\",\"name\":\"Legacy\",\"source\":\"USER\",\"maxFrequencies\":{}}]",
        ).single()
        assertEquals(null, legacy.gpuMaxFrequencyHz)

        val profile = PerformanceProfile("gpu", "GPU", emptyMap(), ProfileSource.USER, gpuMaxFrequencyHz = 650_000_000)
        val parsed = ProfileStorageCodec.parseProfiles(ProfileStorageCodec.encodeProfiles(listOf(profile))).single()
        assertEquals(650_000_000, parsed.gpuMaxFrequencyHz)
    }

    @Test
    fun `profile storage rejects invalid gpu values and normalizes mixed app targets`() {
        val profile = ProfileStorageCodec.parseProfiles(
            "[{\"id\":\"bad\",\"name\":\"Bad\",\"maxFrequencies\":{},\"gpuMaxFrequencyHz\":-1}]",
        ).single()
        assertEquals(null, profile.gpuMaxFrequencyHz)

        val assignments = ProfileStorageCodec.parseAppProfileAssignments(
            """
            [
              {"packageName":"mixed","appLabel":"Mixed","profileId":"stock","customGpuMaxFrequencyHz":400},
              {"packageName":"gpu","appLabel":"GPU","customGpuMaxFrequencyHz":500}
            ]
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                AppProfileAssignment("gpu", "GPU", customGpuMaxFrequencyHz = 500),
                AppProfileAssignment("mixed", "Mixed", profileId = "stock"),
            ),
            assignments,
        )
    }

    @Test
    fun `int maps and string lists read json shapes`() {
        assertEquals(
            mapOf(0 to 2_227_200, 6 to 3_072_000),
            ProfileStorageCodec.parseIntMap("""{"0":2227200,"6":3072000}"""),
        )

        assertEquals(
            listOf("stock", "custom"),
            ProfileStorageCodec.parseStringList("""["stock","custom"]"""),
        )
    }
}
