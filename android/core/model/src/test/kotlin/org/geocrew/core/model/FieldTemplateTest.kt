package org.geocrew.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FieldTemplateTest {
    @Test
    fun textKeepsLeadingZeros() {
        assertEquals(JsonPrimitive("007"), FieldTemplate("sample_no").encode(" 007 "))
        assertNull(FieldTemplate("sample_no").encode("  "))
    }

    @Test
    fun numberAcceptsCommaDecimal() {
        val f = FieldTemplate("thickness", "厚度", FieldType.NUMBER)
        assertEquals(JsonPrimitive(2.5), f.encode("2,5"))
        assertEquals("2.5", f.display(f.encode("2,5")))
        assertEquals("3", f.display(JsonPrimitive(3.0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun numberRejectsText() {
        FieldTemplate("thickness", "厚度", FieldType.NUMBER).encode("厚")
    }

    @Test(expected = IllegalArgumentException::class)
    fun choiceRejectsOtherValues() {
        FieldTemplate("s", "构造", FieldType.CHOICE, options = listOf("层理")).encode("断层")
    }

    @Test
    fun problemCatchesClashes() {
        val existing = listOf(FieldTemplate("formation", "地层"))
        assertNotNull(FieldTemplate.problem(FieldTemplate("formation", "地层"), existing))
        assertNotNull(FieldTemplate.problem(FieldTemplate(Props.DIP, "倾角"), existing))
        assertNotNull(FieldTemplate.problem(FieldTemplate("a/b", "x"), existing))
        assertNotNull(FieldTemplate.problem(FieldTemplate("c", "c", FieldType.CHOICE), existing))
        assertNull(FieldTemplate.problem(FieldTemplate("样品 编号", "样品编号"), existing))
    }

    @Test
    fun presetIsValidAndRoundTrips() {
        val preset = FieldTemplate.geologyPreset
        preset.forEachIndexed { i, f -> assertNull(f.key, FieldTemplate.problem(f, preset.take(i))) }
        val cfg = ProjectConfig(fields = preset + FieldTemplate("grade", kinds = listOf(FeatureKind.GEO_POINT.id)))
        val back = Json.decodeFromString(ProjectConfig.serializer(), Json.encodeToString(ProjectConfig.serializer(), cfg))
        assertEquals(cfg, back)
        assertEquals(preset.size + 1, back.fieldsFor(FeatureKind.GEO_POINT).size)
        assertEquals(preset.size, back.fieldsFor(FeatureKind.LINE).size)
    }

    @Test
    fun oldConfigWithoutFieldsStillReads() {
        val cfg = Json { ignoreUnknownKeys = true }.decodeFromString(ProjectConfig.serializer(), """{"description":"x"}""")
        assertEquals(emptyList<FieldTemplate>(), cfg.fields)
    }
}

class RequiredFieldTest {
    private fun f(kind: FeatureKind, vararg props: Pair<String, String>) = GeoFeature(
        id = "f", projectId = "p", kind = kind, geometry = null,
        properties = buildProperties { props.forEach { (k, v) -> this[k] = v } },
        createdAt = java.time.Instant.EPOCH, sync = SyncInfo(updatedAt = java.time.Instant.EPOCH),
    )

    @org.junit.Test
    fun missingListsOnlyRequiredApplicableBlankFields() {
        val config = ProjectConfig(
            fields = listOf(
                FieldTemplate("formation", "地层", required = true, kinds = listOf("geo_point")),
                FieldTemplate("sample_no", "样品编号"),
                FieldTemplate("width", "宽度", FieldType.NUMBER, required = true, kinds = listOf("line")),
            ),
        )
        org.junit.Assert.assertEquals(listOf("地层"), config.missing(f(FeatureKind.GEO_POINT)).map { it.label })
        org.junit.Assert.assertEquals(emptyList<FieldTemplate>(), config.missing(f(FeatureKind.GEO_POINT, "formation" to "J3")))
        org.junit.Assert.assertEquals(listOf("地层"), config.missing(f(FeatureKind.GEO_POINT, "formation" to "  ")).map { it.label })
        org.junit.Assert.assertEquals(emptyList<FieldTemplate>(), config.missing(f(FeatureKind.POINT)))
    }
}
