package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран «Почему VPN не работает»: причины, по которым Nova не подключается или
 * теряет туннель, и кнопка системного экрана к каждой.
 *
 * ## Что здесь чинится, а что нет
 *
 * Приложение не меняет чужие настройки молча: включить разрешение на VPN, вывести
 * себя из оптимизации батареи или отключить чужой VPN оно не вправе. Поэтому
 * каждая находка [VpnHealthAudit.Finding] несёт не «исправлено», а кнопку, которая
 * открывает ровно тот системный экран, где это решается. Возврат с такого экрана
 * перезапускает проверку ([auditOnResume]) — человек видит результат своего
 * действия, а не гадает.
 *
 * ## Почему проверки в фоне
 *
 * Сбор условий — это `VpnService.prepare`, перечисление сетей, чтение скрытых
 * настроек и резолв вендорных экранов: десятки межпроцессных вызовов. На главном
 * потоке это заметный подлаг ещё до первого кадра, а на слабой приставке — и
 * вовсе замирание. Поэтому экран рисуется сразу с «Проверяю…», а условия
 * собираются в [Dispatchers.IO].
 *
 * ## Почему проверка пишется в журнал
 *
 * Причина отказа нужна и тогда, когда человек не станет читать экран, а просто
 * отправит лог. Условия пишет сам [VpnHealthProbe] одной строкой, итог — этот
 * экран: список найденных ограничений попадает в журнал рядом с той попыткой
 * подключения, которую он объясняет.
 */
class VpnHealthActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvStamp: TextView
    private lateinit var progress: ProgressBar
    private lateinit var findingsContainer: LinearLayout
    private lateinit var passedCard: LinearLayout
    private lateinit var tvPassed: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var auditJob: Job? = null

    /** Последний результат: из него собирается отчёт для буфера обмена. */
    private var latestFacts: VpnHealthAudit.Facts? = null
    private var latestFindings: List<VpnHealthAudit.Finding> = emptyList()

    /** Перезапуск проверки при возврате с системного экрана, а не при первом показе. */
    private var auditOnResume = false

    /** Согласие на VPN приходит отдельным окном системы и меняет первый же ответ. */
    private val vpnPermissionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(
                this,
                NovaLanguage.tr(this, "Система не подтвердила разрешение на VPN"),
                Toast.LENGTH_LONG,
            ).show()
        }
        runAudit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_health)
        LogManager.setAppContext(this)

        tvSummary = findViewById(R.id.tv_health_summary)
        tvStamp = findViewById(R.id.tv_health_stamp)
        progress = findViewById(R.id.pb_health)
        findingsContainer = findViewById(R.id.ll_health_findings)
        passedCard = findViewById(R.id.ll_health_passed)
        tvPassed = findViewById(R.id.tv_health_passed)

        findViewById<TextView>(R.id.btn_health_refresh).setOnClickListener { runAudit() }
        findViewById<TextView>(R.id.btn_health_copy).setOnClickListener { copyReport() }

        // Пока идёт проверка, повторное нажатие не должно плодить потоки.
        runAudit()
    }

    override fun onPause() {
        super.onPause()
        auditOnResume = true
    }

    override fun onResume() {
        super.onResume()
        // Возврат с системного экрана: настройка могла измениться, и человек
        // обязан увидеть это сразу, а не после нажатия «Проверить снова».
        if (auditOnResume) runAudit()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---- проверка ---------------------------------------------------------

    private fun runAudit() {
        auditJob?.cancel()
        progress.visibility = View.VISIBLE
        tvSummary.text = NovaLanguage.tr(this, "Проверяю…")
        tvStamp.text = ""
        findingsContainer.removeAllViews()
        passedCard.visibility = View.GONE

        auditJob = scope.launch {
            val collected = withContext(Dispatchers.IO) {
                runCatching { VpnHealthProbe.collect(this@VpnHealthActivity) }
            }
            collected.fold(
                onSuccess = { facts ->
                    val findings = withContext(Dispatchers.Default) { VpnHealthAudit.audit(facts) }
                    latestFacts = facts
                    latestFindings = findings
                    render(findings)
                },
                onFailure = { error ->
                    // Пустой список находок означал бы «ограничений нет» — ровно
                    // противоположное тому, что случилось. Поэтому отказ сбора
                    // показывается своей находкой, а не тишиной.
                    renderFailed(error)
                },
            )
        }
    }

    private fun render(findings: List<VpnHealthAudit.Finding>) {
        progress.visibility = View.GONE
        val problems = VpnHealthAudit.problems(findings)
        val blockers = problems.count { it.severity == VpnHealthAudit.Severity.BLOCKER }
        tvSummary.text = when {
            problems.isEmpty() -> NovaLanguage.tr(
                this,
                "Ограничений не найдено — система не мешает Nova",
            )

            blockers > 0 -> NovaLanguage.tr(
                this,
                "Найдено причин: $blockers — подключение не состоится, пока они не сняты",
            )

            else -> NovaLanguage.tr(
                this,
                "Найдено ограничений: ${problems.size} — подключиться не мешают, но могут оборвать туннель",
            )
        }
        tvStamp.text = NovaLanguage.tr(
            this,
            "Проверено в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
        )

        findingsContainer.removeAllViews()
        problems.forEach { finding -> findingsContainer.addView(buildFindingCard(finding)) }

        val passed = findings.filter { it.severity == VpnHealthAudit.Severity.OK }
        if (passed.isEmpty()) {
            passedCard.visibility = View.GONE
        } else {
            passedCard.visibility = View.VISIBLE
            tvPassed.text = passed.joinToString("  ·  ") { NovaLanguage.tr(this, it.title) }
        }

        logOutcome(problems)
    }

    /**
     * Сбор не удался. Не «всё в порядке» и не пустой экран: на экране одна
     * находка без выдуманных условий, а причина — в журнале.
     */
    private fun renderFailed(error: Throwable) {
        progress.visibility = View.GONE
        LogManager.e("Проверка ограничений не собралась", "VpnHealth", error)
        val finding = VpnHealthAudit.Finding(
            id = "probe_failed",
            severity = VpnHealthAudit.Severity.WARNING,
            title = "Проверка не собралась",
            detail = "Система не отдала данные проверки. Это не значит, что ограничений нет: " +
                "откройте журнал — там причина отказа.",
            fixes = listOf(VpnHealthAudit.Fix.LOGS),
        )
        latestFacts = null
        latestFindings = listOf(finding)
        tvSummary.text = NovaLanguage.tr(this, "Проверку не удалось выполнить")
        tvStamp.text = ""
        findingsContainer.removeAllViews()
        findingsContainer.addView(buildFindingCard(finding))
        passedCard.visibility = View.GONE
        logOutcome(listOf(finding))
    }

    /**
     * Найденное — одной строкой в журнал. Подробности уже записал [VpnHealthProbe];
     * здесь важен итог: что именно сработало в минуту отказа.
     */
    private fun logOutcome(problems: List<VpnHealthAudit.Finding>) {
        if (problems.isEmpty()) {
            LogManager.i("Проверка ограничений: ограничений не найдено", "VpnHealth")
            return
        }
        val text = problems.joinToString("; ") { "${it.severity.name}: ${it.title}" }
        LogManager.i("Проверка ограничений: $text", "VpnHealth")
    }

    private fun buildFindingCard(finding: VpnHealthAudit.Finding): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            themedDrawable(R.attr.novaCardBackground)?.let { background = it }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        val title = TextView(this).apply {
            text = "${prefixOf(finding.severity)} ${NovaLanguage.tr(this@VpnHealthActivity, finding.title)}"
            setTextColor(colorOf(finding.severity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(title)

        if (finding.detail.isNotBlank()) {
            card.addView(
                TextView(this).apply {
                    text = NovaLanguage.tr(this@VpnHealthActivity, finding.detail)
                    setTextColor(NovaTheme.color(this@VpnHealthActivity, R.attr.novaTextHint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setLineSpacing(0f, 1.15f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(5) }
                },
            )
        }

        if (finding.fixes.isNotEmpty()) {
            val row = FlowLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(9) }
            }
            finding.fixes.forEach { fix ->
                val button = Button(this).apply {
                    text = NovaLanguage.tr(this@VpnHealthActivity, fix.label())
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    setOnClickListener { openFix(fix) }
                }
                NovaDialogs.styleButton(button, primary = false)
                // Параметры с полями строим сами: `generateDefaultLayoutParams`
                // у `ViewGroup` защищённый, а вложенный `MarginLayoutParams`
                // объявлен в `ViewGroup`, не в `FlowLayout`.
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.rightMargin = dp(8)
                params.bottomMargin = dp(4)
                row.addView(button, params)
            }
            card.addView(row)
        }
        return card
    }

    // ---- действия ---------------------------------------------------------

    private fun openFix(fix: VpnHealthAudit.Fix) {
        when (fix) {
            VpnHealthAudit.Fix.VPN_CONSENT -> requestVpnConsent()
            VpnHealthAudit.Fix.VPN_SETTINGS -> openSystem(Settings.ACTION_VPN_SETTINGS)
            VpnHealthAudit.Fix.BATTERY -> openBatterySettings()
            VpnHealthAudit.Fix.VENDOR_BACKGROUND ->
                if (!VendorBackgroundSettingsHelper.openBackground(this)) openAppDetails()
            VpnHealthAudit.Fix.VENDOR_AUTOSTART ->
                if (!VendorBackgroundSettingsHelper.openAutoStart(this)) openAppDetails()
            VpnHealthAudit.Fix.NOTIFICATIONS -> openNotificationSettings()
            VpnHealthAudit.Fix.APP_DETAILS -> openAppDetails()
            VpnHealthAudit.Fix.DATA_SAVER -> openSystem(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Settings.ACTION_DATA_USAGE_SETTINGS
                } else {
                    Settings.ACTION_WIRELESS_SETTINGS
                },
            )
            VpnHealthAudit.Fix.PRIVATE_DNS -> openSystem(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Settings.ACTION_PRIVATE_DNS_SETTINGS
                } else {
                    Settings.ACTION_WIRELESS_SETTINGS
                },
            )
            VpnHealthAudit.Fix.NETWORK -> openSystem(Settings.ACTION_WIRELESS_SETTINGS)
            VpnHealthAudit.Fix.LOGS -> runCatching {
                startActivity(Intent(this, LogsActivity::class.java))
            }.onFailure { reportMissingScreen() }
        }
    }

    /**
     * Согласие на VPN. Окно принадлежит системе, и на урезанных прошивках его нет
     * вовсе — тогда [VpnConsent.request] пишет причину в журнал и возвращает
     * `false`, а человек получает подсказку с обходным путём по ADB.
     */
    private fun requestVpnConsent() {
        val intent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (intent == null) {
            Toast.makeText(
                this,
                NovaLanguage.tr(this, "Разрешение на VPN уже выдано"),
                Toast.LENGTH_SHORT,
            ).show()
            runAudit()
            return
        }
        if (!VpnConsent.request(vpnPermissionLauncher, intent)) {
            Toast.makeText(
                this,
                NovaLanguage.tr(this, VpnConsent.UNAVAILABLE_HINT),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openBatterySettings() {
        // Сначала персональный диалог: он спрашивает про Nova, а не заставляет
        // искать её в общем списке приложений. Разрешение на такой запрос уже
        // объявлено в манифесте (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
            if (tryStart(direct)) return
        }
        openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        }
        if (!tryStart(intent)) openAppDetails()
    }

    private fun openAppDetails() {
        openSystem(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
    }

    /** Открывает системный экран; если прошивка его не знает — общие настройки. */
    private fun openSystem(action: String, dataUri: Uri? = null) {
        val intent = Intent(action).apply { if (dataUri != null) setData(dataUri) }
        if (tryStart(intent)) return
        if (action != Settings.ACTION_SETTINGS && tryStart(Intent(Settings.ACTION_SETTINGS))) return
        reportMissingScreen()
    }

    private fun tryStart(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    private fun reportMissingScreen() {
        Toast.makeText(
            this,
            NovaLanguage.tr(
                this,
                "Прошивка не открыла этот экран — найдите его в настройках Android вручную",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    // ---- отчёт ------------------------------------------------------------

    private fun copyReport() {
        if (latestFacts == null && latestFindings.isEmpty()) {
            Toast.makeText(
                this,
                NovaLanguage.tr(this, "Проверка ещё идёт"),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val report = VpnHealthProbe.buildReport(latestFacts, latestFindings)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(
                this,
                NovaLanguage.tr(this, "Буфер обмена недоступен"),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Nova VPN health", report))
        Toast.makeText(
            this,
            NovaLanguage.tr(this, "Отчёт скопирован"),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ---- мелочи -----------------------------------------------------------

    private fun themedDrawable(attr: Int): Drawable? {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true) || value.resourceId == 0) return null
        return ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun prefixOf(severity: VpnHealthAudit.Severity): String = when (severity) {
        VpnHealthAudit.Severity.BLOCKER -> "⛔"
        VpnHealthAudit.Severity.WARNING -> "⚠️"
        VpnHealthAudit.Severity.NOTICE -> "ℹ️"
        VpnHealthAudit.Severity.OK -> "✓"
    }

    /**
     * Цвет заголовка. Тревожные — фиксированные: они обязаны читаться одинаково
     * на любой теме, включая светлую, а акцент темы бывает и зелёным, и красным.
     */
    private fun colorOf(severity: VpnHealthAudit.Severity): Int = when (severity) {
        VpnHealthAudit.Severity.BLOCKER -> Color.parseColor("#FF6B6B")
        VpnHealthAudit.Severity.WARNING -> Color.parseColor("#FFC857")
        VpnHealthAudit.Severity.NOTICE -> NovaTheme.color(this, R.attr.novaTextHint)
        VpnHealthAudit.Severity.OK -> NovaTheme.color(this, R.attr.novaAccent)
    }
}
