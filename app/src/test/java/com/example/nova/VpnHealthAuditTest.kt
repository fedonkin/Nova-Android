package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила проверки ограничений.
 *
 * Каждая находка — обещание человеку: «дело вот в этом, и вот экран, где это
 * снимается». Поэтому тесты смотрят не только на наличие находки, но и на её
 * тяжесть, набор кнопок и на то, что пройденная проверка не выдаёт себя за
 * найденную причину.
 */
class VpnHealthAuditTest {

    private fun audit(facts: VpnHealthAudit.Facts) = VpnHealthAudit.audit(facts)

    private fun ids(facts: VpnHealthAudit.Facts): List<String> =
        audit(facts).map { it.id }

    private fun finding(facts: VpnHealthAudit.Facts, id: String): VpnHealthAudit.Finding? =
        audit(facts).firstOrNull { it.id == id }

    @Test
    fun `по умолчанию ограничений нет`() {
        val findings = audit(VpnHealthAudit.Facts())
        assertTrue(
            "найдено: " + VpnHealthAudit.problems(findings).map { it.id },
            VpnHealthAudit.problems(findings).isEmpty(),
        )
        assertTrue(findings.any { it.id == "vpn_consent_ok" })
        assertTrue(findings.any { it.id == "tunnel_ok" || it.severity == VpnHealthAudit.Severity.OK })
    }

