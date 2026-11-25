package org.geocrew.feature.compass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.SunDay
import org.geocrew.core.model.SunTimes
import org.geocrew.core.sensors.LocationSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Where the sun times are for, and how we know it. */
data class SunPlace(val position: LngLat, val fromGps: Boolean)

/** The sun page: today and the next days at the user's position (or the map's centre until a fix comes). */
@HiltViewModel
class SunViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val location: LocationSource,
) : ViewModel() {
    private val _place = MutableStateFlow<SunPlace?>(null)
    val place: StateFlow<SunPlace?> = _place.asStateFlow()

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(30_000)
        }
    }
    val now: StateFlow<Instant> = clock.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Instant.now())

    val zone: ZoneId = ZoneId.systemDefault()

    /** Today first, then six more days. Recomputed when the date or place changes. */
    val days: StateFlow<List<SunDay>> = combine(_place, clock.map { LocalDate.ofInstant(it, zone) }.distinctUntilChanged()) { p, today ->
        if (p == null) emptyList() else (0L until 7L).map { SunTimes.of(today.plusDays(it), p.position) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val view = projects.ensureCurrent().config.view
            if (view != null && _place.value == null) _place.value = SunPlace(LngLat(view.lng, view.lat), fromGps = false)
        }
        locate()
    }

    fun locate() {
        if (_locating.value || !location.hasPermission() || !location.isEnabled()) return
        viewModelScope.launch {
            _locating.value = true
            try {
                withTimeoutOrNull(30_000) { location.fixes(intervalMillis = 1000).first() }?.let {
                    _place.value = SunPlace(it.position, fromGps = true)
                }
            } finally {
                _locating.value = false
            }
        }
    }
}
