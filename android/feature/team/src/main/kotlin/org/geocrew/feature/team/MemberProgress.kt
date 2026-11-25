package org.geocrew.feature.team

import org.geocrew.core.model.DailyActivity
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.task
import java.time.LocalDate
import java.time.ZoneId

/** How far one member is with the records assigned to them, how many records they last edited, and what they did today. */
data class MemberProgress(
    val todo: Int = 0,
    val doing: Int = 0,
    val done: Int = 0,
    val edited: Int = 0,
    /** What they did today (records, samples, track). */
    val today: DailyActivity = DailyActivity(),
) {
    val assigned: Int get() = todo + doing + done

    fun label(): String? = buildList {
        today.label()?.let { add("今天 $it") }
        if (assigned > 0) add("任务 $done/$assigned 完成" + if (doing > 0) "，$doing 进行中" else "")
        if (edited > 0) add("最后编辑 $edited 条记录")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    companion object {
        fun of(
            features: List<GeoFeature>,
            day: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Map<String, MemberProgress> {
            val out = HashMap<String, MemberProgress>()
            DailyActivity.byMember(features, day, zone).forEach { (who, a) -> out[who] = MemberProgress(today = a) }
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
