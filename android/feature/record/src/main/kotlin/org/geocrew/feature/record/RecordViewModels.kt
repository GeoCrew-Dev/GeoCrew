package org.geocrew.feature.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Props
import org.geocrew.core.model.string
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecordListViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
) : ViewModel() {
    val kindFilter = MutableStateFlow<FeatureKind?>(null)
    val query = MutableStateFlow("")

    private val all: StateFlow<List<GeoFeature>> = projects.current
        .map { it.id }
        .distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projectName: StateFlow<String> = projects.current.map { it.name }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val items: StateFlow<List<GeoFeature>> = combine(all, kindFilter, query) { list, kind, q ->
        list.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { q.isBlank() || (it.title ?: "").contains(q, ignoreCase = true) || (it.note ?: "").contains(q, ignoreCase = true) }
            .sortedByDescending { it.sync.updatedAt }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val counts: StateFlow<Map<FeatureKind, Int>> = all.map { l -> l.groupingBy { it.kind }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
}

/** Route argument holding the feature id. */
const val FEATURE_ID_ARG = "featureId"

@HiltViewModel
class FeatureDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val features: FeatureRepository,
    val photos: PhotoStore,
    settings: SettingsRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[FEATURE_ID_ARG])

    val feature: StateFlow<GeoFeature?> = features.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val coordinateFormat: StateFlow<CoordinateFormat> = settings.settings.map { it.coordinateFormat }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoordinateFormat.DECIMAL)

    /** Saves text fields; blank values remove the property. */
    fun saveText(title: String, note: String, lithology: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (cur.title.orEmpty() == title && cur.note.orEmpty() == note && cur.properties.string(Props.LITHOLOGY).orEmpty() == lithology) return@launch
        features.edit(id) {
            this[Props.TITLE] = title.trim().ifBlank { null }
            this[Props.NOTE] = note.trim().ifBlank { null }
            this[Props.LITHOLOGY] = lithology.trim().ifBlank { null }
        }
    }

    fun setAttitude(a: Attitude?) = viewModelScope.launch {
        features.edit(id) {
            if (a == null) {
                remove(Props.STRIKE); remove(Props.DIP_DIRECTION); remove(Props.DIP)
            } else {
                a.writeTo(this)
            }
        }
    }

    fun setKind(kind: FeatureKind) = viewModelScope.launch {
        features.changeKind(id, kind)
    }

    fun newCaptureTarget() = photos.newCaptureTarget()

    fun addCaptured(file: File) = viewModelScope.launch {
        photos.commit(file)?.let(::addPhoto)
    }

    fun addPicked(uri: android.net.Uri) = viewModelScope.launch {
        photos.import(uri)?.let(::addPhoto)
    }

    private fun addPhoto(sha: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (sha in cur.photos) return@launch
        features.edit(id) { strings(Props.PHOTOS, cur.photos + sha) }
    }

    fun removePhoto(sha: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        features.edit(id) { strings(Props.PHOTOS, cur.photos - sha) }
    }

    fun delete() = viewModelScope.launch { features.delete(id) }
}
