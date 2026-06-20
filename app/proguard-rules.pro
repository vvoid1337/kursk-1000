# Правила R8/ProGuard для release-сборки.
#
# Shrinking включён для release. Добавляйте сюда только keep-правила,
# подтверждённые release-сборкой/ручной проверкой, чтобы не раздувать APK заранее.
#
# Полезные дефолты можно посмотреть в getDefaultProguardFile("proguard-android-optimize.txt").

# Сохраняем строки номеров для читаемых стектрейсов в релизных крэшах.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
