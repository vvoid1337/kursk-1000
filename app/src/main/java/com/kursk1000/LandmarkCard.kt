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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage

// Coil грузит фото, Media3 играет видео - в сеть сам не ходит.
private val MediaShape = RoundedCornerShape(14.dp)
private val SidePad = 20.dp

@Composable
fun LandmarkCard(landmark: Landmark, onClose: () -> Unit) {
    val sectionExpanded = remember(landmark.uuid) { mutableStateMapOf<Int, Boolean>() }
    var fullscreenMedia by remember(landmark.uuid) { mutableStateOf<MediaItem?>(null) }
    val close = stringResource(R.string.close)

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
                // 404 обложки не ломает карточку - показывается эмодзи-заглушка
                landmark.coverImage?.let { url ->
                    item(key = "cover") {
                        CoverHero(url = url, landmarkName = landmark.name)
                    }
                }

                item(key = "header") { HeaderBlock(landmark) }

                if (landmark.summary.isNotBlank()) {
                    item(key = "summary") {
                        Text(
                            text = landmark.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = SidePad, end = SidePad, top = 16.dp),
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
                    val expanded = sectionExpanded[index] ?: (index == 0) // первая секция раскрыта по умолчанию
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
                        text = stringResource(R.string.anniversary),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = SidePad, end = SidePad, top = 24.dp),
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .semantics { contentDescription = close },
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

// --- Шапка ---

@Composable
private fun CoverHero(url: String, landmarkName: String) {
    // SubcomposeAsyncImage: success рисуется сам, нужны только заглушки
    SubcomposeAsyncImage(
        model = url,
        contentDescription = landmarkName,
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
        error = { CoverFallback() },
    )
}

@Composable
private fun CoverFallback() {
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
        Text(text = "📍", fontSize = 72.sp)
    }
}

@Composable
private fun HeaderBlock(landmark: Landmark) {
    Column(modifier = Modifier.padding(start = SidePad, end = SidePad, top = 18.dp)) {
        Text(
            text = landmark.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Год показываем только если нет подзаголовка - в нём даты уже есть
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
                    text = stringResource(R.string.founded_year, landmark.year),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Секции (раскрывающиеся) ---

@Composable
private fun SectionItem(section: Section, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidePad, end = SidePad, top = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = section.body.isNotBlank(), onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.title.ifBlank { stringResource(R.string.details) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (section.body.isNotBlank()) {
                // Unicode-шеврон, поворот 0→180° — без зависимости material-icons
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

// --- Панель фактов ---

@Composable
private fun FactsPanel(facts: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SidePad, end = SidePad, top = 24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.facts_title),
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

// --- Галерея ---

@Composable
private fun GallerySection(
    gallery: List<MediaItem>,
    onOpenMedia: (MediaItem) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Text(
            text = stringResource(R.string.gallery),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SidePad, end = SidePad, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SidePad),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // src в авторском контенте может повториться - берём составной ключ с индексом
            itemsIndexed(gallery, key = { index, item -> "${index}_${item.src}" }) { _, mediaItem ->
                Column(modifier = Modifier.width(280.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MediaShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenMedia(mediaItem) },
                    ) {
                        when (mediaItem.type) {
                            MediaType.IMAGE -> GalleryImage(
                                url = mediaItem.src,
                                contentDescription = mediaItem.caption.ifBlank { null },
                            )
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
private fun GalleryImage(url: String, contentDescription: String?) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
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

@Composable
private fun GalleryVideoPoster() {
    val playVideo = stringResource(R.string.play_video)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x66000000))
                .semantics { contentDescription = playVideo },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "▶", color = Color.White, fontSize = 26.sp)
        }
    }
}

// --- Полноэкранный просмотр медиа ---

@Composable
private fun FullscreenMediaViewer(item: MediaItem, onDismiss: () -> Unit) {
    val close = stringResource(R.string.close)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
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

            // Крестик в безопасной зоне - не уезжает под вырез в альбомной ориентации
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding(),
            ) {
                // Подпись только для фото - у видео внизу свои контролы плеера
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
                        .padding(8.dp)
                        .semantics { contentDescription = close },
                ) {
                    Text(text = "✕", color = Color.White, fontSize = 22.sp)
                }
            }
        }
    }
}

/**
 * Настраивает окно диалога под иммерсивный полноэкранный просмотр: edge-to-edge,
 * без статус-бара и серой подложки у выреза. Окно Activity не трогаем.
 */
@Composable
private fun ImmersiveFullscreenWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        // Серые полосы — это фон Activity, просвечивающий там где окно не дотягивается.
        // Три меры: растягиваем окно, убираем затемнение фона и красим зазор в чёрный.
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // На части прошивок система рисует полупрозрачную подложку за панелями —
        // убираем её прозрачными цветами + отключаем contrast enforcement на Q+
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
            // Не даём утащить фото за пределы экрана
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
 * Встроенный плеер. Живёт только пока открыт просмотрщик - release() в onDispose.
 * На фоне (ON_PAUSE) ставим на паузу: в split-screen хост уходит в PAUSED, не STOPPED.
 */
@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(url: String) {
    val context = LocalContext.current
    var hasError by remember(url) { mutableStateOf(false) }
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
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exoPlayer) {
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
            // XML нужен для TextureView: SurfaceView в диалоге растягивает видео на 12+/14
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
                // Сбой обычно сетевой и восстановим - по тапу перезапрашиваем
                modifier = Modifier.clickable {
                    hasError = false
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                },
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
