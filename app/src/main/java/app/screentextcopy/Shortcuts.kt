package app.screentextcopy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.InputType
import android.util.Patterns
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** One saved action that runs on the selected text. */
data class Shortcut(
    val type: String,
    val name: String,
    val value: String = "",
    val pkg: String = "",
    val cls: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("name", name).put("value", value).put("pkg", pkg).put("cls", cls)

    fun describe(): String = when (type) {
        TYPE_SMART -> "Finds phone numbers, emails, links and addresses"
        TYPE_PROCESS -> "Sends the text to this app's text action"
        TYPE_SHARE -> "Shares the text directly to this app"
        TYPE_LINK -> "Opens $value"
        TYPE_NUMBERS -> "Copies only the numbers"
        TYPE_CONTACTS -> "Copies only emails and phone numbers"
        TYPE_JOIN -> "Joins all lines into one and copies"
        TYPE_TEMPLATE -> "Copies as: $value"
        else -> ""
    }

    companion object {
        const val TYPE_SMART = "smart"
        const val TYPE_PROCESS = "process"
        const val TYPE_SHARE = "share"
        const val TYPE_LINK = "link"
        const val TYPE_NUMBERS = "numbers"
        const val TYPE_CONTACTS = "contacts"
        const val TYPE_JOIN = "join"
        const val TYPE_TEMPLATE = "template"

        fun fromJson(o: JSONObject) = Shortcut(
            o.optString("type"), o.optString("name"), o.optString("value"),
            o.optString("pkg"), o.optString("cls")
        )
    }
}

/** Saved shortcuts and which one is the default (starred). */
object ShortcutStore {
    private const val KEY_LIST = "shortcuts"
    private const val KEY_DEFAULT = "shortcut_default"

    val SMART = Shortcut(Shortcut.TYPE_SMART, "Smart action")
    val GOOGLE = Shortcut(Shortcut.TYPE_LINK, "Search Google", "https://www.google.com/search?q={text}")
    val IMDB = Shortcut(Shortcut.TYPE_LINK, "Search IMDb", "https://www.imdb.com/find/?q={text}")
    val NUMBERS = Shortcut(Shortcut.TYPE_NUMBERS, "Numbers only")
    val CONTACTS = Shortcut(Shortcut.TYPE_CONTACTS, "Emails & phones")
    val JOIN = Shortcut(Shortcut.TYPE_JOIN, "Join lines")

    /** First-run list: the smart action is first and is the default. */
    private val STARTER = listOf(SMART, GOOGLE, IMDB, NUMBERS, JOIN)

    fun load(c: Context): MutableList<Shortcut> {
        val raw = Prefs.get(c).getString(KEY_LIST, null) ?: return STARTER.toMutableList()
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { Shortcut.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            STARTER.toMutableList()
        }
    }

    fun save(c: Context, list: List<Shortcut>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        Prefs.get(c).edit().putString(KEY_LIST, a.toString()).apply()
    }

    fun defaultIndex(c: Context, size: Int): Int =
        if (size <= 0) 0 else Prefs.get(c).getInt(KEY_DEFAULT, 0).coerceIn(0, size - 1)

    fun setDefault(c: Context, index: Int) {
        Prefs.get(c).edit().putInt(KEY_DEFAULT, index).apply()
    }

    fun defaultShortcut(c: Context): Shortcut? {
        val list = load(c)
        return if (list.isEmpty()) null else list[defaultIndex(c, list.size)]
    }
}

