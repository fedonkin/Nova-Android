package com.example.nova

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Сбор условий для [VpnHealthAudit]: только чтение, ничего не меняется.
 *
 * Все обращения к системе завёрнуты в [runCatching]. Это не перестраховка ради
 * галочки: часть полей — скрытые настройки (`always_on_vpn_app`, `private_dns_mode`),
 * и на урезанных прошивках и ТВ-приставках чтение иного ключа заканчивается
 * `SecurityException`. Один недоступный ключ не должен превращать проверку в
 * пустой экран — он должен оставить одно поле неизвестным и показать остальное.
 *
 * Зовётся с фонового потока: `VpnService.prepare` — межпроцессный вызов,
 * вендорные экраны резолвятся десятком запросов к `PackageManager`, а
 * `ConnectivityManager.allNetworks` перечисляет сети.
 */
object VpnHealthProbe {

    private const val TAG = "VpnHealth"

    /** Сколько миллисекунд служба находится в текущем состоянии. */
    private const val STATE_AGE_UNKNOWN = 0L

    /**
     * Полосы ожидания приложения (`getAppStandbyBucket`).
     *
     * В SDK константы `STANDBY_BUCKET_*` помечены `@hide`: у `ActivityManager` их
     * не видит компилятор, а у `UsageStatsManager` — тоже. Значения заданы
     * документацией полос и не менялись с Android 9; сравниваем числами, потому
     * что имя константы для нас важнее её видимости.
     */
    private const val STANDBY_BUCKET_ACTIVE = 10
    private const val STANDBY_BUCKET_RARE = 40

