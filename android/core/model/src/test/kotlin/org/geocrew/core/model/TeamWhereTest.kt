package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TeamWhereTest {
    private val now = Instant.parse("2026-11-25T06:00:00Z")
    private val me = LngLat(116.0, 40.0)

    @Test
    fun nearestFirstWithDirectionDistanceAndAge() {
        val mates = listOf(
            TeamWhere.Mate("李四", LngLat(116.0, 39.99), now.minusSeconds(720), online = false),
            TeamWhere.Mate("张三", LngLat(116.003, 40.003), now.minusSeconds(20), online = true),
        )
        val rows = TeamWhere.rows(me, mates, now)
        assertEquals(listOf("张三", "李四"), rows.map { it.name })
        assertEquals("2 位队友：张三 在东北 420 米（刚刚）；李四 在南 1.1 公里（已离线，12 分钟前）。", TeamWhere.text(rows, haveMe = true))
    }

    @Test
    fun withoutMyPositionFreshestFirst() {
        val mates = listOf(
            TeamWhere.Mate("李四", LngLat(116.0, 39.99), now.minusSeconds(7300), online = true),
            TeamWhere.Mate("", LngLat(116.003, 40.003), now.minusSeconds(130), online = true),
        )
        val rows = TeamWhere.rows(null, mates, now)
        assertEquals("拿不到你的位置，只说队友最后的位置时间：2 位队友，队友（2 分钟前）；李四（2 小时前）。", TeamWhere.text(rows, haveMe = false))
        assertEquals("还没有队友共享位置。", TeamWhere.text(emptyList(), haveMe = true))
    }

    @Test
    fun distanceText() {
        assertEquals("80 米", TeamWhere.distanceText(83.0))
        assertEquals("3.5 公里", TeamWhere.distanceText(3460.0))
        assertEquals("12 公里", TeamWhere.distanceText(12400.0))
    }
}
