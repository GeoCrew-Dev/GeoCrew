package org.geocrew.core.formats

import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.GeoRoute
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

class RouteTableTest {
    private val t0 = Instant.parse("2026-11-24T02:00:00Z")

    private fun point(id: String, lng: Double) = GeoFeature(
        id, "p", FeatureKind.GEO_POINT, Geometry.Point(LngLat(lng, 40.0, 1250.0)),
        buildProperties {
            this[Props.TITLE] = id
            this[Props.LITHOLOGY] = "灰岩"
            this[Props.NOTE] = "=层理清楚"
            Attitude.of(125.0, 42.0).writeTo(this)
        },
        t0, SyncInfo(updatedAt = t0),
    )

    private fun route(id: String, title: String, r: GeoRoute) = GeoFeature(
        id, "p", FeatureKind.ROUTE, null,
        buildProperties { this[Props.TITLE] = title; put(Props.ROUTE, r.toJson()) }, t0, SyncInfo(updatedAt = t0),
    )

    @Test
    fun rowsInWalkingOrderWithPurposeAndSummary() {
        val r = GeoRoute(LocalDate.of(2026, 11, 24), "王", "追索断层", summary = "断层走向北东").plus(listOf("D2", "D1")).withOnward("D2", "沿沟向东")
        val older = GeoRoute(LocalDate.of(2026, 11, 23)).plus(listOf("D1"))
        val out = ByteArrayOutputStream()
        val res = RouteTableFormat.write(listOf(point("D1", 116.0), point("D2", 116.01), route("r2", "L002", r), route("r1", "L001", older)), out, ExportContext())
        assertEquals(5, res.written)
        val lines = out.toString(Charsets.UTF_8).removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(RouteTableFormat.HEADER.joinToString(","), lines[0])
        assertEquals("L001,2026-11-23,,1,D1,地质点,116.000000,40.000000,1250.0,灰岩,125°∠42°,'=层理清楚,,D1", lines[1])
        assertEquals("L002,2026-11-24,王,目的,,,,,,,,追索断层,,r2", lines[2])
        assertEquals("L002,2026-11-24,王,1,D2,地质点,116.010000,40.000000,1250.0,灰岩,125°∠42°,'=层理清楚,沿沟向东,D2", lines[3])
        assertEquals("L002,2026-11-24,王,小结,,,,,,,,断层走向北东,,r2", lines[5])
    }

    @Test
    fun nothingToWriteWithoutRoutes() {
        assertNotNull(RouteTableFormat.nothingToWrite(listOf(point("D1", 116.0))))
        assertNull(RouteTableFormat.nothingToWrite(listOf(route("r", "L1", GeoRoute()))))
    }
}
