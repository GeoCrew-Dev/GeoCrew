package org.geocrew.core.formats

import org.geocrew.core.model.AssayPlan
import org.geocrew.core.model.AssayValue
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Sample
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.applyAssays
import org.geocrew.core.model.buildProperties
import org.geocrew.core.model.edit
import org.geocrew.core.model.results
import org.geocrew.core.model.samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AssayCsvTest {
    private val t0 = Instant.parse("2026-11-24T02:00:00Z")

    @Test
    fun readsUnitsRowBelowLimitAndGbk() {
        val text = "样品编号,Cu,Au,分析方法,备注\n单位,ppm,ppb,,\nD1-1,35.2,<0.5,ICP-MS,\nd2-1,\"1,200\",3,ICP-MS,复测\nD9-9,-,ND,,\n"
        val t = AssayCsv.read(text.toByteArray(charset("GB18030")))
        assertEquals(listOf("Cu(ppm)", "Au(ppb)"), t.columns)
        assertEquals(3, t.rows.size)
        assertEquals(AssayValue.Measured(35.2), t.rows[0].values["Cu(ppm)"])
        assertEquals(AssayValue.BelowLimit(0.5), t.rows[0].values["Au(ppb)"])
        assertEquals(1200.0, t.rows[1].values["Cu(ppm)"]!!.forAnalysis, 0.0)
        assertTrue(t.rows[2].values.isEmpty())
    }

    @Test
    fun plansAndAppliesResults() {
        fun station(id: String, vararg nos: String) = GeoFeature(
            id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(116.0, 40.0)),
            buildProperties { samples(nos.map { Sample(it, "岩石化学") }) }, t0, SyncInfo(updatedAt = t0),
        )
        val d1 = station("a", "D1-1", "D1-2")
        val d2 = station("b", "D2-1")
        val t = AssayCsv.parse("样号,Cu,dip\nD1-1,30,1\nD1-2,<10,2\nD2-1,7,3\nX-1,1,\n")
        val plan = AssayPlan.of(t, listOf(d1, d2))
        assertEquals(listOf("Cu"), plan.columns)
        assertEquals(listOf("dip"), plan.reserved)
        assertEquals(listOf("X-1"), plan.unmatched)
        assertEquals(3, plan.sampleCount)
        assertEquals(1, plan.belowLimit)
        val r = plan.records.first { it.featureId == "a" }
        assertEquals(17.5, r.means["Cu"]!!, 1e-9) // (30 + 10/2) / 2
        val props = d1.properties.edit { applyAssays(d1, r) }
        val after = d1.copy(properties = props)
        assertEquals(17.5, (props["Cu"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 1e-9)
        assertEquals(AssayValue.BelowLimit(10.0), after.samples[1].results["Cu"])
        assertEquals("<10", after.samples[1].raw["results"].toString().substringAfter(":\"").substringBefore("\""))
    }
}
