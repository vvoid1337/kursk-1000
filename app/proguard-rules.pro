# Правила R8/ProGuard для release-сборки.
#
<<<<<<< HEAD
# Shrinking включён для release. Добавляйте сюда только keep-правила,
# подтверждённые release-сборкой/ручной проверкой, чтобы не раздувать APK заранее.
=======
# Минификация сейчас выключена (см. android.buildTypes.release.optimization.enable = false
# в app/build.gradle.kts), поэтому эти правила пока не применяются. Файл — заготовка:
# когда включим shrinking, сюда лягут keep-правила.
#
# Что почти наверняка понадобится при включении R8:
#  - Media3/ExoPlayer: часть классов грузится рефлексией (renderers, extractors).
#  - Coil 3 / OkHttp: keep-правила обычно поставляются с библиотеками, но проверить.
#  - Модели, если перейдём на kotlinx.serialization, потребуют @Keep/serializer-правил.
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
#
# Полезные дефолты можно посмотреть в getDefaultProguardFile("proguard-android-optimize.txt").

# Сохраняем строки номеров для читаемых стектрейсов в релизных крэшах.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
