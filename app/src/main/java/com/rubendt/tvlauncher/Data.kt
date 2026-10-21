package com.rubendt.tvlauncher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.accounts.AccountManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.net.URL

data class Content(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val imageUrl: String?,
    val packageName: String?,
    val progress: Float?,
    val intent: Intent?,
    val app: TvApp? = null,
)

data class Weather(val temp: Int, val glyph: String, val city: String)

data class Account(val name: String, val initial: String)

const val CITY = "Lima"
const val LAT = -12.05
const val LON = -77.04

suspend fun loadWatchNext(context: Context): List<Content> = withContext(Dispatchers.IO) {
    runCatching {
        val out = mutableListOf<Content>()
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val p = WatchNextProgram.fromCursor(c)
                val dur = p.durationMillis
                out += Content(
                    id = p.id.toString(),
                    title = p.title ?: continue,
                    subtitle = p.episodeTitle ?: p.description.orEmpty(),
                    description = p.description.orEmpty(),
                    imageUrl = (p.posterArtUri ?: p.thumbnailUri)?.toString(),
                    packageName = p.packageName,
                    progress = if (dur > 0 && p.lastPlaybackPositionMillis > 0) p.lastPlaybackPositionMillis.toFloat() / dur else null,
                    intent = p.intent,
                )
            }
        }
        out
    }.getOrDefault(emptyList())
}

suspend fun loadWeather(): Weather? = withContext(Dispatchers.IO) {
    runCatching {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code"
        val cur = JSONObject(URL(url).readText()).getJSONObject("current")
        val glyph = when (cur.getInt("weather_code")) {
            0 -> "\u2600"
            1, 2 -> "\u26C5"
            3, 45, 48 -> "\u2601"
            in 51..67, in 80..82 -> "\u2602"
            in 71..77 -> "\u2744"
            else -> "\u26A1"
        }
        Weather(Math.round(cur.getDouble("temperature_2m")).toInt(), glyph, CITY)
    }.getOrNull()
}

fun loadAccount(context: Context): Account {
    val email = runCatching { AccountManager.get(context).getAccountsByType("com.google").firstOrNull()?.name }.getOrNull()
    val name = email?.substringBefore('@')?.replaceFirstChar { it.uppercase() } ?: "Invitado"
    return Account(name, name.first().uppercase())
}

val ProviderCode: Map<String, String> = mapOf(
    "com.netflix.ninja" to "nfx",
    "com.wbd.stream" to "hbm",
    "com.disney.disneyplus" to "dnp",
    "com.amazon.amazonvideo.livingroom" to "prv",
    "com.crunchyroll.crunchyroid" to "cru",
    "com.apple.atve.androidtv.appletv" to "atp",
)

suspend fun loadRecentUsage(context: Context, days: Int = 14): Map<String, Long> = withContext(Dispatchers.IO) {
    runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(System.currentTimeMillis() - days * 86_400_000L, System.currentTimeMillis())
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || e.eventType == 23) last.merge(e.packageName, e.timeStamp, ::maxOf)
        }
        last.filterKeys { it in ProviderCode }
    }.getOrDefault(emptyMap())
}

private fun lastSeenLabel(lastUsed: Long): String {
    val hours = (System.currentTimeMillis() - lastUsed) / 3_600_000
    return when {
        hours < 1 -> "Visto hace un momento"
        hours < 24 -> "Visto hace ${hours}h"
        else -> "Visto hace ${hours / 24}d"
    }
}

private const val JW_QUERY =
    "query(\$c:Country!,\$f:Int!,\$p:[String!]){popularTitles(country:\$c,first:\$f,sortBy:POPULAR,filter:{packages:\$p,includeTitlesWithoutUrl:true}){edges{node{content(country:\$c,language:es){title posterUrl}}}}}"

private fun jwImage(template: String?): String? =
    template?.let { "https://images.justwatch.com" + it.replace("{profile}", "s592").replace("{format}", "webp") }

private suspend fun fetchProvider(pkg: String, code: String, perProvider: Int): List<Content> {
    val body = JSONObject()
        .put("query", JW_QUERY)
        .put("variables", JSONObject().put("c", "PE").put("f", perProvider).put("p", JSONArray(listOf(code))))
        .toString()
    repeat(3) { attempt ->
        val list = runCatching {
            val conn = URL("https://apis.justwatch.com/graphql").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val edges = JSONObject(conn.inputStream.bufferedReader().readText())
                .optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: JSONArray()
            buildList {
                for (i in 0 until edges.length()) {
                    val c = edges.getJSONObject(i).getJSONObject("node").getJSONObject("content")
                    add(Content(
                        id = "$pkg#" + c.getString("title"), title = c.getString("title"),
                        subtitle = "", description = "", imageUrl = jwImage(c.optString("posterUrl")),
                        packageName = pkg, progress = null, intent = null,
                    ))
                }
            }
        }.onFailure { android.util.Log.w("TvLauncher", "justwatch $code attempt $attempt failed", it) }.getOrNull()
        if (!list.isNullOrEmpty()) return list.distinctBy { it.title }
        kotlinx.coroutines.delay(2_000L * (attempt + 1))
    }
    return emptyList()
}