    @Test
    fun `без согласия на VPN подключение не начнётся`() {
        val facts = VpnHealthAudit.Facts(vpnConsentGranted = false)
        val consent = finding(facts, "vpn_consent")
        assertEquals(VpnHealthAudit.Severity.BLOCKER, consent?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.VPN_CONSENT), consent?.fixes)
        assertTrue(VpnHealthAudit.hasBlockers(audit(facts)))
        assertFalse("пройденная проверка не должна оставаться", ids(facts).contains("vpn_consent_ok"))
    }

    @Test
    fun `если окна согласия нет в прошивке — подсказка про appops и без кнопки`() {
        val facts = VpnHealthAudit.Facts(
            vpnConsentGranted = false,
            vpnConsentDialogAvailable = false,
        )
        val missing = finding(facts, "vpn_consent_dialog")
        assertEquals(VpnHealthAudit.Severity.BLOCKER, missing?.severity)
        assertTrue(
            "подсказка должна нести обходной путь: " + missing?.detail,
            missing?.detail.orEmpty().contains("appops"),
        )
        assertTrue("нажимать некуда — кнопок быть не должно", missing?.fixes.orEmpty().isEmpty())
    }

    @Test
    fun `без сети виновата сеть, а не VPN`() {
        val noNetworks = VpnHealthAudit.Facts(networkCount = 0, internetAvailable = false)
        assertEquals(VpnHealthAudit.Severity.BLOCKER, finding(noNetworks, "no_network")?.severity)
        assertEquals(
            listOf(VpnHealthAudit.Fix.NETWORK),
            finding(noNetworks, "no_network")?.fixes,
        )

        val noInternet = VpnHealthAudit.Facts(internetAvailable = false)
        assertEquals(VpnHealthAudit.Severity.BLOCKER, finding(noInternet, "no_internet")?.severity)
    }

    @Test
    fun `мобильные данные выключены и wifi нет — так и говорим`() {
        val facts = VpnHealthAudit.Facts(
            internetAvailable = false,
            wifiConnected = false,
            mobileDataEnabled = false,
        )
        val detail = finding(facts, "no_internet")?.detail.orEmpty()
        assertTrue("подробность: $detail", detail.contains("Мобильные данные"))
    }

    @Test
    fun `режим полёта называется своим именем`() {
        val facts = VpnHealthAudit.Facts(
            networkCount = 0,
            internetAvailable = false,
            airplaneMode = true,
        )
        assertTrue(finding(facts, "no_network")?.detail.orEmpty().contains("режим полёта"))
    }

    @Test
    fun `портал входа объясняет, почему сеть без интернета`() {
        val facts = VpnHealthAudit.Facts(internetAvailable = true, captivePortal = true)
        val portal = finding(facts, "captive_portal")
        assertEquals(VpnHealthAudit.Severity.WARNING, portal?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.NETWORK), portal?.fixes)
    }

    @Test
    fun `чужой VPN занимает единственный туннель`() {
        val facts = VpnHealthAudit.Facts(foreignVpnDetected = true, tunnelPresent = true)
        val foreign = finding(facts, "foreign_vpn")
        assertEquals(VpnHealthAudit.Severity.BLOCKER, foreign?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.VPN_SETTINGS), foreign?.fixes)
        assertFalse(ids(facts).contains("vpn_slot_ok"))
    }

    @Test
    fun `если владельца VPN-сети не прочитать, чужого VPN не выдумываем`() {
        val facts = VpnHealthAudit.Facts(
            tunnelPresent = true,
            vpnOwnershipKnown = false,
            serviceConnected = true,
        )
        assertFalse(ids(facts).contains("vpn_slot_ok"))
        assertFalse(ids(facts).contains("foreign_vpn"))
        assertTrue("свой туннель при этом на месте", ids(facts).contains("tunnel_ok"))
    }

    @Test
    fun `постоянный VPN на другое приложение перехватывает подключение`() {
        val facts = VpnHealthAudit.Facts(alwaysOnVpnPackage = "com.other.vpn")
        val alwaysOn = finding(facts, "always_on_other_app")
        assertEquals(VpnHealthAudit.Severity.WARNING, alwaysOn?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.VPN_SETTINGS), alwaysOn?.fixes)
    }

    @Test
    fun `постоянный VPN на Nova — это не проблема, а подтверждение`() {
        val facts = VpnHealthAudit.Facts(alwaysOnVpnPackage = "com.brent.nova")
        assertTrue(ids(facts).contains("always_on_ok"))
        assertFalse(ids(facts).contains("always_on_other_app"))
    }

    @Test
    fun `соединение без туннеля — блокер с журналом в кнопке`() {
        val facts = VpnHealthAudit.Facts(serviceConnected = true, tunnelPresent = false)
        val missing = finding(facts, "tunnel_missing")
        assertEquals(VpnHealthAudit.Severity.BLOCKER, missing?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.LOGS), missing?.fixes)
        assertFalse(ids(facts).contains("tunnel_ok"))
    }

    @Test
    fun `зависшее подключение замечается только после минуты`() {
        val short = VpnHealthAudit.Facts(
            serviceConnecting = true,
            serviceConnectingMs = VpnHealthAudit.CONNECTING_TIMEOUT_MS - 1,
        )
        assertFalse(ids(short).contains("connecting_too_long"))

        val long = VpnHealthAudit.Facts(
            serviceConnecting = true,
            serviceConnectingMs = VpnHealthAudit.CONNECTING_TIMEOUT_MS,
        )
        assertEquals(VpnHealthAudit.Severity.WARNING, finding(long, "connecting_too_long")?.severity)
    }

    @Test
    fun `оптимизация батареи — предупреждение с кнопкой батареи`() {
        val facts = VpnHealthAudit.Facts(batteryOptimized = true)
        val battery = finding(facts, "battery_optimized")
        assertEquals(VpnHealthAudit.Severity.WARNING, battery?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.BATTERY), battery?.fixes)
        assertFalse(ids(facts).contains("battery_ok"))
    }

    @Test
    fun `запрещённый фон и редкая корзина называются по отдельности`() {
        val facts = VpnHealthAudit.Facts(
            backgroundRestricted = true,
            standbyBucketRare = true,
        )
        assertEquals(
            listOf(VpnHealthAudit.Fix.APP_DETAILS),
            finding(facts, "background_restricted")?.fixes,
        )
        assertEquals(listOf(VpnHealthAudit.Fix.BATTERY), finding(facts, "standby_rare")?.fixes)
    }

    @Test
    fun `вендорная прошивка получает обе кнопки, которые на ней существуют`() {
        val both = VpnHealthAudit.Facts(
            vendorManagedBattery = true,
            vendorBackgroundScreenAvailable = true,
            vendorAutoStartScreenAvailable = true,
        )
        assertEquals(
            listOf(VpnHealthAudit.Fix.VENDOR_BACKGROUND, VpnHealthAudit.Fix.VENDOR_AUTOSTART),
            finding(both, "vendor_background")?.fixes,
        )

        val onlyAutostart = VpnHealthAudit.Facts(
            vendorManagedBattery = true,
            vendorAutoStartScreenAvailable = true,
        )
        assertEquals(
            listOf(VpnHealthAudit.Fix.VENDOR_AUTOSTART),
            finding(onlyAutostart, "vendor_background")?.fixes,
        )
    }

    @Test
    fun `вендорный список без единого экрана не оставляет человека без кнопки`() {
        val facts = VpnHealthAudit.Facts(vendorManagedBattery = true)
        assertEquals(
            listOf(VpnHealthAudit.Fix.APP_DETAILS),
            finding(facts, "vendor_background")?.fixes,
        )
    }

    @Test
    fun `выключенные уведомления — объяснение, а не блокер`() {
        val noPermission = VpnHealthAudit.Facts(notificationPermissionGranted = false)
        assertEquals(
            VpnHealthAudit.Severity.NOTICE,
            finding(noPermission, "notification_permission")?.severity,
        )
        val disabled = VpnHealthAudit.Facts(notificationsEnabled = false)
        assertEquals(
            VpnHealthAudit.Severity.NOTICE,
            finding(disabled, "notifications_disabled")?.severity,
        )
        assertEquals(
            listOf(VpnHealthAudit.Fix.NOTIFICATIONS),
            finding(disabled, "notifications_disabled")?.fixes,
        )
    }

    @Test
    fun `экономия трафика опасна только без исключения для Nova`() {
        val restricted = VpnHealthAudit.Facts(dataSaverEnabled = true)
        assertEquals(VpnHealthAudit.Severity.WARNING, finding(restricted, "data_saver")?.severity)

        val whitelisted = VpnHealthAudit.Facts(
            dataSaverEnabled = true,
            dataSaverWhitelisted = true,
        )
        assertFalse(ids(whitelisted).contains("data_saver"))
        assertTrue(ids(whitelisted).contains("data_saver_ok"))
    }

    @Test
    fun `частный DNS предупреждает только в ручном режиме`() {
        val strict = VpnHealthAudit.Facts(privateDnsStrictMode = true)
        val dns = finding(strict, "private_dns")
        assertEquals(VpnHealthAudit.Severity.WARNING, dns?.severity)
        assertEquals(listOf(VpnHealthAudit.Fix.PRIVATE_DNS), dns?.fixes)
        assertTrue(ids(VpnHealthAudit.Facts()).contains("private_dns_ok"))
    }

    @Test
    fun `объяснения службы попадают на экран вместе с журналом`() {
        val facts = VpnHealthAudit.Facts(lastTransportNotice = "Среди импортированных нет VLESS")
        val notice = finding(facts, "last_transport_notice")
        assertEquals(VpnHealthAudit.Severity.NOTICE, notice?.severity)
        assertEquals("Среди импортированных нет VLESS", notice?.detail)
        assertEquals(listOf(VpnHealthAudit.Fix.LOGS), notice?.fixes)
    }

    @Test
    fun `блокеры идут раньше предупреждений, предупреждения — раньше подтверждений`() {
        val facts = VpnHealthAudit.Facts(
            vpnConsentGranted = false,
            batteryOptimized = true,
            powerSaveMode = true,
        )
        val severities = audit(facts).map { it.severity }
        assertEquals(severities.sortedBy { it.ordinal }, severities)
        assertEquals(VpnHealthAudit.Severity.BLOCKER, severities.first())
        assertEquals(VpnHealthAudit.Severity.OK, severities.last())
    }

    @Test
    fun `подписи кнопок — действия, а не заголовки разделов`() {
        VpnHealthAudit.Fix.values().forEach { fix ->
            assertTrue("пустая подпись у $fix", fix.label().isNotBlank())
        }
        assertEquals("Разрешить VPN", VpnHealthAudit.Fix.VPN_CONSENT.label())
    }
}
