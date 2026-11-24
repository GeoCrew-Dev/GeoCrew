package org.geocrew.core.model

/** Progress of a record that was handed to someone as a task (protocol v0 §6.1). */
enum class TaskStatus(val wire: String, val label: String) {
    TODO("todo", "待办"),
    DOING("doing", "进行中"),
    DONE("done", "已完成"),
    ;

    companion object {
        /** Unknown values from newer clients read as [TODO] so the task stays visible. */
        fun parse(s: String?): TaskStatus = entries.firstOrNull { it.wire == s } ?: TODO
    }
}

/**
 * A work assignment carried by any record's properties: a leader draws a
 * target point, line or area and hands it to a teammate. The member's
 * nickname is stored next to the id so exports and offline devices can
 * show who it is without asking the server.
 */
data class TaskInfo(
    val assignee: String,
    val assigneeName: String,
    val status: TaskStatus = TaskStatus.TODO,
) {
    fun writeTo(b: PropertiesBuilder) {
        b[Props.ASSIGNEE] = assignee
        b[Props.ASSIGNEE_NAME] = assigneeName.ifBlank { null }
        b[Props.TASK_STATUS] = status.wire
    }

    companion object {
        fun fromProperties(p: kotlinx.serialization.json.JsonObject): TaskInfo? {
            val who = p.string(Props.ASSIGNEE)?.takeIf { it.isNotBlank() } ?: return null
            return TaskInfo(who, p.string(Props.ASSIGNEE_NAME).orEmpty(), TaskStatus.parse(p.string(Props.TASK_STATUS)))
        }

        fun clear(b: PropertiesBuilder) {
            b.remove(Props.ASSIGNEE)
            b.remove(Props.ASSIGNEE_NAME)
            b.remove(Props.TASK_STATUS)
        }
    }
}

val GeoFeature.task: TaskInfo? get() = TaskInfo.fromProperties(properties)
