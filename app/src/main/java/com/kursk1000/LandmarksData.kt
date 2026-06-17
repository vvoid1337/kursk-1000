package com.kursk1000

object BeaconUuids {
    const val ZNAMENSKY_CATHEDRAL = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
    const val TRIUMPHAL_ARCH      = "B2C3D4E5-F6A7-8901-BCDE-F12345678901"
    const val RED_SQUARE          = "C3D4E5F6-A7B8-9012-CDEF-123456789012"

    // Единый список — добавляй новые маяки только сюда,
    // фильтры сканера подтянутся автоматически
    val ALL = listOf(ZNAMENSKY_CATHEDRAL, TRIUMPHAL_ARCH, RED_SQUARE)
}

data class Landmark(
    val name: String,
    val emoji: String,
    val description: String,
    val fact: String,
    val year: String
)

val landmarksDb: Map<String, Landmark> = mapOf(
    BeaconUuids.ZNAMENSKY_CATHEDRAL to Landmark(
        name = "Знаменский собор",
        emoji = "⛪",
        description = "Главный православный храм Курска, построен в 1816 году на месте явления Курской Коренной иконы Божией Матери.",
        fact = "К 1000-летию Курска собор прошёл масштабную реставрацию. Колокольня высотой 52 метра видна из любой точки города.",
        year = "1816"
    ),
    BeaconUuids.TRIUMPHAL_ARCH to Landmark(
        name = "Триумфальная арка",
        emoji = "🏛️",
        description = "Возведена в честь победы в Курской битве 1943 года — крупнейшего танкового сражения в истории человечества.",
        fact = "К 1000-летию Курска арка была полностью отреставрирована. Высота сооружения составляет 24 метра.",
        year = "1943"
    ),
    BeaconUuids.RED_SQUARE to Landmark(
        name = "Красная площадь",
        emoji = "🏙️",
        description = "Главная площадь Курска, административный и культурный центр города на протяжении нескольких столетий.",
        fact = "Площадь заложена одновременно с основанием города в 1032 году. За 1000 лет здесь проходили главные события истории Курска.",
        year = "1032"
    )
)
