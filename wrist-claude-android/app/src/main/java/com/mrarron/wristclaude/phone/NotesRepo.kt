package com.mrarron.wristclaude.phone

import android.content.Context
import androidx.core.content.edit
import com.mrarron.wristclaude.App
import com.mrarron.wristclaude.core.Notes
import com.mrarron.wristclaude.core.NotesTidy
import com.mrarron.wristclaude.data.SecureKeyStore
import com.mrarron.wristclaude.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Appends entries to the notes doc (with an encrypted offline queue) and keeps a cached copy of the
 * context doc. Docs REST API over OkHttp; the doc ids come from settings only.
 */
object NotesRepo {
    private const val PREFS = "notes"
    private const val QUEUE = "queue_enc"
    private const val CONTEXT = "context_enc"
    private const val CONTEXT_AT = "context_at"
    private const val LAST_ERROR = "last_error"
    private const val TIDY_AT = "tidy_at"
    private const val TIDY_REMOVED = "tidy_removed"
    private const val SENT_HEAD = "sent_head"
    private const val TIDY_FAIL_AT = "tidy_fail_at"
    private const val TIDY_ERROR = "tidy_error"
    const val TIDY_RETRY_AFTER_FAIL_MS = 60L * 60 * 1000
    const val TIDY_MIN_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val CONTEXT_MAX_AGE_MS = 3L * 60 * 60 * 1000
    private const val API = "https://docs.googleapis.com/v1/documents/"
    private const val CREATE_API = "https://docs.googleapis.com/v1/documents"
    private const val SEEDED = "seeded_docs"
    private const val DOCS_NOTICE = "docs_notice"
    private val JSON_TYPE = "application/json".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }
    private val lock = Mutex()
    /** Guards reads and writes of the persisted queue; held only for the short load/save, never across network calls. */
    private val queueMonitor = Any()
    private val tidyLock = Mutex()
    /** Serializes creating and seeding the app's docs, so they are never created twice. */
    private val docsLock = Mutex()

    private val _docsNotice = MutableStateFlow<String?>(null)
    /** Set when a doc had to be re-created (deleted by the user), or null. */
    val docsNotice: StateFlow<String?> = _docsNotice

    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued
    private val _context = MutableStateFlow<String?>(null)
    val context: StateFlow<String?> = _context
    private val _contextAt = MutableStateFlow(0L)
    val contextAt: StateFlow<Long> = _contextAt
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _tidyAt = MutableStateFlow(0L)
    /** When the notes doc was last tidied (0 = never). */
    val tidyAt: StateFlow<Long> = _tidyAt
    private val _tidyRemoved = MutableStateFlow(0)
    val tidyRemoved: StateFlow<Int> = _tidyRemoved

    private val _tidyError = MutableStateFlow<String?>(null)
    /** Why the last tidy attempt failed (no note text), or null. */
    val tidyError: StateFlow<String?> = _tidyError
    @Volatile private var tidyFailAt = 0L

    fun init(ctx: Context) {
        val p = prefs(ctx)
        tidyFailAt = p.getLong(TIDY_FAIL_AT, 0L)
        _tidyError.value = p.getString(TIDY_ERROR, null)
        _tidyAt.value = p.getLong(TIDY_AT, 0L)
        _tidyRemoved.value = p.getInt(TIDY_REMOVED, 0)
        _queued.value = loadQueue(ctx).size
        _context.value = p.getString(CONTEXT, null)?.let { SecureKeyStore.decrypt(it) }
        _contextAt.value = p.getLong(CONTEXT_AT, 0L)
        _lastError.value = p.getString(LAST_ERROR, null)
        _docsNotice.value = p.getString(DOCS_NOTICE, null)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- queue (doc id + text pairs, encrypted) ----------

    private data class Pending(val doc: String, val text: String)

    private fun loadQueue(ctx: Context): MutableList<Pending> {
        val enc = prefs(ctx).getString(QUEUE, null) ?: return mutableListOf()
        val raw = SecureKeyStore.decrypt(enc) ?: return mutableListOf()
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return mutableListOf()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val d = (o["doc"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val t = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Pending(d, t)
        }.toMutableList()
    }

    /** Persists [q] encrypted. Returns false when it couldn't be stored (the previous stored queue is left as it was). */
    private fun saveQueue(ctx: Context, q: List<Pending>): Boolean {
        var ok = true
        val arr = buildJsonArray { q.forEach { add(buildJsonObject { put("doc", it.doc); put("text", it.text) }) } }
        prefs(ctx).edit {
            if (q.isEmpty()) remove(QUEUE)
            else {
                val enc = SecureKeyStore.encrypt(arr.toString())
                if (enc == null) ok = false else putString(QUEUE, enc)
            }
        }
        if (ok) _queued.value = q.size
        return ok
    }

    private fun setError(ctx: Context, msg: String?) {
        prefs(ctx).edit { if (msg == null) remove(LAST_ERROR) else putString(LAST_ERROR, msg) }
        _lastError.value = msg
    }

    // ---------- HTTP ----------

    private sealed class Http {
        data class Ok(val json: JsonObject?) : Http()
        data class Fail(val status: Int, val message: String, val retryable: Boolean) : Http()
    }

    /** An access token, bounded to 10 s (Play Services can hang), or the failure to report. */
    private suspend fun tokenOrFail(ctx: Context): Pair<String?, Http.Fail?> {
        val t = withTimeoutOrNull(10_000) { GoogleAuth.token(ctx) }
            ?: return null to Http.Fail(0, "Couldn't reach Google sign-in.", retryable = true)
        return when (t) {
            is GoogleAuth.Token.Ok -> t.accessToken to null
            is GoogleAuth.Token.NeedsUser -> null to Http.Fail(401, "Reconnect your Google account in the Wrist Assistant app.", retryable = true)
            is GoogleAuth.Token.ScopeMissing -> null to Http.Fail(403, Notes.MSG_SCOPE_MISSING, retryable = true)
            is GoogleAuth.Token.Failed -> null to Http.Fail(0, "Couldn't reach Google sign-in.", retryable = true)
        }
    }

    private suspend fun call(ctx: Context, req: (String) -> Request): Http {
        val (token, fail) = tokenOrFail(ctx)
        if (token == null) return fail!!
        return execute(token, req)
    }

    private suspend fun execute(token: String, req: (String) -> Request): Http {
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(req(token)).execute().use { r ->
                    val body = runCatching { r.body.string() }.getOrNull()
                    val json = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    when {
                        r.isSuccessful -> Http.Ok(json)
                        r.code == 401 -> Http.Fail(401, "Reconnect your Google account in the Wrist Assistant app.", true)
                        r.code == 403 -> Http.Fail(403, Notes.forbiddenMessage(body), true)
                        r.code == 404 -> Http.Fail(404, "The doc wasn't found.", true)
                        r.code == 429 || r.code >= 500 -> Http.Fail(r.code, "Google Docs is busy. It will retry.", true)
                        else -> Http.Fail(r.code, "Google Docs error ${r.code}.", false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Http.Fail(0, "No internet. It will retry.", true)
            }
        }
    }

    private suspend fun append(token: String, doc: String, text: String): Http = execute(token) { token ->
        Request.Builder()
            .url(API + doc + ":batchUpdate")
            .header("Authorization", "Bearer $token")
            .post(Notes.appendRequest(text).toString().toRequestBody(JSON_TYPE))
            .build()
    }

    // ---------- the app's own docs (drive.file only opens files the app created) ----------

    private const val REPLACED_ID = "replaced_id"
    private const val REPLACED_AT_NOTES = "replaced_at_notes"
    private const val REPLACED_AT_CONTEXT = "replaced_at_context"
    const val REPLACE_MIN_INTERVAL_MS = 60L * 60 * 1000
    private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"

    private val _docsForbidden = MutableStateFlow(false)
    /** Google refused a doc-level PERMISSION_DENIED on the app's own doc (or a recovery is unfinished): "Make new docs". */
    val docsForbidden: StateFlow<Boolean> = _docsForbidden
    @Volatile private var forbiddenNotes = false
    @Volatile private var forbiddenContext = false
    /** A flagged doc was dropped and its replacement isn't saved yet: don't adopt the old one meanwhile. */
    @Volatile private var noAdoptNotes = false
    @Volatile private var noAdoptContext = false

    private fun refreshForbidden() {
        _docsForbidden.value = forbiddenNotes || forbiddenContext || noAdoptNotes || noAdoptContext
    }

    private fun seededIds(ctx: Context): Set<String> = prefs(ctx).getStringSet(SEEDED, emptySet()).orEmpty()

    private fun markSeeded(ctx: Context, id: String) {
        prefs(ctx).edit { putStringSet(SEEDED, seededIds(ctx) + id) }
    }

    private fun setNotice(ctx: Context, msg: String?) {
        prefs(ctx).edit { if (msg == null) remove(DOCS_NOTICE) else putString(DOCS_NOTICE, msg) }
        _docsNotice.value = msg
    }

    /** Only a doc-level PERMISSION_DENIED counts; scope-missing, disabled-API and sign-in failures don't. */
    private fun noteForbidden(f: Http.Fail, isNotes: Boolean) {
        if (f.status != 403 || f.message != Notes.MSG_PERMISSION_DENIED) return
        if (isNotes) forbiddenNotes = true else forbiddenContext = true
        refreshForbidden()
    }

    private fun clearForbidden(isNotes: Boolean) {
        if (isNotes) forbiddenNotes = false else forbiddenContext = false
        refreshForbidden()
    }

    /** The app's docs named [title] that Google still lists (newest first), or the failure. */
    private suspend fun listDocs(token: String, title: String): Pair<List<String>?, String?> {
        val url = DRIVE_FILES.toHttpUrl().newBuilder()
            .addQueryParameter("q", Notes.driveListQuery(title))
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "10")
            .build()
        return when (val r = execute(token) { t -> Request.Builder().url(url).header("Authorization", "Bearer $t").get().build() }) {
            is Http.Ok -> Notes.fileIds(r.json) to null
            is Http.Fail -> null to r.message
        }
    }

    /**
     * Adopts the app's newest existing doc with this title, else creates one, and saves its id at once (before any
     * text goes in). The token is fetched first (bounded); the list/create/save part is non-cancellable so a timeout
     * or navigation can't leave a created doc unsaved. Caller holds [docsLock]. Returns the failure message or null.
     */
    private suspend fun adoptOrCreate(ctx: Context, isNotes: Boolean): String? {
        val (token, fail) = tokenOrFail(ctx)
        if (token == null) return fail!!.message
        val title = if (isNotes) Notes.NOTES_TITLE else Notes.CONTEXT_TITLE
        return withContext(NonCancellable) {
            var id: String? = null
            if (!(if (isNotes) noAdoptNotes else noAdoptContext)) {
                val (found, err) = listDocs(token, title)
                if (found == null) return@withContext err ?: "Couldn't look for the doc."
                id = found.firstOrNull()
            }
            if (id == null) {
                when (val r = execute(token) { t ->
                    Request.Builder().url(CREATE_API).header("Authorization", "Bearer $t")
                        .post(Notes.createRequest(title).toString().toRequestBody(JSON_TYPE)).build()
                }) {
                    is Http.Ok -> id = (r.json?.get("documentId") as? JsonPrimitive)?.contentOrNull
                    is Http.Fail -> return@withContext r.message
                }
                if (id.isNullOrBlank()) return@withContext "Google didn't return the new doc."
            }
            val newId: String = id
            SettingsStore.update(ctx) { if (isNotes) it.copy(notesDoc = newId, docsAppCreated = true) else it.copy(contextDoc = newId, docsAppCreated = true) }
            if (isNotes) noAdoptNotes = false else noAdoptContext = false
            refreshForbidden()
            null
        }
    }

    /** Writes the starting text into a new doc unless it already has content (a retry after a lost reply). */
    private suspend fun seedDoc(ctx: Context, id: String, text: String): Http {
        val got = call(ctx) { token -> Request.Builder().url(API + id + "?fields=body").header("Authorization", "Bearer $token").get().build() }
        if (got is Http.Fail) return got
        if (got is Http.Ok && !Notes.isBlankDoc(got.json)) return got
        return call(ctx) { token ->
            Request.Builder().url(API + id + ":batchUpdate").header("Authorization", "Bearer $token")
                .post(Notes.seedRequest(text).toString().toRequestBody(JSON_TYPE)).build()
        }
    }

    /** One doc: adopt or create if missing, then seed if needed. Caller holds [docsLock]. */
    private suspend fun ensureLocked(ctx: Context, isNotes: Boolean): String? {
        var id = SettingsStore.current().let { if (isNotes) it.notesDoc else it.contextDoc }
        if (id.isBlank()) {
            adoptOrCreate(ctx, isNotes)?.let { return it }
            id = SettingsStore.current().let { if (isNotes) it.notesDoc else it.contextDoc }
        }
        if (id !in seededIds(ctx)) {
            when (val r = seedDoc(ctx, id, if (isNotes) Notes.NOTES_SEED else Notes.CONTEXT_SEED)) {
                is Http.Ok -> markSeeded(ctx, id)
                is Http.Fail -> { noteForbidden(r, isNotes); return r.message }
            }
        }
        return null
    }

    /** The notes doc exists and is seeded. Null when ready, else why not. */
    suspend fun ensureNotesDoc(ctx: Context): String? {
        if (!GoogleAuth.connected.value) return "Connect your Google account first."
        return docsLock.withLock { ensureLocked(ctx, true) }
    }

    /** The context doc exists and is seeded. Null when ready, else why not. */
    suspend fun ensureContextDoc(ctx: Context): String? {
        if (!GoogleAuth.connected.value) return "Connect your Google account first."
        return docsLock.withLock { ensureLocked(ctx, false) }
    }

    /** Both docs, independently (one failing doesn't stop the other). Returns the notes doc's problem first. */
    suspend fun ensureDocs(ctx: Context): String? {
        val n = ensureNotesDoc(ctx)
        val c = ensureContextDoc(ctx)
        return n ?: c
    }

    private fun appScope() = App.get().scope

    /**
     * Recovery after a doc-level PERMISSION_DENIED: drops only the docs that were refused (never one made during
     * this recovery) and makes new ones without adopting; on a retry it only fills blank ones. Runs in the app scope.
     */
    suspend fun makeNewDocs(ctx: Context): String? {
        val app = ctx.applicationContext
        return appScope().async {
            if (!GoogleAuth.connected.value) return@async "Connect your Google account first."
            docsLock.withLock {
                val dropNotes = forbiddenNotes
                val dropContext = forbiddenContext
                if (dropNotes || dropContext) {
                    SettingsStore.update(app) {
                        it.copy(notesDoc = if (dropNotes) "" else it.notesDoc, contextDoc = if (dropContext) "" else it.contextDoc)
                    }
                    if (dropNotes) { noAdoptNotes = true; forbiddenNotes = false }
                    if (dropContext) { noAdoptContext = true; forbiddenContext = false }
                    refreshForbidden()
                }
                val n = ensureLocked(app, true)
                val c = ensureLocked(app, false)
                if (n == null && c == null) setNotice(app, "Made new docs. Your old docs stay in Google Drive.")
                n ?: c
            }
        }.await()
    }

    /** First-connect setup (docs, context, queue), in the app scope so leaving the screen can't cancel it. */
    suspend fun connectSetup(ctx: Context): String? {
        val app = ctx.applicationContext
        return appScope().async {
            val docErr = ensureDocs(app)
            val ctxErr = if (docErr == null) refreshContext(app) else null
            val qErr = flush(app)
            docErr ?: ctxErr ?: qErr
        }.await()
    }

    /** Disconnect: forget the docs so the next connect adopts or creates this account's own. */
    suspend fun forgetDocs(ctx: Context) {
        val app = ctx.applicationContext
        appScope().async {
            docsLock.withLock {
                SettingsStore.update(app) { it.copy(notesDoc = "", contextDoc = "", docsAppCreated = false) }
                prefs(app).edit { remove(SEEDED); remove(REPLACED_ID); remove(REPLACED_AT_NOTES); remove(REPLACED_AT_CONTEXT) }
                forbiddenNotes = false; forbiddenContext = false; noAdoptNotes = false; noAdoptContext = false
                refreshForbidden()
            }
        }.await()
    }

    /**
     * [id] returned 404. Confirms with a second read, and doesn't recreate if Google still lists it, if it was
     * already replaced, or if a replacement happened within the last hour. Otherwise forgets it, adopts or makes a
     * new doc and says so. Null when a usable doc is ready.
     */
    private suspend fun docMissing(ctx: Context, id: String): String? {
        val confirm = call(ctx) { token -> Request.Builder().url(API + id + "?fields=documentId").header("Authorization", "Bearer $token").get().build() }
        if (confirm !is Http.Fail || confirm.status != 404) {
            return if (confirm is Http.Fail) confirm.message else "The doc was found after all; it will retry."
        }
        return docsLock.withLock {
            val cur = SettingsStore.current()
            val isNotes = id == cur.notesDoc
            if (!isNotes && id != cur.contextDoc) return@withLock null // already replaced by someone else
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            val atKey = if (isNotes) REPLACED_AT_NOTES else REPLACED_AT_CONTEXT
            if (p.getString(REPLACED_ID, null) == id || now - p.getLong(atKey, 0L) < REPLACE_MIN_INTERVAL_MS) {
                return@withLock "The doc wasn't found. It will retry later."
            }
            val (token, fail) = tokenOrFail(ctx)
            if (token == null) return@withLock fail!!.message
            val (listed, err) = listDocs(token, if (isNotes) Notes.NOTES_TITLE else Notes.CONTEXT_TITLE)
            if (listed == null) return@withLock err
            if (id in listed) return@withLock "The doc wasn't found. It will retry later."
            p.edit { putString(REPLACED_ID, id); putLong(atKey, now) }
            SettingsStore.update(ctx) { if (isNotes) it.copy(notesDoc = "") else it.copy(contextDoc = "") }
            setNotice(ctx, "The ${if (isNotes) "notes" else "context"} doc was deleted, so Wrist Assistant made a new one.")
            ensureLocked(ctx, isNotes)
        }
    }

    // ---------- public ----------

    sealed class SaveResult {
        object Saved : SaveResult()
        data class Queued(val reason: String) : SaveResult()
        object NotConnected : SaveResult()
        /** The note could not be stored or sent; it is not queued. */
        data class Failed(val reason: String) : SaveResult()
    }

    /** What happened to a save so far, so a timeout can report truthfully. */
    private class SaveState {
        @Volatile var persisted = false
        @Volatile var sent = false
    }

    private const val SAVE_FAILED = "Couldn't store or send the note"

    /**
     * Appends [text] to the notes doc; anything that can't be written within [timeoutMs] is left in the
     * queue and retried later (when the watch app opens, from the Notes section, or on the next save).
     */
    suspend fun save(ctx: Context, text: String, timeoutMs: Long = 20_000): SaveResult {
        val state = SaveState()
        return withTimeoutOrNull(timeoutMs) { saveNow(ctx, text, state) } ?: when {
            state.sent -> SaveResult.Saved
            state.persisted -> SaveResult.Queued("slow connection")
            else -> SaveResult.Failed(SAVE_FAILED)
        }
    }

    private suspend fun saveNow(ctx: Context, text: String, state: SaveState): SaveResult {
        if (!GoogleAuth.connected.value) return SaveResult.NotConnected
        // No doc id here: entries wait with a blank doc and go to the app's notes doc when it exists.
        val doc = ""
        // Persisted first, before waiting on any network lock, so a timeout can never drop a note.
        val persisted = synchronized(queueMonitor) {
            val q = loadQueue(ctx)
            q += Pending(doc, text)
            saveQueue(ctx, q)
        }
        state.persisted = persisted
        if (!persisted) {
            // The encrypted queue is unavailable: send this note directly (no lock needed; nothing was queued),
            // and only report Saved if it went through.
            val (token, fail) = tokenOrFail(ctx)
            if (token == null) return SaveResult.Failed(SAVE_FAILED)
            return withContext(NonCancellable) {
                if (SettingsStore.current().notesDoc.isNotBlank() && append(token, SettingsStore.current().notesDoc, text) is Http.Ok) { state.sent = true; SaveResult.Saved } else SaveResult.Failed(SAVE_FAILED)
            }
        }
        if (SettingsStore.current().notesDoc.isBlank()) {
            // Fresh connect: don't make the watch wait on list + create. Set up in the app scope; the note is queued.
            val app = ctx.applicationContext
            appScope().launch { runCatching { ensureNotesDoc(app); flush(app) } }
            return SaveResult.Queued("setting up your notes doc")
        }
        val reason = lock.withLock { flushLocked(ctx) }
        return if (reason == null) SaveResult.Saved else SaveResult.Queued(reason)
    }

    /** Retries the queue. Returns null when it's empty afterwards, else the reason it isn't. */
    suspend fun flush(ctx: Context): String? = lock.withLock {
        if (!GoogleAuth.connected.value) return@withLock null
        if (synchronized(queueMonitor) { loadQueue(ctx).isEmpty() }) null else flushLocked(ctx)
    }

    private fun hashOf(p: Pending): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((p.doc + "\n" + p.text).toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Volatile private var sentHead: String? = null

    /**
     * Sends the queue in order, stopping at the first retryable failure or when the queue can't be re-saved.
     * The token is fetched (bounded) before the non-cancellable part; only the HTTP send and the removal from
     * the stored queue are non-cancellable: OkHttp's execute() can't be cancelled, so a send that went through
     * must also be removed or it would be sent twice later. If removal fails after a send, a hash of that
     * entry is remembered (no text) so the next flush removes it instead of sending it again.
     * At most as many steps as the queue held on entry.
     */
    private suspend fun flushLocked(ctx: Context): String? {
        var reason: String? = null
        val limit = synchronized(queueMonitor) { loadQueue(ctx).size }
        if (limit > 0) {
            // Queued entries wait until the app's notes doc exists.
            val docErr = ensureNotesDoc(ctx)
            if (docErr != null) { setError(ctx, docErr); return docErr }
        }
        if (sentHead == null) sentHead = prefs(ctx).getString(SENT_HEAD, null)
        var recovered = false
        loop@ for (i in 0 until limit) {
            var redo: Boolean
            do {
                redo = false
                currentCoroutineContext().ensureActive()
                val first = synchronized(queueMonitor) { loadQueue(ctx).firstOrNull() } ?: break@loop
                val h = hashOf(first)
                val alreadySent = h == sentHead
                var token: String? = null
                if (!alreadySent) {
                    val (t, fail) = tokenOrFail(ctx)
                    if (t == null) { reason = fail!!.message; break@loop }
                    token = t
                }
                var missing: String? = null
                // Only the send and the queue removal are non-cancellable; recovering a deleted doc is not.
                val stop = withContext(NonCancellable) {
                    if (!alreadySent) {
                        val target = SettingsStore.current().notesDoc
                        val r = append(token!!, target, first.text)
                        if (r is Http.Fail && r.status == 404 && !recovered) {
                            missing = target
                            return@withContext true
                        }
                        if (r is Http.Fail) noteForbidden(r, true) else clearForbidden(true)
                        if (r is Http.Fail && r.retryable) {
                            reason = r.message
                            return@withContext true
                        }
                        // Not retryable (e.g. a malformed entry): drop it so it doesn't block the rest.
                        if (r is Http.Fail) reason = r.message
                    }
                    val saved = synchronized(queueMonitor) {
                        val q = loadQueue(ctx)
                        if (q.firstOrNull() == first) q.removeAt(0)
                        saveQueue(ctx, q)
                    }
                    if (saved) {
                        if (sentHead != null) { sentHead = null; prefs(ctx).edit { remove(SENT_HEAD) } }
                    } else {
                        sentHead = h
                        prefs(ctx).edit { putString(SENT_HEAD, h) }
                        reason = "Couldn't update the saved notes queue."
                    }
                    !saved
                }
                val gone = missing
                if (gone != null) {
                    recovered = true
                    val e = docMissing(ctx, gone)
                    if (e != null) { reason = e; break@loop }
                    redo = true
                } else if (stop) break@loop
            } while (redo)
        }
        val left = synchronized(queueMonitor) { loadQueue(ctx).isNotEmpty() }
        setError(ctx, if (left) reason else null)
        return if (left) reason ?: "Notes are still waiting to sync." else null
    }

    /** The cached context text, if the feature is on. */
    fun cachedContext(): String? = if (SettingsStore.current().useContext) _context.value else null

    fun contextIsStale(now: Long = System.currentTimeMillis()) = now - _contextAt.value > CONTEXT_MAX_AGE_MS

    /** Reads the context doc. Returns null on success, else the reason it failed. */
    suspend fun refreshContext(ctx: Context): String? {
        if (!GoogleAuth.connected.value) return "Connect your Google account first."
        if (!SettingsStore.current().useContext) return null
        ensureContextDoc(ctx)?.let { return it }
        val s = SettingsStore.current()
        return when (val r = call(ctx) { token ->
            Request.Builder().url(API + s.contextDoc + "?fields=body").header("Authorization", "Bearer $token").get().build()
        }) {
            is Http.Ok -> {
                clearForbidden(false)
                val text = Notes.extractContext(Notes.plainText(r.json))
                val now = System.currentTimeMillis()
                prefs(ctx).edit {
                    if (text == null) remove(CONTEXT) else SecureKeyStore.encrypt(text)?.let { putString(CONTEXT, it) }
                    putLong(CONTEXT_AT, now)
                }
                _context.value = text
                _contextAt.value = now
                null
            }
            is Http.Fail -> {
                noteForbidden(r, false)
                if (r.status == 404) docMissing(ctx, s.contextDoc) ?: "The context doc was deleted; made a new one."
                else r.message
            }
        }
    }

    /** Due when the last success is a day old and the last failure (if any) is over an hour old. */
    fun tidyIsDue(now: Long = System.currentTimeMillis()) =
        now - _tidyAt.value >= TIDY_MIN_INTERVAL_MS && now - tidyFailAt >= TIDY_RETRY_AFTER_FAIL_MS

    private fun tidyFailed(ctx: Context, msg: String): String {
        val now = System.currentTimeMillis()
        tidyFailAt = now
        prefs(ctx).edit { putLong(TIDY_FAIL_AT, now); putString(TIDY_ERROR, msg) }
        _tidyError.value = msg
        return msg
    }

    /**
     * Removes DONE entries from the notes doc (see [NotesTidy]). Flushes the offline queue first and does
     * nothing while appends are still pending. It doesn't take the append lock: the delete is guarded by the
     * doc's revisionId, so if an append (or anything else) landed since the read, Google rejects it and a later
     * tidy tries again. Returns a short status.
     */
    suspend fun tidy(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!GoogleAuth.connected.value) return@withContext "Connect your Google account first."
        if (!tidyLock.tryLock()) return@withContext "Already tidying."
        try {
            if (flush(ctx) != null || synchronized(queueMonitor) { loadQueue(ctx).isNotEmpty() }) {
                return@withContext "Notes are still waiting to sync; tidy later."
            }
            ensureNotesDoc(ctx)?.let { return@withContext tidyFailed(ctx, it) }
            val doc = SettingsStore.current().notesDoc
            val got = call(ctx) { token ->
                Request.Builder().url(API + doc + "?fields=revisionId,body").header("Authorization", "Bearer $token").get().build()
            }
            val raw = when (got) {
                is Http.Ok -> got.json?.toString()
                is Http.Fail -> {
                    noteForbidden(got, true)
                    if (got.status == 404) docMissing(ctx, doc)
                    return@withContext tidyFailed(ctx, got.message)
                }
            }
            val rev = NotesTidy.revisionId(raw) ?: return@withContext tidyFailed(ctx, "Couldn't read the notes doc.")
            val ranges = NotesTidy.doneRanges(raw)
            if (ranges.isNotEmpty()) {
                val res = call(ctx) { token ->
                    Request.Builder()
                        .url(API + doc + ":batchUpdate")
                        .header("Authorization", "Bearer $token")
                        .post(NotesTidy.deleteRequest(ranges, rev).toString().toRequestBody(JSON_TYPE))
                        .build()
                }
                if (res is Http.Fail) {
                    return@withContext tidyFailed(
                        ctx,
                        if (res.status == 400) "The doc changed while tidying; it will try again later." else res.message,
                    )
                }
            }
            val now = System.currentTimeMillis()
            prefs(ctx).edit { putLong(TIDY_AT, now); putInt(TIDY_REMOVED, ranges.size); remove(TIDY_ERROR); remove(TIDY_FAIL_AT) }
            tidyFailAt = 0L
            _tidyError.value = null
            _tidyAt.value = now
            _tidyRemoved.value = ranges.size
            if (ranges.isEmpty()) "Nothing to tidy." else "Removed ${ranges.size} done note" + (if (ranges.size == 1) "." else "s.")
        } finally {
            tidyLock.unlock()
        }
    }

    /** Called when the watch app opens: retry queued notes, refresh old context, tidy once a day, quietly. */
    suspend fun onWatchOpen(ctx: Context) {
        runCatching { flush(ctx) }
        if (SettingsStore.current().tidyDone && GoogleAuth.connected.value && tidyIsDue()) runCatching { tidy(ctx) }
        if (SettingsStore.current().useContext && contextIsStale()) runCatching { refreshContext(ctx) }
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit { remove(CONTEXT); remove(CONTEXT_AT); remove(LAST_ERROR); remove(TIDY_AT); remove(TIDY_REMOVED); remove(TIDY_FAIL_AT); remove(TIDY_ERROR); remove(DOCS_NOTICE) }
        _docsNotice.value = null
        forbiddenNotes = false; forbiddenContext = false; noAdoptNotes = false; noAdoptContext = false
        refreshForbidden()
        tidyFailAt = 0L
        _tidyError.value = null
        _tidyAt.value = 0L
        _tidyRemoved.value = 0
        _context.value = null
        _contextAt.value = 0L
        _lastError.value = null
    }
}
