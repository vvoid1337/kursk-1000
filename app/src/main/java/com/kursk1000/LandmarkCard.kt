package com.kursk1000

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
<<<<<<< HEAD
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
=======
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
<<<<<<< HEAD
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
<<<<<<< HEAD
import com.kursk1000.ui.theme.Kursk1000Theme
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3

// Богатая вики-карточка достопримечательности. Контент кэшируется целиком, поэтому
// LandmarkCard рендерит готовую модель Landmark (см. Landmark.kt) и не ходит в сеть
// сам. Фото грузит Coil; видео проигрывает встроенный Media3-плеер с дисковым кешем.

private val MediaShape = RoundedCornerShape(14.dp)
private const val SidePad = 20

@Composable
fun LandmarkCard(landmark: Landmark, onClose: () -> Unit) {
    // Состояние карточки сбрасывается при смене достопримечательности (uuid).
    val sectionExpanded = remember(landmark.uuid) { mutableStateMapOf<Int, Boolean>() }
    // Открытый во весь экран элемент галереи — фото или видео (null — закрыт).
    var fullscreenMedia by remember(landmark.uuid) { mutableStateOf<MediaItem?>(null) }
<<<<<<< HEAD
    val close = stringResource(R.string.close)
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3

    // Карточка-окно во весь экран без скруглений (прежние скруглённые углы остались
    // от старого «плавающего» оформления). Закрывается только крестиком — см. onClose.
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            // Обложка — только если у объекта вообще задана. 404 (ассеты ещё не залиты)
            // переживаем как эмодзи-заглушку, а не «битую картинку».
            landmark.coverImage?.let { url ->
                item(key = "cover") {
<<<<<<< HEAD
                    CoverHero(url = url, emoji = landmark.emoji, landmarkName = landmark.name)
=======
                    CoverHero(url = url, emoji = landmark.emoji)
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                }
            }

            item(key = "header") { HeaderBlock(landmark) }

            if (landmark.summary.isNotBlank()) {
                item(key = "summary") {
                    Text(
                        text = landmark.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = SidePad.dp, end = SidePad.dp, top = 16.dp),
                    )
                }
            }

            if (landmark.gallery.isNotEmpty()) {
                item(key = "gallery") {
                    GallerySection(
                        gallery = landmark.gallery,
                        onOpenMedia = { fullscreenMedia = it },
                    )
                }
            }

            itemsIndexed(landmark.sections, key = { index, _ -> "section_$index" }) { index, section ->
                val expanded = sectionExpanded[index] ?: (index == 0) // первая секция раскрыта
                SectionItem(
                    section = section,
                    expanded = expanded,
                    onToggle = { sectionExpanded[index] = !expanded },
                )
            }

            if (landmark.facts.isNotEmpty()) {
                item(key = "facts") { FactsPanel(landmark.facts) }
            }

            item(key = "footer") {
                Text(
<<<<<<< HEAD
                    text = stringResource(R.string.anniversary),
=======
                    text = "К 1000-летию Курска",
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = SidePad.dp, end = SidePad.dp, top = 24.dp),
                )
            }
        }

            // Крестик поверх контента: единственный способ закрыть карточку. Подложка-скрим,
            // чтобы глиф был виден и над фото-обложкой, и над светлым фоном.
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
<<<<<<< HEAD
                    .padding(8.dp)
                    .semantics { contentDescription = close },
=======
                    .padding(8.dp),
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }

    fullscreenMedia?.let { item ->
        FullscreenMediaViewer(item = item, onDismiss = { fullscreenMedia = null })
    }
}

// --- Шапка ---------------------------------------------------------------

@Composable
<<<<<<< HEAD
private fun CoverHero(url: String, emoji: String, landmarkName: String) {
=======
private fun CoverHero(url: String, emoji: String) {
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
    // Слотовый overload Coil: success-картинка рисуется сама, нам нужны только
    // заглушки загрузки и ошибки (в Coil 3 painter.state — это StateFlow, а не State,
    // поэтому ветвление по нему вручную не работает).
    SubcomposeAsyncImage(
        model = url,
<<<<<<< HEAD
        contentDescription = landmarkName,
=======
        contentDescription = null,
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        error = { CoverFallback(emoji) },
    )
}

/** Заглушка обложки при отсутствии/404 фото: мягкий градиент + эмодзи как мотив. */
@Composable
private fun CoverFallback(emoji: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.primaryContainer,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji.ifBlank { "📍" }, fontSize = 72.sp)
    }
}

