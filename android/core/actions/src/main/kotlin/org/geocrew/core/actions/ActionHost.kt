package org.geocrew.core.actions

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something only the screen can do for an action: show a page, open the camera. */
sealed interface UiEffect {
    data class Open(val page: String, val id: String? = null) : UiEffect
    data class TakePhoto(val recordId: String) : UiEffect
}

/**
 * Where the user is, so actions and the assistant know what "this record"
 * means without asking. Screens report themselves here; the app shell
 * collects [effects].
 */
@Singleton
class ActionHost @Inject constructor() {
    /** Page id from [org.geocrew.core.model.action.ActionCatalog], or null. */
    val page = MutableStateFlow<String?>(null)

    /** The record open on screen (detail page or selected on the map). */
    val openRecord = MutableStateFlow<String?>(null)

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    fun emit(effect: UiEffect) = _effects.tryEmit(effect)

    /** Called by a screen when it shows; [record] when it is about one record. */
    fun at(page: String, record: String? = null) {
        this.page.value = page
        if (record != null || page != org.geocrew.core.model.action.ActionCatalog.PAGE_RECORD) openRecord.value = record ?: openRecord.value
    }
}
