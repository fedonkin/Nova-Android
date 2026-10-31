package com.example.nova

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Волны остались только на время подключения и по-прежнему идут из центра кнопки
 * ([setPulseOrigin]). След — **один** штрих, поперёк которого лежит радиальный
 * градиент: снаружи обрыв в прозрачность, на фронте максимум, внутрь длинный
 * хвост. Повторённые контуры читались как повторённые контуры — три отдельные
 * линии вместо следа.
 *
 * Поверх волн зажигается созвездие — то же самое, что человек увидит после
 * подключения: точки берутся из [NovaConstellation], общей с фоновой анимацией.
 *
 * ## Чем это платится
 *
 * Ни одного размытия, ни одного программного слоя. На кадр: круг зарева,
 * два обода, 72 отрезка рисок, след головки из [COMET_SEGMENTS] дуг и — только
 * в подключении — обход созвездия и до трёх волн. Шейдеры создаются на кадр
 * по одному на волну, как и раньше.
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
    private var animatedPhase = 0f

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

    /** Сколько волн в кадре и как они разнесены по фазе. */
    private val ringOffsets = if (lowEndDevice) {
        floatArrayOf(0f, 0.5f)
    } else {
        floatArrayOf(0f, 0.34f, 0.67f)
    }

    /** Длина следа за фронтом волны. Он рисуется градиентом, а не повтором контура. */
    private val trailWidth = (if (lowEndDevice) 34f else 48f) * density

    private val cycleDurationMs = if (lowEndDevice) 9800f else 8200f

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
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

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
     * Фаза волн по времени часов, без продвижения: зовётся из отрисовки и привязки,
     * кадр сам часы не двигает.
     */
    private fun syncWavePhase(now: Double) {
        val start = animationStartSeconds
            ?: NovaAnimationMemory.tronStartOrBegin(now).also { animationStartSeconds = it }
        animatedPhase = SweepPhase.fraction(now - start, cycleDurationMs / 1000.0)
    }

    /** Общая точка входа движения: время кадра и фаза волн, если они идут. */
    private fun syncMotion(now: Double) {
        motionSeconds = now
        if (enteredAtSeconds == null) enteredAtSeconds = now
        if (mode == Mode.CONNECTING) syncWavePhase(now)
    }

    /** Явный перезапуск волн: отсчёт заново от текущего времени часов. */
    private fun restartWavePhase() {
        val now = NovaAnimationClock.peek()
        animationStartSeconds = NovaAnimationMemory.tronRestart(now)
        animatedPhase = 0f
        syncWavePhase(now)
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
                    syncWavePhase(now)
                }
                invalidate()
                scheduleFrame()
            }

            Mode.IDLE -> {
                animationStartSeconds = null
                NovaAnimationMemory.tronStop()
                animatedPhase = 0f
                refreshIdleMotionPolicy()
                if (enteredIdle) enteredAtSeconds = null
                invalidate()
                scheduleFrame()
            }

            Mode.STOPPED -> {
                animationStartSeconds = null
                NovaAnimationMemory.tronStop()
                animatedPhase = 0f
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
        if (constellationEnabled) {
            if (mode == Mode.CONNECTING) drawConstellation(canvas)
        }
        drawDial(canvas)
        if (mode == Mode.CONNECTING) drawWaves(canvas)
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

    private fun drawWaves(canvas: Canvas) {
        val reach = maxReach()
        for (index in ringOffsets.indices) {
            val progress = ((animatedPhase - ringOffsets[index]) % 1f + 1f) % 1f
            if (progress <= 0.001f || progress >= 1f) continue
            val radius = reach * progress
            if (radius <= 1f) continue

            // Яркость гаснет к краю экрана квадратично: у самой кнопки волна
            // плотная, к углам от неё остаётся намёк.
            val fade = (1f - progress) * (1f - progress)
            val peak = (fade * 190f).toInt().coerceIn(0, 255)
            if (peak <= 2) continue

            // Свечение — один штрих с радиальным градиентом поперёк, а не
            // несколько контуров подряд. Здесь фронт яркий, снаружи обрыв
            // в прозрачность, а внутрь уходит длинный хвост.
            val tail = trailWidth
            val ahead = tail * 0.22f
            val outer = radius + ahead
            val inner = (radius - tail).coerceAtLeast(0f)
            val width = outer - inner
            if (width <= 1f) continue

            wavePaint.shader = RadialGradient(
                originX,
                originY,
                outer,
                intArrayOf(
                    Color.argb(0, accentR, accentG, accentB),
                    Color.argb((peak * 0.18f).toInt().coerceIn(0, 255), accentR, accentG, accentB),
                    Color.argb(peak, accentR, accentG, accentB),
                    Color.argb(0, accentR, accentG, accentB),
                ),
                floatArrayOf(
                    (inner / outer).coerceIn(0f, 1f),
                    ((radius - tail * 0.45f) / outer).coerceIn(0f, 1f),
                    (radius / outer).coerceIn(0f, 1f),
                    1f,
                ),
                Shader.TileMode.CLAMP,
            )
            // Штрих шириной во весь след: сама краска уже несёт и фронт, и хвост.
            wavePaint.strokeWidth = width
            canvas.drawCircle(originX, originY, (inner + outer) * 0.5f, wavePaint)
            wavePaint.shader = null
        }
    }

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
        for (offset in ringOffsets) {
            val progress = ((animatedPhase - offset) % 1f + 1f) % 1f
            if (progress <= 0f) continue
            val delta = abs(distance - reach * progress)
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
