package com.example.nova

/**
 * Разбор условий, в которых живёт VPN: что именно мешает Nova подключиться или
 * удержать туннель.
 *
 * ## Зачем отдельный разбор
 *
 * README заканчивается списком «если связь не восстановилась»: другое приложение
 * VPN, ограничения фона, выбор региона, импортированный профиль. Человек проходит
 * этот список руками, а добрая половина пунктов вообще не видна снаружи: занятый
 * другим VPN туннель, постоянный VPN на чужое приложение, частный DNS на
 * заблокированный резолвер, редкая корзина ожидания. Приложение при этом честно
 * пишет «не удалось подключиться» — и не говорит, что дело не в нём.
 *
 * Здесь тот же список превращён в проверки. Каждая возвращает [Finding]: что
 * найдено, чем это грозит и какой системный экран это лечит. Ничего не чинится
 * само — приложение не имеет права молча менять чужие настройки, — но человек
 * получает не догадку, а причину и кнопку.
 *
 * ## Почему без Android
 *
 * Класс не знает ни одного `android.*`: условия собирает [VpnHealthProbe], а
 * здесь остаётся только «если A, то B». Поэтому правила проверяются обычными
 * unit-тестами — как [RegionTransportPolicy] и [ProfileIssueLabels], — и ни одна
 * ветка не требует телефона под рукой.
 *
 * ## Что считается ответом
 *
 * Проверка, которая ничего не нашла, всё равно возвращает находку с [Severity.OK]:
 * экран показывает их отдельным списком «проверено и в порядке». Молчание о
 * пройденной проверке читается как «её не было», и человек идёт искать причину
 * там, где её уже исключили.
 *
 * Проверка, которую нечем выполнить (владельца VPN-сети прочитать не удалось),
 * не возвращает ничего. «Другого VPN нет» и «не знаю» — разные ответы, и второй
 * не должен выглядеть как первый.
 */
object VpnHealthAudit {

    /** Насколько найденное мешает работать. Порядок объявления — порядок показа. */
    enum class Severity {
        /** Туннель не поднимется или уже не держится. */
        BLOCKER,

        /** Туннель поднимется, но система может оборвать его или замедлить. */
        WARNING,

        /** Не мешает, но объясняет то, что человек видит: «сети нет», «долго висит». */
        NOTICE,

        /** Проверено, всё в порядке. */
        OK,
    }

    /** Системный экран или наш экран, который снимает найденное ограничение. */
    enum class Fix {
        VPN_CONSENT,
        VPN_SETTINGS,
        BATTERY,
        VENDOR_BACKGROUND,
        VENDOR_AUTOSTART,
        NOTIFICATIONS,
        APP_DETAILS,
        DATA_SAVER,
        PRIVATE_DNS,
        NETWORK,
        LOGS,
        ;

        /**
         * Подпись кнопки. Глагол первым: «Настройки VPN» на кнопке читается как
         * заголовок раздела, а не как действие.
         */
        fun label(): String = when (this) {
            VPN_CONSENT -> "Разрешить VPN"
            VPN_SETTINGS -> "Открыть настройки VPN"
            BATTERY -> "Разрешить работу в фоне"
            VENDOR_BACKGROUND -> "Фон в прошивке"
            VENDOR_AUTOSTART -> "Автозапуск"
            NOTIFICATIONS -> "Открыть уведомления"
            APP_DETAILS -> "Настройки приложения"
            DATA_SAVER -> "Экономия трафика"
            PRIVATE_DNS -> "Частный DNS"
            NETWORK -> "Сеть и интернет"
            LOGS -> "Открыть журнал"
        }
    }