suspend fun loadFreshShows(perProvider: Int = 10): Map<String, List<Content>> =
    coroutineScope {
        ProviderCode.map { (pkg, code) ->
            async(Dispatchers.IO) { pkg to fetchProvider(pkg, code, perProvider) }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
    }

fun recordWatch(context: Context, c: Content) {
    if (c.packageName == null || c.app != null) return
    recordWatch(context, c.packageName, c.title, c.subtitle, c.imageUrl)
}

private fun recordWatch(context: Context, pkg: String, title: String, subtitle: String, imageUrl: String?) {
    runCatching {
        context.getSharedPreferences("watch_history", Context.MODE_PRIVATE).edit()
            .putString(pkg, JSONObject()
                .put("title", title).put("subtitle", subtitle).put("imageUrl", imageUrl)
                .put("t", System.currentTimeMillis()).toString())
            .apply()
    }
}

suspend fun captureActiveMedia(context: Context) = withContext(Dispatchers.IO) {
    runCatching {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(context, MediaTracker::class.java))
            .filter { it.packageName in ProviderCode }
            .forEach { mc ->
                val md = mc.metadata ?: return@forEach
                val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@forEach
                val subtitle = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                var imageUrl: String? = null
                if (art != null) {
                    val f = java.io.File(context.filesDir, "art_${mc.packageName}.png")
                    java.io.FileOutputStream(f).use { art.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                    imageUrl = f.absolutePath
                }
                recordWatch(context, mc.packageName, title, subtitle, imageUrl)
            }
    }
}

private fun recordedWatch(context: Context, pkg: String): Content? = runCatching {
    val raw = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE).getString(pkg, null) ?: return null
    val j = JSONObject(raw)
    Content(
        id = "rec:$pkg", title = j.getString("title"), subtitle = j.optString("subtitle"),
        description = "", imageUrl = j.optString("imageUrl").ifBlank { null },
        packageName = pkg, progress = null, intent = null,
    )
}.getOrNull()

suspend fun buildContinuing(context: Context, apps: List<TvApp>): List<Content> {
    val usage = loadRecentUsage(context)
    val byPkg = apps.associateBy { it.packageName }
    val continuing = loadWatchNext(context).toMutableList()
    continuing += usage.entries.sortedByDescending { it.value }.mapNotNull { (pkg, t) ->
        val a = byPkg[pkg] ?: return@mapNotNull null
        val rec = recordedWatch(context, pkg)
        if (rec != null) rec.copy(
            subtitle = listOf(rec.subtitle, lastSeenLabel(t)).filter { it.isNotBlank() }.joinToString("  \u2022  "),
            intent = a.launchIntent,
        ) else Content(
            id = "app:$pkg", title = a.label, subtitle = lastSeenLabel(t),
            description = "", imageUrl = null, packageName = pkg, progress = null,
            intent = a.launchIntent, app = a,
        )
    }
    return continuing.distinctBy { it.id }
}

suspend fun buildRecommended(context: Context, apps: List<TvApp>): List<Content> {
    val usage = loadRecentUsage(context)
    val byPkg = apps.associateBy { it.packageName }
    val fresh = loadFreshShows()
    val ranked = usage.entries.sortedByDescending { it.value }.map { it.key } +
        ProviderCode.keys.filter { it !in usage.keys }
    val maxTitles = fresh.values.maxOfOrNull { it.size } ?: 0
    val recommended = mutableListOf<Content>()
    for (i in 0 until maxTitles) for (pkg in ranked) {
        val c = fresh[pkg]?.getOrNull(i) ?: continue
        recommended += c.copy(subtitle = byPkg[pkg]?.label ?: c.subtitle, intent = byPkg[pkg]?.launchIntent)
    }
    return recommended.distinctBy { it.title }
}

suspend fun loadBackdrop(title: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val q = URLEncoder.encode(title, "utf-8")
        val arr = JSONArray(URL("https://api.tvmaze.com/search/shows?q=$q").readText())
        if (arr.length() == 0) return@runCatching null
        val id = arr.getJSONObject(0).getJSONObject("show").getInt("id")
        val imgs = JSONArray(URL("https://api.tvmaze.com/shows/$id/images").readText())
        for (i in 0 until imgs.length()) {
            val im = imgs.getJSONObject(i)
            if (im.optString("type") == "background")
                return@runCatching im.getJSONObject("resolutions").getJSONObject("original").getString("url")
        }
        null
    }.getOrNull()
}
