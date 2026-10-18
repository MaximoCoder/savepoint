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
 *  - Marca el contenido como sensible: Android 13+ oculta la vista previa y los teclados
 *    que respetan la marca no lo guardan en su historial.
 *  - Lo borra a los [CLEAR_AFTER_SECONDS] segundos.
 *
 * Por qué hay tres mecanismos de borrado:
 *  1. Handler: funciona mientras savePoint siga en pantalla.
 *  2. JobScheduler: cuando pasas a otra app, Android congela el proceso de savePoint a los
 *     pocos segundos y un Handler ya no se ejecuta. El sistema sí despierta la app para un job.
 *  3. Al volver a abrir savePoint, si el plazo ya venció y nada lo borró, se borra ahí.
 * El vencimiento se guarda en preferencias para sobrevivir si el sistema mata el proceso.
 */
object SecureClipboard {
    const val CLEAR_AFTER_SECONDS = 30

    private const val JOB_ID = 7001
    private const val PREFS = "savepoint_clipboard"
    private const val KEY_EXPIRES_AT = "expiresAt"

    // Mismo valor que ClipDescription.EXTRA_IS_SENSITIVE (API 33); en versiones previas se ignora.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())

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

            val delayMs = CLEAR_AFTER_SECONDS * 1000L
            prefs(app).edit().putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + delayMs).apply()

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ clearIfExpired(app) }, delayMs)
            scheduleJob(app, delayMs)
        }
    }

    /** Borra si ya venció el plazo. Se llama desde el Handler, el job y al volver a la app. */
    fun clearIfExpired(ctx: Context) {
        val app = ctx.applicationContext
        val expiresAt = prefs(app).getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt == 0L) return
        // Tolerancia de 1 s por si el job corre un instante antes del milisegundo exacto
        if (System.currentTimeMillis() + 1000 < expiresAt) return
        clearNow(app)
    }

    private fun clearNow(app: Context) {
        prefs(app).edit().remove(KEY_EXPIRES_AT).apply()
        handler.removeCallbacksAndMessages(null)
        app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)

        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Algunas capas de fabricante restringen el portapapeles en segundo plano;
            // en ese caso se reintenta al volver a la app.
            prefs(app).edit().putLong(KEY_EXPIRES_AT, 1L).apply()
        }
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

/** Job que el sistema ejecuta aunque savePoint esté congelado en segundo plano. */
class ClipboardClearJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        SecureClipboard.clearIfExpired(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
