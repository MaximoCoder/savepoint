package com.savepoint.vault

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val updatedAt: Long
)

class VaultException(val code: String, message: String) : Exception(message)

data class BackupInfo(val exportedAt: Long, val entryCount: Int)

data class MergeResult(val added: Int, val updated: Int, val unchanged: Int)

/**
 * Núcleo de la bóveda de savePoint. Es la única pieza que sabe cifrar y descifrar.
 * La usan tanto el plugin de Capacitor (UI en Angular) como, más adelante, el
 * servicio de Autofill. Es un singleton: la llave desbloqueada vive solo en memoria
 * de este proceso y se borra al bloquear.
 *
 * Esquema:
 *  - Al crear la bóveda se genera una llave maestra aleatoria de 256 bits (DEK).
 *  - La contraseña maestra pasa por Argon2id para obtener una llave de envoltura (KEK).
 *  - La DEK se guarda cifrada con la KEK (AES-256-GCM).
 *  - Cada entrada se cifra por separado con la DEK (AES-256-GCM), con su id como AAD
 *    para que nadie pueda intercambiar entradas cifradas dentro del archivo.
 *  - Cambiar la contraseña maestra solo requiere re-envolver la DEK, no re-cifrar todo.
 */
object VaultStore {
    private const val FILE_NAME = "vault.json"
    private const val FORMAT_VERSION = 1
    private const val KEY_LEN = 32
    private const val SALT_LEN = 16
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val AAD_KEY = "savepoint:key"
    private const val MIN_MASTER_LEN = 12

    // Argon2id: 64 MiB de memoria, 3 pasadas, 1 hilo. Tarda alrededor de un segundo en un teléfono actual.
    private const val ARGON_MEMORY_KIB = 65536
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 1

    private val random = SecureRandom()
    private var vaultKey: ByteArray? = null
    private var lastActivity = 0L

    // ---------- Ajustes de bloqueo e intentos fallidos ----------

    private const val PREFS = "savepoint_vault"
    private const val KEY_AUTOLOCK_MIN = "autoLockMinutes"
    private const val KEY_FAILED = "failedAttempts"
    private const val KEY_LOCKED_UNTIL = "lockedUntil"

    /** Minutos permitidos para el bloqueo automático. 0 = al salir de la app. */
    val AUTOLOCK_OPTIONS = setOf(0, 1, 5, 15)

    /** Intentos fallidos sin penalización; después la espera se duplica en cada fallo. */
    private const val FREE_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30 * 60_000L

    @Volatile private var settingsLoaded = false
    @Volatile var autoLockMinutes = 5
        private set

    /**
     * Lo activa el plugin justo antes de abrir una pantalla del sistema (selector de archivos,
     * ajustes de autocompletado) para que "bloquear al salir" no cierre la bóveda a media operación.
     */
    @Volatile var externalActivityExpected = false

    /** Tiempo sin actividad tras el cual la bóveda se bloquea sola. */
    private val autoLockMillis: Long
        get() = if (autoLockMinutes == 0) 60_000L else autoLockMinutes * 60_000L

    /** Con "bloquear al salir", MainActivity llama a esto cuando la app deja de verse. */
    fun shouldLockOnLeave(): Boolean = autoLockMinutes == 0 && !externalActivityExpected

    fun loadSettings(ctx: Context) {
        if (settingsLoaded) return
        autoLockMinutes = prefs(ctx).getInt(KEY_AUTOLOCK_MIN, 5).takeIf { it in AUTOLOCK_OPTIONS } ?: 5
        settingsLoaded = true
    }

    fun setAutoLockMinutes(ctx: Context, minutes: Int) {
        if (minutes !in AUTOLOCK_OPTIONS) throw VaultException("INVALID", "Tiempo de bloqueo no válido")
        prefs(ctx).edit().putInt(KEY_AUTOLOCK_MIN, minutes).apply()
        autoLockMinutes = minutes
        settingsLoaded = true
    }

