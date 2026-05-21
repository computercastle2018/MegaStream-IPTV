package com.MegaStream.app.localization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.MegaStream.data.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the quick language switcher in the top navigation bar. */
@HiltViewModel
class LanguageMenuViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val appLanguage: StateFlow<String> = preferencesRepository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "system")

    fun setLanguage(tag: String) {
        viewModelScope.launch { preferencesRepository.setAppLanguage(tag) }
    }
}
