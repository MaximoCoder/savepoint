package com.savepoint.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi

/** Lo que encontramos en la pantalla que pidió autocompletado. */
data class AutofillFields(
    val packageName: String,
    val webDomain: String?,
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
) {
    val allIds: List<AutofillId> get() = usernameIds + passwordIds
    val isEmpty: Boolean get() = usernameIds.isEmpty() && passwordIds.isEmpty()

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_PACKAGE, packageName)
        putExtra(EXTRA_DOMAIN, webDomain)
        putParcelableArrayListExtra(EXTRA_USERNAME_IDS, ArrayList(usernameIds))
        putParcelableArrayListExtra(EXTRA_PASSWORD_IDS, ArrayList(passwordIds))
    }

    companion object {
        private const val EXTRA_PACKAGE = "savepoint.package"
        private const val EXTRA_DOMAIN = "savepoint.domain"
        private const val EXTRA_USERNAME_IDS = "savepoint.usernameIds"
        private const val EXTRA_PASSWORD_IDS = "savepoint.passwordIds"

        @Suppress("DEPRECATION")
        fun readFrom(intent: Intent): AutofillFields? {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return null
            val users = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_USERNAME_IDS) ?: arrayListOf()
            val passes = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_PASSWORD_IDS) ?: arrayListOf()
            return AutofillFields(pkg, intent.getStringExtra(EXTRA_DOMAIN), users, passes)
        }
    }
}

/**
 * Recorre la estructura de la pantalla y clasifica los campos de texto.
 * Orden de confianza: autofillHints declarados por la app > tipo de input > atributos HTML > texto del id/hint.
 */
@RequiresApi(Build.VERSION_CODES.O)
object StructureParser {
    private enum class Kind { USERNAME, PASSWORD, TEXT }

    private val USER_WORDS = listOf("user", "usuario", "email", "e-mail", "correo", "login", "account", "cuenta")
    private val PASS_WORDS = listOf("password", "contrase", "passwd", "pwd", "clave")

    fun parse(structure: AssistStructure): AutofillFields {
        val found = mutableListOf<Pair<AutofillId, Kind>>()
        var domain: String? = null

        fun walk(node: AssistStructure.ViewNode) {
            node.webDomain?.takeIf { it.isNotBlank() }?.let { if (domain == null) domain = it }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
                classify(node)?.let { found += id to it }
            }
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let { walk(it) }
        }

        val passwords = found.filter { it.second == Kind.PASSWORD }.map { it.first }
        var usernames = found.filter { it.second == Kind.USERNAME }.map { it.first }

        // Sin pistas claras: el campo de texto justo antes de la contraseña suele ser el usuario
        if (usernames.isEmpty() && passwords.isNotEmpty()) {
            val firstPass = found.indexOfFirst { it.second == Kind.PASSWORD }
            found.subList(0, firstPass).lastOrNull { it.second == Kind.TEXT }?.let { usernames = listOf(it.first) }
        }

        return AutofillFields(
            packageName = structure.activityComponent.packageName,
            webDomain = domain,
            usernameIds = usernames,
            passwordIds = passwords,
        )
    }

    private fun classify(node: AssistStructure.ViewNode): Kind? {
        val hints = node.autofillHints?.map { it.lowercase() }.orEmpty()
        if (hints.any { "password" in it }) return Kind.PASSWORD
        if (hints.any { "username" in it || "email" in it }) return Kind.USERNAME

        val type = node.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) return Kind.PASSWORD
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return Kind.PASSWORD
        if (cls == InputType.TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS) return Kind.USERNAME

        val html = node.htmlInfo
        var htmlText = ""
        if (html != null && html.tag.equals("input", ignoreCase = true)) {
            val attrs = html.attributes.orEmpty().associate { it.first.lowercase() to (it.second ?: "").lowercase() }
            when (attrs["type"]) {
                "password" -> return Kind.PASSWORD
                "email" -> return Kind.USERNAME
                "hidden", "submit", "button", "checkbox", "radio", "search" -> return null
            }
            val ac = attrs["autocomplete"].orEmpty()
            if ("password" in ac) return Kind.PASSWORD
            if ("username" in ac || "email" in ac) return Kind.USERNAME
            htmlText = listOfNotNull(attrs["name"], attrs["id"], attrs["placeholder"]).joinToString(" ")
        }

        val text = listOfNotNull(node.idEntry, node.hint?.toString(), htmlText).joinToString(" ").lowercase()
        if (PASS_WORDS.any { it in text }) return Kind.PASSWORD
        if (USER_WORDS.any { it in text }) return Kind.USERNAME

        val isEditableText = cls == InputType.TYPE_CLASS_TEXT || (html != null && html.tag.equals("input", true))
        return if (isEditableText) Kind.TEXT else null
    }

    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    private val EMAIL_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )
}
