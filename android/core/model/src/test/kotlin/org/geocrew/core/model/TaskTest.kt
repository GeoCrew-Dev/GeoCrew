package org.geocrew.core.model

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.JsonPrimitive

class TaskTest {
    @Test
    fun roundTripsThroughProperties() {
        val t = TaskInfo("m1", "小王", TaskStatus.DOING)
        val p = buildProperties { this[Props.TITLE] = "D001"; t.writeTo(this) }
        assertEquals(t, TaskInfo.fromProperties(p))
        assertEquals(JsonPrimitive("doing"), p[Props.TASK_STATUS])
    }

    @Test
    fun clearingKeepsOtherProperties() {
        val p = buildProperties { this[Props.TITLE] = "D001"; TaskInfo("m1", "小王").writeTo(this) }
        val cleared = p.edit { TaskInfo.clear(this) }
        assertNull(TaskInfo.fromProperties(cleared))
        assertEquals(setOf(Props.TITLE), cleared.keys)
    }

    @Test
    fun unknownStatusReadsAsTodoAndBlankAssigneeIsNoTask() {
        val p = buildProperties { this[Props.ASSIGNEE] = "m1"; this[Props.TASK_STATUS] = "blocked" }
        assertEquals(TaskStatus.TODO, TaskInfo.fromProperties(p)?.status)
        assertNull(TaskInfo.fromProperties(buildProperties { this[Props.ASSIGNEE] = " " }))
    }
}
