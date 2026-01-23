package com.example.rocketplan_android.ui.conflict

import com.example.rocketplan_android.data.repository.ConflictRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * State representing the conflict banner visibility.
 */
sealed class ConflictBannerState {
    /** Banner is hidden (no unresolved conflicts) */
    data object Hidden : ConflictBannerState()

    /** Banner is visible with the count of unresolved conflicts */
    data class Visible(val count: Int) : ConflictBannerState()
}

/**
 * Manages the conflict banner state by observing the unresolved conflict count.
 * Use this class in fragments/activities to show/hide the conflict banner.
 */
class ConflictBannerManager(
    private val conflictRepository: ConflictRepository,
    scope: CoroutineScope
) {
    private val _bannerState = MutableStateFlow<ConflictBannerState>(ConflictBannerState.Hidden)
    val bannerState: StateFlow<ConflictBannerState> = _bannerState.asStateFlow()

    init {
        conflictRepository.observeUnresolvedCount()
            .onEach { count ->
                _bannerState.value = if (count > 0) {
                    ConflictBannerState.Visible(count)
                } else {
                    ConflictBannerState.Hidden
                }
            }
            .launchIn(scope)
    }
}
