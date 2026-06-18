package com.kursk1000.ui.theme

import androidx.compose.ui.graphics.Color

// Фиксированная высококонтрастная «дневная» палитра гида: приложение используют
// на улице при ярком солнце, поэтому отказались от dynamic color (см. Theme.kt) и
// тёмной темы в пользу предсказуемого контраста. Тон — тёплый, «исторический»,
// под 1000-летие Курска (глубокий бордо-акцент на почти белом фоне).

val HeritageRed = Color(0xFF8E2A2A)          // primary — акцент, заголовки фактов
val OnHeritageRed = Color(0xFFFFFFFF)
val HeritageRedContainer = Color(0xFFF6D9D4) // подложка-заглушка обложки (градиент)
val OnHeritageRedContainer = Color(0xFF3A0A09)

val WarmBrown = Color(0xFF6E5B55)            // secondary
val OnWarmBrown = Color(0xFFFFFFFF)
val FactsPanel = Color(0xFFF3E3DD)           // secondaryContainer — фон панели фактов
val OnFactsPanel = Color(0xFF2B211D)         // высококонтрастный текст фактов

val MutedGreen = Color(0xFF4C6B3F)           // tertiary — маркеры-точки в фактах
val OnMutedGreen = Color(0xFFFFFFFF)

val PaperSurface = Color(0xFFFDF8F6)         // фон/surface — тёплый почти-белый
val OnSurfaceInk = Color(0xFF1B1B1B)         // основной текст — почти чёрный
val OnSurfaceMuted = Color(0xFF4A413C)       // вторичный текст (подзаголовки, тела секций)
val SurfacePlaceholder = Color(0xFFE8E0DB)   // плейсхолдер изображений во время загрузки
val OutlineHairline = Color(0xFF857A74)
val OutlineSoft = Color(0xFFD7CCC6)

val ErrorRed = Color(0xFFB3261E)
val OnError = Color(0xFFFFFFFF)