    /** Milisegundos que faltan para poder volver a intentar la contraseña maestra. */
    fun retryAfterMs(ctx: Context): Long {
        val until = prefs(ctx).getLong(KEY_LOCKED_UNTIL, 0L)
        val now = System.currentTimeMillis()
        // Si el reloj del teléfono se atrasó, no dejar una espera mayor al máximo
        return (until - now).coerceIn(0L, MAX_DELAY_MS)
    }

    fun failedAttempts(ctx: Context): Int = prefs(ctx).getInt(KEY_FAILED, 0)

    private fun registerFailure(ctx: Context) {
        val failed = failedAttempts(ctx) + 1
        val editor = prefs(ctx).edit().putInt(KEY_FAILED, failed)
        if (failed >= FREE_ATTEMPTS) {
            val exp = (failed - FREE_ATTEMPTS).coerceAtMost(10)
            val delay = (BASE_DELAY_MS shl exp).coerceAtMost(MAX_DELAY_MS)
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + delay)
        }
        editor.apply()
    }

    private fun resetFailures(ctx: Context) {
        prefs(ctx).edit().remove(KEY_FAILED).remove(KEY_LOCKED_UNTIL).apply()
    }

    private fun throttleMessage(ms: Long): String {
        val secs = (ms + 999) / 1000
        val text = if (secs >= 60) "${(secs + 59) / 60} min" else "$secs s"
        return "Demasiados intentos. Espera $text para volver a intentar."
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Estado ----------

    fun exists(ctx: Context): Boolean = vaultFile(ctx).exists()

    @Synchronized
    fun isUnlocked(): Boolean {
        if (vaultKey == null) return false
        if (System.currentTimeMillis() - lastActivity > autoLockMillis) {
            lock()
            return false
        }
        return true
    }

    @Synchronized
    fun lock() {
        vaultKey?.fill(0)
        vaultKey = null
    }

    // ---------- Crear / desbloquear ----------

    @Synchronized
    fun create(ctx: Context, master: CharArray) {
        try {
            loadSettings(ctx)
            if (exists(ctx)) throw VaultException("ALREADY_EXISTS", "La bóveda ya existe")
            if (master.size < MIN_MASTER_LEN) {
                throw VaultException("WEAK_PASSWORD", "La contraseña maestra debe tener al menos $MIN_MASTER_LEN caracteres")
            }
            val salt = randomBytes(SALT_LEN)
            val kek = deriveKey(master, salt, ARGON_MEMORY_KIB, ARGON_ITERATIONS, ARGON_PARALLELISM)
            val dek = randomBytes(KEY_LEN)
            val wrapped = try { encrypt(kek, dek, AAD_KEY) } finally { kek.fill(0) }

            val root = JSONObject()
                .put("version", FORMAT_VERSION)
                .put(
                    "kdf", JSONObject()
                        .put("alg", "argon2id")
                        .put("salt", b64(salt))
                        .put("m", ARGON_MEMORY_KIB)
                        .put("t", ARGON_ITERATIONS)
                        .put("p", ARGON_PARALLELISM)
                )
                .put("wrappedKey", wrapped)
                .put("entries", JSONObject())
            write(ctx, root)

            vaultKey = dek
            touch()
        } finally {
            master.fill('\u0000')
        }
    }

    @Synchronized
    fun unlock(ctx: Context, master: CharArray) {
        try {
            loadSettings(ctx)
            val wait = retryAfterMs(ctx)
            if (wait > 0) throw VaultException("THROTTLED", throttleMessage(wait))
            val root = read(ctx)
            val kdf = root.getJSONObject("kdf")
            val kek = deriveKey(
                master,
                unb64(kdf.getString("salt")),
                kdf.getInt("m"),
                kdf.getInt("t"),
                kdf.getInt("p")
            )
            try {
                lock()
                vaultKey = decrypt(kek, root.getString("wrappedKey"), AAD_KEY)
            } catch (e: GeneralSecurityException) {
                registerFailure(ctx)
                val wait = retryAfterMs(ctx)
                if (wait > 0) throw VaultException("THROTTLED", throttleMessage(wait))
                throw VaultException("WRONG_PASSWORD", "Contraseña maestra incorrecta")
            } finally {
                kek.fill(0)
            }
            resetFailures(ctx)
            touch()
        } finally {
            master.fill('\u0000')
        }
    }

    /**
     * Cambia la contraseña maestra. Solo se vuelve a envolver la llave de la bóveda con la nueva,
     * así que las entradas y la huella siguen igual. Los respaldos ya exportados conservan la anterior.
     */
    @Synchronized
    fun changeMasterPassword(ctx: Context, current: CharArray, next: CharArray) {
        try {
            val key = requireKey()
            if (next.size < MIN_MASTER_LEN) {
                throw VaultException("WEAK_PASSWORD", "La contraseña maestra debe tener al menos $MIN_MASTER_LEN caracteres")
            }
            val root = read(ctx)
            val kdf = root.getJSONObject("kdf")
            val oldKek = deriveKey(current, unb64(kdf.getString("salt")), kdf.getInt("m"), kdf.getInt("t"), kdf.getInt("p"))
            try {
                decrypt(oldKek, root.getString("wrappedKey"), AAD_KEY).fill(0)
            } catch (e: GeneralSecurityException) {
                throw VaultException("WRONG_PASSWORD", "La contraseña actual no es correcta")
            } finally {
                oldKek.fill(0)
            }

            val salt = randomBytes(SALT_LEN)
            val newKek = deriveKey(next, salt, ARGON_MEMORY_KIB, ARGON_ITERATIONS, ARGON_PARALLELISM)
            val wrapped = try { encrypt(newKek, key, AAD_KEY) } finally { newKek.fill(0) }
            root.put(
                "kdf", JSONObject()
                    .put("alg", "argon2id")
                    .put("salt", b64(salt))
                    .put("m", ARGON_MEMORY_KIB)
                    .put("t", ARGON_ITERATIONS)
                    .put("p", ARGON_PARALLELISM)
            ).put("wrappedKey", wrapped)
            write(ctx, root)
            touch()
        } finally {
            current.fill('\u0000')
            next.fill('\u0000')
        }
    }

    /**
     * Copia de la llave de la bóveda para envolverla con la llave biométrica del Keystore.
     * Quien la reciba debe borrarla (fill(0)) en cuanto termine.
     */
    @Synchronized
    fun exportKey(): ByteArray = requireKey().copyOf()

    /** Desbloquea con la llave ya descifrada por la huella. Verifica que abra la bóveda antes de aceptarla. */
    @Synchronized
    fun unlockWithKey(ctx: Context, dek: ByteArray) {
        try {
            if (dek.size != KEY_LEN) throw VaultException("INVALID_KEY", "Llave inválida")
            val entries = read(ctx).getJSONObject("entries")
            val anyId = entries.keys().asSequence().firstOrNull()
            if (anyId != null) {
                try {
                    decrypt(dek, entries.getString(anyId), entryAad(anyId)).fill(0)
                } catch (e: GeneralSecurityException) {
                    throw VaultException("INVALID_KEY", "La llave no corresponde a esta bóveda")
                }
            }
            loadSettings(ctx)
            resetFailures(ctx)
            lock()
            vaultKey = dek.copyOf()
            touch()
        } finally {
            dek.fill(0)
        }
    }

    // ---------- Entradas ----------

    /** Lista sin contraseñas ni notas, para pintar la pantalla principal. */
    @Synchronized
    fun list(ctx: Context): List<VaultEntry> =
        listAll(ctx).map { it.copy(password = "", notes = "") }

    /** Todas las entradas completas. Solo para uso nativo (Autofill); nunca se envía a JavaScript. */
    @Synchronized
    fun listAll(ctx: Context): List<VaultEntry> {
        val key = requireKey()
        val entries = read(ctx).getJSONObject("entries")
        return entries.keys().asSequence()
            .map { id -> decodeEntry(id, decrypt(key, entries.getString(id), entryAad(id))) }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    @Synchronized
    fun get(ctx: Context, id: String): VaultEntry {
        val key = requireKey()
        val entries = read(ctx).getJSONObject("entries")
        if (!entries.has(id)) throw VaultException("NOT_FOUND", "La entrada no existe")
        return decodeEntry(id, decrypt(key, entries.getString(id), entryAad(id)))
    }

    /** Crea si el id viene vacío, actualiza si ya existe. Devuelve el id. */
    @Synchronized
    fun save(ctx: Context, entry: VaultEntry): String {
        val key = requireKey()
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val toStore = entry.copy(id = id, updatedAt = System.currentTimeMillis())
        val root = read(ctx)
        root.getJSONObject("entries").put(id, encrypt(key, encodeEntry(toStore), entryAad(id)))
        write(ctx, root)
        return id
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        requireKey()
        val root = read(ctx)
        root.getJSONObject("entries").remove(id)
        write(ctx, root)
    }

    // ---------- Respaldo ----------
    //
    // El respaldo es el mismo archivo de la bóveda (ya cifrado) dentro de un sobre con metadatos.
    // Se abre con la contraseña maestra que tenía la bóveda al momento de exportarlo.
    // Nada se descifra para exportar: el archivo nunca contiene datos en claro.

    private const val BACKUP_FORMAT = "savepoint-backup"
    private const val BACKUP_VERSION = 1

    @Synchronized
    fun exportBackup(ctx: Context): ByteArray {
        requireKey()
        val vault = read(ctx)
        return JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("vault", vault)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun inspectBackup(bytes: ByteArray): BackupInfo {
        val env = parseBackup(bytes)
        return BackupInfo(env.optLong("exportedAt"), env.getJSONObject("vault").getJSONObject("entries").length())
    }

    /** Sin bóveda en el teléfono: el respaldo pasa a ser la bóveda y queda desbloqueada. Devuelve cuántas entradas trae. */
    @Synchronized
    fun restoreBackup(ctx: Context, bytes: ByteArray, master: CharArray): Int {
        try {
            if (exists(ctx)) throw VaultException("ALREADY_EXISTS", "Ya existe una bóveda en este teléfono")
            val vault = parseBackup(bytes).getJSONObject("vault")
            val dek = backupKey(vault, master)
            val entries = vault.getJSONObject("entries")
            // Antes de instalarlo, comprueba que todas las entradas se pueden leer
            try {
                entries.keys().asSequence().toList().forEach { id ->
                    decrypt(dek, entries.getString(id), entryAad(id)).fill(0)
                }
            } catch (e: GeneralSecurityException) {
                dek.fill(0)
                throw VaultException("INVALID_BACKUP", "El respaldo está dañado")
            }
            write(ctx, vault)
            loadSettings(ctx)
            resetFailures(ctx)
            lock()
            vaultKey = dek
            touch()
            return entries.length()
        } finally {
            master.fill('\u0000')
        }
    }

    /**
     * Con bóveda abierta: agrega las entradas del respaldo que no existen y actualiza las que
     * en el respaldo son más recientes. Nunca borra nada de la bóveda actual.
     */
    @Synchronized
    fun mergeBackup(ctx: Context, bytes: ByteArray, master: CharArray): MergeResult {
        try {
            val key = requireKey()
            val vault = parseBackup(bytes).getJSONObject("vault")
            val backupDek = backupKey(vault, master)
            try {
                val incoming = vault.getJSONObject("entries")
                val root = read(ctx)
                val current = root.getJSONObject("entries")
                var added = 0
                var updated = 0
                var unchanged = 0
                for (id in incoming.keys().asSequence().toList()) {
                    val entry = try {
                        decodeEntry(id, decrypt(backupDek, incoming.getString(id), entryAad(id)))
                    } catch (e: GeneralSecurityException) {
                        throw VaultException("INVALID_BACKUP", "El respaldo está dañado")
                    }
                    if (current.has(id)) {
                        val mine = decodeEntry(id, decrypt(key, current.getString(id), entryAad(id)))
                        if (entry.updatedAt <= mine.updatedAt) {
                            unchanged++
                            continue
                        }
                        updated++
                    } else {
                        added++
                    }
                    current.put(id, encrypt(key, encodeEntry(entry), entryAad(id)))
                }
                if (added + updated > 0) write(ctx, root)
                return MergeResult(added, updated, unchanged)
            } finally {
                backupDek.fill(0)
            }
        } finally {
            master.fill('\u0000')
        }
    }

    private fun parseBackup(bytes: ByteArray): JSONObject {
        val invalid = VaultException("INVALID_BACKUP", "El archivo no es un respaldo de savePoint")
        val env = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw invalid
        }
        if (env.optString("format") != BACKUP_FORMAT) throw invalid
        if (env.optInt("version") > BACKUP_VERSION) {
            throw VaultException("INVALID_BACKUP", "El respaldo es de una versión más nueva de savePoint")
        }
        val vault = env.optJSONObject("vault") ?: throw invalid
        if (!vault.has("kdf") || !vault.has("wrappedKey") || vault.optJSONObject("entries") == null) throw invalid
        return env
    }

    /** Descifra la llave del respaldo con su contraseña maestra. */
    private fun backupKey(vault: JSONObject, master: CharArray): ByteArray {
        val kdf = vault.getJSONObject("kdf")
        val m = kdf.getInt("m")
        val t = kdf.getInt("t")
        val p = kdf.getInt("p")
        // Un archivo manipulado podría pedir parámetros enormes y colgar el teléfono
        if (m !in 8_192..1_048_576 || t !in 1..20 || p !in 1..8) {
            throw VaultException("INVALID_BACKUP", "El respaldo tiene parámetros de cifrado no válidos")
        }
        val kek = deriveKey(master, unb64(kdf.getString("salt")), m, t, p)
        try {
            return decrypt(kek, vault.getString("wrappedKey"), AAD_KEY)
        } catch (e: GeneralSecurityException) {
            throw VaultException("WRONG_PASSWORD", "La contraseña no abre este respaldo")
        } finally {
            kek.fill(0)
        }
    }

    // ---------- Internos ----------

    private fun requireKey(): ByteArray {
        if (!isUnlocked()) throw VaultException("LOCKED", "La bóveda está bloqueada")
        touch()
        return vaultKey!!
    }

    private fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    private fun entryAad(id: String) = "savepoint:entry:$id"

    private fun encodeEntry(e: VaultEntry): ByteArray = JSONObject()
        .put("title", e.title)
        .put("username", e.username)
        .put("password", e.password)
        .put("url", e.url)
        .put("notes", e.notes)
        .put("updatedAt", e.updatedAt)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeEntry(id: String, plain: ByteArray): VaultEntry {
        val o = JSONObject(String(plain, Charsets.UTF_8))
        plain.fill(0)
        return VaultEntry(
            id = id,
            title = o.optString("title"),
            username = o.optString("username"),
            password = o.optString("password"),
            url = o.optString("url"),
            notes = o.optString("notes"),
            updatedAt = o.optLong("updatedAt")
        )
    }

    private fun deriveKey(master: CharArray, salt: ByteArray, m: Int, t: Int, p: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(KEY_LEN)
        gen.generateBytes(master, out)
        return out
    }

    /** Formato: base64(iv[12] || ciphertext || tag[16]). */
    private fun encrypt(key: ByteArray, plain: ByteArray, aad: String): String {
        val iv = randomBytes(GCM_IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return b64(iv + c.doFinal(plain))
    }

    private fun decrypt(key: ByteArray, blob: String, aad: String): ByteArray {
        val raw = unb64(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_LEN)
        )
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.doFinal(raw, GCM_IV_LEN, raw.size - GCM_IV_LEN)
    }

    // noBackupFilesDir: el archivo no se sube a respaldos en la nube de Google.
    private fun vaultFile(ctx: Context) = File(ctx.noBackupFilesDir, FILE_NAME)

    private fun read(ctx: Context): JSONObject {
        if (!exists(ctx)) throw VaultException("NO_VAULT", "No existe una bóveda todavía")
        val bytes = AtomicFile(vaultFile(ctx)).readFully()
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    /** Escritura atómica: si la app muere a la mitad, el archivo anterior queda intacto. */
    private fun write(ctx: Context, root: JSONObject) {
        val af = AtomicFile(vaultFile(ctx))
        val out = af.startWrite()
        try {
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            throw e
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