@Composable
private fun HeaderBlock(landmark: Landmark) {
    Column(modifier = Modifier.padding(start = SidePad.dp, end = SidePad.dp, top = 18.dp)) {
        val title = listOf(landmark.emoji, landmark.name)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Подзаголовок уже несёт даты; год показываем отдельной строкой только если
        // подзаголовка нет — чтобы не дублировать.
        when {
            landmark.subtitle.isNotBlank() -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = landmark.subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            landmark.year.isNotBlank() -> {
                Spacer(Modifier.height(6.dp))
                Text(
<<<<<<< HEAD
                    text = stringResource(R.string.founded_year, landmark.year),
=======
                    text = "📅 Основан в ${landmark.year} году",
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Секции (раскрывающиеся) ---------------------------------------------

@Composable
private fun SectionItem(section: Section, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidePad.dp, end = SidePad.dp, top = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = section.body.isNotBlank(), onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
<<<<<<< HEAD
                text = section.title.ifBlank { stringResource(R.string.details) },
=======
                text = section.title.ifBlank { "Подробнее" },
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (section.body.isNotBlank()) {
                // Шеврон без зависимости material-icons: Unicode-глиф, поворот 0→180°.
                Text(
                    text = "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded && section.body.isNotBlank()) {
            Text(
                text = section.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// --- Панель фактов -------------------------------------------------------

@Composable
private fun FactsPanel(facts: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidePad.dp, end = SidePad.dp, top = 24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
<<<<<<< HEAD
                text = stringResource(R.string.facts_title),
=======
                text = "✨ Интересные факты",
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            facts.forEachIndexed { index, fact ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

// --- Галерея -------------------------------------------------------------

@Composable
private fun GallerySection(
    gallery: List<MediaItem>,
    onOpenMedia: (MediaItem) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Text(
<<<<<<< HEAD
            text = stringResource(R.string.gallery),
=======
            text = "Галерея",
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SidePad.dp, end = SidePad.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SidePad.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ключ с индексом: src в авторском контенте может повториться, а
            // одинаковые ключи в LazyRow роняют композицию (IllegalArgumentException).
            itemsIndexed(gallery, key = { index, item -> "${index}_${item.src}" }) { _, mediaItem ->
                Column(modifier = Modifier.width(280.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MediaShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            // И фото, и видео открываются в полноэкранном просмотре по тапу.
                            .clickable { onOpenMedia(mediaItem) },
                    ) {
                        when (mediaItem.type) {
<<<<<<< HEAD
                            MediaType.IMAGE -> GalleryImage(
                                url = mediaItem.src,
                                contentDescription = mediaItem.caption.ifBlank { null },
                            )
=======
                            MediaType.IMAGE -> GalleryImage(url = mediaItem.src)
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                            MediaType.VIDEO -> GalleryVideoPoster()
                        }
                    }
                    if (mediaItem.caption.isNotBlank()) {
                        Text(
                            text = mediaItem.caption,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
<<<<<<< HEAD
private fun GalleryImage(url: String, contentDescription: String?) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
=======
private fun GalleryImage(url: String) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🖼", fontSize = 40.sp)
            }
        },
    )
}

/** Превью видео в галерее: заглушка с кнопкой play; по тапу открывается полноэкранный плеер. */
@Composable
private fun GalleryVideoPoster() {
<<<<<<< HEAD
    val playVideo = stringResource(R.string.play_video)
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
<<<<<<< HEAD
                .background(Color(0x66000000))
                .semantics { contentDescription = playVideo },
=======
                .background(Color(0x66000000)),
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "▶", color = Color.White, fontSize = 26.sp)
        }
    }
}

// --- Полноэкранный просмотр медиа (фото и видео) -------------------------

/** Полноэкранный просмотрщик элемента галереи: фото с пинч-зумом или видео в плеере. */
@Composable
private fun FullscreenMediaViewer(item: MediaItem, onDismiss: () -> Unit) {
<<<<<<< HEAD
    val close = stringResource(R.string.close)
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Делаем окно диалога по-настоящему полноэкранным (без статус-бара и без серой
        // полосы у выреза камеры).
        ImmersiveFullscreenWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (item.type) {
                MediaType.IMAGE -> ZoomableImage(url = item.src, caption = item.caption)
                MediaType.VIDEO -> FullscreenVideoPlayer(url = item.src)
            }

            // Подпись и крестик держим в безопасной зоне — чтобы крестик не уезжал
            // под вырез камеры в альбомной ориентации (само медиа остаётся во весь экран).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding(),
            ) {
                // Подпись — только для фото: у видео внизу свои контролы плеера (перемотка).
                if (item.type == MediaType.IMAGE && item.caption.isNotBlank()) {
                    Text(
                        text = item.caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
<<<<<<< HEAD
                        .padding(8.dp)
                        .semantics { contentDescription = close },
=======
                        .padding(8.dp),
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
                ) {
                    Text(text = "✕", color = Color.White, fontSize = 22.sp)
                }
            }
        }
    }
}

/**
 * Настраивает окно диалога под иммерсивный полноэкранный просмотр: рисуем edge-to-edge,
 * прячем статус- и навигационную панели и разрешаем заходить в область выреза камеры
 * (без этого в альбомной ориентации сбоку от выреза остаётся серая полоса). Трогаем
 * только окно диалога — окно Activity не меняется, после закрытия панели вернутся сами.
 */
@Composable
private fun ImmersiveFullscreenWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        // Серые полосы сверху/снизу — это затемнённый светлый фон Activity (тема Light),
        // просвечивающий там, где окно диалога не дотягивается до зон статус- и
        // навигационной панелей. На части прошивок окно ложится только в «контентную»
        // область, оставляя эти полосы. Лечим тремя независимыми мерами, чтобы серому
        // негде было появиться:
        //  1) растягиваем окно на весь экран (MATCH_PARENT) и рисуем edge-to-edge;
        //  2) убираем затемнение фона (FLAG_DIM_BEHIND) — именно оно красит полосы в серый;
        //  3) даём окну сплошной чёрный фон — любой возможный зазор будет чёрным, как и
        //     сам просмотрщик, а не серым.
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Делаем сами панели и их подложку прозрачными. Иначе на части прошивок (особенно
        // с 3-кнопочной навигацией) система рисует за статус- и навигационной панелями
        // полупрозрачную серую подложку — те самые серые полосы сверху и снизу поверх
        // фото/видео. statusBar/navigationBarColor + отключение «контрастной подложки»
        // на Android 10+ убирают её.
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ZoomableImage(url: String, caption: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale > 1f) {
            // Ограничиваем сдвиг, чтобы зум-фото нельзя было утащить за пределы экрана.
            val maxX = size.width * (scale - 1f) / 2f
            val maxY = size.height * (scale - 1f) / 2f
            Offset(
                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                (offset.y + panChange.y).coerceIn(-maxY, maxY),
            )
        } else {
            Offset.Zero
        }
    }

    AsyncImage(
        model = url,
        contentDescription = caption.ifBlank { null },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .transformable(transformState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
}

/**
 * Встроенный плеер. Существует, только пока открыт просмотрщик, поэтому ресурс
 * детерминированно освобождается в onDispose. На уходе приложения в фон (ON_STOP) ставим
 * на паузу. Источник кеширующий (см. buildCachingPlayer) — видео ложится на диск по мере
 * проигрывания, перемотка и повторный просмотр берутся локально.
 */
@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(url: String) {
    val context = LocalContext.current
<<<<<<< HEAD
    var hasError by remember(url) { mutableStateOf(false) }
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
    val exoPlayer = remember(url) {
        buildCachingPlayer(context).apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exoPlayer) {
<<<<<<< HEAD
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            // Инфлейтим из XML, чтобы получить PlayerView на TextureView (surface_type
            // задаётся только в разметке): SurfaceView в диалоге растягивает видео на 12+/14.
            factory = { ctx ->
                (LayoutInflater.from(ctx)
                    .inflate(R.layout.view_fullscreen_player, null) as PlayerView)
                    .apply { player = exoPlayer }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )
        if (hasError) {
            Surface(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.video_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LandmarkCardPreview() {
    Kursk1000Theme {
        LandmarkCard(landmark = sampleLandmark(), onClose = {})
    }
}

private fun sampleLandmark(): Landmark =
    Landmark(
        uuid = "00000000-0000-0000-0000-000000000001",
        name = "Знаменский собор",
        emoji = "⛪",
        subtitle = "Исторический центр Курска",
        year = "1816",
        summary = "Одна из узнаваемых доминант города с богатой историей и выразительной архитектурой.",
        coverImage = null,
        sections = listOf(
            Section("История", "Собор связан с ключевыми страницами городской жизни и сохраняет память нескольких эпох."),
            Section("Архитектура", "Классические формы сочетаются с торжественным силуэтом и светлым внутренним пространством."),
        ),
        facts = listOf(
            "Карточка поддерживает длинные тексты и раскрывающиеся секции.",
            "Галерея может включать фотографии и видео.",
        ),
        gallery = listOf(
            MediaItem(MediaType.IMAGE, "https://example.com/photo.jpg", "Фасад собора"),
            MediaItem(MediaType.VIDEO, "https://example.com/video.mp4", "Короткое видео"),
        ),
        publicKey = "sample",
    )
=======
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        // Инфлейтим из XML, чтобы получить PlayerView на TextureView (surface_type
        // задаётся только в разметке): SurfaceView в диалоге растягивает видео на 12+/14.
        factory = { ctx ->
            (LayoutInflater.from(ctx)
                .inflate(R.layout.view_fullscreen_player, null) as PlayerView)
                .apply { player = exoPlayer }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.player = null },
    )
}
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