    /**
     * Одна находка.
     *
     * [id] — постоянный ключ: по нему журнал и тесты отличают находку от её
     * переписанного заголовка. [fixes] — не одна кнопка, потому что у вендорной
     * прошивки ограничений два (фон и автозапуск), и оба лечатся разными экранами.
     */
    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String = "",
        val fixes: List<Fix> = emptyList(),
    )

    /**
     * Условия, в которых находится приложение. Значения по умолчанию — «всё
     * хорошо»: тесту тогда достаточно назвать только то, что он проверяет.
     */
    data class Facts(
        /** Служба сообщает CONNECTED. */
        val serviceConnected: Boolean = false,
        /** Служба сообщает CONNECTING. */
        val serviceConnecting: Boolean = false,
        /** Сколько миллисекунд служба находится в текущем состоянии. */
        val serviceConnectingMs: Long = 0L,
        val vpnConsentGranted: Boolean = true,
        /** Прошивка умеет показать системное окно согласия на VPN. */
        val vpnConsentDialogAvailable: Boolean = true,
        val networkCount: Int = 1,
        val internetAvailable: Boolean = true,
        val captivePortal: Boolean = false,
        val wifiConnected: Boolean = false,
        val cellularConnected: Boolean = false,
        val airplaneMode: Boolean = false,
        val mobileDataEnabled: Boolean = true,
        /** В системе поднят VPN-интерфейс — любой, не обязательно наш. */
        val tunnelPresent: Boolean = false,
        /** Туннель держит другое приложение. */
        val foreignVpnDetected: Boolean = false,
        /** Владельца VPN-сети удалось определить; иначе «чужого VPN нет» не утверждаем. */
        val vpnOwnershipKnown: Boolean = true,
        val alwaysOnVpnPackage: String = "",
        val ownPackageName: String = "com.brent.nova",
        val lockdownEnabled: Boolean = false,
        /** Системная оптимизация батареи включена — приложение могут выгрузить. */
        val batteryOptimized: Boolean = false,
        val backgroundRestricted: Boolean = false,
        /** Корзина ожидания RARE или хуже. */
        val standbyBucketRare: Boolean = false,
        val powerSaveMode: Boolean = false,
        val deviceIdle: Boolean = false,
        /** Прошивка ведёт собственный список фоновых прав (MIUI, EMUI, ColorOS…). */
        val vendorManagedBattery: Boolean = false,
        val vendorBackgroundScreenAvailable: Boolean = false,
        val vendorAutoStartScreenAvailable: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val notificationPermissionGranted: Boolean = true,
        val dataSaverEnabled: Boolean = false,
        val dataSaverWhitelisted: Boolean = false,
        /** Частный DNS задан именем сервера, а не «автоматически». */
        val privateDnsStrictMode: Boolean = false,
        val lowMemory: Boolean = false,
        /** Последнее пояснение службы: почему не вышло в прошлый раз. */
        val lastTransportNotice: String = "",
    )

    /** Порог, после которого «подключается» считается зависшим. */
    const val CONNECTING_TIMEOUT_MS = 60_000L

    fun audit(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += consentFindings(facts)
        findings += networkFindings(facts)
        findings += tunnelFindings(facts)
        findings += backgroundFindings(facts)
        findings += privacyFindings(facts)
        findings += noticeFindings(facts)
        return findings.sortedBy { it.severity.ordinal }
    }

    fun hasBlockers(findings: List<Finding>): Boolean =
        findings.any { it.severity == Severity.BLOCKER }

    fun problems(findings: List<Finding>): List<Finding> =
        findings.filter { it.severity != Severity.OK }

    // ---- разрешение на VPN ------------------------------------------------

    private fun consentFindings(facts: Facts): List<Finding> = when {
        facts.vpnConsentGranted -> listOf(
            Finding(
                id = "vpn_consent_ok",
                severity = Severity.OK,
                title = "Разрешение на VPN выдано",
            ),
        )

        !facts.vpnConsentDialogAvailable -> listOf(
            Finding(
                id = "vpn_consent_dialog",
                severity = Severity.BLOCKER,
                title = "В прошивке нет окна согласия на VPN",
                // Текст общий с отказом при подключении: второй такой же подсказки
                // быть не должно, иначе обходной путь разойдётся между экранами.
                detail = VpnConsent.UNAVAILABLE_HINT,
            ),
        )

        else -> listOf(
            Finding(
                id = "vpn_consent",
                severity = Severity.BLOCKER,
                title = "Нет разрешения на VPN",
                detail = "Система не выдала Nova право строить VPN. Без него туннель " +
                    "не поднимется, и подключение закончится отказом.",
                fixes = listOf(Fix.VPN_CONSENT),
            ),
        )
    }

    // ---- сеть -------------------------------------------------------------

    private fun networkFindings(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (facts.networkCount == 0) {
            findings += Finding(
                id = "no_network",
                severity = Severity.BLOCKER,
                title = "На телефоне нет ни одной сети",
                detail = when {
                    facts.airplaneMode -> "Включён режим полёта: связи нет ни у Nova, ни у остальных приложений."
                    else -> "VPN работает только поверх сети. Включите Wi-Fi или мобильные данные."
                },
                fixes = listOf(Fix.NETWORK),
            )
        } else if (!facts.internetAvailable) {
            findings += Finding(
                id = "no_internet",
                severity = Severity.BLOCKER,
                title = "Сети нет, но интернета в ней нет",
                detail = when {
                    facts.airplaneMode -> "Включён режим полёта: связи нет ни у Nova, ни у остальных приложений."
                    !facts.wifiConnected && !facts.mobileDataEnabled ->
                        "Мобильные данные выключены, Wi-Fi не подключён. Подключите сеть — VPN работает только поверх неё."
                    else -> "Ни одна сеть не подтвердила выход в интернет. Проверьте Wi-Fi или мобильные данные."
                },
                fixes = listOf(Fix.NETWORK),
            )
        } else {
            findings += Finding(
                id = "network_ok",
                severity = Severity.OK,
                title = "Интернет есть",
            )
        }
        if (facts.captivePortal) {
            findings += Finding(
                id = "captive_portal",
                severity = Severity.WARNING,
                title = "Сеть просит войти",
                detail = "Wi-Fi подключён, но до интернета не пускает: откройте браузер и " +
                    "пройдите вход на странице провайдера. До этого VPN подключать некуда.",
                fixes = listOf(Fix.NETWORK),
            )
        }
        return findings
    }

    // ---- туннель ----------------------------------------------------------

    private fun tunnelFindings(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (facts.foreignVpnDetected) {
            findings += Finding(
                id = "foreign_vpn",
                severity = Severity.BLOCKER,
                title = "Туннель занят другим VPN",
                detail = "Android пропускает только одно VPN-подключение одновременно. " +
                    "Пока другое приложение держит туннель, Nova не сможет его поднять — " +
                    "даже если этим приложением давно не пользуются.",
                fixes = listOf(Fix.VPN_SETTINGS),
            )
        } else if (facts.vpnOwnershipKnown) {
            findings += Finding(
                id = "vpn_slot_ok",
                severity = Severity.OK,
                title = "Другого VPN в системе нет",
            )
        }

        val alwaysOnElsewhere = facts.alwaysOnVpnPackage.isNotBlank() &&
            facts.alwaysOnVpnPackage != facts.ownPackageName
        if (alwaysOnElsewhere) {
            findings += Finding(
                id = "always_on_other_app",
                severity = Severity.WARNING,
                title = "Постоянный VPN назначен другому приложению",
                detail = "Система обязана поднимать его при каждой сети и после перезагрузки. " +
                    "Nova останется в стороне, пока не выбрана в этом же списке.",
                fixes = listOf(Fix.VPN_SETTINGS),
            )
        } else if (facts.alwaysOnVpnPackage == facts.ownPackageName) {
            findings += Finding(
                id = "always_on_ok",
                severity = Severity.OK,
                title = "Постоянный VPN — Nova",
            )
        }

        if (facts.lockdownEnabled) {
            findings += Finding(
                id = "lockdown",
                severity = Severity.NOTICE,
                title = "Включена блокировка без VPN",
                detail = "Пока Nova не подключится, интернет заблокирован для всех приложений. " +
                    "Это объясняет «сети нет», если туннель лёг.",
            )
        }

        when {
            facts.serviceConnected && !facts.tunnelPresent -> findings += Finding(
                id = "tunnel_missing",
                severity = Severity.BLOCKER,
                title = "Служба подключена, а туннеля нет",
                detail = "Nova считает себя подключённой, но системный VPN-интерфейс не поднят: " +
                    "трафик идёт напрямую. Отключите и подключите заново — причина отказа " +
                    "останется в журнале.",
                fixes = listOf(Fix.LOGS),
            )

            facts.serviceConnected -> findings += Finding(
                id = "tunnel_ok",
                severity = Severity.OK,
                title = "Туннель поднят",
            )

            facts.serviceConnecting && facts.serviceConnectingMs >= CONNECTING_TIMEOUT_MS -> findings += Finding(
                id = "connecting_too_long",
                severity = Severity.WARNING,
                title = "Подключение висит больше минуты",
                detail = "Служба всё ещё перебирает узлы или ждёт ответа. Если так и останется, " +
                    "смените протокол в настройках — журнал покажет, на каком шаге остановилось.",
                fixes = listOf(Fix.LOGS),
            )
        }
        return findings
    }

    // ---- фон --------------------------------------------------------------

    private fun backgroundFindings(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (facts.batteryOptimized) {
            findings += Finding(
                id = "battery_optimized",
                severity = Severity.WARNING,
                title = "Батарея ограничивает Nova",
                detail = "Система может выгрузить службу VPN, и туннель отвалится без " +
                    "предупреждения. Разрешите Nova работу без оптимизации батареи.",
                fixes = listOf(Fix.BATTERY),
            )
        } else if (!facts.backgroundRestricted) {
            findings += Finding(
                id = "battery_ok",
                severity = Severity.OK,
                title = "Фон не ограничен",
            )
        }

        if (facts.backgroundRestricted) {
            findings += Finding(
                id = "background_restricted",
                severity = Severity.WARNING,
                title = "Фоновая работа запрещена",
                detail = "Android не даёт Nova работать в фоне: туннель держится, пока экран " +
                    "открыт, и падает, когда приложение уходит.",
                fixes = listOf(Fix.APP_DETAILS),
            )
        }

        if (facts.standbyBucketRare) {
            findings += Finding(
                id = "standby_rare",
                severity = Severity.WARNING,
                title = "Система редко даёт Nova сеть",
                detail = "Приложение попало в редкую корзину ожидания: сеть ему выдают " +
                    "считанные разы в сутки. Этого не хватает ни подключиться, ни удержать туннель.",
                fixes = listOf(Fix.BATTERY),
            )
        }

        if (facts.vendorManagedBattery) {
            val fixes = buildList {
                if (facts.vendorBackgroundScreenAvailable) add(Fix.VENDOR_BACKGROUND)
                if (facts.vendorAutoStartScreenAvailable) add(Fix.VENDOR_AUTOSTART)
            }
            findings += Finding(
                id = "vendor_background",
                severity = Severity.WARNING,
                title = "Прошивка управляет фоном по-своему",
                detail = "У Xiaomi, Huawei, Honor, OPPO, vivo и Tecno свой список фоновых прав, " +
                    "и системное разрешение на него не влияет. Отключите ограничение в экране прошивки.",
                fixes = if (fixes.isEmpty()) listOf(Fix.APP_DETAILS) else fixes,
            )
        }
        return findings
    }

    // ---- уведомления, трафик, DNS -----------------------------------------

    private fun privacyFindings(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (!facts.notificationPermissionGranted) {
            findings += Finding(
                id = "notification_permission",
                severity = Severity.NOTICE,
                title = "Уведомления запрещены",
                detail = "На Android 13 и новее без разрешения уведомление VPN скрыто: служба " +
                    "работает, но статус и кнопку «Отключить» в шторке не видно.",
                fixes = listOf(Fix.NOTIFICATIONS),
            )
        } else if (!facts.notificationsEnabled) {
            findings += Finding(
                id = "notifications_disabled",
                severity = Severity.NOTICE,
                title = "Уведомления Nova выключены",
                detail = "Туннелю они не мешают, но состояние и кнопка отключения из шторки " +
                    "пропадают — кажется, что VPN включился молча.",
                fixes = listOf(Fix.NOTIFICATIONS),
            )
        } else {
            findings += Finding(
                id = "notifications_ok",
                severity = Severity.OK,
                title = "Уведомления включены",
            )
        }

        if (facts.dataSaverEnabled && !facts.dataSaverWhitelisted) {
            findings += Finding(
                id = "data_saver",
                severity = Severity.WARNING,
                title = "Экономия трафика режет фон",
                detail = "Android ограничивает фоновый трафик приложений, которым не разрешено " +
                    "его тратить. Добавьте Nova в исключения — иначе подключение может " +
                    "зависать на переборе узлов, а туннель — рваться в фоне.",
                fixes = listOf(Fix.DATA_SAVER),
            )
        } else if (facts.dataSaverEnabled) {
            findings += Finding(
                id = "data_saver_ok",
                severity = Severity.OK,
                title = "Экономия трафика Nova не касается",
            )
        }

        if (facts.privateDnsStrictMode) {
            findings += Finding(
                id = "private_dns",
                severity = Severity.WARNING,
                title = "Частный DNS задан вручную",
                detail = "Система резолвит имена через конкретный DoT-сервер. Если оператор его " +
                    "блокирует, Nova не найдёт адрес узла, и подключение не начнётся. " +
                    "Переключите на «Автоматически» или «Выкл.».",
                fixes = listOf(Fix.PRIVATE_DNS),
            )
        } else {
            findings += Finding(
                id = "private_dns_ok",
                severity = Severity.OK,
                title = "Частный DNS не мешает",
            )
        }
        return findings
    }

    // ---- объяснения и ресурсы --------------------------------------------

    private fun noticeFindings(facts: Facts): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (facts.lowMemory) {
            findings += Finding(
                id = "low_memory",
                severity = Severity.WARNING,
                title = "Системе не хватает памяти",
                detail = "Служба VPN живёт в отдельном процессе, и при нехватке памяти Android " +
                    "убивает его первым. Закройте лишние приложения и проверьте снова.",
            )
        }
        if (facts.powerSaveMode) {
            findings += Finding(
                id = "power_save",
                severity = Severity.NOTICE,
                title = "Включена экономия заряда",
                detail = "В этом режиме система реже будит приложения и может придержать сеть " +
                    "для Nova.",
                fixes = listOf(Fix.BATTERY),
            )
        }
        if (facts.deviceIdle) {
            findings += Finding(
                id = "device_idle",
                severity = Severity.NOTICE,
                title = "Телефон в режиме ожидания",
                detail = "Doze придерживает сеть и фоновую работу. Туннель обычно переживает " +
                    "его, но переподключение может занять дольше.",
            )
        }
        if (facts.lastTransportNotice.isNotBlank()) {
            findings += Finding(
                id = "last_transport_notice",
                severity = Severity.NOTICE,
                title = "Последнее слово службы",
                detail = facts.lastTransportNotice.trim(),
                fixes = listOf(Fix.LOGS),
            )
        }
        return findings
    }
}
