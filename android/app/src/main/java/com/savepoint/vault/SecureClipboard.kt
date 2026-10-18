package com.savepoint.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copia endurecida al portapapeles.
 *  - Marca el contenido como sensible: Android 13+ oculta la vista previa y los teclados
 *    que respetan la marca (Gboard, Samsung Keyboard reciente) no lo guardan en su historial.
 *  - Borra el portapapeles a los [CLEAR_AFTER_SECONDS] segundos, salvo que se haya copiado
 *    otra cosa desde savePoint mientras tanto.
 * El valor se lee de la bóveda en Kotlin, así que la contraseña no pasa por JavaScript para copiarse.
 */
object SecureClipboard {
    const val CLEAR_AFTER_SECONDS = 30

    // Mismo valor que ClipDescription.EXTRA_IS_SENSITIVE (API 33); en versiones previas se ignora sin error.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    fun copy(ctx: Context, text: String, sensitive: Boolean) {
        val app = ctx.applicationContext
        handler.post {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@post
            val clip = ClipData.newPlainText("savePoint", text)
            if (sensitive) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)

            val gen = ++generation
            handler.postDelayed({
                if (gen == generation) clear(app)
            }, CLEAR_AFTER_SECONDS * 1000L)
        }
    }

    private fun clear(app: Context) {
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
