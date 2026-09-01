package com.awr.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.*

class UltraSurface(context: Context, private val actions: Actions) : View(context) {
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
        val autoBest: Boolean = true,
        val downloadBytes: Long = 0L,
        val uploadBytes: Long = 0L,
        val connectedSeconds: Long = 0L
    )

    var state = UiState()
        set(value) { field = value; invalidate() }
    var loadingRepository = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private var ticker = 0f
    private var tapBurst = 0f
    private var pressedCore = false
    private val connectHit = RectF()
    private val serverHit = RectF()
    private val vipHit = RectF()
    private val protocolHit = RectF()
    private val settingsHit = RectF()

    private val stars = List(72) { i ->
        val x = ((i * 83 + 13) % 991) / 991f
        val y = ((i * 149 + 31) % 983) / 983f
        val z = .35f + ((i * 29) % 65) / 100f
        Triple(x, y, z)
    }

    init {
        isClickable = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { ticker = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun d(v: Float) = v * density

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawBackground(c, w, h)
        drawHeader(c, w)
        drawStatusCapsule(c, w, h)
        drawHero(c, w, h)
        drawLiveRail(c, w, h)
        drawServerCard(c, w, h)
        drawBottomNav(c, w, h)
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        val active = state.phase == ConnPhase.ON
        val connecting = state.phase == ConnPhase.FINDING || state.phase == ConnPhase.AUTH || state.phase == ConnPhase.CONNECTING
        val topColor = if (active) Color.rgb(3, 15, 19) else Color.rgb(4, 8, 18)
        val midColor = if (active) Color.rgb(4, 28, 31) else Color.rgb(6, 17, 31)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(topColor, midColor, Color.rgb(3, 8, 16)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        val a = ticker * PI.toFloat() * 2f
        val glow1 = if (active) Color.rgb(29, 235, 187) else Color.rgb(21, 136, 194)
        val glow2 = if (active) Color.rgb(24, 153, 117) else Color.rgb(91, 72, 210)
        fun blob(x: Float, y: Float, radius: Float, col: Int, alpha: Int) {
            p.shader = RadialGradient(x, y, radius, Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, radius, p); p.shader = null
        }
        blob(w * (.17f + .045f * sin(a)), h * .22f, w * .58f, glow1, if (active) 76 else 49)
        blob(w * (.85f + .035f * cos(a * .72f)), h * .43f, w * .53f, glow2, 44)
        blob(w * (.52f + .055f * sin(a * .52f)), h * .79f, w * .46f, Color.rgb(21, 91, 140), 35)

        stars.forEachIndexed { i, s ->
            val twinkle = ((sin(a * (0.7f + s.third) + i * .83f) + 1f) * .5f)
            p.color = Color.argb((18 + twinkle * 52).toInt(), 179, 229, 238)
            c.drawCircle(w * s.first, h * s.second, d(.45f + s.third * 1.15f), p)
        }

        if (connecting || active) {
            stroke.strokeWidth = d(.55f)
            stroke.color = Color.argb(if (active) 27 else 18, 78, 234, 205)
            for (i in 0..8) {
                val y = h * .16f + i * d(56f)
                c.drawLine(d(18f), y, w - d(18f), y + d(14f * sin(a + i)), stroke)
            }
        }
    }

    private fun drawHeader(c: Canvas, w: Float) {
        p.textAlign = Paint.Align.LEFT
        p.typeface = bold; p.textSize = d(24f)
        p.shader = LinearGradient(d(20f), d(31f), d(170f), d(31f), Color.WHITE, Color.rgb(71, 243, 198), Shader.TileMode.CLAMP)
        c.drawText("AWR VPN", d(21f), d(38f), p); p.shader = null
        p.typeface = bold; p.textSize = d(7.6f); p.color = Color.rgb(93, 126, 148)
        c.drawText("ULTRA PRIVATE NETWORK • 2.0", d(22f), d(54f), p)

        val bw = d(104f); val bh = d(38f)
        vipHit.set(w - d(20f) - bw, d(20f), w - d(20f), d(20f) + bh)
        p.color = if (state.vip) Color.argb(42, 71, 243, 198) else Color.argb(38, 255, 193, 91)
        c.drawRoundRect(vipHit, d(19f), d(19f), p)
        stroke.strokeWidth = d(1f)
        stroke.color = if (state.vip) Color.argb(125, 71, 243, 198) else Color.argb(110, 255, 193, 91)
        c.drawRoundRect(vipHit, d(19f), d(19f), stroke)
        p.typeface = bold; p.textSize = d(9.5f); p.textAlign = Paint.Align.CENTER
        p.color = if (state.vip) Color.rgb(71, 243, 198) else Color.rgb(255, 205, 111)
        c.drawText(if (state.vip) "✦  VIP ACTIVE" else "✦  UNLOCK VIP", vipHit.centerX(), vipHit.centerY() + d(3.3f), p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawStatusCapsule(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val y = h * .145f
        val active = state.phase == ConnPhase.ON
        val working = state.phase == ConnPhase.FINDING || state.phase == ConnPhase.AUTH || state.phase == ConnPhase.CONNECTING
        val text = when (state.phase) {
            ConnPhase.ON -> "●  SECURE TUNNEL ACTIVE"
            ConnPhase.FINDING -> "◌  ANALYZING BEST ROUTE"
            ConnPhase.AUTH -> "◌  AUTHORIZING PRIVATE PROFILE"
            ConnPhase.CONNECTING -> "◌  ESTABLISHING ENCRYPTED TUNNEL"
            ConnPhase.ERROR -> "!  CONNECTION INTERRUPTED"
            ConnPhase.OFF -> if (state.vip) "◦  READY • TAP TO CONNECT" else "⌁  VIP REQUIRED FOR SERVER ACCESS"
        }
        p.typeface = bold; p.textSize = d(8.8f)
        val tw = p.measureText(text); val r = RectF(cx - tw / 2 - d(15f), y - d(15f), cx + tw / 2 + d(15f), y + d(9f))
        val col = when {
            active -> Color.rgb(71, 243, 198)
            working -> Color.rgb(106, 210, 241)
            state.phase == ConnPhase.ERROR -> Color.rgb(255, 114, 124)
            else -> Color.rgb(142, 166, 181)
        }
        p.color = Color.argb(26, Color.red(col), Color.green(col), Color.blue(col)); c.drawRoundRect(r, d(20f), d(20f), p)
        stroke.strokeWidth = d(.8f); stroke.color = Color.argb(75, Color.red(col), Color.green(col), Color.blue(col)); c.drawRoundRect(r, d(20f), d(20f), stroke)
        p.textAlign = Paint.Align.CENTER; p.color = col; c.drawText(text, cx, y + d(1f), p); p.textAlign = Paint.Align.LEFT
    }

    private fun drawHero(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f; val cy = h * .365f
        val a = ticker * PI.toFloat() * 2f
        val active = state.phase == ConnPhase.ON
        val working = state.phase == ConnPhase.FINDING || state.phase == ConnPhase.AUTH || state.phase == ConnPhase.CONNECTING
        val powered = active || working
        val accent = when {
            active -> Color.rgb(71, 243, 198)
            state.phase == ConnPhase.ERROR -> Color.rgb(255, 108, 120)
            state.vip -> Color.rgb(83, 199, 235)
            else -> Color.rgb(128, 149, 165)
        }

        // animated portal rings
        for (i in 0..4) {
            val pulse = if (powered) sin(a * (1f + i * .04f) + i) * d(3.4f) else 0f
            val r = d(102f + i * 14f) + pulse
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = d(if (i == 0) 1.5f else .65f)
            stroke.color = Color.argb((82 - i * 12).coerceAtLeast(18), Color.red(accent), Color.green(accent), Color.blue(accent))
            c.drawCircle(cx, cy, r, stroke)
        }

        // rotating arcs
        for (i in 0..3) {
            val r = d(121f + i * 11f)
            val rect = RectF(cx-r, cy-r, cx+r, cy+r)
            stroke.strokeWidth = d(2.1f - i * .25f)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = Color.argb(if (powered) 130 - i * 20 else 48, Color.red(accent), Color.green(accent), Color.blue(accent))
            c.drawArc(rect, a * 57.2958f * (if (i % 2 == 0) 1f else -1f) + i * 70f, 38f + i * 8f, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT

        if (powered) {
            for (i in 0..5) {
                val aa = a * (if (i % 2 == 0) 1f else -.72f) + i * 1.047f
                val r = d(126f + (i % 3) * 13f)
                val x = cx + cos(aa) * r; val y = cy + sin(aa) * r
                p.color = Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent))
                c.drawCircle(x, y, d(2.1f + (i % 2)), p)
            }
        }

        // central glass sphere
        val sphere = d(94f)
        p.shader = RadialGradient(cx - d(25f), cy - d(30f), sphere * 1.45f,
            intArrayOf(lighten(accent, if (active) .08f else .03f), Color.rgb(9, 35, 48), Color.rgb(4, 14, 25)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, sphere, p); p.shader = null
        stroke.strokeWidth = d(1.2f); stroke.color = Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)); c.drawCircle(cx, cy, sphere, stroke)

        val clipped = c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, sphere - d(1f), Path.Direction.CW) })
        stroke.strokeWidth = d(.75f); stroke.color = Color.argb(39, 126, 225, 223)
        for (i in -2..2) {
            val yy = cy + i * d(24f)
            c.drawOval(RectF(cx-sphere, yy-d(11f), cx+sphere, yy+d(11f)), stroke)
        }
        for (i in -2..2) {
            val ww = sphere * (.24f + abs(i) * .22f)
            c.drawOval(RectF(cx-ww, cy-sphere, cx+ww, cy+sphere), stroke)
        }
        // moving route on globe
        val routeShift = sin(a) * d(6f)
        val path = Path().apply {
            moveTo(cx-d(74f), cy+d(27f))
            cubicTo(cx-d(45f), cy-d(57f)+routeShift, cx+d(45f), cy-d(51f)-routeShift, cx+d(76f), cy+d(12f))
        }
        stroke.strokeWidth = d(2.2f); stroke.color = Color.argb(if (active) 195 else 105, 71, 243, 198); c.drawPath(path, stroke)
        c.restoreToCount(clipped)

        // tap shockwave
        if (tapBurst > 0f) {
            val rr = d(63f + 72f * tapBurst)
            stroke.strokeWidth = d(3f * (1f - tapBurst) + .5f)
            stroke.color = Color.argb((180 * (1f - tapBurst)).toInt(), 71, 243, 198)
            c.drawCircle(cx, cy, rr, stroke)
            for (i in 0 until 10) {
                val aa = i * PI.toFloat() / 5f + a * .3f
                val r1 = d(63f + 28f * tapBurst); val r2 = r1 + d(12f * (1f - tapBurst))
                stroke.strokeWidth = d(1.5f); stroke.color = Color.argb((160 * (1f - tapBurst)).toInt(), 111, 235, 220)
                c.drawLine(cx+cos(aa)*r1, cy+sin(aa)*r1, cx+cos(aa)*r2, cy+sin(aa)*r2, stroke)
            }
        }

        val coreScale = if (pressedCore) .92f else 1f
        val coreR = d(57f) * coreScale
        val pulse = if (powered) ((sin(a * 1.65f)+1f)*.5f) else .08f
        p.color = Color.argb((24 + 38*pulse).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)); c.drawCircle(cx, cy, coreR+d(18f+9f*pulse), p)
        p.shader = RadialGradient(cx-d(15f), cy-d(18f), coreR*1.45f, lighten(accent,.22f), darken(accent,.42f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, coreR, p); p.shader = null
        connectHit.set(cx-coreR-d(7f), cy-coreR-d(7f), cx+coreR+d(7f), cy+coreR+d(7f))
        drawPower(c, cx, cy-d(5f), Color.rgb(4, 21, 27))
        p.textAlign = Paint.Align.CENTER; p.typeface = bold; p.textSize = d(9f); p.color = Color.argb(210, 3, 19, 24)
        c.drawText(if (active) "DISCONNECT" else if (working) "CANCEL" else "CONNECT", cx, cy+d(37f), p)

        p.typeface = medium; p.textSize = d(10.5f); p.color = if (active) Color.rgb(124, 242, 213) else Color.rgb(132, 158, 176)
        val sub = when {
            state.phase == ConnPhase.ERROR && state.error.isNotBlank() -> state.error.take(46)
            active -> "Encrypted • ${formatDuration(state.connectedSeconds)} • ${state.flag} ${state.server.take(22)}"
            state.autoBest && state.vip -> "SMART ROUTE is ready to choose the strongest endpoint"
            !state.vip -> "Activate VIP to unlock the private server vault"
            else -> "Touch the core to create a protected tunnel"
        }
        c.drawText(sub, cx, cy+d(128f), p); p.textAlign = Paint.Align.LEFT
    }

    private fun drawLiveRail(c: Canvas, w: Float, h: Float) {
        val top = h * .575f; val margin = d(20f); val gap = d(7f)
        val cardW = (w-margin*2-gap*2)/3f; val cardH = d(66f)
        fun card(i:Int, label:String, value:String, sub:String, accent:Int, hit:RectF?=null) {
            val x=margin+i*(cardW+gap); val r=RectF(x,top,x+cardW,top+cardH)
            p.color=Color.argb(188,8,23,37); c.drawRoundRect(r,d(18f),d(18f),p)
            stroke.strokeWidth=d(.8f); stroke.color=Color.argb(78,52,82,98); c.drawRoundRect(r,d(18f),d(18f),stroke)
            p.typeface=bold; p.textSize=d(7.2f); p.color=Color.rgb(92,123,143); c.drawText(label,x+d(11f),top+d(17f),p)
            p.typeface=bold; p.textSize=d(12.4f); p.color=accent; c.drawText(value,x+d(11f),top+d(39f),p)
            p.typeface=regular; p.textSize=d(7.5f); p.color=Color.rgb(84,110,129); c.drawText(sub,x+d(11f),top+d(54f),p)
            hit?.set(r)
        }
        card(0,"DOWNLOAD",formatBytes(state.downloadBytes),if(state.phase==ConnPhase.ON)"protected traffic" else "session total",Color.rgb(111,219,244))
        card(1,"UPLOAD",formatBytes(state.uploadBytes),if(state.phase==ConnPhase.ON)"encrypted traffic" else "session total",Color.rgb(71,243,198))
        card(2,"PROTOCOL",state.protocol,if(state.autoBest)"SMART route" else "manual route",Color.WHITE,protocolHit)
    }

    private fun drawServerCard(c: Canvas, w: Float, h: Float) {
        val margin=d(20f); val top=h*.68f; val bottom=min(h-d(105f),top+d(112f))
        serverHit.set(margin,top,w-margin,bottom)
        p.color=Color.argb(205,8,22,36); c.drawRoundRect(serverHit,d(24f),d(24f),p)
        stroke.strokeWidth=d(1f); stroke.color=if(state.vip) Color.argb(82,71,243,198) else Color.argb(80,255,197,94); c.drawRoundRect(serverHit,d(24f),d(24f),stroke)
        p.textSize=d(31f); c.drawText(state.flag,margin+d(16f),top+d(48f),p)
        p.typeface=bold; p.textSize=d(8f); p.color=if(state.autoBest) Color.rgb(71,243,198) else Color.rgb(122,151,170)
        c.drawText(if(state.autoBest)"⚡ SMART ROUTE" else "MANUAL SERVER",margin+d(62f),top+d(20f),p)
        p.typeface=medium; p.textSize=d(15.2f); p.color=Color.WHITE; c.drawText(state.server.take(28),margin+d(62f),top+d(43f),p)
        p.typeface=regular; p.textSize=d(10f); p.color=Color.rgb(116,144,162)
        val detail=if(!state.vip)"AWR-VIP authorization required" else "${if(state.ping>0)"${state.ping} ms" else "Live quality"}  •  ${state.serverCount} VIP endpoints  •  ${state.dns} DNS"
        c.drawText(detail,margin+d(62f),top+d(64f),p)
        val label=if(loadingRepository)"SCANNING…" else if(state.vip)"SELECT  ›" else "UNLOCK  ›"
        p.typeface=bold; p.textSize=d(9f); p.color=if(state.vip)Color.rgb(71,243,198) else Color.rgb(255,205,111); p.textAlign=Paint.Align.RIGHT
        c.drawText(label,w-margin-d(15f),top+d(44f),p); p.textAlign=Paint.Align.LEFT
    }

    private fun drawBottomNav(c: Canvas,w:Float,h:Float) {
        val margin=d(16f); val top=h-d(80f); val bar=RectF(margin,top,w-margin,h-d(14f))
        p.color=Color.argb(232,5,17,29); c.drawRoundRect(bar,d(25f),d(25f),p)
        stroke.strokeWidth=d(.8f); stroke.color=Color.argb(82,42,70,87); c.drawRoundRect(bar,d(25f),d(25f),stroke)
        val labels=arrayOf("HOME","SERVERS","VIP","CONTROL"); val icons=arrayOf("⌂","◎","✦","⚙"); val cell=bar.width()/4f
        for(i in 0..3){
            val cx=bar.left+cell*(i+.5f); p.textAlign=Paint.Align.CENTER; p.typeface=medium; p.textSize=d(16f)
            p.color=when(i){0->Color.rgb(71,243,198);2->if(state.vip)Color.rgb(71,243,198) else Color.rgb(255,205,111);else->Color.rgb(117,145,164)}
            c.drawText(icons[i],cx,top+d(26f),p); p.typeface=bold; p.textSize=d(7.2f); p.color=if(i==0)Color.rgb(71,243,198) else Color.rgb(91,117,136); c.drawText(labels[i],cx,top+d(48f),p)
        }
        settingsHit.set(bar.left+cell*3,bar.top,bar.right,bar.bottom); p.textAlign=Paint.Align.LEFT
    }

    private fun drawPower(c: Canvas,cx:Float,cy:Float,color:Int){
        stroke.color=color; stroke.strokeWidth=d(4.1f); stroke.strokeCap=Paint.Cap.ROUND; val r=d(18f); val rect=RectF(cx-r,cy-r,cx+r,cy+r)
        c.drawArc(rect,-48f,276f,false,stroke); c.drawLine(cx,cy-d(27f),cx,cy-d(5f),stroke); stroke.strokeCap=Paint.Cap.BUTT
    }

    private fun triggerTapBurst(){
        ValueAnimator.ofFloat(0f,1f).apply{duration=680L;interpolator=DecelerateInterpolator();addUpdateListener{tapBurst=it.animatedValue as Float;invalidate()};start()}
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside=connectHit.contains(event.x,event.y)
        when(event.action){
            MotionEvent.ACTION_DOWN->{ if(inside){pressedCore=true;invalidate()}; return true }
            MotionEvent.ACTION_CANCEL->{pressedCore=false;invalidate();return true}
            MotionEvent.ACTION_UP->{
                val x=event.x; val y=event.y; val wasCore=pressedCore&&inside; pressedCore=false
                if(wasCore){triggerTapBurst();actions.onConnect()}
                else when{
                    vipHit.contains(x,y)->actions.onVip()
                    serverHit.contains(x,y)->actions.onServer()
                    protocolHit.contains(x,y)->actions.onProtocol()
                    settingsHit.contains(x,y)->actions.onSettings()
                    y>height-d(80f)&&x in (width/4f)..(width/2f)->actions.onServer()
                    y>height-d(80f)&&x in (width/2f)..(width*.75f)->actions.onVip()
                }
                performClick();invalidate();return true
            }
        }
        return true
    }

    override fun performClick():Boolean{super.performClick();return true}

    private fun formatBytes(v:Long):String = when {
        v>=1_073_741_824L -> String.format("%.1f GB",v/1_073_741_824.0)
        v>=1_048_576L -> String.format("%.1f MB",v/1_048_576.0)
        v>=1024L -> String.format("%.0f KB",v/1024.0)
        else -> "$v B"
    }
    private fun formatDuration(sec:Long):String{val h=sec/3600;val m=(sec%3600)/60;val s=sec%60;return if(h>0)String.format("%02d:%02d:%02d",h,m,s) else String.format("%02d:%02d",m,s)}
    private fun lighten(color:Int,f:Float)=Color.rgb((Color.red(color)+(255-Color.red(color))*f).toInt().coerceIn(0,255),(Color.green(color)+(255-Color.green(color))*f).toInt().coerceIn(0,255),(Color.blue(color)+(255-Color.blue(color))*f).toInt().coerceIn(0,255))
    private fun darken(color:Int,f:Float)=Color.rgb((Color.red(color)*(1f-f)).toInt().coerceIn(0,255),(Color.green(color)*(1f-f)).toInt().coerceIn(0,255),(Color.blue(color)*(1f-f)).toInt().coerceIn(0,255))
}
