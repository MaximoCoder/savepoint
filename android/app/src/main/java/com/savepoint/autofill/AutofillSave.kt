package com.savepoint.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.autofill.FillContext
import androidx.annotation.RequiresApi
import com.savepoint.vault.VaultEntry
import com.savepoint.vault.VaultStore

/** Usuario y contraseña que el usuario acaba de escribir en otra app. */
data class Credential(
    val packageName: String,
    val webDomain: String?,
    val username: String,
    val password: String,
) {
    override fun toString() = "Credential($packageName, $webDomain)" // nunca imprimir la contraseña
}

enum class SaveOutcome { CREATED, UPDATED, UNCHANGED }

@RequiresApi(Build.VERSION_CODES.O)
object CredentialExtractor {
    /**
     * Lee lo que el usuario escribió. Recorre todas las pantallas de la sesión de autocompletado:
     * en inicios de sesión de dos pasos el usuario puede estar en una y la contraseña en otra.
     */
    fun extract(contexts: List<FillContext>): Credential? {
        var pkg: String? = null
        var domain: String? = null
        var username: String? = null
        var password: String? = null
        for (ctx in contexts) {
            val fields = StructureParser.parse(ctx.structure)
            pkg = fields.packageName
            if (domain == null) domain = fields.webDomain
            fields.usernameValue()?.let { if (username == null) username = it.trim() }
            fields.passwordValue()?.let { password = it }
        }
        val p = password ?: return null
        return Credential(pkg ?: return null, domain, username.orEmpty(), p)
    }
}

/**
 * Guarda credenciales que vienen del autocompletado.
 * Si ya hay una entrada de la misma app o sitio con el mismo usuario, actualiza su contraseña;
 * si no, crea una nueva.
 */
@RequiresApi(Build.VERSION_CODES.O)
object CredentialSaver {
    fun save(ctx: Context, c: Credential): SaveOutcome {
        val probe = AutofillFields(c.packageName, c.webDomain, emptyList(), emptyList())
        val existing = VaultStore.listAll(ctx).firstOrNull {
            AutofillMatcher.matches(it, probe) && it.username.equals(c.username, ignoreCase = true)
        }
        if (existing != null) {
            if (existing.password == c.password) return SaveOutcome.UNCHANGED
            VaultStore.save(ctx, existing.copy(password = c.password))
            return SaveOutcome.UPDATED
        }
        val url = c.webDomain?.removePrefix("www.") ?: c.packageName
        VaultStore.save(
            ctx,
            VaultEntry(
                id = "",
                title = titleFor(ctx, c),
                username = c.username,
                password = c.password,
                url = url,
                notes = "",
                updatedAt = 0L,
            ),
        )
        return SaveOutcome.CREATED
    }

    fun message(outcome: SaveOutcome): String = when (outcome) {
        SaveOutcome.CREATED -> "Guardado en savePoint"
        SaveOutcome.UPDATED -> "Contraseña actualizada en savePoint"
        SaveOutcome.UNCHANGED -> "savePoint ya tenía esta contraseña"
    }

    /** Nombre legible: el dominio para sitios web, el nombre de la app para apps. */
    private fun titleFor(ctx: Context, c: Credential): String {
        c.webDomain?.let { return it.removePrefix("www.") }
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // Android 11+ oculta otras apps sin permiso de consulta; usamos el paquete
            c.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Credencial esperando a que el usuario desbloquee la bóveda para guardarla.
 * Vive solo en memoria y caduca a los 2 minutos; nunca se escribe a disco ni viaja en un Intent.
 */
object PendingSave {
    private const val TTL_MS = 2 * 60_000L
    private var credential: Credential? = null
    private var createdAt = 0L

    @Synchronized
    fun put(c: Credential) {
        credential = c
        createdAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun take(): Credential? {
        val c = credential
        credential = null
        return c?.takeIf { SystemClock.elapsedRealtime() - createdAt <= TTL_MS }
    }
}