/** Runs shortcuts. Detection work happens off the main thread. */
object ShortcutRunner {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Runs [s] on [text]. [done] is called when the capture screen can close;
     * [other] opens the list of shortcuts (used when the smart action finds nothing).
     */
    fun run(a: Activity, s: Shortcut, text: String, done: () -> Unit, other: () -> Unit) {
        when (s.type) {
            Shortcut.TYPE_SMART -> smart(a, text, done, other)
            Shortcut.TYPE_PROCESS -> launch(
                a,
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(s.pkg, s.cls)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
                done
            )
            Shortcut.TYPE_SHARE -> launch(
                a,
                Intent(Intent.ACTION_SEND).setType("text/plain").setClassName(s.pkg, s.cls)
                    .putExtra(Intent.EXTRA_TEXT, text),
                done
            )
            Shortcut.TYPE_LINK -> launch(a, Intent(Intent.ACTION_VIEW, Uri.parse(fillLink(s.value, text))), done)
            Shortcut.TYPE_NUMBERS -> copyResult(a, extractNumbers(text), done)
            Shortcut.TYPE_CONTACTS -> copyResult(a, extractContacts(text), done)
            Shortcut.TYPE_JOIN -> copyResult(a, text.replace(Regex("\\s*\\n\\s*"), " ").trim(), done)
            Shortcut.TYPE_TEMPLATE -> copyResult(a, fillTemplate(s.value, text), done)
            else -> Toast.makeText(a, "Unknown shortcut", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Helpers ----------

    private fun launch(a: Activity, intent: Intent, done: () -> Unit) {
        try {
            a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            done()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, "No app found for this shortcut", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(a, "This app does not allow being opened this way", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyResult(a: Activity, result: String, done: () -> Unit) {
        if (result.isBlank()) {
            Toast.makeText(a, "Nothing to copy for this shortcut", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = a.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", result))
        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        done()
    }

    private fun encode(text: String): String =
        URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

    fun fillLink(template: String, text: String): String =
        if (template.contains("{text}")) template.replace("{text}", encode(text)) else template + encode(text)

    private fun fillTemplate(template: String, text: String): String =
        if (template.contains("{text}")) template.replace("{text}", text) else template + text

    /** Converts Arabic-Indic and other digits to 0–9. */
    private fun asciiDigits(s: String): String = buildString {
        for (ch in s) {
            if (Character.isDigit(ch)) append(Character.getNumericValue(ch)) else append(ch)
        }
    }

    private fun extractNumbers(text: String): String =
        Regex("\\p{Nd}+(?:[-/.,:]\\p{Nd}+)*").findAll(text).map { it.value }.toList().joinToString("\n")

    private fun extractContacts(text: String): String {
        val found = LinkedHashSet<String>()
        val emails = Patterns.EMAIL_ADDRESS.matcher(text)
        while (emails.find()) found.add(emails.group())
        val normalized = asciiDigits(text)
        val phones = Patterns.PHONE.matcher(normalized)
        while (phones.find()) {
            val p = phones.group().trim()
            if (p.count { it.isDigit() } >= 8) found.add(p)
        }
        return found.joinToString("\n")
    }

    // ---------- Smart action ----------

    private class Found(val label: String, val intent: Intent)

    private fun smart(a: Activity, text: String, done: () -> Unit, other: () -> Unit) {
        worker.execute {
            val found = try {
                detect(a.applicationContext, text)
            } catch (_: Exception) {
                emptyList()
            }
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                when {
                    found.isEmpty() -> MaterialAlertDialogBuilder(a)
                        .setTitle("Smart action")
                        .setMessage("No phone number, email, link or address was found in the selection.")
                        .setPositiveButton("Other shortcuts") { _, _ -> other() }
                        .setNegativeButton("Cancel", null)
                        .show()
                    found.size == 1 -> launch(a, found[0].intent, done)
                    else -> MaterialAlertDialogBuilder(a)
                        .setTitle("Smart action")
                        .setItems(found.map { it.label }.toTypedArray()) { _, i -> launch(a, found[i].intent, done) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    /** Finds entities with Android's on-device text classifier, falling back to simple patterns. */
    private fun detect(c: Context, text: String): List<Found> {
        val entities = LinkedHashMap<String, String>() // value -> type, in reading order
        try {
            val tc = c.getSystemService(TextClassificationManager::class.java)?.textClassifier
            if (tc != null && tc !== TextClassifier.NO_OP) {
                val max = tc.maxGenerateLinksTextLength
                val t = if (text.length > max) text.substring(0, max) else text
                val links = tc.generateLinks(TextLinks.Request.Builder(t).build())
                for (link in links.links) {
                    if (link.entityCount == 0) continue
                    val value = t.substring(link.start, link.end).trim()
                    if (value.isNotEmpty()) entities.putIfAbsent(value, link.getEntity(0))
                }
            }
        } catch (_: Exception) {
        }

        if (entities.isEmpty()) {
            val urls = Patterns.WEB_URL.matcher(text)
            while (urls.find()) {
                val v = urls.group()
                if (v.startsWith("http", ignoreCase = true) || v.startsWith("www.", ignoreCase = true)) {
                    entities.putIfAbsent(v, TextClassifier.TYPE_URL)
                }
            }
            val emails = Patterns.EMAIL_ADDRESS.matcher(text)
            while (emails.find()) entities.putIfAbsent(emails.group(), TextClassifier.TYPE_EMAIL)
            val phones = Patterns.PHONE.matcher(asciiDigits(text))
            while (phones.find()) {
                val p = phones.group().trim()
                if (p.count { it.isDigit() } >= 8) entities.putIfAbsent(p, TextClassifier.TYPE_PHONE)
            }
        }

        val out = ArrayList<Found>()
        for ((value, type) in entities) {
            when (type) {
                TextClassifier.TYPE_PHONE -> {
                    val number = asciiDigits(value)
                    out.add(Found("Call  $value", Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))))
                    whatsappNumber(c, number)?.let {
                        out.add(Found("WhatsApp  $value", Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$it"))))
                    }
                }
                TextClassifier.TYPE_EMAIL ->
                    out.add(Found("Email  $value", Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(value)))))
                TextClassifier.TYPE_URL -> {
                    val url = if (value.contains("://")) value else "https://$value"
                    out.add(Found("Open  $value", Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                }
                TextClassifier.TYPE_ADDRESS ->
                    out.add(Found("Map  $value", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(value)))))
            }
            if (out.size >= 12) break
        }
        return out
    }

    /** International number for wa.me links, using the SIM's country for local numbers. */
    private fun whatsappNumber(c: Context, number: String): String? {
        val iso = (c.getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "")
            .ifEmpty { Locale.getDefault().country }
            .uppercase(Locale.ROOT)
        val e164 = try {
            PhoneNumberUtils.formatNumberToE164(number, iso)
        } catch (_: Exception) {
            null
        } ?: number
        val digits = e164.filter { it in '0'..'9' }
        return digits.ifEmpty { null }
    }
}

/** Dialogs for adding and managing shortcuts (used by the settings screen). */
object ShortcutEditor {

    fun showAdd(a: Activity, onChanged: () -> Unit) {
        val choices = listOf<Pair<String, () -> Unit>>(
            "Smart action (phone, email, link, address)" to { add(a, ShortcutStore.SMART, onChanged) },
            "App text action (Translate, AI assistant, dictionary…)" to {
                pickApp(a, Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), Shortcut.TYPE_PROCESS, onChanged)
            },
            "Share directly to an app" to {
                pickApp(a, Intent(Intent.ACTION_SEND).setType("text/plain"), Shortcut.TYPE_SHARE, onChanged)
            },
            "Search Google" to { add(a, ShortcutStore.GOOGLE, onChanged) },
            "Search IMDb" to { add(a, ShortcutStore.IMDB, onChanged) },
            "Custom link…" to {
                askNameAndValue(a, "Custom link", "My search", "https://example.com/search?q={text}") { n, v ->
                    add(a, Shortcut(Shortcut.TYPE_LINK, n, v), onChanged)
                }
            },
            "Copy numbers only" to { add(a, ShortcutStore.NUMBERS, onChanged) },
            "Copy emails & phone numbers only" to { add(a, ShortcutStore.CONTACTS, onChanged) },
            "Join lines into one & copy" to { add(a, ShortcutStore.JOIN, onChanged) },
            "Copy with a template…" to {
                askNameAndValue(a, "Copy with a template", "Reference", "Ref: {text}") { n, v ->
                    add(a, Shortcut(Shortcut.TYPE_TEMPLATE, n, v), onChanged)
                }
            }
        )
        MaterialAlertDialogBuilder(a)
            .setTitle("Add shortcut")
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Options for one saved shortcut: make default, move up, delete. */
    fun showOptions(a: Activity, index: Int, onChanged: () -> Unit) {
        val list = ShortcutStore.load(a)
        val s = list.getOrNull(index) ?: return
        val items = arrayOf("Make default (★)", "Move up", "Delete")
        MaterialAlertDialogBuilder(a)
            .setTitle(s.name)
            .setItems(items) { _, which ->
                val def = ShortcutStore.defaultIndex(a, list.size)
                when (which) {
                    0 -> ShortcutStore.setDefault(a, index)
                    1 -> if (index > 0) {
                        list.add(index - 1, list.removeAt(index))
                        ShortcutStore.save(a, list)
                        when (def) {
                            index -> ShortcutStore.setDefault(a, index - 1)
                            index - 1 -> ShortcutStore.setDefault(a, index)
                        }
                    }
                    2 -> {
                        list.removeAt(index)
                        ShortcutStore.save(a, list)
                        when {
                            index < def -> ShortcutStore.setDefault(a, def - 1)
                            index == def -> ShortcutStore.setDefault(a, 0)
                        }
                    }
                }
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun add(a: Activity, s: Shortcut, onChanged: () -> Unit) {
        val list = ShortcutStore.load(a)
        list.add(s)
        ShortcutStore.save(a, list)
        Toast.makeText(a, "Added: ${s.name}", Toast.LENGTH_SHORT).show()
        onChanged()
    }

    private fun pickApp(a: Activity, intent: Intent, type: String, onChanged: () -> Unit) {
        val pm = a.packageManager
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != a.packageName }
        if (infos.isEmpty()) {
            Toast.makeText(a, "No apps on this phone offer this", Toast.LENGTH_LONG).show()
            return
        }
        val entries = infos.map { info ->
            val action = info.loadLabel(pm).toString()
            val app = pm.getApplicationLabel(info.activityInfo.applicationInfo).toString()
            val label = if (action.equals(app, ignoreCase = true)) action else "$action — $app"
            Triple(label, action, info)
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        MaterialAlertDialogBuilder(a)
            .setTitle(if (type == Shortcut.TYPE_PROCESS) "Choose a text action" else "Share to")
            .setItems(entries.map { it.first }.toTypedArray()) { _, i ->
                val (_, action, info) = entries[i]
                add(a, Shortcut(type, action, "", info.activityInfo.packageName, info.activityInfo.name), onChanged)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askNameAndValue(
        a: Activity, title: String, nameHint: String, valueHint: String, onOk: (String, String) -> Unit
    ) {
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val name = EditText(a).apply {
            hint = "Name (e.g. $nameHint)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val value = EditText(a).apply {
            setText(valueHint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name)
        box.addView(value)
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setMessage("{text} is replaced by the selected text.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { nameHint }
                val v = value.text.toString().trim()
                if (v.isNotEmpty()) onOk(n, v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
