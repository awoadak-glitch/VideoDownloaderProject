package com.awr.vpn

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.text.DecimalFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class AwrVpnSurface(context: Context, private val actions: Actions) : View(context) {
    interface Actions {
        fun onConnect()
        fun onServer()
        fun onVip()
        fun onProtocol()
        fun onSettings()
    }

    data class UiState(
        val vip: Boolean = false,
        val phase: ConnPhase = ConnPhase.OFF,
        val flag: String = "🌐",
        val server: String = "VIP server vault locked",
        val ping: Int = 0,
        val protocol: String = "AUTO",
        val dns: String = "1.1.1.1",
        val serverCount: Int = 0,
        val error: String = "",
        val rxRate: Long = 0,
        val txRate: Long = 0,
        val quality: Int = 0,
        val connectedSince: Long = 0L
    )

    var state: UiState = UiState()
        set(value) { field = value; invalidate() }
    var loadingRepository = false

    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private var phaseAnim = 0f
    private var pressScale = 1f
    private var pressFlash = 0f
    private var burst = 0f
    private var touchingCore = false

    private val connectHit = RectF()
    private val serverHit = RectF()
    private val vipHit = RectF()
    private val protocolHit = RectF()
    private val settingsHit = RectF()

    private val stars = List(54) { i ->
        Triple(((i * 73 + 19) % 997) / 997f, ((i * 137 + 31) % 991) / 991f, .45f + ((i * 17) % 50) / 100f)
    }

    init {
        isClickable = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phaseAnim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun triggerConnectBurst() {
        val flash = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 560
            interpolator = DecelerateInterpolator()
            addUpdateListener { burst = it.animatedValue as Float; invalidate() }
        }
        val scale = ValueAnimator.ofFloat(pressScale, .90f, 1.09f, 1f).apply {
            duration = 430
            interpolator = DecelerateInterpolator()
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }
        AnimatorSet().apply { playTogether(flash, scale); start() }
    }

    private fun d(v: Float) = v * density

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        drawBackground(c, w, h)
        drawHeader(c, w)
        drawHero(c, w, h)
        drawTelemetry(c, w, h)
        drawServerCard(c, w, h)
        drawBottomNav(c, w, h)
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(2, 7, 15), Color.rgb(3, 15, 26), Color.rgb(5, 10, 22), Color.rgb(2, 6, 13)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        val t = phaseAnim * (PI * 2).toFloat()
        val blobs = arrayOf(
            floatArrayOf(w * (.18f + .05f * sin(t)), h * .20f, .64f),
            floatArrayOf(w * (.83f + .04f * cos(t * .7f)), h * .36f, .56f),
            floatArrayOf(w * (.43f + .06f * cos(t * .55f)), h * .76f, .70f)
        )
        blobs.forEachIndexed { i, b ->
            val col = when (i) { 0 -> Color.rgb(0, 117, 156); 1 -> Color.rgb(0, 120, 95); else -> Color.rgb(51, 45, 135) }
            p.shader = RadialGradient(b[0], b[1], w * b[2], Color.argb(68, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(b[0], b[1], w * b[2], p); p.shader = null
        }
        stroke.strokeWidth = d(.6f); stroke.color = Color.argb(15, 118, 194, 216)
        var x = d(18f)
        while (x < w) { c.drawLine(x, 0f, x, h, stroke); x += d(38f) }
        var y = d(90f)
        while (y < h - d(90f)) { c.drawLine(0f, y, w, y, stroke); y += d(42f) }
        stars.forEachIndexed { i, s ->
            val a = (22 + 45 * ((sin(t + i * .61f) + 1f) / 2f)).toInt()
            p.color = Color.argb(a, 187, 232, 242)
            c.drawCircle(w * s.first, h * s.second, d(.55f + s.third), p)
        }
    }

    private fun drawHeader(c: Canvas, w: Float) {
        val top = d(49f)
        p.textAlign = Paint.Align.LEFT
        p.typeface = bold; p.textSize = d(23f)
        p.shader = LinearGradient(d(22f), top, d(170f), top, intArrayOf(Color.WHITE, Color.rgb(102, 255, 218), Color.rgb(106, 210, 255)), null, Shader.TileMode.CLAMP)
        c.drawText("AWR VPN", d(22f), top, p); p.shader = null
        p.typeface = medium; p.textSize = d(8.4f); p.color = Color.rgb(91, 124, 145)
        c.drawText("ULTRA PRIVATE NETWORK  •  2.0", d(23f), top + d(16f), p)

        val vipW = d(101f); val vipH = d(37f)
        vipHit.set(w - d(22f) - vipW, d(29f), w - d(22f), d(29f) + vipH)
        p.color = if (state.vip) Color.argb(38, 70, 255, 205) else Color.argb(35, 255, 194, 91)
        c.drawRoundRect(vipHit, d(19f), d(19f), p)
        stroke.strokeWidth = d(1f); stroke.color = if (state.vip) Color.argb(130, 70, 255, 205) else Color.argb(110, 255, 194, 91)
        c.drawRoundRect(vipHit, d(19f), d(19f), stroke)
        p.textAlign = Paint.Align.CENTER; p.typeface = bold; p.textSize = d(9.5f); p.color = if (state.vip) Color.rgb(70, 255, 205) else Color.rgb(255, 205, 112)
        c.drawText(if (state.vip) "✦  VIP ACTIVE" else "✦  UNLOCK VIP", vipHit.centerX(), vipHit.centerY() + d(3.3f), p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawHero(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h * .36f
        val t = phaseAnim * (PI * 2).toFloat()
        val active = state.phase != ConnPhase.OFF && state.phase != ConnPhase.ERROR
        val accent = when (state.phase) {
            ConnPhase.ON -> Color.rgb(70, 255, 205)
            ConnPhase.ERROR -> Color.rgb(255, 105, 123)
            else -> Color.rgb(98, 216, 255)
        }

        p.textAlign = Paint.Align.CENTER; p.typeface = bold; p.textSize = d(9.2f); p.color = when (state.phase) {
            ConnPhase.ON -> Color.rgb(70, 255, 205)
            ConnPhase.ERROR -> Color.rgb(255, 127, 140)
            else -> Color.rgb(127, 165, 185)
        }
        val status = when (state.phase) {
            ConnPhase.ON -> "ENCRYPTED TUNNEL • ONLINE"
            ConnPhase.FINDING -> "SCANNING • BEST ROUTE ENGINE"
            ConnPhase.AUTH -> "AUTHORIZING • AWR VIP"
            ConnPhase.CONNECTING -> "NEGOTIATING SECURE TUNNEL"
            ConnPhase.ERROR -> "CONNECTION INTERRUPTED"
            ConnPhase.OFF -> if (state.vip) "READY • SMART ROUTE STANDBY" else "AWR VIP REQUIRED"
        }
        c.drawText(status, cx, cy - d(145f), p)

        val pulse = if (active) (sin(t * 1.35f) + 1f) / 2f else .12f
        val baseR = d(116f)
        for (i in 0..3) {
            val rr = baseR + d(i * 11f) + if (active) d(2.5f * sin(t + i)) else 0f
            stroke.strokeWidth = d(if (i == 0) 1.5f else .65f)
            stroke.color = Color.argb((78 - i * 14).coerceAtLeast(18), Color.red(accent), Color.green(accent), Color.blue(accent))
            c.drawCircle(cx, cy, rr, stroke)
        }

        for (i in 0..2) {
            val rr = baseR + d(6f + i * 18f)
            val start = phaseAnim * 360f * (if (i % 2 == 0) 1f else -1f) + i * 61f
            stroke.strokeWidth = d(if (i == 0) 2.2f else 1.2f)
            stroke.color = Color.argb(105 - i * 22, Color.red(accent), Color.green(accent), Color.blue(accent))
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), start, 57f + i * 16f, false, stroke)
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), start + 180f, 32f + i * 10f, false, stroke)
        }

        if (active) {
            repeat(6) { i ->
                val a = t * (if (i % 2 == 0) 1f else -1f) * (1f + i * .04f) + i * 1.04f
                val rr = d(132f + (i % 3) * 8f)
                p.color = Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent))
                c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, d(1.8f + (i % 2)), p)
            }
        }

        val globeR = d(96f)
        p.shader = RadialGradient(cx - d(28f), cy - d(35f), globeR * 1.4f, intArrayOf(Color.rgb(17, 65, 84), Color.rgb(5, 29, 46), Color.rgb(2, 12, 23)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, globeR, p); p.shader = null
        stroke.strokeWidth = d(.8f); stroke.color = Color.argb(52, 110, 215, 231); c.drawCircle(cx, cy, globeR, stroke)
        val save = c.save(); c.clipPath(Path().apply { addCircle(cx, cy, globeR - d(1f), Path.Direction.CW) })
        stroke.color = Color.argb(32, 102, 203, 223); stroke.strokeWidth = d(.75f)
        for (i in -2..2) c.drawOval(RectF(cx - globeR, cy + i * d(27f) - d(13f), cx + globeR, cy + i * d(27f) + d(13f)), stroke)
        for (i in -2..2) {
            val ww = globeR * (.23f + abs(i) * .20f)
            c.drawOval(RectF(cx - ww, cy - globeR, cx + ww, cy + globeR), stroke)
        }
        stroke.strokeWidth = d(2.2f); stroke.color = Color.argb(if (state.phase == ConnPhase.ON) 178 else 95, Color.red(accent), Color.green(accent), Color.blue(accent))
        c.drawPath(Path().apply { moveTo(cx - d(76f), cy + d(29f)); cubicTo(cx - d(48f), cy - d(70f), cx + d(58f), cy - d(57f), cx + d(78f), cy + d(17f)) }, stroke)
        c.restoreToCount(save)

        if (burst > 0f) {
            repeat(3) { i ->
                val rr = d(61f + burst * (54f + i * 21f))
                stroke.strokeWidth = d(2f - i * .4f); stroke.color = Color.argb(((1f - burst) * (145 - i * 25)).toInt().coerceIn(0, 180), 70, 255, 205)
                c.drawCircle(cx, cy, rr, stroke)
            }
        }

        val coreR = d(59f) * pressScale
        p.color = Color.argb((24 + 40 * pulse).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)); c.drawCircle(cx, cy, coreR + d(17f * pulse), p)
        p.shader = RadialGradient(cx - d(19f), cy - d(21f), coreR * 1.55f, brighten(accent, .22f), darken(accent, .43f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, coreR, p); p.shader = null
        connectHit.set(cx - d(72f), cy - d(72f), cx + d(72f), cy + d(72f))
        drawPower(c, cx, cy - d(5f), Color.rgb(2, 18, 24), pressScale)
        p.typeface = bold; p.textSize = d(8.8f); p.textAlign = Paint.Align.CENTER; p.color = Color.argb(210, 2, 18, 24)
        c.drawText(if (state.phase == ConnPhase.ON) "DISCONNECT" else "CONNECT", cx, cy + d(38f), p)

        p.typeface = regular; p.textSize = d(10.8f); p.color = if (state.phase == ConnPhase.ERROR) Color.rgb(255, 138, 150) else Color.rgb(127, 153, 171)
        val sub = when {
            state.phase == ConnPhase.ERROR && state.error.isNotBlank() -> state.error.take(46)
            state.phase == ConnPhase.ON -> "${state.flag} ${state.server}  •  ${elapsed(state.connectedSince)}"
            !state.vip -> "VIP authorization unlocks the private server vault"
            else -> "Tap to auto-select and establish the fastest secure route"
        }
        c.drawText(sub, cx, cy + d(136f), p); p.textAlign = Paint.Align.LEFT
    }

    private fun drawTelemetry(c: Canvas, w: Float, h: Float) {
        val top = h * .585f; val margin = d(20f); val gap = d(7f); val cardW = (w - margin * 2 - gap * 3) / 4f; val cardH = d(64f)
        fun metric(index: Int, label: String, value: String, accent: Int) {
            val left = margin + index * (cardW + gap); val r = RectF(left, top, left + cardW, top + cardH)
            p.color = Color.argb(188, 5, 20, 34); c.drawRoundRect(r, d(17f), d(17f), p)
            stroke.strokeWidth = d(.7f); stroke.color = Color.argb(72, 45, 80, 97); c.drawRoundRect(r, d(17f), d(17f), stroke)
            p.typeface = regular; p.textSize = d(7.5f); p.color = Color.rgb(92, 121, 142); c.drawText(label, left + d(9f), top + d(18f), p)
            p.typeface = bold; p.textSize = d(10.6f); p.color = accent; c.drawText(value, left + d(9f), top + d(43f), p)
        }
        metric(0, "LATENCY", if (state.ping > 0) "${state.ping} ms" else "LIVE", Color.WHITE)
        metric(1, "QUALITY", if (state.quality > 0) "${state.quality}%" else "AUTO", Color.rgb(70, 255, 205))
        metric(2, "DOWNLOAD", rate(state.rxRate), Color.rgb(104, 216, 255))
        metric(3, "UPLOAD", rate(state.txRate), Color.rgb(196, 141, 255))
    }

    private fun drawServerCard(c: Canvas, w: Float, h: Float) {
        val margin = d(20f); val top = h * .68f; val bottom = min(h - d(106f), top + d(112f))
        serverHit.set(margin, top, w - margin, bottom)
        p.color = Color.argb(206, 5, 18, 32); c.drawRoundRect(serverHit, d(23f), d(23f), p)
        stroke.strokeWidth = d(1f); stroke.color = if (state.vip) Color.argb(88, 70, 255, 205) else Color.argb(80, 255, 195, 91); c.drawRoundRect(serverHit, d(23f), d(23f), stroke)

        p.textSize = d(29f); c.drawText(state.flag, margin + d(16f), top + d(46f), p)
        p.typeface = bold; p.textSize = d(14.6f); p.color = Color.WHITE; c.drawText(state.server.take(27), margin + d(59f), top + d(32f), p)
        p.typeface = regular; p.textSize = d(9.8f); p.color = Color.rgb(112, 140, 158)
        val line = if (!state.vip) "Secure repository locked • AWR VIP required" else "${state.protocol}  •  ${state.dns} DNS  •  ${state.serverCount} endpoints"
        c.drawText(line, margin + d(59f), top + d(52f), p)

        val chip = RectF(margin + d(15f), top + d(69f), margin + d(133f), top + d(94f))
        p.color = Color.argb(28, 70, 255, 205); c.drawRoundRect(chip, d(13f), d(13f), p)
        p.typeface = bold; p.textSize = d(8f); p.color = Color.rgb(70, 255, 205); c.drawText("⚡ SMART ROUTE", chip.left + d(10f), chip.centerY() + d(2.8f), p)

        p.textAlign = Paint.Align.RIGHT; p.typeface = bold; p.textSize = d(8.8f); p.color = if (state.vip) Color.rgb(70, 255, 205) else Color.rgb(255, 206, 112)
        c.drawText(if (loadingRepository) "SYNCING…" else if (state.vip) "CHANGE SERVER  ›" else "UNLOCK  ›", w - margin - d(16f), top + d(86f), p); p.textAlign = Paint.Align.LEFT
    }

    private fun drawBottomNav(c: Canvas, w: Float, h: Float) {
        val barTop = h - d(79f); val margin = d(17f); val bar = RectF(margin, barTop, w - margin, h - d(15f))
        p.color = Color.argb(235, 4, 14, 26); c.drawRoundRect(bar, d(24f), d(24f), p)
        stroke.strokeWidth = d(.8f); stroke.color = Color.argb(82, 41, 73, 90); c.drawRoundRect(bar, d(24f), d(24f), stroke)
        val cell = bar.width() / 4f; val labels = arrayOf("HOME", "SERVERS", "VIP", "CONTROL"); val icons = arrayOf("⌂", "◉", "✦", "⚙")
        repeat(4) { i ->
            val cx = bar.left + cell * (i + .5f)
            p.textAlign = Paint.Align.CENTER; p.typeface = medium; p.textSize = d(15.5f); p.color = when (i) { 0 -> Color.rgb(70, 255, 205); 2 -> if (state.vip) Color.rgb(70, 255, 205) else Color.rgb(255, 205, 112); else -> Color.rgb(115, 143, 161) }
            c.drawText(icons[i], cx, barTop + d(25f), p)
            p.typeface = bold; p.textSize = d(7.2f); p.color = if (i == 0) Color.rgb(70, 255, 205) else Color.rgb(93, 120, 139); c.drawText(labels[i], cx, barTop + d(47f), p)
        }
        p.textAlign = Paint.Align.LEFT
        settingsHit.set(bar.left + cell * 3f, bar.top, bar.right, bar.bottom)
    }

    private fun drawPower(c: Canvas, cx: Float, cy: Float, color: Int, scale: Float) {
        stroke.color = color; stroke.strokeWidth = d(4.1f) * scale; stroke.strokeCap = Paint.Cap.ROUND
        val r = d(18f) * scale; c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -50f, 280f, false, stroke)
        c.drawLine(cx, cy - d(27f) * scale, cx, cy - d(5f) * scale, stroke); stroke.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (connectHit.contains(event.x, event.y)) {
                    touchingCore = true; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    ValueAnimator.ofFloat(pressScale, .91f).apply { duration = 100; addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }; start() }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> if (touchingCore) releaseCore(false)
            MotionEvent.ACTION_UP -> {
                val x = event.x; val y = event.y
                if (touchingCore) {
                    val hit = connectHit.contains(x, y); releaseCore(hit); if (hit) actions.onConnect(); performClick(); return true
                }
                when {
                    vipHit.contains(x, y) -> actions.onVip()
                    serverHit.contains(x, y) -> actions.onServer()
                    protocolHit.contains(x, y) -> actions.onProtocol()
                    settingsHit.contains(x, y) -> actions.onSettings()
                    y > height - d(79f) && x in (width / 4f)..(width / 2f) -> actions.onServer()
                    y > height - d(79f) && x in (width / 2f)..(width * .75f) -> actions.onVip()
                }
                performClick()
            }
        }
        return true
    }

    private fun releaseCore(triggered: Boolean) {
        touchingCore = false
        val anim = ValueAnimator.ofFloat(pressScale, if (triggered) 1.08f else 1f, 1f).apply {
            duration = 230; interpolator = DecelerateInterpolator(); addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }
        anim.start()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun rate(v: Long): String {
        if (v <= 0) return "0 B/s"
        val kb = v / 1024.0
        return when {
            kb >= 1024 -> "${DecimalFormat("0.0").format(kb / 1024)} MB/s"
            kb >= 1 -> "${DecimalFormat("0").format(kb)} KB/s"
            else -> "$v B/s"
        }
    }

    private fun elapsed(since: Long): String {
        if (since <= 0) return "protected"
        val sec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private fun brighten(color: Int, f: Float) = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * f).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt().coerceIn(0, 255)
    )
    private fun darken(color: Int, f: Float) = Color.rgb(
        (Color.red(color) * (1f - f)).toInt().coerceIn(0, 255),
        (Color.green(color) * (1f - f)).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1f - f)).toInt().coerceIn(0, 255)
    )
}
