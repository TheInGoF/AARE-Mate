package net.prototux.aare.host

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Telephony
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.media.MediaDiscovery
import org.xmlpull.v1.XmlPullParser

/** An app that declares Android Auto support but that AARE doesn't show, and why. */
data class UnsupportedCarApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    /** The `<uses name="…">` entries of its `automotive_app_desc`. */
    val uses: Set<String>,
    val reason: String,
)

/** An app on the phone listed in the car (a messaging or media app), by package. */
data class PhoneApp(val packageName: String, val label: String, val icon: Bitmap?)

/**
 * Finds every app that declares Android Auto support (`com.google.android.gms.car.application`
 * meta-data): the messaging apps among them, and the ones AARE can't show, to tell the user.
 */
object AndroidAutoApps {
    private const val META_CAR_APPLICATION = "com.google.android.gms.car.application"
    private val MESSAGING_USES = setOf("notification", "sms")

    /**
     * Messaging apps, as Android Auto finds them: apps declaring `<uses name="notification"/>`
     * (or `sms`), plus the default SMS app.
     */
    fun messagingApps(context: Context, iconSizePx: Int = 128): List<PhoneApp> {
        val pm = context.packageManager
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return declaring(context)
            .filter { (app, uses) -> uses.any { it in MESSAGING_USES } || app.packageName == sms }
            .map { (app, _) -> PhoneApp(app.packageName, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull()) }
            .let { found ->
                if (sms == null || found.any { it.packageName == sms }) found
                else found + listOfNotNull(runCatching {
                    val app = pm.getApplicationInfo(sms, 0)
                    PhoneApp(sms, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull())
                }.getOrNull())
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Media apps, as the car's launcher lists them (see MediaDiscovery): one per package. */
    fun mediaApps(context: Context, iconSizePx: Int = 128): List<PhoneApp> {
        val pm = context.packageManager
        return mediaPackages(pm).filter { it != context.packageName }.mapNotNull { pkg ->
            runCatching {
                val app = pm.getApplicationInfo(pkg, 0)
                PhoneApp(pkg, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull())
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Apps declaring Android Auto support that AARE lists neither as car apps ([carApps]) nor as media or messaging apps. */
    fun unsupported(context: Context, carApps: Collection<String>, iconSizePx: Int = 96): List<UnsupportedCarApp> {
        val pm = context.packageManager
        val shown = carApps.toSet() + mediaPackages(pm)
        return declaring(context)
            .filter { (app, uses) -> app.packageName !in shown && uses.none { it in MESSAGING_USES } }
            .map { (app, uses) ->
                UnsupportedCarApp(
                    packageName = app.packageName,
                    label = app.loadLabel(pm).toString(),
                    icon = runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
                    uses = uses,
                    reason = reason(context, app.packageName, uses),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * For the diagnosis log (System I-013, 18.11.2026: "Suche nach, warum Android-Auto-native Apps nicht automatisch in
     * AARE auftauchen"): every app that declares Android Auto support and is not in the car's list ([listed]: the
     * packages shown there), with what it declares and why Mate cannot show it - so the reason is in the exported log
     * and need not be read off the phone app's page. Apps that refused Mate as their host in the car are named too.
     */
    fun report(context: Context, listed: Collection<String>): List<String> {
        val pm = context.packageManager
        val shown = listed.toSet()
        val lines = ArrayList<String>()
        for ((app, uses) in declaring(context)) {
            if (app.packageName in shown) continue
            // the car service with its categories, a switched-off one too
            @Suppress("DEPRECATION")
            val service = pm.queryIntentServices(Intent(CarAppInfo.SERVICE_ACTION).setPackage(app.packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_RESOLVED_FILTER).firstOrNull()
            val cats = buildList { service?.filter?.categoriesIterator()?.forEach { add(it.substringAfterLast('.')) } }
            lines += "apps: not listed - ${app.loadLabel(pm)} (${app.packageName}), declares ${uses.joinToString("+").ifEmpty { "nothing" }}" +
                (if (service != null) ", car service ${cats.joinToString("+").ifEmpty { "without category" }}" else "") +
                ": " + reason(context, app.packageName, uses, log = true)
        }
        HostRejections.refused(context).forEach { pkg ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            // a known limit of that app, not a fault of Mate: worded without "refused", which made it an ERROR in the log's
            // list of problems twice at every connection (21.11.2026)
            lines += "apps: $name ($pkg) is listed, but takes Google's Android Auto only as its host, not Mate"
        }
        return lines.sorted()
    }

    /** Enabled apps with `com.google.android.gms.car.application` meta-data, with their `<uses>` entries. */
    private fun declaring(context: Context): List<Pair<ApplicationInfo, Set<String>>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.enabled && it.metaData?.containsKey(META_CAR_APPLICATION) == true && it.packageName != context.packageName }
            .map { it to uses(pm, it) }
    }

    /** Packages with a media browser service, which AARE lists as media apps (see MediaDiscovery). */
    private fun mediaPackages(pm: PackageManager): Set<String> =
        listOf(MediaDiscovery.ACTION_MEDIA3_LIBRARY, MediaDiscovery.ACTION_LEGACY_BROWSER).flatMap { action ->
            @Suppress("DEPRECATION") pm.queryIntentServices(Intent(action), 0)
                .mapNotNull { ri -> ri.serviceInfo?.takeIf { it.exported }?.packageName }
        }.toSet()

    /**
     * Why an app with Android Auto support is not in Mate's list (System I-013, L-011: "Native Android-Auto-Apps, die auf
     * dem Tablet installiert sind, werden in Mate nicht angezeigt"): for an app with a car screen of templates its car
     * service is looked at closely - switched off, not open to other apps, or behind a permission.
     */
    private fun reason(context: Context, pkg: String, uses: Set<String>, log: Boolean = false): String {
        val pm = context.packageManager
        // the log is English whatever the device's language (a change of language wrote the whole report anew);
        // the phone app's page stays in the device's language
        val r = if (!log) context.resources else context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(java.util.Locale.ENGLISH) }).resources
        if ("template" in uses) {
            @Suppress("DEPRECATION")
            val any = pm.queryIntentServices(Intent(CarAppInfo.SERVICE_ACTION).setPackage(pkg),
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                .mapNotNull { it.serviceInfo }
            val si = any.firstOrNull()
            val why = when {
                si == null -> r.getString(net.prototux.aare.R.string.aa_reason_template_none)
                !si.enabled || !si.applicationInfo.enabled || runCatching { pm.getComponentEnabledSetting(android.content.ComponentName(pkg, si.name)) }.getOrNull() ==
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> r.getString(net.prototux.aare.R.string.aa_reason_template_disabled)
                !si.exported -> r.getString(net.prototux.aare.R.string.aa_reason_template_private)
                si.permission != null && context.checkSelfPermission(si.permission) != PackageManager.PERMISSION_GRANTED ->
                    r.getString(net.prototux.aare.R.string.aa_reason_template_permission, si.permission)
                else -> r.getString(net.prototux.aare.R.string.aa_reason_template_none)
            }
            android.util.Log.i("MateApps", "car app $pkg not listed: $why (service ${si?.name})")
            return why
        }
        return when {
            "media" in uses -> r.getString(net.prototux.aare.R.string.aa_reason_media)
            uses.isEmpty() -> r.getString(net.prototux.aare.R.string.aa_reason_private)
            else -> r.getString(net.prototux.aare.R.string.aa_reason_private_uses, uses.joinToString())
        }
    }

    /** The `<uses name="…">` entries of the app's `automotive_app_desc` XML. */
    private fun uses(pm: PackageManager, app: ApplicationInfo): Set<String> {
        val res = app.metaData?.getInt(META_CAR_APPLICATION, 0)?.takeIf { it != 0 } ?: return emptySet()
        return runCatching {
            pm.getResourcesForApplication(app).getXml(res).use { xml ->
                buildSet {
                    var type = xml.eventType
                    while (type != XmlPullParser.END_DOCUMENT) {
                        if (type == XmlPullParser.START_TAG && xml.name == "uses") xml.getAttributeValue(null, "name")?.let { add(it) }
                        type = xml.next()
                    }
                }
            }
        }.getOrDefault(emptySet())
    }
}
