package com.example.nova

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Центральная композиция главного экрана: циферблат вокруг кнопки подключения
 * и волны, которые расходятся из неё во время подключения.
 *
 * ## Почему циферблат
 *
 * До этой версии в покое вид ничего не рисовал: центр экрана был пустым, а всю
 * «дорогую» работу делало зарево вокруг надписи статуса — двенадцать размытых
 * слоёв, которые читались как жирное цветное пятно. Пятно убрано, и место под
 * ним занято тем, что в премиальном приборе и должно там стоять: **механизм**.
 *
 * Циферблат собран из четырёх вещей, и каждая видна отдельно:
 *
 * 1. тонкий внутренний волосок — полная окружность, 0.7dp, еле заметная;
 * 2. внешний обод с разрывом внизу — как безель часов, 0.55dp;
 * 3. 72 риски между ними: двенадцать длинных, шестьдесят коротких;
 * 4. головка — короткий след по ободу и точка на нём.
 *
 * В покое головка делает оборот за [IDLE_REVOLUTION_SECONDS] секунд. Это медленно
 * настолько, что глаз не следит за движением, но замечает, что экран живой.
 * Зарево под кнопкой дышит с периодом [BREATH_PERIOD_SECONDS] секунд.
 *
 * ## Волны
 *
 * Волны живут только во время подключения и идут из центра кнопки
 * ([setPulseOrigin]). Каждая волна — это **тонкая линия света** по фронту и мягкий
 * свет вокруг неё: длинный слабый хвост внутрь, подъём к кромке, обрыв сразу за
 * ней. Кромка не идеальная окружность — три медленные гармоники смещают её на
 * ~2 %, поэтому фронт «расходится», а не «вращается».
 *
 * Приём, который делает это дешёвым: **профиль зашит в шейдер радиусом 1**, и
 * путь строится в единичном радиусе, а размер задаёт `canvas.scale`. Шейдер
 * собирается один раз на смену акцента; в кадре — один `Path`, одна кисть, ноль
 * аллокаций, ноль битмапов, ноль размытий. Это та же идея, что у шейдерных
 * «аврор» на лендингах: цвет и форма считаются один раз, а кадр двигает только
 * масштаб и альфу.
 *
 * Поверх волн зажигается созвездие — то же самое, что человек увидит после
 * подключения: точки берутся из [NovaConstellation], общей с фоновой анимацией.
 *
 * ## Чем это платится
 *
 * Ни одного размытия, ни одного программного слоя, ни одной аллокации в кадре.
 * На кадр: круг зарева, два обода, 72 отрезка рисок, след головки из
 * [COMET_SEGMENTS] дуг и — только в подключении — обход созвездия, до четырёх
 * волн (по [BAND_SEGMENTS] точек) и один общий шейдер волны.
 *
 * Кадры идут, только пока вид прикреплён, видим и не прозрачен. В покое это
 * 24 кадра в секунду ([IDLE_FRAME_DELAY_MS]), в подключении — по кадру на
 * обновление экрана. При включённой экономии энергии покой рисуется **одним
 * статичным кадром**: композиция остаётся, движение выключается.
 */
class TronRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        /** Вид ничего не рисует: экран показывает изображение или покой другого вида. */
        STOPPED,

        /** Покой: циферблат и дышащее зарево. */
        IDLE,

        /** Подключение: волны из кнопки и созвездие. */
        CONNECTING,
    }

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
    )

    private data class Edge(
        val a: Int,
        val b: Int,
    )

    /** Фоновая анимация вообще разрешена на этом устройстве (см. [MainBackgroundPolicy]). */
    private val supported = MainBackgroundPolicy.isAnimationSupported(context)

    private val lowEndDevice = run {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val lowRam = activityManager?.isLowRamDevice == true
        lowRam || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }

    private val density = context.resources.displayMetrics.density

    private var mode: Mode = Mode.STOPPED

    /** Начало волн на времени общих часов; `null` — ещё не привязано. */
    private var animationStartSeconds: Double? = null

    /** Момент, с которого считается появление композиции; `null` — с первого кадра. */
    private var enteredAtSeconds: Double? = null

    /** Время последнего кадра (или отрисовки). Всё движение считается от него. */
    private var motionSeconds = 0.0

    private var canvasWidth = 0f
    private var canvasHeight = 0f
    private var frameArmed = false

    /** Показывать ли созвездие. Оно принадлежит режиму «анимация» у фона экрана. */
    private var constellationEnabled = false

    /**
     * Можно ли двигать покой. Выключается режимом энергосбережения: там важнее
     * не будить лишний раз GPU, а композиция читается и без движения.
     */
    private var idleMotionAllowed = true

    /**
     * Центр кнопки, из которой расходятся волны, и её половинные размеры.
     *
     * Размеры циферблат не повторяет — он круглый, — но по ним считается его
     * малый радиус: обод обязан стоять снаружи кнопки, а не под ней.
     */
    private var originX = 0f
    private var originY = 0f
    private var baseHalfW = 96f * density
    private var baseHalfH = 26f * density

    /** Радиусы циферблата: внутренний волосок, обод, обод головки. */
    private var dialInnerRadius = 0f
    private var dialOuterRadius = 0f
    private var dialHeadRadius = 0f

    private var accentR = 112
    private var accentG = 228
    private var accentB = 255

    /**
     * Волны: сколько их, с каким периодом и насколько разнесены.
     *
     * Периоды разные и не кратные друг другу — иначе волны выстраиваются в
     * равномерную гребёнку, и рисунок повторяется через каждые несколько секунд.
     * Смещения задают, что видно на экране в первую же секунду подключения.
     */
    private val bandOffsets = if (lowEndDevice) {
        floatArrayOf(0f, 0.5f)
    } else {
        floatArrayOf(0f, 0.31f, 0.62f, 0.83f)
    }

    private val bandCycleSeconds = if (lowEndDevice) {
        floatArrayOf(9.8f, 8.6f)
    } else {
        floatArrayOf(8.2f, 7.0f, 9.3f, 7.7f)
    }

    /** Фазы волн текущего кадра. Один массив, пересчитывается на месте. */
    private val bandProgress = FloatArray(bandOffsets.size)

    private val bandSegments = if (lowEndDevice) 64 else BAND_SEGMENTS

    /**
     * Кромка волны: единичные векторы и гармоники смещения по точкам.
     *
     * Считаются один раз при создании вида. В кадре остаётся подставить фазу по
     * формуле сложения углов — шесть синусов на волну вместо трёхсот.
     */
    private val segmentCos = FloatArray(bandSegments)
    private val segmentSin = FloatArray(bandSegments)
    private val harm2Sin = FloatArray(bandSegments)
    private val harm2Cos = FloatArray(bandSegments)
    private val harm3Sin = FloatArray(bandSegments)
    private val harm3Cos = FloatArray(bandSegments)
    private val harm5Sin = FloatArray(bandSegments)
    private val harm5Cos = FloatArray(bandSegments)

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Стыки сегментов следа не должны вспухать: круглые торцы складываются в точки.
        strokeCap = Paint.Cap.BUTT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val constellationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val constellationStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    /**
     * Кисть волны. Шейдер задаёт профиль свечения, `alpha` — силу конкретной
     * волны; ни то, ни другое не создаёт объектов в кадре.
     */
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }

    /**
     * Кисть кромки: тонкая линия света по фронту волны. Отдельная кисть, потому
     * что тело волны — широкий градиент, а кромка — волосок в 1.25dp: одним
     * штрихом это не нарисовать без размытия.
     */
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    /** Кромка волны в единичном радиусе; пересобирается на месте, без мусора. */
    private val bandPath = Path()

    /** Профиль волны в единичном радиусе; пересобирается только при смене акцента. */
    private var bandShader: Shader? = null

    private val arcRect = RectF()
    private val stars = ArrayList<Star>(64)
    private val edges = ArrayList<Edge>(128)

    /**
     * Направления рисок. Считаются один раз: углы не меняются, меняется только
     * радиус, а его даёт раскладка.
     */
    private val tickCos = FloatArray(TICK_COUNT)
    private val tickSin = FloatArray(TICK_COUNT)

    init {
        // Волны и циферблат рисуются каждый кадр. Аппаратный слой пришлось бы
        // собирать заново на каждом кадре во весь экран — это дороже самой отрисовки.
        setLayerType(LAYER_TYPE_NONE, null)
        for (index in 0 until TICK_COUNT) {
            val angle = radians(index * TICK_STEP_DEGREES)
            tickCos[index] = cos(angle).toFloat()
            tickSin[index] = sin(angle).toFloat()
        }
        for (step in 0 until bandSegments) {
            val angle = radians(step * 360f / bandSegments)
            val a = angle.toFloat()
            segmentCos[step] = cos(angle).toFloat()
            segmentSin[step] = sin(angle).toFloat()
            harm2Sin[step] = sin(2f * a)
            harm2Cos[step] = cos(2f * a)
            harm3Sin[step] = sin(3f * a)
            harm3Cos[step] = cos(3f * a)
            harm5Sin[step] = sin(5f * a)
            harm5Cos[step] = cos(5f * a)
        }
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameArmed = false
            if (!canAnimate()) return
            // На паузе часы стоят: кадров нет, фаза не уходит в фон и не прыгает на возврате.
            if (!NovaAnimationClock.isRunning()) return
            syncMotion(NovaAnimationClock.now())
            invalidate()
            scheduleFrame()
        }
    }

    /**
     * Откуда расходятся волны.
     *
     * Зовётся главным экраном по фактическим границам кнопки: считать их здесь
     * значило бы завести вторую копию её размеров и разметки.
     */
    fun setPulseOrigin(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        if (halfWidth <= 0f || halfHeight <= 0f) return
        if (originX == centerX && originY == centerY &&
            baseHalfW == halfWidth && baseHalfH == halfHeight
        ) {
            return
        }
        originX = centerX
        originY = centerY
        baseHalfW = halfWidth
        baseHalfH = halfHeight
        updateDialGeometry()
        if (mode != Mode.STOPPED) invalidate()
    }

    /** Цвет циферблата, волн и созвездия — акцент текущей темы. */
    fun applyAccent(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        if (r == accentR && g == accentG && b == accentB) return
        accentR = r
        accentG = g
        accentB = b
        // Профиль волны зашит в акцент: шейдер пересоберётся лениво, на следующем кадре.
        bandShader = null
        if (mode != Mode.STOPPED) invalidate()
    }

    fun setYogurtIndigoEnabled(enabled: Boolean) {
        if (constellationEnabled == enabled) return
        constellationEnabled = enabled
        if (mode == Mode.CONNECTING) {
            restartWavePhase()
            invalidate()
        }
    }

    /**
     * Фазы волн по времени часов, без продвижения: зовётся из отрисовки и привязки,
     * кадр сам часы не двигает.
     */
    private fun syncWaveProgress(now: Double) {
        val start = animationStartSeconds
            ?: NovaAnimationMemory.tronStartOrBegin(now).also { animationStartSeconds = it }
        val elapsed = now - start
        for (index in bandProgress.indices) {
            bandProgress[index] = CoreDial.bandProgress(
                elapsed,
                bandCycleSeconds[index].toDouble(),
                bandOffsets[index],
            )
        }
    }

    /** Общая точка входа движения: время кадра и фазы волн, если они идут. */
    private fun syncMotion(now: Double) {
        motionSeconds = now
        if (enteredAtSeconds == null) enteredAtSeconds = now
        if (mode == Mode.CONNECTING) syncWaveProgress(now)
    }

    /** Явный перезапуск волн: отсчёт заново от текущего времени часов. */
    private fun restartWavePhase() {
        val now = NovaAnimationClock.peek()
        animationStartSeconds = NovaAnimationMemory.tronRestart(now)
        bandProgress.fill(0f)
        syncWaveProgress(now)
    }

    /** Возврат на экран: движение продолжается с той же фазы, кадры снова заказываются. */
    fun resumeAnimation() {
        if (mode == Mode.STOPPED) return
        refreshIdleMotionPolicy()
        syncMotion(NovaAnimationClock.peek())
        scheduleFrame()
        invalidate()
    }

    fun setMode(value: Mode, restart: Boolean = false) {
        if (mode == value && !restart) return
        val enteredIdle = value == Mode.IDLE && mode != Mode.IDLE
        val enteredConnecting = value == Mode.CONNECTING && mode != Mode.CONNECTING
        mode = value
        when (value) {
            Mode.CONNECTING -> {
                animationStartSeconds = null
                refreshIdleMotionPolicy()
                // Появление композиции — только при входе в режим, а не при его повторе:
                // иначе обод мигал бы на каждом обновлении состояния.
                enteredAtSeconds = null
                if (restart || enteredConnecting) {
                    restartWavePhase()
                } else {
                    // Продолжаем уже идущие волны: пересоздание вида не должно их сбрасывать.
                    val now = NovaAnimationClock.peek()
                    animationStartSeconds = NovaAnimationMemory.tronStartOrBegin(now)
                    syncWaveProgress(now)
                }
                invalidate()
                scheduleFrame()
            }

            Mode.IDLE -> {
                animationStartSeconds = null
                NovaAnimationMemory.tronStop()
                bandProgress.fill(0f)
                refreshIdleMotionPolicy()
                if (enteredIdle) enteredAtSeconds = null
                invalidate()
                scheduleFrame()
            }

            Mode.STOPPED -> {
                animationStartSeconds = null
                NovaAnimationMemory.tronStop()
                bandProgress.fill(0f)
                enteredAtSeconds = null
                unscheduleFrame()
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshIdleMotionPolicy()
        if (mode != Mode.STOPPED) {
            syncMotion(NovaAnimationClock.peek())
            scheduleFrame()
        }
    }

    override fun onDetachedFromWindow() {
        unscheduleFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (mode != Mode.STOPPED) {
                syncMotion(NovaAnimationClock.peek())
                scheduleFrame()
            }
        } else {
            unscheduleFrame()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasWidth = w.toFloat()
        canvasHeight = h.toFloat()
        if (originX == 0f && originY == 0f) {
            originX = canvasWidth / 2f
            originY = canvasHeight * 0.5f
        }
        rebuildConstellation(w, h)
        updateDialGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.STOPPED || canvasWidth <= 0f || canvasHeight <= 0f) return
        syncMotion(NovaAnimationClock.peek())
        if (mode == Mode.CONNECTING) {
            // Порядок слоёв — как у прибора в свете: сначала волны, потом звёзды,
            // и только сверху циферблат. Волна проходит ПОД рисками и ободом,
            // поэтому шкала остаётся чёткой, а не тонет в засветке.
            drawAurora(canvas)
            if (constellationEnabled) drawConstellation(canvas)
        }
        drawDial(canvas)
        // Заказываем кадр и отсюда: смена прозрачности или разметки приходит
        // без нашего участия, и без этого вид замирал бы после плавного появления.
        scheduleFrame()
    }

    // ---------------------------------------------------------------- циферблат

    /**
     * Радиусы циферблата от геометрии кнопки. Обод стоит на 24dp снаружи кнопки
     * и не выходит ни за половину меньшей стороны экрана, ни за ближайший край.
     */
    private fun updateDialGeometry() {
        val w = canvasWidth
        val h = canvasHeight
        if (w <= 0f || h <= 0f) return
        val minSide = min(w, h)
        val base = max(baseHalfW, baseHalfH)
        val preferredInner = base + 24f * density
        // Обод не должен выходить за экран: в горизонтали кнопка стоит ниже центра,
        // и без этого верх обода срезался бы краем. Берём меньшее из «половины
        // меньшей стороны» и фактического запаса до ближайшего края.
        val edgeRoom = minOf(originX, w - originX, originY, h - originY) - 8f * density
        val maxOuter = minOf(minSide * 0.47f, edgeRoom)
        val outer = (preferredInner + 16f * density).coerceAtMost(maxOuter)
        val inner = (outer - 16f * density)
            .coerceAtLeast(baseHalfH + 16f * density)
            .coerceAtMost(outer - 8f * density)
        dialInnerRadius = inner
        dialOuterRadius = outer
        dialHeadRadius = outer + 3.5f * density
    }

    private fun drawDial(canvas: Canvas) {
        val innerR = dialInnerRadius
        val outerR = dialOuterRadius
        if (innerR <= 1f || outerR <= innerR) return

        val running = NovaAnimationClock.isRunning()
        // Статичный кадр (экономия энергии, пауза) не должен проигрывать появление:
        // композиция обязана быть видна сразу и целиком.
        val still = !running || !supported || (mode == Mode.IDLE && !idleMotionAllowed)
        val reveal = if (still) 1f else CoreDial.reveal(nowSinceEntered(), REVEAL_SECONDS)
        if (reveal <= 0.01f) return

        val breathe = if (still) 0.55f else CoreDial.breath(motionSeconds, BREATH_PERIOD_SECONDS)
        val head = if (still) 0f else CoreDial.headDegrees(motionSeconds, revolutionSeconds())

        drawBloom(canvas, outerR, breathe, reveal)
        drawBezel(canvas, reveal)
        drawTicks(canvas, head, reveal)
        // Головка рисуется и в статичном кадре: без неё циферблат читается как
        // шкала без стрелки. В статике она стоит на нуле и не двигается.
        drawHead(canvas, head, reveal, breathe)
    }

    private fun nowSinceEntered(): Double {
        val from = enteredAtSeconds ?: return 0.0
        return (NovaAnimationClock.peek() - from).coerceAtLeast(0.0)
    }

    private fun revolutionSeconds(): Double =
        if (mode == Mode.CONNECTING) CONNECTING_REVOLUTION_SECONDS else IDLE_REVOLUTION_SECONDS

    /** Зарево под кнопкой: один радиальный градиент, без размытия. */
    private fun drawBloom(canvas: Canvas, outerR: Float, breathe: Float, reveal: Float) {
        val base = when (mode) {
            Mode.CONNECTING -> 16f + breathe * 9f
            else -> 8f + breathe * 5f
        }
        val alpha = (base * reveal).toInt().coerceIn(0, 255)
        if (alpha <= 1) return
        val radius = outerR * 1.5f
        bloomPaint.shader = RadialGradient(
            originX,
            originY,
            radius,
            intArrayOf(
                accent(alpha),
                accent((alpha * 0.38f).toInt()),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(originX, originY, radius, bloomPaint)
        bloomPaint.shader = null
    }

    /** Внутренний волосок и внешний обод с разрывом снизу. */
    private fun drawBezel(canvas: Canvas, reveal: Float) {
        val boost = if (mode == Mode.CONNECTING) 1.35f else 1f

        ringPaint.color = tone((26f * boost * reveal).toInt(), 0.10f)
        ringPaint.strokeWidth = 0.7f * density
        canvas.drawCircle(originX, originY, dialInnerRadius, ringPaint)

        ringPaint.color = tone((20f * boost * reveal).toInt(), 0.06f)
        ringPaint.strokeWidth = 0.55f * density
        arcRect.set(
            originX - dialOuterRadius,
            originY - dialOuterRadius,
            originX + dialOuterRadius,
            originY + dialOuterRadius,
        )
        canvas.drawArc(arcRect, BEZEL_START_DEGREES, BEZEL_SWEEP_DEGREES * reveal, false, ringPaint)
    }

    /**
     * Риски. Под головкой они подсвечиваются и уходят в акцент — это единственное
     * место, где цвет темы касается покоя. В подключении к подсветке головки
     * добавляется вспышка от волны: риска загорается, когда фронт проходит мимо.
     * Считается это один раз на кадр — все риски лежат на одном радиусе.
     */
    private fun drawTicks(canvas: Canvas, headDegrees: Float, reveal: Float) {
        val startRadius = dialOuterRadius - 2f * density
        val headWidth = if (mode == Mode.CONNECTING) 30f else 24f
        val boost = if (mode == Mode.CONNECTING) 1.25f else 1f
        val waveFlash = waveEnergyAt(dialOuterRadius)
        for (index in 0 until TICK_COUNT) {
            val major = index % MAJOR_TICK_EVERY == 0
            val length = (if (major) 5.5f else 2.4f) * density
            val emphasis = CoreDial.emphasis(index * TICK_STEP_DEGREES, headDegrees, headWidth)
            val baseAlpha = (if (major) 30f else 15f) * boost * reveal
            val alpha = (baseAlpha + emphasis * 150f * reveal + waveFlash * 120f * reveal)
                .toInt().coerceIn(0, 255)
            if (alpha <= 1) continue
            tickPaint.color = tone(alpha, 0.14f + emphasis * 0.86f)
            tickPaint.strokeWidth = (if (major) 0.9f else 0.55f) * density
            val cosA = tickCos[index]
            val sinA = tickSin[index]
            val endRadius = startRadius - length
            canvas.drawLine(
                originX + cosA * startRadius,
                originY + sinA * startRadius,
                originX + cosA * endRadius,
                originY + sinA * endRadius,
                tickPaint,
            )
        }
    }

    /**
     * Головка: след по ободу из [COMET_SEGMENTS] дуг с затуханием от головы назад
     * и точка на самом ободе с мягким ореолом.
     */
    private fun drawHead(canvas: Canvas, headDegrees: Float, reveal: Float, breathe: Float) {
        val length = if (mode == Mode.CONNECTING) 74f else 52f
        val peakAlpha = (if (mode == Mode.CONNECTING) 205f else 118f) * (0.9f + breathe * 0.1f) * reveal
        val radius = dialHeadRadius
        if (radius <= 1f) return

        arcRect.set(originX - radius, originY - radius, originX + radius, originY + radius)
        cometPaint.strokeWidth = 1.5f * density
        for (segment in 0 until COMET_SEGMENTS) {
            val from = segment.toFloat() / COMET_SEGMENTS
            val to = (segment + 1).toFloat() / COMET_SEGMENTS
            // След тянется против вращения: голова на `headDegrees`, хвост назад.
            val alpha = (peakAlpha * (1f - from) * (1f - from)).toInt().coerceIn(0, 255)
            if (alpha <= 2) continue
            cometPaint.color = accent(alpha)
            val start = headDegrees - length * to
            val sweep = length * (to - from)
            // Полградуса внахлёст: без него на стыках сегментов видны волоски фона.
            canvas.drawArc(arcRect, start, sweep + 0.5f, false, cometPaint)
        }

        val headRadians = radians(headDegrees)
        val hx = originX + cos(headRadians).toFloat() * radius
        val hy = originY + sin(headRadians).toFloat() * radius
        dotPaint.color = accent((238f * reveal).toInt())
        canvas.drawCircle(hx, hy, 1.5f * density, dotPaint)

        val halo = 9f * density
        bloomPaint.shader = RadialGradient(
            hx,
            hy,
            halo,
            intArrayOf(accent((86f * reveal).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(hx, hy, halo, bloomPaint)
        bloomPaint.shader = null
    }

    // -------------------------------------------------------------------- волны

    /**
     * Насколько далеко волне идти, чтобы уйти за любой угол экрана.
     *
     * Считается от контура кнопки, а не от точки: волна — это отодвинутый контур,
     * и до угла ей остаётся меньше на половину кнопки.
     */
    private fun maxReach(): Float {
        val dx = max(originX, canvasWidth - originX)
        val dy = max(originY, canvasHeight - originY)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    /**
     * Волны подключения: светящиеся полосы, расходящиеся из кнопки.
     *
     * ## Почему это дешёвая «шейдерная» волна, а не размытие
     *
     * Волна — **один** путь и две кисти. Свет вокруг фронта зашит в радиальный
     * шейдер радиусом **1**, путь строится в единичном радиусе, размер задаёт
     * `canvas.scale`. Кромка — тот же путь, второй масштаб: тонкая линия в
     * 1.25dp, которую градиентом не нарисовать (на таком радиусе она тоньше
     * пикселя). Отсюда: шейдер собирается не на кадр, а один раз — на смену
     * акцента; в кадре нет ни одной аллокации: ни битмапа, ни градиента, ни
     * размытия, ни программного слоя.
     *
     * ## Почему кромка неровная
     *
     * Ровная окружность читается как радар. Кромка смещается тремя медленными
     * гармониками (2θ, 3θ, 5θ) на полтора-два процента радиуса: полоса остаётся
     * окружностью, но дышит. На радиальном шейдере это даёт ещё и плавные
     * переливы яркости вдоль кольца — бесплатно, тем же одним штрихом.
     *
     * ## Почему волны расходятся с замедлением
     *
     * Фронт идёт как `progress^0.72`: вблизи кнопки быстро, к краям всё
     * медленнее. Равномерный ход читается как метроном, замедляющийся — как
     * энергия, которая уходит в экран.
     */
    private fun drawAurora(canvas: Canvas) {
        val shader = bandShader ?: rebuildBandShader()
        val reach = maxReach()
        if (reach <= 1f) return
        bandPaint.shader = shader
        // Ширина тела задана в единичном радиусе: к краю экрана волна толще,
        // у кнопки — тоньше, ровно как расходящийся фронт.
        bandPaint.strokeWidth = BAND_TAIL_UNIT + BAND_FRONT_UNIT
        crestPaint.color = crestColor()
        for (index in bandProgress.indices) {
            val progress = bandProgress[index]
            val radius = reach * CoreDial.expansion(progress)
            if (radius < 6f) continue
            // Волна гаснет к краю экрана: у кнопки она плотная, у углов — намёк.
            val fade = (1f - progress) * (1f - progress)
            val haloAlpha = (fade * 220f).toInt().coerceIn(0, 255)
            val crestAlpha = (fade * 235f).toInt().coerceIn(0, 255)
            if (crestAlpha <= 3) continue
            bandPaint.alpha = haloAlpha
            buildBandPath(index, progress)
            canvas.save()
            canvas.translate(originX, originY)
            canvas.scale(radius, radius)
            // Тело: широкий градиент — свет вокруг фронта.
            canvas.drawPath(bandPath, bandPaint)
            // Кромка: тот же путь, второй масштаб — тонкая линия на единичном
            // радиусе. Ширина пересчитывается в единицы пути, поэтому в dp она
            // одна и та же у кнопки и у края экрана.
            canvas.scale(CREST_SCALE, CREST_SCALE)
            crestPaint.alpha = crestAlpha
            // Молодая волна толще зрелой: по толщине читается, какая ближе.
            val crestWidthDp = CREST_WIDTH_DP + (CREST_WIDTH_MIN_DP - CREST_WIDTH_DP) * progress
            crestPaint.strokeWidth = crestWidthDp * density / (radius * CREST_SCALE)
            canvas.drawPath(bandPath, crestPaint)
            canvas.restore()
        }
        bandPaint.alpha = 255
        bandPaint.shader = null
    }

    /**
     * Кромка волны в единичном радиусе. Путь пересобирается на месте: в кадре
     * не появляется ни одного объекта.
     */
    private fun buildBandPath(index: Int, progress: Float) {
        bandPath.rewind()
        // Свежая волна почти круглая, зрелая волнится сильнее: фронт «расходится»,
        // а не «вращается».
        val amplitude = BAND_WOBBLE * (0.55f + 0.45f * progress)
        val drift = (motionSeconds * BAND_WOBBLE_SPEED + index * 1.7).toFloat()
        val phase2 = drift * 1.7f
        val phase3 = drift * 3.1f
        val phase5 = drift * 5.3f
        val sin2 = sin(phase2)
        val cos2 = cos(phase2)
        val sin3 = sin(phase3)
        val cos3 = cos(phase3)
        val sin5 = sin(phase5)
        val cos5 = cos(phase5)
        // Шейдер ярче всего на единичном радиусе, штрих лежит вокруг него: центр
        // пути сдвинут так, чтобы наружу выступал короткий гребень, а внутрь —
        // длинный хвост.
        val radiusUnit = BAND_PATH_RADIUS_UNIT
        for (step in 0 until bandSegments) {
            val wobble = 0.18f * (harm2Sin[step] * cos2 + harm2Cos[step] * sin2) +
                0.60f * (harm3Sin[step] * cos3 + harm3Cos[step] * sin3) +
                0.22f * (harm5Sin[step] * cos5 + harm5Cos[step] * sin5)
            val r = radiusUnit * (1f + amplitude * wobble)
            val x = segmentCos[step] * r
            val y = segmentSin[step] * r
            if (step == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
        }
        bandPath.close()
    }

    /**
     * Свет вокруг фронта: прозрачность → длинный слабый хвост внутрь → подъём к
     * кромке → прозрачность сразу за ней.
     *
     * Профиль задан в радиусах пути, а не в долях шейдера: так по коду видно, где
     * он лежит относительно кромки. Нормируется на радиус шейдера один раз,
     * поэтому шейдер масштабируется вместе с путём и не пересобирается на кадр.
     */
    private fun rebuildBandShader(): Shader {
        val unit = 1f + BAND_FRONT_UNIT
        val radii = floatArrayOf(
            1f - BAND_TAIL_UNIT,
            1f - BAND_TAIL_UNIT * 0.66f,
            1f - BAND_TAIL_UNIT * 0.35f,
            0.995f,
            1.008f,
            1f + BAND_FRONT_UNIT,
        )
        val shares = floatArrayOf(0f, 0.10f, 0.25f, 0.55f, 0.85f, 0f)
        val stops = FloatArray(radii.size) { index -> radii[index] / unit }
        val colors = IntArray(radii.size) { index ->
            if (shares[index] <= 0f) Color.TRANSPARENT else withAlpha(accent(255), shares[index])
        }
        val shader = RadialGradient(0f, 0f, unit, colors, stops, Shader.TileMode.CLAMP)
        bandShader = shader
        return shader
    }

    /** Гребень: акцент, подсвеченный к белому, — так волна читается «мокрой». */
    private fun crestColor(): Int = Color.rgb(
        (accentR + (255 - accentR) * CREST_WHITEN).toInt().coerceIn(0, 255),
        (accentG + (255 - accentG) * CREST_WHITEN).toInt().coerceIn(0, 255),
        (accentB + (255 - accentB) * CREST_WHITEN).toInt().coerceIn(0, 255),
    )

    private fun withAlpha(color: Int, share: Float): Int = Color.argb(
        (255f * share).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    // --------------------------------------------------------------- созвездие

    private fun rebuildConstellation(w: Int, h: Int) {
        stars.clear()
        edges.clear()
        if (w <= 0 || h <= 0) return
        // Раскладка общая с фоновой анимацией — см. [NovaConstellation].
        NovaConstellation.build(w, h, density).forEach { point ->
            stars += Star(point.x, point.y, point.radius)
        }
        val maxDistance = minOf(170f * density, maxOf(118f * density, minOf(w, h) * 0.17f))
        for (i in stars.indices) {
            val nearest = ArrayList<Pair<Int, Float>>(4)
            val a = stars[i]
            for (j in i + 1 until stars.size) {
                val b = stars[j]
                val distance = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                if (distance <= maxDistance) {
                    nearest += j to distance
                }
            }
            nearest
                .sortedBy { it.second }
                .take(2)
                .forEach { (target, _) -> edges += Edge(i, target) }
        }
    }

    private fun drawConstellation(canvas: Canvas) {
        for (edge in edges) {
            val a = stars.getOrNull(edge.a) ?: continue
            val b = stars.getOrNull(edge.b) ?: continue
            val energy = waveEnergy((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
            val alpha = (energy * 84f).toInt().coerceIn(0, 84)
            if (alpha <= 3) continue
            constellationLinePaint.color = Color.argb(alpha, accentR, accentG, accentB)
            constellationLinePaint.strokeWidth = 0.75f * density + energy * 0.45f * density
            canvas.drawLine(a.x, a.y, b.x, b.y, constellationLinePaint)
        }

        for (star in stars) {
            val energy = waveEnergy(star.x, star.y)
            val alpha = (energy * 168f).toInt().coerceIn(0, 168)
            if (alpha <= 3) continue
            constellationStarPaint.color = Color.argb(alpha, accentR, accentG, accentB)
            canvas.drawCircle(star.x, star.y, star.radius * (1f + energy * 1.5f), constellationStarPaint)
        }
    }

    /**
     * Расстояние от точки до центра волн.
     *
     * Волны круговые — они идут из кнопки, — поэтому и подсветка созвездия
     * считается по кругу: разойдись они формой, вспышки шли бы не по фронту,
     * а рядом с ним.
     */
    private fun contourDistance(x: Float, y: Float): Float =
        hypot((x - originX).toDouble(), (y - originY).toDouble()).toFloat()

    private fun waveEnergy(x: Float, y: Float): Float = waveEnergyAt(contourDistance(x, y))

    /**
     * Энергия волн на заданном расстоянии от кнопки: [0, 1]. Одна и та же
     * величина зажигает узлы созвездия, связи между ними и риски циферблата —
     * поэтому всё, что стоит на пути фронта, вспыхивает одновременно.
     */
    private fun waveEnergyAt(distance: Float): Float {
        if (mode != Mode.CONNECTING) return 0f
        val reach = maxReach()
        val waveWidth = 26f * density
        var energy = 0f
        for (index in bandProgress.indices) {
            val progress = bandProgress[index]
            if (progress <= 0f) continue
            val delta = abs(distance - reach * CoreDial.expansion(progress))
            if (delta > waveWidth) continue
            val near = 1f - delta / waveWidth
            energy = max(energy, near * near * (1f - progress))
        }
        return energy.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ кадры

    private fun canAnimate(): Boolean {
        if (!supported || !isAttachedToWindow) return false
        if (visibility != VISIBLE || alpha <= 0.02f) return false
        return when (mode) {
            Mode.CONNECTING -> true
            Mode.IDLE -> idleMotionAllowed
            Mode.STOPPED -> false
        }
    }

    private fun frameDelayMs(): Long = when {
        lowEndDevice -> 32L
        mode == Mode.IDLE -> IDLE_FRAME_DELAY_MS
        else -> 16L
    }

    private fun scheduleFrame() {
        if (frameArmed || !canAnimate()) return
        frameArmed = true
        postOnAnimationDelayed(frameRunnable, frameDelayMs())
    }

    private fun unscheduleFrame() {
        frameArmed = false
        removeCallbacks(frameRunnable)
    }

    private fun refreshIdleMotionPolicy() {
        idleMotionAllowed = supported && runCatching {
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
        }.getOrDefault(true)
    }

    // ------------------------------------------------------------------ краска

    private fun accent(alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), accentR, accentG, accentB)

    /**
     * Нейтральный тон с долей акцента: [accentShare] 0 — холодный белый, 1 — акцент.
     * Так риски связываются с цветом темы, не превращаясь в цветные полоски.
     */
    private fun tone(alpha: Int, accentShare: Float): Int {
        val a = alpha.coerceIn(0, 255)
        if (a <= 0) return Color.TRANSPARENT
        val share = accentShare.coerceIn(0f, 1f)
        return Color.argb(
            a,
            mix(246, accentR, share),
            mix(248, accentG, share),
            mix(252, accentB, share),
        )
    }

    private fun mix(base: Int, accentValue: Int, share: Float): Int =
        (base * (1f - share) + accentValue * share).toInt().coerceIn(0, 255)

    private fun radians(degrees: Float): Double = degrees * PI / 180.0

    private companion object {
        /** Риск на окружности. 72 — это шаг 5°, ровно по 12 длинных на оборот. */
        const val TICK_COUNT = 72
        const val TICK_STEP_DEGREES = 360f / TICK_COUNT
        const val MAJOR_TICK_EVERY = 6

        /** Обод снизу разомкнут: без разрыва циферблат читается как мишень. */
        const val BEZEL_START_DEGREES = 118f
        const val BEZEL_SWEEP_DEGREES = 304f

        const val COMET_SEGMENTS = 10

        /** Точек на кромке волны; на слабых устройствах — половина. */
        const val BAND_SEGMENTS = 96

        /**
         * Профиль волны в единичном радиусе: хвост внутрь, гребень наружу.
         * Отношение 3.75 : 1 — свет уходит в экран, а не вперёд, как у настоящей
         * расходящейся волны.
         */
        const val BAND_TAIL_UNIT = 0.075f
        const val BAND_FRONT_UNIT = 0.020f

        /**
         * Центр пути: шейдер ярче всего на единичном радиусе, а штрих должен
         * покрыть [1 − хвост, 1 + гребень], поэтому путь сдвинут внутрь.
         */
        const val BAND_PATH_RADIUS_UNIT = 1f + (BAND_FRONT_UNIT - BAND_TAIL_UNIT) * 0.5f

        /** Амплитуда смещения кромки: 1.9 % радиуса — заметно, но не «радар». */
        const val BAND_WOBBLE = 0.019f

        /** Скорость дрейфа гармоник, рад/с: медленнее волны, иначе кромка «кипит». */
        const val BAND_WOBBLE_SPEED = 0.37

        /**
         * Кромка волны: толщина в dp (у молодой волны толще) и во сколько раз её
         * радиус больше радиуса тела.
         */
        const val CREST_WIDTH_DP = 1.5f
        const val CREST_WIDTH_MIN_DP = 0.9f
        const val CREST_SCALE = 1f / BAND_PATH_RADIUS_UNIT

        /** Насколько кромка подсвечена к белому. */
        const val CREST_WHITEN = 0.32f

        /** Оборот головки в покое: медленно настолько, что движение не отвлекает. */
        const val IDLE_REVOLUTION_SECONDS = 46.0

        /** Оборот головки в подключении: заметно быстрее, но без суеты. */
        const val CONNECTING_REVOLUTION_SECONDS = 13.0

        /** Период дыхания зарева. Не кратен обороту — картинка не повторяется. */
        const val BREATH_PERIOD_SECONDS = 7.5

        /** Появление композиции при входе в режим. */
        const val REVEAL_SECONDS = 0.9

        /** Покой — 24 кадра в секунду: этого хватает медленному движению. */
        const val IDLE_FRAME_DELAY_MS = 41L
    }
}
