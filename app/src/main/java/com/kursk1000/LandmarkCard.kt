package com.kursk1000

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage

// Богатая вики-карточка достопримечательности. Контент кэшируется целиком, поэтому
// LandmarkCard рендерит готовую модель Landmark (см. ApiClient.kt) и не ходит в сеть
// сам — кроме мультимедиа (Coil грузит фото, Media3 проигрывает видео из галереи).

private val CardShape = RoundedCornerShape(20.dp)
private val MediaShape = RoundedCornerShape(14.dp)
private const val SidePad = 20

@Composable
fun LandmarkCard(landmark: Landmark) {
    // Состояние карточки сбрасывается при смене достопримечательности (uuid).
    val sectionExpanded = remember(landmark.uuid) { mutableStateMapOf<Int, Boolean>() }
    // Какое видео сейчас активно (играет) — гарантирует «не больше одного плеера разом».
    var activeVideoSrc by remember(landmark.uuid) { mutableStateOf<String?>(null) }
    // Открытое во весь экран фото галереи (null — просмотрщик закрыт).
    var fullscreen by remember(landmark.uuid) { mutableStateOf<MediaItem?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.94f)
            .padding(horizontal = 12.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            // Обложка — только если у объекта вообще задана. 404 (ассеты ещё не залиты)
            // переживаем как эмодзи-заглушку, а не «битую картинку».
            landmark.coverImage?.let { url ->
                item(key = "cover") {
                    CoverHero(url = url, emoji = landmark.emoji)
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
                        activeVideoSrc = activeVideoSrc,
                        onActivateVideo = { activeVideoSrc = it },
                        onOpenImage = { fullscreen = it },
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
                    text = "К 1000-летию Курска",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = SidePad.dp, end = SidePad.dp, top = 24.dp),
                )
            }
        }
    }

    fullscreen?.let { item ->
        FullscreenImageViewer(item = item, onDismiss = { fullscreen = null })
    }
}

// --- Шапка ---------------------------------------------------------------

@Composable
private fun CoverHero(url: String, emoji: String) {
    // Слотовый overload Coil: success-картинка рисуется сама, нам нужны только
    // заглушки загрузки и ошибки (в Coil 3 painter.state — это StateFlow, а не State,
    // поэтому ветвление по нему вручную не работает).
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
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
                    text = "📅 Основан в ${landmark.year} году",
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
                text = section.title.ifBlank { "Подробнее" },
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
                text = "✨ Интересные факты",
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
    activeVideoSrc: String?,
    onActivateVideo: (String) -> Unit,
    onOpenImage: (MediaItem) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Text(
            text = "Галерея",
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
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        when (mediaItem.type) {
                            MediaType.IMAGE -> GalleryImage(
                                url = mediaItem.src,
                                onClick = { onOpenImage(mediaItem) },
                            )
                            MediaType.VIDEO -> GalleryVideo(
                                url = mediaItem.src,
                                isActive = activeVideoSrc == mediaItem.src,
                                onActivate = { onActivateVideo(mediaItem.src) },
                            )
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
private fun GalleryImage(url: String, onClick: () -> Unit) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
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

@Composable
private fun GalleryVideo(url: String, isActive: Boolean, onActivate: () -> Unit) {
    if (isActive) {
        InlineVideoPlayer(url = url, modifier = Modifier.fillMaxSize())
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onActivate),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "▶", color = Color.White, fontSize = 26.sp)
            }
        }
    }
}

/**
 * Встроенный плеер. Существует в композиции только пока видео активно (одно за раз),
 * поэтому ресурс детерминированно освобождается в onDispose. На уходе приложения в фон
 * (ON_STOP) ставим на паузу, чтобы звук не играл из кармана.
 */
@OptIn(UnstableApi::class)
@Composable
private fun InlineVideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(url) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier,
        onRelease = { it.player = null },
    )
}

// --- Полноэкранный просмотр фото -----------------------------------------

@Composable
private fun FullscreenImageViewer(item: MediaItem, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 4f)
            offset = if (scale > 1f) offset + panChange else Offset.Zero
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEB000000)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = item.src,
                contentDescription = item.caption.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformState)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )

            if (item.caption.isNotBlank()) {
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
                    .padding(8.dp),
            ) {
                Text(text = "✕", color = Color.White, fontSize = 22.sp)
            }
        }
    }
}