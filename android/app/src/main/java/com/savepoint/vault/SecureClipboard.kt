package com.savepoint.vault

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copia endurecida al portapapeles.
 *
 * Contraseñas con "copia protegida" (por defecto): el portapapeles solo guarda un enlace a
 * [ProtectedClipProvider]. La app donde pegas recibe la contraseña; el teclado y el historial
 * del portapapeles reciben "••••••••". A los [CLEAR_AFTER_SECONDS] el enlace deja de funcionar
 * aunque el portapapeles no se haya podido limpiar.
 *
 * Borrado del portapapeles, en orden de fiabilidad:
 *  1. [ClipboardGuardService]: servicio corto en primer plano; Samsung no puede congelarlo.
 *  2. Handler: mientras el proceso siga despierto.
 *  3. JobScheduler: si el sistema mató el proceso.
 *  4. Al volver a abrir savePoint.
 */
object SecureClipboard {
    const val CLEAR_AFTER_SECONDS = 30

    private const val JOB_ID = 7001
    private const val PREFS = "savepoint_clipboard"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_PROTECTED = "protectedCopy"

    // Mismo valor que ClipDescription.EXTRA_IS_SENSITIVE (API 33); en versiones previas se ignora.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())

    fun isProtectedCopy(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PROTECTED, true)

    fun setProtectedCopy(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PROTECTED, enabled).apply()
    }

    /** @return true si la copia quedó protegida (enlace custodiado). */
    fun copy(ctx: Context, text: String, sensitive: Boolean): Boolean {
        val app = ctx.applicationContext
        val delayMs = CLEAR_AFTER_SECONDS * 1000L
        val expiresAt = System.currentTimeMillis() + delayMs
        val useProtected = sensitive && isProtectedCopy(app)

        val clip = if (useProtected) {
            val uri = ProtectedClip.put(app, text, delayMs)
            ClipData.newUri(app.contentResolver, "savePoint", uri)
        } else {
            ProtectedClip.wipe()
            ClipData.newPlainText("savePoint", text)
        }
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }

        prefs(app).edit().putLong(KEY_EXPIRES_AT, expiresAt).apply()
        // Primero se cancela el borrado de una copia anterior; luego se escribe la nueva
        handler.removeCallbacksAndMessages(null)
        handler.post {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@post
            cm.setPrimaryClip(clip)
        }
        handler.postDelayed({ clearIfExpired(app) }, delayMs)
        scheduleJob(app, delayMs)
        ClipboardGuardService.start(app, expiresAt)
        return useProtected
    }

    /** Borra si ya venció el plazo. Se llama desde el servicio, el Handler, el job y al volver a la app. */
    fun clearIfExpired(ctx: Context) {
        val app = ctx.applicationContext
        val expiresAt = prefs(app).getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt == 0L) return
        // Tolerancia de 1 s por si algo corre un instante antes del milisegundo exacto
        if (System.currentTimeMillis() + 1000 < expiresAt) return
        clearNow(app)
    }

    /** Borra ya, sin esperar el plazo ("Borrar ahora" en la notificación). */
    fun clearNow(ctx: Context) {
        val app = ctx.applicationContext
        ProtectedClip.wipe()
        prefs(app).edit().remove(KEY_EXPIRES_AT).apply()
        handler.removeCallbacksAndMessages(null)
        app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)

        val cm = app.getSystemService(ClipboardManager::class.java)
        if (cm != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (e: Exception) {
                // Aunque falle, el enlace protegido ya no entrega nada
            }
        }
        ClipboardGuardService.stop(app)
    }

    @Suppress("DEPRECATION")
    private fun scheduleJob(app: Context, delayMs: Long) {
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, ClipboardClearJob::class.java))
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs + 15_000)
            .build()
        // Reprogramar con el mismo id reemplaza el job anterior (copias seguidas)
        scheduler.schedule(job)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Job de respaldo por si el sistema mató el proceso antes del plazo. */
class ClipboardClearJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        SecureClipboard.clearIfExpired(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
