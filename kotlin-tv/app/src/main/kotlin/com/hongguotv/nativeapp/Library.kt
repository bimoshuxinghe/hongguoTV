// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Account session stored in JSON (cookies + x-tt-token are valid for 60 days). */
private object AccountCodec {
    fun encode(s: AccountApi.Session) = org.json.JSONObject().apply {
        put("cookies", s.cookies); put("token", s.token); put("userId", s.userId); put("name", s.name); put("avatar", s.avatar)
    }.toString()
    fun decode(raw: String): AccountApi.Session? = runCatching {
        val o = org.json.JSONObject(raw)
        AccountApi.Session(o.getString("cookies"), o.getString("token"), o.getString("userId"), o.optString("name"), o.optString("avatar"))
    }.getOrNull()
}

private data class Result4(
    val loaded: LibraryIndex,
    val cached: Map<ContentType, CatalogPage>,
    val stamps: Map<ContentType, Long>,
    val account: AccountApi.Session?,
)

/** 注册下发的设备档案（device_register 一次注册长期复用；passport 按「设备是否存在」风控） */
private data class DeviceArchive(val deviceId: String,val installId: String,val cdid: String,val openudid: String,val ttreq: String) {
    companion object {
        fun decode(values: Map<String, Any?>): DeviceArchive? {
            val deviceId=values["deviceId"] as? String ?: return null
            val installId=values["installId"] as? String ?: return null
            val cdid=values["cdid"] as? String ?: return null
            if(deviceId.isEmpty() || installId.isEmpty() || cdid.isEmpty()) return null
            return DeviceArchive(deviceId,installId,cdid,values["openudid"] as? String ?: "",values["ttreq"] as? String ?: "")
        }
    }
}

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private val devicePrefs by lazy { app.getSharedPreferences("native-device-v1", Context.MODE_PRIVATE) }
    private val deviceLock = Any()
    private var deviceEnsured = false
    private var lastRotateAttempt = 0L
    init {
        // 播放全域名被拒（设备档案被风控清理）时，由 core 层回调轮换设备并重试
        PlaybackDeviceRotation.rotator = { rotateDevice() }
    }
    private var index: LibraryIndex? = null
    private var accountSession: AccountApi.Session? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                val account = (values["account"] as? String).let { raw -> raw?.let { AccountCodec.decode(it) } }
                Result4(loaded, cached, stamps, account)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { r ->
                    index = r.loaded; homes.putAll(r.cached); homeSavedAt.putAll(r.stamps); accountSession = r.account
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
        // 设备档案 bootstrap 不阻塞首屏（浏览用静态档案即可，登录链路会幂等确认）；
        // close() 后 loader 已 shutdown，吞掉 RejectedExecutionException。
        runCatching { loader.execute { runCatching { ensureDevice() } } }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    fun snapshot() = state().snapshot()
    fun account(): AccountApi.Session? = accountSession
    /** 登录成功写入、退出登录清除；凭据立刻落盘。 */
    fun saveAccount(session: AccountApi.Session?) {
        accountSession = session
        put("account", session?.let(AccountCodec::encode))
        flush()
    }

    // ── 设备档案 bootstrap ─────────────────────────────────────

    /** 换装现役设备档案；archive=null 回落 73932 静态兜底 */
    private fun adopt(archive: DeviceArchive?) {
        val next = VendorConstants.defaultDevice()
        if (archive != null) {
            next["device_id"] = archive.deviceId
            next["iid"] = archive.installId
            next["cdid"] = archive.cdid
            next["openudid"] = archive.openudid
        }
        VendorConstants.device = next
        VendorConstants.deviceTtreq = archive?.ttreq ?: ""
    }

    private fun deviceArchiveOf(profile: DeviceRegister.Profile) =
        DeviceArchive(profile.deviceId, profile.installId, profile.cdid, profile.openudid, profile.ttreq)

    private fun persistDevice(archive: DeviceArchive?) {
        devicePrefs.edit().also { edit ->
            edit.clear()
            if (archive != null) {
                edit.putString("deviceId", archive.deviceId); edit.putString("installId", archive.installId)
                edit.putString("cdid", archive.cdid); edit.putString("openudid", archive.openudid)
                edit.putString("ttreq", archive.ttreq); edit.putLong("savedAt", System.currentTimeMillis())
            }
        }.apply()
    }

    /** 设备档案 bootstrap（幂等、线程安全、阻塞）：持久化档案优先 adopt；无档案或命中
     *  阵亡名单时按 24h 退避注册新设备，失败回落静态兜底。登录对设备身份敏感——
     *  从未注册的设备 passport 直接回 22「非法应用」，发码/登录前必须先过这里。 */
    fun ensureDevice() {
        if (deviceEnsured) return
        synchronized(deviceLock) {
            if (deviceEnsured) return
            deviceEnsured = true
            val values = devicePrefs.all
            val saved = DeviceArchive.decode(values)
            if (saved != null) {
                if (saved.installId in VendorConstants.DEAD_IIDS) persistDevice(null)
                else { adopt(saved); return }
            }
            val now = System.currentTimeMillis()
            if (now - (values["lastRegisterAt"] as? Long ?: 0L) < DeviceRegister.BACKOFF_MS) return
            runCatching { DeviceRegister.register() }.fold(onSuccess = { fresh ->
                val archive = deviceArchiveOf(fresh)
                adopt(archive); persistDevice(archive)
            }, onFailure = { Log.w("HongguoTV", "设备注册失败，使用静态兜底档案", it) })
            devicePrefs.edit().putLong("lastRegisterAt", now).apply()
        }
    }

    /** 档案被服务端清理（passport 22「非法应用」）时强制换新：清持久化并立即重注册。
     *  5 分钟节流，避免注册接口被打爆；失败回落静态兜底并返回 false。 */
    fun rotateDevice(): Boolean {
        synchronized(deviceLock) {
            val now = System.currentTimeMillis()
            if (now - lastRotateAttempt < 5 * 60_000L) return false
            lastRotateAttempt = now
            persistDevice(null)
            return runCatching { DeviceRegister.register() }.fold(onSuccess = { fresh ->
                val archive = deviceArchiveOf(fresh)
                adopt(archive); persistDevice(archive)
                devicePrefs.edit().putLong("lastRegisterAt", System.currentTimeMillis()).apply()
                true
            }, onFailure = {
                Log.w("HongguoTV", "设备轮换注册失败，回落静态兜底档案", it)
                adopt(null)
                false
            })
        }
    }
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext }, "contentType" to { merged.settings.type.storedValue }, "frameMode" to { merged.settings.frame.name }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
