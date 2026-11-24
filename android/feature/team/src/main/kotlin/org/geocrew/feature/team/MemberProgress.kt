package org.geocrew.feature.team

import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.task

/** How far one member is with the records assigned to them, and how many records they last edited. */
data class MemberProgress(val todo: Int = 0, val doing: Int = 0, val done: Int = 0, val edited: Int = 0) {
    val assigned: Int get() = todo + doing + done

    fun label(): String? = buildList {
        if (assigned > 0) add("任务 $done/$assigned 完成" + if (doing > 0) "，$doing 进行中" else "")
        if (edited > 0) add("最后编辑 $edited 条记录")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    companion object {
        fun of(features: List<GeoFeature>): Map<String, MemberProgress> {
            val out = HashMap<String, MemberProgress>()
            for (f in features) {
                if (f.deleted) continue
                f.task?.let { t ->
                    val p = out[t.assignee] ?: MemberProgress()
                    out[t.assignee] = when (t.status) {
                        TaskStatus.TODO -> p.copy(todo = p.todo + 1)
                        TaskStatus.DOING -> p.copy(doing = p.doing + 1)
                        TaskStatus.DONE -> p.copy(done = p.done + 1)
                    }
                }
                f.sync.updatedBy?.let { who ->
                    val p = out[who] ?: MemberProgress()
                    out[who] = p.copy(edited = p.edited + 1)
                }
            }
            return out
        }
    }
}
