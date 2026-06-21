package com.kursk1000

// Состояние карточки на экране. Раньше жило в MainActivity.kt; вынесено к держателю
// состояния (LandmarkViewModel формирует его, BleScreen — рендерит).
sealed class UiState {
    data object Searching : UiState()                      // маяк не найден
    data class Loaded(val landmark: Landmark) : UiState()  // данные получены
}
