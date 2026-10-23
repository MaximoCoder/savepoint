package com.savepoint.vault

import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.inputmethod.InputMethodManager
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * Contraseña "custodiada" para el portapapeles.
 *
 * En lugar de poner el texto en el portapapeles, savePoint pone un enlace
 * content://com.savepoint.clip/s/<token>. Android lo convierte en texto en el momento de pegar
 * (ClipData.Item.coerceToText), leyéndolo desde este provider, y en ese instante se sabe QUIÉN lo pide:
 *  - La app donde pegas (WhatsApp, Chrome, etc.) recibe la contraseña real.
 *  - El teclado, el historial del portapapeles de Samsung, SystemUI y el sistema reciben "••••••••".
 *  - Pasado el plazo, nadie recibe nada: aunque el portapapeles no se haya podido limpiar,
 *    el enlace ya está muerto. El secreto solo vive en memoria; si el proceso muere, desaparece.
 */
object ProtectedClip {
    const val MASK = "••••••••"
    private val random = SecureRandom()

    @Volatile private var token: String? = null
    @Volatile private var secret: String? = null
    @Volatile private var expiresAt: Long = 0L

    fun authority(ctx: Context) = "${ctx.packageName}.clip"

    @Synchronized
    fun put(ctx: Context, text: String, ttlMs: Long): Uri {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        token = t
        secret = text
        expiresAt = System.currentTimeMillis() + ttlMs
        return Uri.Builder().scheme("content").authority(authority(ctx)).appendPath("s").appendPath(t).build()
    }

    /** Devuelve el secreto si el token es el vigente y no ha vencido; si venció, lo olvida. */
    @Synchronized
    fun read(uri: Uri): String? {
        val t = uri.lastPathSegment ?: return null
        if (t != token) return null
        if (System.currentTimeMillis() > expiresAt) {
            wipe()
            return null
        }
        return secret
    }

    fun isKnown(uri: Uri): Boolean = uri.lastPathSegment != null && uri.lastPathSegment == token

    @Synchronized
    fun wipe() {
        token = null
        secret = null
        expiresAt = 0L
    }
}

class ProtectedClipProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (ProtectedClip.isKnown(uri)) TEXT else null

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
        if (ClipDescription.compareMimeTypes(TEXT, mimeTypeFilter)) arrayOf(TEXT) else null

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor {
        if (!ClipDescription.compareMimeTypes(TEXT, mimeTypeFilter)) {
            throw FileNotFoundException("Solo texto")
        }
        val ctx = context ?: throw FileNotFoundException()
        val uid = Binder.getCallingUid()
        val real = ProtectedClip.read(uri)
        val text = when {
            real == null -> "" // vencido o desconocido: se pega vacío, nunca el enlace
            ReaderPolicy.mustMask(ctx, uid) -> ProtectedClip.MASK
            else -> real
        }
        val pipe = ParcelFileDescriptor.createPipe()
        // Una contraseña cabe de sobra en el búfer del pipe, así que se escribe de una vez
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        openTypedAssetFile(uri, TEXT, null).parcelFileDescriptor

    // No es una base de datos: no se puede consultar ni modificar
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

    private companion object {
        const val TEXT = "text/plain"
    }
}

/** Decide quién NO debe ver la contraseña real: teclados, historial del portapapeles y el sistema. */
internal object ReaderPolicy {

    private val KNOWN_READERS = setOf(
        "com.android.systemui",
        "com.google.android.as",                       // Android System Intelligence
        "com.samsung.android.honeyboard",              // Teclado Samsung
        "com.sec.android.inputmethod",
        "com.samsung.android.clipboarduiservice",      // Historial del portapapeles Samsung
        "com.samsung.android.smartsuggestions",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.visionintelligence",
        "com.samsung.android.app.routines",
    )

    private val NAME_HINTS = listOf("keyboard", "inputmethod", "clipboard", "honeyboard")

    fun mustMask(ctx: Context, uid: Int): Boolean {
        if (uid == Process.myUid()) return false
        // UIDs del sistema (system_server, donde vive el portapapeles de Samsung)
        if (uid < Process.FIRST_APPLICATION_UID) return true
        val packages = ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        if (packages.isEmpty()) return true
        val imes = imePackages(ctx)
        return packages.any { pkg ->
            pkg in imes || pkg in KNOWN_READERS || NAME_HINTS.any { pkg.contains(it, ignoreCase = true) }
        }
    }

    /** Todos los teclados instalados, no solo el activo. */
    private fun imePackages(ctx: Context): Set<String> = try {
        ctx.getSystemService(InputMethodManager::class.java)
            ?.inputMethodList
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    } catch (e: Exception) {
        emptySet()
    }
}
