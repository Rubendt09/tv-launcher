package com.rubendt.tvlauncher

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val Bg = Color(0xFF0A0E17)
private val SidebarBg = Color(0xFF0D121D)
private val Card = Color(0xFF151B2B)
private val Accent = Color(0xFF2A3A66)
private val Muted = Color(0xFF9AA3B8)


@Composable
fun launcherScreen() {
    BackHandler(true) {}
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<TvApp>()) }
    var continuing by remember { mutableStateOf(emptyList<Content>()) }
    var recommended by remember { mutableStateOf(emptyList<Content>()) }
    var selected by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumeTick) {
        captureActiveMedia(context)
        apps = loadApps(context)
        continuing = buildContinuing(context, apps)
        recommended = buildRecommended(context, apps)
        recommended = coroutineScope {
            recommended.map { c ->
                async {
                    val (banner, bg) = loadImages(c.title)
                    c.copy(imageUrl = bg ?: c.imageUrl, wideUrl = banner, info = loadShowInfo(c.title))
                }
            }.awaitAll()
        }
    }
    val app = apps.getOrNull(selected)
    val hero = (continuing + recommended).firstOrNull { it.app == null && it.packageName == app?.packageName }

    Row(Modifier.fillMaxSize().background(Bg)) {
        sidebar(apps, onSelected = { selected = it }, Modifier.width(280.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            header()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item { hero(app, hero, context) }
                if (continuing.isNotEmpty()) item { contentRow("Continuar viendo", continuing, context) }
                if (recommended.isNotEmpty()) item { contentRow("Recomendados para ti", recommended, context) }
            }
        }
    }
}

@Composable
fun header() {
    val context = LocalContext.current
    var time by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf<Weather?>(null) }
    val account = remember { loadAccount(context) }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            val now = System.currentTimeMillis()
            time = fmt.format(Date(now))
            delay((60_000 - now % 60_000).milliseconds)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            weather = loadWeather()
            delay((30 * 60_000L).milliseconds)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        weather?.let {
            Spacer(Modifier.width(24.dp))
            Text(it.glyph, fontSize = 24.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("${it.temp}\u00B0C", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(it.city, fontSize = 11.sp, color = Muted)
            }
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF3558E0)), contentAlignment = Alignment.Center) {
            Text(account.initial, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(account.name, fontSize = 14.sp)
        Spacer(Modifier.width(16.dp))
        focusSurface(
            onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            shape = CircleShape,
            modifier = Modifier.size(40.dp),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("\u2699", fontSize = 22.sp) } }
    }
}

@Composable
fun sidebar(apps: List<TvApp>, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(SidebarBg).padding(horizontal = 16.dp, vertical = 20.dp)) {
        Text(
            "Tu TV",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)
        )
        appList(apps, onSelected, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun appList(apps: List<TvApp>, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) first.requestFocus() }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        itemsIndexed(apps, key = { _, a -> a.packageName }) { i, a ->
            val letter = a.label.first().uppercaseChar()
            val isFirstOfLetter = i == 0 || apps[i - 1].label.first().uppercaseChar() != letter
            Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(24.dp)) {
                    if (isFirstOfLetter) Text(letter.toString(), fontSize = 12.sp, color = Muted)
                }
                focusSurface(
                    onClick = { context.startActivity(a.launchIntent) },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { if (it.isFocused) onSelected(i) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            remember(a) { a.icon.toBitmap(96, 96).asImageBitmap() },
                            null,
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Text(
                            a.label,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun hero(app: TvApp?, content: Content?, context: Context) {
    focusSurface(
        onClick = { launch(context, content, app) },
        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 12.dp).fillMaxWidth().height(300.dp),
        shape = RoundedCornerShape(20.dp),
        idle = Card,
    ) {
        var landscape by remember(content?.imageUrl) { mutableStateOf<Boolean?>(null) }
        when {
            content?.wideUrl != null -> AsyncImage(
                content.wideUrl,
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            content?.imageUrl != null -> AsyncImage(
                content.imageUrl, null,
                modifier = if (landscape == true) Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)
                    .size(320.dp, 180.dp).clip(RoundedCornerShape(12.dp))
                else Modifier.align(Alignment.CenterEnd).padding(end = 64.dp).size(180.dp, 240.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                onSuccess = { st ->
                    val dim = st.painter.intrinsicSize
                    if (dim.width > 0 && dim.height > 0) landscape = dim.width >= dim.height
                })

            else -> app?.let {
                Image(
                    remember(it) { (it.banner ?: it.icon).toBitmap(640, 360).asImageBitmap() },
                    null,
                    Modifier.align(Alignment.CenterEnd).padding(end = 64.dp).size(240.dp, 135.dp)
                )
            }
        }
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Bg, Bg.copy(alpha = 0.6f), Color.Transparent)))
        )
        Column(Modifier.align(Alignment.CenterStart).padding(start = 32.dp).width(420.dp)) {
            Text(
                content?.title ?: app?.label.orEmpty(),
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2
            )
            val meta = listOfNotNull(
                content?.info?.year,
                content?.info?.seasons?.takeIf { it > 0 }?.let { if (it == 1) "1 temporada" else "$it temporadas" },
                content?.info?.status
            ).joinToString("  \u2022  ")
            if (meta.isNotBlank()) Text(meta, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
            content?.subtitle?.takeIf { it.isNotBlank() && it != app?.label }?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            content?.info?.news?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7C94FF),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun launch(context: Context, content: Content?, app: TvApp?) {
    content?.let { recordWatch(context, it) }
    val intent = content?.intent ?: app?.launchIntent ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { app?.let { context.startActivity(it.launchIntent) } }
}

@Composable
fun contentRow(title: String, items: List<Content>, context: Context) {
    Column {
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 32.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.id }) { c ->
                focusSurface(onClick = { launch(context, c, null) }, modifier = Modifier.width(220.dp)) {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().height(124.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF243056), Color(0xFF3B1E4A))))
                        ) {
                            c.imageUrl?.let {
                                AsyncImage(
                                    it,
                                    null,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                                ?: c.app?.let {
                                    Image(remember(it) {
                                        (it.banner ?: it.icon).toBitmap(440, 248).asImageBitmap()
                                    }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            c.progress?.let {
                                Box(
                                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                                        .background(Color(0x66FFFFFF))
                                )
                                Box(
                                    Modifier.align(Alignment.BottomStart).fillMaxWidth(it).height(4.dp)
                                        .background(Color(0xFFE53935))
                                )
                            }
                        }
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                c.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (c.subtitle.isNotBlank()) Text(
                                c.subtitle,
                                fontSize = 11.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun focusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    idle: Color = Color.Transparent,
    focusedColor: Color = Accent,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = idle, focusedContainerColor = focusedColor, pressedContainerColor = focusedColor,
            contentColor = Color.White, focusedContentColor = if (idle == Color.White) Color.Black else Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color(0xFF7C94FF)))),
    ) { Box(content = content) }
}