    fun collect(context: Context): VpnHealthAudit.Facts {
        val app = context.applicationContext ?: context
        val clientData = runCatching { ClientData(app) }.getOrNull()
        val serviceState = runCatching { clientData?.getServiceState() }.getOrNull().orEmpty()
        val stateAge = stateAgeMs(clientData)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val networkFacts = readNetworks(connectivity, serviceState)

        val consentIntent = runCatching { VpnService.prepare(app) }.getOrNull()
        val consentGranted = consentIntent == null
        val consentDialogAvailable = if (consentIntent == null) {
            true
        } else {
            runCatching {
                app.packageManager.resolveActivity(consentIntent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }.getOrDefault(false)
        }

        val power = app.getSystemService(PowerManager::class.java)
        val activity = app.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { info ->
            runCatching { activity?.getMemoryInfo(info) }
        }
        val alwaysOnPackage = runCatching {
            Settings.Secure.getString(app.contentResolver, "always_on_vpn_app")
        }.getOrNull().orEmpty()
        val lockdown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            Settings.Secure.getInt(app.contentResolver, "always_on_vpn_lockdown", 0) == 1
        }.getOrDefault(false)
        val privateDnsMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Settings.Global.getString(app.contentResolver, "private_dns_mode") }
                .getOrNull().orEmpty().lowercase(Locale.US)
        } else {
            ""
        }

        return VpnHealthAudit.Facts(
            serviceConnected = serviceState == NovaVpnService.STATE_CONNECTED,
            serviceConnecting = serviceState == NovaVpnService.STATE_CONNECTING,
            serviceConnectingMs = stateAge,
            vpnConsentGranted = consentGranted,
            vpnConsentDialogAvailable = consentDialogAvailable,
            networkCount = networkFacts.count,
            internetAvailable = networkFacts.internet,
            captivePortal = networkFacts.captivePortal,
            wifiConnected = networkFacts.wifi,
            cellularConnected = networkFacts.cellular,
            airplaneMode = runCatching {
                Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }.getOrDefault(false),
            mobileDataEnabled = runCatching {
                Settings.Global.getInt(app.contentResolver, "mobile_data", 1) != 0
            }.getOrDefault(true),
            tunnelPresent = networkFacts.tunnel,
            foreignVpnDetected = networkFacts.foreignVpn,
            vpnOwnershipKnown = networkFacts.ownershipKnown,
            alwaysOnVpnPackage = alwaysOnPackage.trim(),
            ownPackageName = app.packageName,
            lockdownEnabled = lockdown,
            batteryOptimized = !isIgnoringBatteryOptimizations(app, power),
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                runCatching { activity?.isBackgroundRestricted == true }.getOrDefault(false),
            standbyBucketRare = isStandbyBucketRare(app),
            powerSaveMode = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false),
            deviceIdle = runCatching { power?.isDeviceIdleMode == true }.getOrDefault(false),
            vendorManagedBattery = runCatching {
                VendorBackgroundSettingsHelper.isVendorManagedBattery()
            }.getOrDefault(false),
            vendorBackgroundScreenAvailable = runCatching {
                VendorBackgroundSettingsHelper.canOpenBackground(app)
            }.getOrDefault(false),
            vendorAutoStartScreenAvailable = runCatching {
                VendorBackgroundSettingsHelper.canOpenAutoStart(app)
            }.getOrDefault(false),
            notificationsEnabled = runCatching {
                NotificationManagerCompat.from(app).areNotificationsEnabled()
            }.getOrDefault(true),
            notificationPermissionGranted = notificationPermissionGranted(app),
            dataSaverEnabled = networkFacts.dataSaverEnabled,
            dataSaverWhitelisted = networkFacts.dataSaverWhitelisted,
            privateDnsStrictMode = privateDnsMode == "hostname",
            lowMemory = memory.lowMemory,
            lastTransportNotice = runCatching {
                clientData?.getLastTransportNotice().orEmpty()
            }.getOrDefault(""),
        ).also { facts ->
            // Условия идут в журнал той же строкой, что видит человек. Без этого
            // отправленный отчёт не отвечает на главный вопрос: какие ограничения
            // действовали в минуту отказа.
            LogManager.i(describeFacts(facts), TAG)
        }
    }

    // ---- сети -------------------------------------------------------------

    private class NetworkFacts(
        val count: Int,
        val internet: Boolean,
        val captivePortal: Boolean,
        val wifi: Boolean,
        val cellular: Boolean,
        val tunnel: Boolean,
        val foreignVpn: Boolean,
        val ownershipKnown: Boolean,
        val dataSaverEnabled: Boolean,
        val dataSaverWhitelisted: Boolean,
    )

    private fun readNetworks(
        connectivity: ConnectivityManager?,
        serviceState: String,
    ): NetworkFacts {
        if (connectivity == null) {
            return NetworkFacts(1, true, false, false, false, false, false, false, false, false)
        }
        @Suppress("DEPRECATION")
        val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        val ourServiceActive = serviceState == NovaVpnService.STATE_CONNECTED ||
            serviceState == NovaVpnService.STATE_CONNECTING
        var internet = false
        var captivePortal = false
        var wifi = false
        var cellular = false
        var tunnel = false
        var foreignVpn = false
        var ownershipKnown = false

        networks.forEach { network ->
            val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                captivePortal = true
            }
            val vpnNetwork = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (vpnNetwork) tunnel = true

            val ownership = if (vpnNetwork) vpnOwnership(caps) else null
            if (ownership != null) ownershipKnown = true
            // Свой туннель чужим не считается никогда: во время подключения
            // владельца на старых прошивках не прочитать, а ложное «чужой VPN»
            // отправило бы человека выключать наш же туннель.
            if (ownership == false && !ourServiceActive) foreignVpn = true

            // Свой туннель считается выходом в интернет и без отметки
            // «проверена»: ConnectivityManager ставит её с задержкой в секунды, а
            // экран, открытый сразу после подключения, иначе объявил бы блокером
            // исправный туннель. Чужой VPN и подвисший туннель в счёт не идут.
            val ourTunnel = vpnNetwork && ourServiceActive && ownership != false
            val usable = !(vpnNetwork && ownership == false)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable && hasInternet && (validated || ourTunnel)) {
                internet = true
            }
        }

        val dataSaver = runCatching { connectivity.restrictBackgroundStatus }.getOrNull()
        return NetworkFacts(
            count = networks.size,
            internet = internet,
            captivePortal = captivePortal,
            wifi = wifi,
            cellular = cellular,
            tunnel = tunnel,
            foreignVpn = foreignVpn,
            ownershipKnown = ownershipKnown,
            dataSaverEnabled = dataSaver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            dataSaverWhitelisted =
                dataSaver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
        )
    }

    /**
     * Чей это туннель: `true` — наш, `false` — чужой, `null` — прочитать нечем.
     *
     * Три ответа, а не два: на Android 9 владелец сети приложению не виден, и
     * «не знаю» не должно выглядеть как «чужой» — иначе экран обвинит человека в
     * том, чего нет.
     */
    private fun vpnOwnership(caps: NetworkCapabilities): Boolean? {
        NetworkCapabilitiesCompat.getOwnerUid(caps)?.let { ownerUid ->
            return ownerUid == android.os.Process.myUid()
        }
        val label = NetworkCapabilitiesCompat.describeTransport(caps)
        if (label.isBlank()) return null
        return label.contains("Nova", ignoreCase = true)
    }

    // ---- фон --------------------------------------------------------------

    /**
     * Оптимизация батареи «выключена» ровно тогда, когда система отвечает, что
     * приложение в белом списке. До Android 6 такого признака нет вовсе — там
     * нет и Doze, ради которого он нужен.
     *
     * При ошибке чтения отвечаем «ограничения нет»: обвинять прошивку в том,
     * чего мы не видели, хуже, чем промолчать. Вендорные прошивки выставляют
     * свой признак, не связанный с системным, и о них говорит отдельная находка
     * «прошивка управляет фоном по-своему» — вендорное разрешение не выдаётся
     * здесь за системное.
     */
    private fun isIgnoringBatteryOptimizations(context: Context, power: PowerManager?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(true)
    }

    private fun isStandbyBucketRare(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val usage = runCatching {
            context.getSystemService(UsageStatsManager::class.java)
        }.getOrNull() ?: return false
        val bucket = runCatching { usage.appStandbyBucket }
            .getOrDefault(STANDBY_BUCKET_ACTIVE)
        return bucket >= STANDBY_BUCKET_RARE
    }

    private fun notificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        // Строкой, а не `Manifest.permission.POST_NOTIFICATIONS`: константа
        // появилась в API 33, и обращение к полю читается как требование этой
        // версии. Разрешение проверяется только под её же условием.
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS",
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stateAgeMs(clientData: ClientData?): Long {
        if (clientData == null) return STATE_AGE_UNKNOWN
        val updatedAt = runCatching { clientData.getServiceStateUpdatedAt() }.getOrDefault(0L)
        if (updatedAt <= 0L) return STATE_AGE_UNKNOWN
        return (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    }

    // ---- отчёт ------------------------------------------------------------

    /**
     * Текст для буфера обмена и отправки. Человекочитаемая часть — находки; ниже,
     * после черты, — сами условия, чтобы разбор не приходилось повторять.
     *
     * `facts == null` — сбор не удался. Тогда так и пишем: подставлять сюда
     * значения по умолчанию значило бы выдать «всё в порядке» за результат.
     */
    fun buildReport(
        facts: VpnHealthAudit.Facts?,
        findings: List<VpnHealthAudit.Finding>,
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return buildString {
            appendLine("Nova: почему не работает VPN")
            appendLine("generated_at=$stamp")
            appendLine(
                "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                    "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}, " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}",
            )
            appendLine()
            findings.forEach { finding ->
                append('[').append(finding.severity.name.take(1)).append("] ")
                append(finding.id).append(" — ").append(finding.title)
                if (finding.detail.isNotBlank()) {
                    append(": ").append(finding.detail.replace('\n', ' '))
                }
                appendLine()
            }
            appendLine()
            if (facts == null) {
                appendLine("--- условия не собраны ---")
            } else {
                appendLine("--- условия ---")
                appendLine(describeFacts(facts))
            }
        }
    }

    /** Условия одной строкой: те же поля, что видит разбор, без имён и адресов. */
    fun describeFacts(facts: VpnHealthAudit.Facts): String {
        fun yesNo(value: Boolean) = if (value) "да" else "нет"
        val state = when {
            facts.serviceConnected -> "CONNECTED"
            facts.serviceConnecting -> "CONNECTING"
            else -> "STOPPED"
        }
        return "Проверка ограничений: " +
            "состояние=$state" +
            ", сетей=${facts.networkCount}, интернет=${yesNo(facts.internetAvailable)}" +
            ", портал=${yesNo(facts.captivePortal)}" +
            ", wifi=${yesNo(facts.wifiConnected)}, мобильные=${yesNo(facts.cellularConnected)}" +
            ", режим полёта=${yesNo(facts.airplaneMode)}" +
            ", туннель=${yesNo(facts.tunnelPresent)}" +
            ", чужой VPN=${yesNo(facts.foreignVpnDetected)}" +
            ", согласие=${yesNo(facts.vpnConsentGranted)}" +
            ", постоянный VPN=" + facts.alwaysOnVpnPackage.ifBlank { "нет" } +
            ", блокировка без VPN=${yesNo(facts.lockdownEnabled)}" +
            ", оптимизация батареи=${yesNo(facts.batteryOptimized)}" +
            ", фон запрещён=${yesNo(facts.backgroundRestricted)}" +
            ", редкая корзина=${yesNo(facts.standbyBucketRare)}" +
            ", вендорный фон=${yesNo(facts.vendorManagedBattery)}" +
            ", уведомления=${yesNo(facts.notificationsEnabled)}" +
            ", экономия трафика=${yesNo(facts.dataSaverEnabled)}" +
            ", частный DNS строгий=${yesNo(facts.privateDnsStrictMode)}" +
            ", мало памяти=${yesNo(facts.lowMemory)}"
    }
}
