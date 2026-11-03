package com.mywisper.core

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to Firestore (same layout and encryption as the Windows app's src/main/sync.js):
 *   users/{uid}/history/{id}     – every dictation / rewrite from every device (text fields encrypted)
 *   users/{uid}/prompts/{id}     – the prompt library (edited on the PC; read here)
 *   users/{uid}/meta/dictionary  – custom words + replacements
 *   users/{uid}/meta/keycheck    – proves this device has the same encryption key
 * The key comes from the MyWisper server once (ServerApi.dataKey) and is then kept in Settings.
 * Firestore keeps its own offline cache on Android, so writes made offline are sent later.
 * The dictionary and prompts are also cached (decrypted) in SharedPreferences for instant use.
 */
object SyncRepo {
    private const val TAG = "MyWisper"
    private const val KEYCHECK_TEXT = "mywisper"
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var listeners = listOf<ListenerRegistration>()
    private var key: ByteArray? = null
    private var keyUid: String? = null
    private val keyLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** "ok" | "mismatch" (different secret than the account's other devices) | "off" */
    @Volatile var status = "off"
        private set

    data class Dictionary(val words: List<String>, val replacements: Map<String, String>)

    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid

    /** This user's sync key: memory → saved on the phone → asked from the server (needs internet once). */
    private suspend fun keyFor(uid: String): ByteArray = keyLock.withLock {
        if (keyUid == uid) key?.let { return it }
        val settings = Settings(FirebaseApp.getInstance().applicationContext)
        val k = settings.dataKey(uid) ?: ServerApi.dataKey().also { settings.saveDataKey(uid, it) }
        key = k
        keyUid = uid
        k
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("mywisper", Context.MODE_PRIVATE)

    /** Checks the key and keeps the dictionary + prompts caches up to date. Safe to call many times. */
    fun start(context: Context) {
        val uid = uid() ?: return
        if (listeners.isNotEmpty() || status == "checking") return
        status = "checking"
        scope.launch {
            val k = runCatching { keyFor(uid) }.getOrElse {
                Log.w(TAG, "sync: no key yet: ${it.message}")
                status = "off" // try again next time
                return@launch
            }
            checkKey(context, uid, k)
        }
    }

    private fun checkKey(context: Context, uid: String, k: ByteArray) {
        val ref = db.document("users/$uid/meta/keycheck")
        ref.get().addOnSuccessListener { snap ->
            val value = snap.getString("value")
            when {
                value == null -> ref.set(mapOf("value" to Crypto.encrypt(k, KEYCHECK_TEXT), "updatedAt" to System.currentTimeMillis()))
                runCatching { Crypto.decrypt(k, value) }.getOrNull() != KEYCHECK_TEXT -> {
                    Log.w(TAG, "sync: encryption key mismatch – the server key differs from the one that encrypted this account")
                    status = "mismatch"
                    return@addOnSuccessListener
                }
            }
            status = "ok"
            listen(context, uid, k)
        }.addOnFailureListener {
            Log.w(TAG, "sync: key check failed: ${it.message}")
            status = "off" // try again next time
        }
    }

    private fun listen(context: Context, uid: String, k: ByteArray) {
        val p = prefs(context)
        listeners = listOf(
            db.document("users/$uid/meta/dictionary").addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "dictionary sync: ${err.message}"); return@addSnapshotListener }
                val data = snap?.data ?: return@addSnapshotListener
                runCatching { p.edit().putString("dictionary", Crypto.openBlob(k, data).toString()).apply() }
                    .onFailure { Log.w(TAG, "dictionary: can't decrypt") }
            },
            db.collection("users/$uid/prompts").addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "prompts sync: ${err.message}"); return@addSnapshotListener }
                val arr = JSONArray()
                for (d in snap?.documents.orEmpty()) {
                    val data = d.data ?: continue
                    runCatching { arr.put(Crypto.openBlob(k, data).put("id", d.id)) }
                }
                p.edit().putString("promptEdits", arr.toString()).apply()
            },
        )
    }

    fun stop() {
        listeners.forEach { it.remove() }
        listeners = emptyList()
        if (status != "mismatch") status = "off"
    }

    /** Sign-out: stop listening and forget everything cached from the account. */
    fun clear(context: Context) {
        stop()
        status = "off"
        key = null
        keyUid = null
        Settings(context).saveDataKey("", null)
        prefs(context).edit().remove("dictionary").remove("promptEdits").apply()
    }

    fun dictionary(context: Context): Dictionary {
        val raw = prefs(context).getString("dictionary", null) ?: return Dictionary(emptyList(), emptyMap())
        return runCatching {
            val o = JSONObject(raw)
            val words = o.optJSONArray("words")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val rep = o.optJSONObject("replacements")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }.orEmpty()
            Dictionary(words, rep)
        }.getOrDefault(Dictionary(emptyList(), emptyMap()))
    }

    /** The user's prompt edits and own prompts (decrypted), merged with the defaults by PromptLibrary. */
    fun promptEdits(context: Context): List<JSONObject> {
        val raw = prefs(context).getString("promptEdits", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    }

    /** Saves one history entry, encrypted (works offline; Firestore sends it later). */
    fun addHistory(entry: Map<String, Any?>) {
        val uid = uid() ?: return
        if (status == "mismatch") { Log.w(TAG, "history not uploaded: encryption key mismatch"); return }
        val id = entry["id"] as String
        scope.launch {
            val k = runCatching { keyFor(uid) }.getOrElse { Log.w(TAG, "history not uploaded: no key (${it.message})"); return@launch }
            db.document("users/$uid/history/$id").set(Crypto.sealHistory(k, entry).filterValues { it != null })
                .addOnFailureListener { Log.w(TAG, "history upload failed: ${it.message}") }
        }
    }

    /** Live history (newest first, deleted ones left out, decrypted). Returns a handle to stop listening. */
    fun watchHistory(onChange: (List<Map<String, Any>>) -> Unit): ListenerRegistration? {
        val uid = uid() ?: return null
        var inner: ListenerRegistration? = null
        var stopped = false
        scope.launch {
            val k = runCatching { keyFor(uid) }.getOrElse { Log.w(TAG, "history: no key (${it.message})"); return@launch }
            if (!stopped) inner = historyListener(uid, k, onChange)
        }
        return ListenerRegistration { stopped = true; inner?.remove() }
    }

    private fun historyListener(uid: String, k: ByteArray, onChange: (List<Map<String, Any>>) -> Unit): ListenerRegistration {
        return db.collection("users/$uid/history").orderBy("time", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "history sync: ${err.message}"); return@addSnapshotListener }
                onChange(snap?.documents.orEmpty().mapNotNull { d ->
                    val data = d.data?.takeIf { it["deleted"] != true } ?: return@mapNotNull null
                    runCatching { Crypto.openHistory(k, data) }.getOrNull()?.let { m ->
                        @Suppress("UNCHECKED_CAST")
                        (m.filterValues { it != null } as Map<String, Any>) + ("id" to d.id)
                    }
                })
            }
    }

    /** Deletes every document this account has in Firestore. Returns how many. Local data stays. */
    suspend fun deleteCloudData(context: Context): Int {
        val uid = uid() ?: return 0
        stop()
        var n = 0
        for (name in listOf("history", "prompts", "meta")) {
            val docs = db.collection("users/$uid/$name").get().await().documents
            docs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
                n += chunk.size
            }
        }
        prefs(context).edit().remove("dictionary").remove("promptEdits").apply()
        return n
    }
}
