package org.geocrew.feature.compass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import javax.inject.Inject

/** A record that can feed the thickness calculator: its attitude, its position, or both. */
data class ThicknessPick(val id: String, val title: String, val attitude: Attitude?, val position: LngLat?)

/** Supplies the open project's records to the stateless thickness calculator. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThicknessViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
) : ViewModel() {
    val picks: StateFlow<List<ThicknessPick>> = projects.current
        .flatMapLatest { p -> features.observeProject(p.id) }
        .map { list ->
            list.mapNotNull { f ->
                val pos = (f.geometry as? Geometry.Point)?.position
                val att = f.attitude
                if (pos == null && att == null) null
                else ThicknessPick(f.id, f.title?.takeIf { it.isNotBlank() } ?: "未命名", att, pos)
            }.sortedBy { it.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
