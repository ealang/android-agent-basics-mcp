package io.github.ealang.agentbasicsmcp.mcp.generic

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import io.github.ealang.agentbasicsmcp.mcp.Preflight
import io.github.ealang.agentbasicsmcp.mcp.UnlockGate
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Experimental: the generic intent surface — inspect what any installed app
 * declares as automatable (exported components and their intent filters),
 * dry-run resolution ("who would handle this intent"), and actually fire
 * an intent as an activity start, broadcast, or service start.
 *
 * Discovery has two layers. PackageManager hands back components, exported
 * flags, and guarding permissions for free. Intent *filters* are not in
 * that API — they live in each app's compiled AndroidManifest.xml, which we
 * read through the app's own asset space (createPackageContext →
 * openXmlResourceParser). The one thing no scan can reveal is extras
 * semantics; those come from documentation or source, and land in skill
 * files.
 *
 * Everything here is synchronous PackageManager work plus fire-and-forget
 * sends — no binder callbacks, no latches, unlike MediaExplorer.
 */
object IntentExplorer {

    private const val TAG = "agent-basics-mcp"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    // ------------------------------------------------------------- inspect

    /** No package: the full installed list, so the agent can find the app the user named. */
    fun listPackages(context: Context): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
            .map { it.loadLabel(pm).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }
        val lines = apps.joinToString("\n") { (label, pkg) -> "$label — $pkg" }
        return "${apps.size} installed apps (inspect one with inspect package=<pkg>):\n$lines"
    }

    /** One package: its exported components, permissions, intent filters, and providers. */
    fun inspect(context: Context, pkg: String): String {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(
                pkg,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return "FAILED: no installed package named \"$pkg\". inspect with no arguments lists every installed app."
        }
        val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        val filters = manifestFilters(context, pkg)

        val sb = StringBuilder("$label ($pkg), version ${info.versionName ?: "?"}\n")

        fun section(
            title: String,
            components: Array<out android.content.pm.ComponentInfo>?,
            exportedOf: (android.content.pm.ComponentInfo) -> Boolean,
            permissionOf: (android.content.pm.ComponentInfo) -> String?,
        ) {
            val all = components?.toList() ?: emptyList()
            val exported = all.filter(exportedOf)
            sb.append("\n$title: ${exported.size} exported")
            if (all.size != exported.size) sb.append(" (${all.size - exported.size} internal, unreachable)")
            sb.append("\n")
            for (c in exported) {
                sb.append("  ${c.name}")
                permissionOf(c)?.let { sb.append("\n    guarded by permission: $it") }
                val fs = filters[c.name].orEmpty()
                if (fs.isEmpty()) {
                    sb.append("\n    filters: none (explicit component only)")
                } else {
                    for (f in fs) sb.append("\n    filter: $f")
                }
                sb.append("\n")
            }
        }

        section("Activities", info.activities, { (it as android.content.pm.ActivityInfo).exported }) {
            (it as android.content.pm.ActivityInfo).permission
        }
        section("Receivers", info.receivers, { (it as android.content.pm.ActivityInfo).exported }) {
            (it as android.content.pm.ActivityInfo).permission
        }
        section("Services", info.services, { (it as android.content.pm.ServiceInfo).exported }) {
            (it as android.content.pm.ServiceInfo).permission
        }

        val providers = info.providers?.filter { it.exported } ?: emptyList()
        if (providers.isNotEmpty()) {
            sb.append("\nProviders: ${providers.size} exported (query with provider_query uri=content://<authority>/<path>)\n")
            for (p in providers) {
                sb.append(ProviderExplorer.describeProvider(p, indent = "  "))
            }
        }

        if (filters.isEmpty()) {
            sb.append(
                "\n(Intent filters could not be read from this app's manifest; " +
                    "components above are still callable by explicit name.)",
            )
        }
        return sb.toString()
    }

    // Asset cookies are small positive ints handed out per APK loaded into an
    // AssetManager; a package context's manager holds the framework, the
    // app's base APK, and every split. High enough for any real app.
    private const val MAX_ASSET_COOKIE = 64

    /**
     * The intent filters, per component, read out of the target app's
     * compiled AndroidManifest.xml. PackageManager never exposes these;
     * the manifest itself does, through the app's asset space.
     *
     * Apps installed as bundles are several APKs — base plus splits — and
     * each carries a manifest, most of them near-empty stubs. Opening
     * "AndroidManifest.xml" without saying which APK resolves against
     * whichever asset path comes first, which for bundled apps is a stub:
     * it parses cleanly and yields nothing, indistinguishable from an app
     * with no filters. So every asset cookie is walked, and each manifest
     * whose root `package` matches the target is parsed and merged. The
     * base manifest has the components; stubs contribute nothing; dynamic
     * feature splits can declare components of their own, which merging
     * picks up too.
     */
    private fun manifestFilters(context: Context, pkg: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val assets = try {
            context.createPackageContext(pkg, 0).assets
        } catch (e: Exception) {
            Log.w(TAG, "intent: no package context for $pkg", e)
            return result
        }
        for (cookie in 1..MAX_ASSET_COOKIE) {
            try {
                assets.openXmlResourceParser(cookie, "AndroidManifest.xml").use { parser ->
                    parseManifest(parser, pkg, result)
                }
            } catch (e: Exception) {
                // No such cookie, or an asset path with no manifest — both
                // ordinary; the loop just keeps walking.
            }
        }
        if (result.isEmpty()) Log.w(TAG, "intent: no filters found in any manifest of $pkg")
        return result
    }

    /** One manifest into [result] — skipped whole if it is not [pkg]'s. */
    private fun parseManifest(
        parser: android.content.res.XmlResourceParser,
        pkg: String,
        result: MutableMap<String, MutableList<String>>,
    ) {
        var component: String? = null
        var actions = mutableListOf<String>()
        var categories = mutableListOf<String>()
        var datas = mutableListOf<String>()
        var inFilter = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "manifest" -> {
                        // The framework and shared-library APKs are in the
                        // same asset space; only the target's own manifests
                        // count. The package attribute has no namespace.
                        val owner = parser.getAttributeValue(null, "package")
                        if (owner != null && owner != pkg) return
                    }
                    "activity", "activity-alias", "receiver", "service" -> {
                        val name = parser.getAttributeValue(ANDROID_NS, "name")
                        component = name?.let { if (it.startsWith(".")) pkg + it else it }
                    }
                    "intent-filter" -> {
                        inFilter = true
                        actions = mutableListOf()
                        categories = mutableListOf()
                        datas = mutableListOf()
                    }
                    "action" -> if (inFilter) {
                        parser.getAttributeValue(ANDROID_NS, "name")?.let { actions.add(it) }
                    }
                    "category" -> if (inFilter) {
                        parser.getAttributeValue(ANDROID_NS, "name")?.let { categories.add(it) }
                    }
                    "data" -> if (inFilter) {
                        val parts = mutableListOf<String>()
                        parser.getAttributeValue(ANDROID_NS, "scheme")?.let { parts.add("scheme=$it") }
                        parser.getAttributeValue(ANDROID_NS, "host")?.let { parts.add("host=$it") }
                        parser.getAttributeValue(ANDROID_NS, "port")?.let { parts.add("port=$it") }
                        parser.getAttributeValue(ANDROID_NS, "path")?.let { parts.add("path=$it") }
                        parser.getAttributeValue(ANDROID_NS, "pathPrefix")?.let { parts.add("pathPrefix=$it") }
                        parser.getAttributeValue(ANDROID_NS, "pathPattern")?.let { parts.add("pathPattern=$it") }
                        parser.getAttributeValue(ANDROID_NS, "mimeType")?.let { parts.add("mimeType=$it") }
                        if (parts.isNotEmpty()) datas.add(parts.joinToString(" "))
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "intent-filter" -> {
                        inFilter = false
                        val c = component
                        if (c != null && actions.isNotEmpty()) {
                            val desc = StringBuilder(actions.joinToString(", "))
                            val extraCats = categories.filter { it != "android.intent.category.DEFAULT" }
                            if (extraCats.isNotEmpty()) desc.append(" [${extraCats.joinToString(", ")}]")
                            if (datas.isNotEmpty()) desc.append(" (${datas.joinToString("; ")})")
                            result.getOrPut(c) { mutableListOf() }.add(desc.toString())
                        }
                    }
                    "activity", "activity-alias", "receiver", "service" -> component = null
                }
            }
            event = parser.next()
        }
    }

    // ------------------------------------------------------------- resolve

    /** Dry run: who would handle this intent, without sending anything. */
    fun resolve(context: Context, args: JSONObject): String {
        val (intent, problem) = buildIntent(args)
        if (intent == null) return "FAILED: $problem"
        val pm = context.packageManager

        fun describe(kind: String, infos: List<ResolveInfo>): String {
            if (infos.isEmpty()) return ""
            val lines = infos.joinToString("\n") { ri ->
                val ci = ri.activityInfo ?: ri.serviceInfo
                val name = ci?.let { "${it.packageName}/${it.name}" } ?: "?"
                val guard = (ri.activityInfo?.permission ?: ri.serviceInfo?.permission)
                    ?.let { " (guarded by $it)" } ?: ""
                "  $name$guard"
            }
            return "\nAs $kind, ${infos.size} handler(s):\n$lines"
        }

        val activities = pm.queryIntentActivities(intent, 0)
        val receivers = pm.queryBroadcastReceivers(intent, 0)
        val services = pm.queryIntentServices(intent, 0)
        val body = describe("activity", activities) +
            describe("broadcast", receivers) +
            describe("service", services)
        return if (body.isEmpty()) {
            "Nothing on the device handles this intent — no activity, receiver, or service matched."
        } else {
            "Intent ${describeIntent(intent)}:$body"
        }
    }

    // ---------------------------------------------------------------- send

    /**
     * Fire the intent. Preflights resolution so a dropped send is named
     * rather than silent; the send itself is fire-and-forget, which is all
     * Android offers for someone else's components.
     */
    fun send(context: Context, args: JSONObject): String {
        val (intent, problem) = buildIntent(args)
        if (intent == null) return "FAILED: $problem"
        val mode = args.optString("mode", "activity").trim().lowercase()
        val pm = context.packageManager

        return when (mode) {
            "activity" -> {
                if (pm.queryIntentActivities(intent, 0).isEmpty()) {
                    "FAILED: no activity on the device handles this intent."
                } else {
                    Preflight.activityLaunch(context)?.let { return "FAILED: $it" }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val locked = context.getSystemService(KeyguardManager::class.java)
                        ?.isKeyguardLocked == true
                    if (args.optBoolean("via_unlock_gate", false) && locked) {
                        // The caller has said this intent needs an unlocked
                        // phone; everything else stays raw as ever.
                        UnlockGate.launch(context, intent)
                        Log.i(TAG, "intent: behind unlock gate ${describeIntent(intent)}")
                        "The phone is locked, so it is now showing the unlock prompt. " +
                            "The activity starts the moment the user unlocks; if they " +
                            "leave the phone locked, the request expires after two " +
                            "minutes and nothing happens."
                    } else {
                        try {
                            context.startActivity(intent)
                            Log.i(TAG, "intent: started activity ${describeIntent(intent)}")
                            "Activity start sent for ${describeIntent(intent)}."
                        } catch (e: Exception) {
                            // A component can resolve and still refuse us — a
                            // guarding permission we do not hold throws
                            // SecurityException at start time, not at query
                            // time. Raw, per the advanced tier: the exception
                            // names the permission, which is the finding.
                            Log.w(TAG, "intent: activity start refused", e)
                            "FAILED: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                        }
                    }
                }
            }
            "broadcast" -> {
                val handlers = pm.queryBroadcastReceivers(intent, 0)
                if (handlers.isEmpty() && intent.component == null && intent.`package` == null) {
                    "FAILED: no receiver matches and the broadcast names no package — " +
                        "Android drops implicit broadcasts; set package or component."
                } else {
                    try {
                        context.sendBroadcast(intent)
                        Log.i(TAG, "intent: broadcast ${describeIntent(intent)} → ${handlers.size} manifest receiver(s)")
                        "Broadcast sent, ${describeIntent(intent)}. ${handlers.size} manifest receiver(s) matched" +
                            (if (handlers.isEmpty()) " — a running app could still catch it with a runtime receiver." else ".")
                    } catch (e: Exception) {
                        Log.w(TAG, "intent: broadcast refused", e)
                        "FAILED: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                    }
                }
            }
            "service", "foreground_service" -> {
                if (pm.queryIntentServices(intent, 0).isEmpty()) {
                    "FAILED: no service on the device handles this intent."
                } else {
                    try {
                        if (mode == "foreground_service") {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        Log.i(TAG, "intent: started $mode ${describeIntent(intent)}")
                        "Start sent to $mode for ${describeIntent(intent)}."
                    } catch (e: Exception) {
                        // Background-start restrictions land here as exceptions.
                        Log.w(TAG, "intent: $mode start failed", e)
                        "FAILED: Android refused the service start — apps in the background " +
                            "may not start another app's plain service; try mode=foreground_service or mode=broadcast."
                    }
                }
            }
            else -> "FAILED: mode must be activity, broadcast, service, or foreground_service."
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * An Intent from JSON: action, data URI, MIME type, categories,
     * package, explicit component "pkg/Class" (leading dot expanded), and
     * typed extras [{"key","type","value"}]. Types matter on the receiving
     * side — an app reading getIntExtra sees nothing if a string arrives.
     */
    private fun buildIntent(args: JSONObject): Pair<Intent?, String?> {
        val intent = Intent()
        var any = false
        args.optString("action").takeIf { it.isNotBlank() }?.let { intent.action = it; any = true }
        val data = args.optString("data").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val type = args.optString("mime_type").takeIf { it.isNotBlank() }
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }
        if (data != null || type != null) any = true
        val (cats, catsProblem) = Args.array(args, "categories", "category strings")
        if (catsProblem != null) return null to catsProblem
        cats?.let { for (i in 0 until it.length()) intent.addCategory(it.getString(i)) }
        args.optString("package").takeIf { it.isNotBlank() }?.let { intent.`package` = it; any = true }
        args.optString("component").takeIf { it.isNotBlank() }?.let { spec ->
            val slash = spec.indexOf('/')
            if (slash <= 0) return null to "component must be \"package/ClassName\"."
            val pkg = spec.substring(0, slash)
            var cls = spec.substring(slash + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            intent.component = ComponentName(pkg, cls)
            any = true
        }
        if (!any) return null to "give at least an action, a data URI, or a component."

        val (extras, extrasProblem) = Args.array(args, "extras", "{key, type, value} objects")
        if (extrasProblem != null) return null to extrasProblem
        extras?.let {
            val (bundle, problem) = parseExtras(it)
            if (bundle == null) return null to problem
            intent.putExtras(bundle)
        }
        return intent to null
    }

    /**
     * Typed extras [{"key","type","value"}] into a Bundle. Shared with
     * provider_call, whose input Bundle is the same grammar — one extras
     * format across the whole generic tier. Types matter on the receiving
     * side — an app reading getIntExtra sees nothing if a string arrives.
     */
    fun parseExtras(extras: JSONArray): Pair<Bundle?, String?> {
        val bundle = Bundle()
        for (i in 0 until extras.length()) {
            val e = extras.optJSONObject(i)
                ?: return null to "each extra is an object with key, type, and value."
            val key = e.optString("key")
            if (key.isBlank()) return null to "each extra needs a key."
            val problem = putExtra(bundle, key, e.optString("type", "string"), e)
            if (problem != null) return null to problem
        }
        return bundle to null
    }

    private fun putExtra(bundle: Bundle, key: String, type: String, e: JSONObject): String? {
        try {
            when (type.trim().lowercase()) {
                "string" -> bundle.putString(key, e.getString("value"))
                "int" -> bundle.putInt(key, e.getInt("value"))
                "long" -> bundle.putLong(key, e.getLong("value"))
                "boolean", "bool" -> bundle.putBoolean(key, e.getBoolean("value"))
                "float" -> bundle.putFloat(key, e.getDouble("value").toFloat())
                "double" -> bundle.putDouble(key, e.getDouble("value"))
                "uri" -> bundle.putParcelable(key, Uri.parse(e.getString("value")))
                "string_array" -> bundle.putStringArray(key, stringArray(e.getJSONArray("value")))
                "int_array" -> {
                    val a = e.getJSONArray("value")
                    bundle.putIntArray(key, IntArray(a.length()) { a.getInt(it) })
                }
                "long_array" -> {
                    val a = e.getJSONArray("value")
                    bundle.putLongArray(key, LongArray(a.length()) { a.getLong(it) })
                }
                // ArrayList, not array. Several of the platform's own extras
                // are specified this way (AlarmClock.EXTRA_DAYS is
                // ArrayList<Integer>), and an app reading
                // getIntegerArrayListExtra gets null from an int[] — no error,
                // just a silently missing extra. So the two are not
                // interchangeable and both have to be sendable.
                "int_arraylist" -> {
                    val a = e.getJSONArray("value")
                    bundle.putIntegerArrayList(key, ArrayList<Int>(a.length()).apply {
                        for (i in 0 until a.length()) add(a.getInt(i))
                    })
                }
                // Also what getCharSequenceArrayListExtra reads: the cast is
                // of the list, and a String is a CharSequence.
                "string_arraylist" -> {
                    val a = e.getJSONArray("value")
                    bundle.putStringArrayList(key, ArrayList<String>(a.length()).apply {
                        for (i in 0 until a.length()) add(a.getString(i))
                    })
                }
                // A KeyEvent parcelable — the payload of a MEDIA_BUTTON
                // intent, which is unsendable without it: the action only
                // says "a button was pressed", the KeyEvent says which.
                // Value is a key name, "up:" prefixed for ACTION_UP
                // (default is ACTION_DOWN, the half receivers act on).
                "key_event" -> {
                    val spec = e.getString("value").trim()
                    val up = spec.startsWith("up:", ignoreCase = true)
                    val name = spec.removePrefix("up:").removePrefix("UP:").removePrefix("down:").removePrefix("DOWN:")
                    val code = SessionExplorer.mediaKeyCode(name)
                        ?: KeyEvent.keyCodeFromString(name.uppercase())
                            .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                        ?: KeyEvent.keyCodeFromString("KEYCODE_${name.uppercase()}")
                            .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                        ?: return "extra \"$key\": unknown key \"$name\" — use play, pause, " +
                            "play_pause, next, previous, stop, fast_forward, rewind, or any " +
                            "android KEYCODE_* name."
                    bundle.putParcelable(
                        key,
                        KeyEvent(if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN, code),
                    )
                }
                else -> return "extra \"$key\": type must be string, int, long, boolean, " +
                    "float, double, uri, string_array, int_array, long_array, " +
                    "int_arraylist, string_arraylist, or key_event."
            }
        } catch (ex: Exception) {
            return "extra \"$key\": value does not match type $type."
        }
        return null
    }

    private fun stringArray(a: JSONArray): Array<String> = Array(a.length()) { a.getString(it) }

    /**
     * The intent as sent, extras included, so a result string shows what
     * actually went out: an extra the caller meant to attach but did not
     * is visible as its absence here.
     */
    private fun describeIntent(intent: Intent): String {
        val parts = mutableListOf<String>()
        intent.action?.let { parts.add(it) }
        intent.dataString?.let { parts.add(it) }
        intent.component?.let { parts.add(it.flattenToShortString()) }
            ?: intent.`package`?.let { parts.add("→ $it") }
        intent.extras?.takeIf { !it.isEmpty }?.let { b ->
            @Suppress("DEPRECATION")
            parts.add("with extras " + b.keySet().sorted().joinToString(", ") { k -> "$k=${Args.brief(b.get(k))}" })
        }
        return parts.joinToString(" ")
    }
}
