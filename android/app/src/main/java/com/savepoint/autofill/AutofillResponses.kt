package com.savepoint.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.savepoint.R
import com.savepoint.vault.VaultEntry
import com.savepoint.vault.VaultStore

/** Decide qué entradas corresponden a la app o sitio que pide autocompletado. */
object AutofillMatcher {
    fun matches(entry: VaultEntry, fields: AutofillFields): Boolean {
        val target = entry.url.trim().lowercase()
        if (target.isEmpty()) return false

        // El usuario escribió el nombre de paquete exacto (ej. com.bbva.bbvacontigo)
        if (target == fields.packageName.lowercase()) return true

        val host = hostOf(target)
        if (host.isEmpty()) return false

        val domain = fields.webDomain?.lowercase()?.removePrefix("www.")
        if (domain != null) {
            // Sitio web dentro de un navegador: mismo dominio o subdominio
            return domain == host || domain.endsWith(".$host") || host.endsWith(".$domain")
        }

        // App nativa: el nombre principal del dominio aparece como segmento del paquete
        // bbva.mx  ->  "bbva"  ->  com.bbva.bbvacontigo
        val label = host.split('.').firstOrNull { it.length >= 3 && it !in GENERIC } ?: return false
        return fields.packageName.lowercase().split('.').any { it == label }
    }

    private val GENERIC = setOf("www", "app", "apps", "com", "org", "net", "mobile", "login", "accounts", "mail")

    fun hostOf(value: String): String =
        value.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .removePrefix("www.")
            .trim()
}

@RequiresApi(Build.VERSION_CODES.O)
object AutofillResponses {
    private const val MAX_DATASETS = 20
    private var requestCode = 0

    /** Bóveda bloqueada: una sola sugerencia que abre el desbloqueo. */
    fun locked(ctx: Context, fields: AutofillFields): FillResponse {
        val intent = fields.writeTo(Intent(ctx, AutofillAuthActivity::class.java))
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val sender = PendingIntent.getActivity(ctx, ++requestCode, intent, flags).intentSender
        val presentation = item(ctx, "Desbloquear savePoint", "Toca para elegir una contraseña")
        return FillResponse.Builder()
            .setAuthentication(fields.allIds.toTypedArray(), sender, presentation)
            .build()
    }

    /**
     * Bóveda abierta: una sugerencia por entrada. Primero las que coinciden con la app o sitio;
     * si ninguna coincide, todas, para que el usuario elija.
     * Devuelve null si no hay nada que ofrecer.
     * (Dataset.Builder con RemoteViews está deprecado en Android 13, pero funciona en todas las versiones.)
     */
    @Suppress("DEPRECATION")
    fun datasets(ctx: Context, fields: AutofillFields): FillResponse? {
        val all = VaultStore.listAll(ctx)
        val matched = all.filter { AutofillMatcher.matches(it, fields) }
        val candidates = (matched.ifEmpty { all }).take(MAX_DATASETS)

        val builder = FillResponse.Builder()
        var added = 0
        for (e in candidates) {
            val subtitle = e.username.ifBlank { AutofillMatcher.hostOf(e.url) }
            val presentation = item(ctx, e.title, subtitle)
            val ds = Dataset.Builder(presentation)
            var hasValue = false
            if (e.username.isNotEmpty()) {
                fields.usernameIds.forEach {
                    ds.setValue(it, AutofillValue.forText(e.username), presentation)
                    hasValue = true
                }
            }
            if (e.password.isNotEmpty()) {
                fields.passwordIds.forEach {
                    ds.setValue(it, AutofillValue.forText(e.password), presentation)
                    hasValue = true
                }
            }
            if (hasValue) {
                builder.addDataset(ds.build())
                added++
            }
        }
        return if (added > 0) builder.build() else null
    }

    private fun item(ctx: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }
}
