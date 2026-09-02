package com.awr.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.HapticFeedbackConstants
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
        val proCount: Int = 0,
        val freeCount: Int = 0,
        val tier: ServerTier = ServerTier.FREE,
        val error: String = "",
        val autoBest: Boolean = true,
        val downloadBytes: Long = 0L,
        val uploadBytes: Long = 0L,
        val connectedSeconds: Long = 0L,
        val quality: Int = 0,
        val verified: Boolean = false,
        val source: String = "AWR Secure Repository"
    )

    var state = UiState()
        set(value) {
            if (field.phase != value.phase) triggerPhaseFlash()
            field = value
            invalidate()
        }

    var loadingRepository = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private var tick = 0f
    private var tapBurst = 0f
    private var phaseFlash = 0f
    private var press = 0f
    private var pressedCore = false

    private val connectHit = RectF()
    private val serverHit = RectF()
    private val vipHit = RectF()
    private val protocolHit = RectF()
    private val settingsHit = RectF()

    private val stars = List(96) { i ->
        Triple(
            ((i * 83 + 17) % 997) / 997f,
            ((i * 151 + 37) % 991) / 991f,
            .28f + ((i * 31) % 72) / 100f
        )
    }

    init {
        isClickable = true
        isFocusable = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun d(v: Float) = v * density

    private fun insetTop(): Float {
        if (Build.VERSION.SDK_INT < 23) return 0f
        return rootWindowInsets?.stableInsetTop?.toFloat()?.coerceAtMost(d(34f)) ?: 0f
    }

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

    private fun accent(): Int = when {
        state.phase == ConnPhase.ON -> Color.rgb(71, 243, 198)
        state.phase == ConnPhase.ERROR -> Color.rgb(255, 91, 119)
        state.phase == ConnPhase.FINDING || state.phase == ConnPhase.AUTH || state.phase == ConnPhase.CONNECTING -> Color.rgb(91, 213, 255)
        state.tier == ServerTier.PRO -> Color.rgb(164, 122, 255)
        else -> Color.rgb(112, 136, 159)
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        val active = state.phase == ConnPhase.ON
        val colA = if (active) Color.rgb(2, 16, 20) else Color.rgb(3, 8, 18)
        val colB = if (active) Color.rgb(3, 33, 34) else Color.rgb(5, 21, 38)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(colA, colB, Color.rgb(2, 7, 14)), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        val a = tick * PI.toFloat() * 2f
        fun glow(x: Float, y: Float, r: Float, color: Int, alpha: Int) {
            p.shader = RadialGradient(x, y, r, Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p); p.shader = null
        }
        glow(w * (.13f + .06f * sin(a * .55f)), h * .25f, w * .66f, if (active) Color.rgb(20, 206, 165) else Color.rgb(22, 126, 208), if (active) 70 else 48)
        glow(w * (.91f + .04f * cos(a * .77f)), h * .43f, w * .60f, Color.rgb(97, 65, 230), 43)
        glow(w * (.47f + .06f * cos(a * .42f)), h * .78f, w * .52f, if (active) Color.rgb(11, 116, 103) else Color.rgb(10, 73, 137), 38)

        // soft aurora ribbons
        for (i in 0..2) {
            val path = Path()
            val yy = h * (.18f + i * .18f)
            path.moveTo(-d(20f), yy)
            path.cubicTo(w * .22f, yy + sin(a + i) * d(45f), w * .68f, yy - cos(a * .7f + i) * d(55f), w + d(20f), yy + d(8f))
            stroke.strokeWidth = d(14f - i * 3f)
            stroke.color = Color.argb(9 + i * 3, 81, 220, 226)
            c.drawPath(path, stroke)
        }

        stars.forEachIndexed { i, s ->
            val pulse = ((sin(a * (.6f + s.third) + i * .71f) + 1f) * .5f)
            p.color = Color.argb((16 + pulse * 62).toInt(), 183, 230, 240)
            c.drawCircle(w * s.first, h * s.second, d(.35f + s.third * 1.25f), p)
        }
    }

    private fun drawHeader(c: Canvas, w: Float) {
        val top = insetTop()
        p.textAlign = Paint.Align.LEFT
        p.typeface = bold; p.textSize = d(23f)
        p.shader = LinearGradient(d(20f), top + d(34f), d(183f), top + d(34f), intArrayOf(Color.WHITE, Color.rgb(91, 213, 255), Color.rgb(71, 243, 198)), null, Shader.TileMode.CLAMP)
        c.drawText("AWR VPN", d(21f), top + d(37f), p); p.shader = null
        p.typeface = bold; p.textSize = d(7.4f); p.color = Color.rgb(91, 126, 149)
        c.drawText("ULTRA GLOBAL NETWORK  •  2.2", d(22f), top + d(53f), p)

        val bw = d(108f); val bh = d(38f)
        vipHit.set(w - d(20f) - bw, top + d(18f), w - d(20f), top + d(18f) + bh)
        val vipColor = if (state.vip) Color.rgb(71, 243, 198) else Color.rgb(255, 193, 91)
        p.color = Color.argb(38, Color.red(vipColor), Color.green(vipColor), Color.blue(vipColor)); c.drawRoundRect(vipHit, d(20f), d(20f), p)
        stroke.strokeWidth = d(1f); stroke.color = Color.argb(135, Color.red(vipColor), Color.green(vipColor), Color.blue(vipColor)); c.drawRoundRect(vipHit, d(20f), d(20f), stroke)
        p.typeface = bold; p.textSize = d(9.2f); p.textAlign = Paint.Align.CENTER; p.color = vipColor
        c.drawText(if (state.vip) "✦  VIP ACTIVE" else "✦  UNLOCK VIP", vipHit.centerX(), vipHit.centerY() + d(3.1f), p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawStatusCapsule(c: Canvas, w: Float, h: Float) {
        val top = insetTop()
        val y = max(top + d(93f), h * .135f)
        val text = when (state.phase) {
            ConnPhase.ON -> "●  SECURE TUNNEL ACTIVE"
            ConnPhase.FINDING -> "◌  MEASURING LIVE ROUTES"
            ConnPhase.AUTH -> "◌  AUTHORIZING VIP PROFILE"
            ConnPhase.CONNECTING -> "◌  ESTABLISHING ENCRYPTED TUNNEL"
            ConnPhase.ERROR -> "!  CONNECTION INTERRUPTED"
            ConnPhase.OFF -> if (state.tier == ServerTier.PRO && !state.vip) "⌁  VPN PRO • VIP REQUIRED" else "◦  READY • TAP THE CORE"
        }
        val col = accent()
        p.typeface = bold; p.textSize = d(8.7f)
        val tw = p.measureText(text)
        val r = RectF(w/2 - tw/2 - d(16f), y-d(15f), w/2 + tw/2 + d(16f), y+d(10f))
        p.color = Color.argb(28 + (phaseFlash * 35).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawRoundRect(r, d(20f), d(20f), p)
        stroke.strokeWidth = d(.8f); stroke.color = Color.argb(82 + (phaseFlash*80).toInt(), Color.red(col), Color.green(col), Color.blue(col)); c.drawRoundRect(r, d(20f), d(20f), stroke)
        p.textAlign = Paint.Align.CENTER; p.color = col; c.drawText(text, w/2, y+d(1.5f), p); p.textAlign = Paint.Align.LEFT
    }

    private fun drawHero(c: Canvas, w: Float, h: Float) {
        val top = insetTop()
        val cx = w/2f
        val cy = max(top + d(264f), h * .365f)
        val a = tick * PI.toFloat() * 2f
        val working = state.phase == ConnPhase.FINDING || state.phase == ConnPhase.AUTH || state.phase == ConnPhase.CONNECTING
        val active = state.phase == ConnPhase.ON
        val powered = working || active
        val col = accent()

        // reactor halo
        val haloPulse = .5f + .5f * sin(a * if (active) 1.8f else 1.1f)
        p.shader = RadialGradient(cx, cy, d(180f), Color.argb((44 + 28*haloPulse).toInt(), Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, d(180f), p); p.shader = null

        // concentric radar rings
        for (i in 0..5) {
            val pulse = if (powered) sin(a * (1f + i*.08f) + i*.7f) * d(3.8f) else 0f
            val rr = d(96f + i*14f) + pulse
            stroke.strokeWidth = d(if (i==0) 1.7f else .65f)
            stroke.color = Color.argb((91 - i*11).coerceAtLeast(20), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(cx, cy, rr, stroke)
        }

        // rotating segmented rings
        for (i in 0..4) {
            val rr = d(118f + i*11f)
            val rect = RectF(cx-rr, cy-rr, cx+rr, cy+rr)
            val dir = if (i%2==0) 1f else -1f
            val speed = if (working) 2.1f else if (active) 1.35f else .45f
            stroke.strokeWidth = d(2.6f - i*.32f); stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = Color.argb(if(powered) 150-i*20 else 58-i*7, Color.red(col), Color.green(col), Color.blue(col))
            c.drawArc(rect, a*57.2958f*dir*speed+i*57f, 24f+i*9f, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT

        if (powered) {
            for (i in 0..9) {
                val aa = a * (if(i%2==0) 1.15f else -.83f) + i * .628f
                val rr = d(121f + (i%4)*10f)
                val x = cx + cos(aa)*rr; val y = cy + sin(aa)*rr
                p.color = Color.argb(225, Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(x, y, d(1.7f + (i%3)*.7f), p)
            }
        }

        // glass globe
        val sphere = d(92f) * (1f - press*.055f)
        p.shader = RadialGradient(cx-d(28f), cy-d(33f), sphere*1.6f,
            intArrayOf(lighten(col, .24f), Color.rgb(9, 39, 52), Color.rgb(3, 14, 25)),
            floatArrayOf(0f,.44f,1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, sphere, p); p.shader = null
        stroke.strokeWidth = d(1.25f); stroke.color = Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(cx, cy, sphere, stroke)

        val save = c.save()
        c.clipPath(Path().apply { addCircle(cx,cy,sphere-d(1f),Path.Direction.CW) })
        stroke.strokeWidth = d(.7f); stroke.color = Color.argb(45, 150, 231, 232)
        for(i in -2..2) {
            val yy = cy+i*d(23f)
            c.drawOval(RectF(cx-sphere,yy-d(10f),cx+sphere,yy+d(10f)),stroke)
        }
        for(i in -2..2) {
            val ww = sphere*(.25f+abs(i)*.21f)
            c.drawOval(RectF(cx-ww,cy-sphere,cx+ww,cy+sphere),stroke)
        }
        // animated secure route arc
        val route = Path().apply {
            moveTo(cx-d(74f),cy+d(29f))
            cubicTo(cx-d(42f),cy-d(58f)+sin(a)*d(7f),cx+d(44f),cy-d(54f)-sin(a)*d(7f),cx+d(76f),cy+d(16f))
        }
        stroke.strokeWidth=d(2.35f);stroke.color=Color.argb(if(active)225 else 130,71,243,198);c.drawPath(route,stroke)
        if(active){
            val px=cx-d(70f)+((tick*150f)%140f).coerceIn(0f,140f)
            p.color=Color.WHITE;c.drawCircle(px,cy-d(35f)+sin((px-cx)/d(45f))*d(14f),d(2.3f),p)
        }
        c.restoreToCount(save)

        // press/tap explosion
        if(tapBurst>0f){
            for(i in 0..2){
                val t=(tapBurst-i*.16f).coerceIn(0f,1f)
                if(t<=0f) continue
                val rr=d(58f+115f*t)
                stroke.strokeWidth=d(3.2f*(1f-t)+.45f)
                stroke.color=Color.argb((210*(1f-t)).toInt(),Color.red(col),Color.green(col),Color.blue(col))
                c.drawCircle(cx,cy,rr,stroke)
            }
        }

        // core button
        val coreR=d(57f)*(1f-press*.075f)
        val corePulse=if(powered) .5f+.5f*sin(a*1.7f) else .12f
        p.color=Color.argb((28+44*corePulse).toInt(),Color.red(col),Color.green(col),Color.blue(col));c.drawCircle(cx,cy,coreR+d(20f+9f*corePulse),p)
        p.shader=RadialGradient(cx-d(15f),cy-d(18f),coreR*1.5f,lighten(col,.28f),darken(col,.48f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,coreR,p);p.shader=null
        connectHit.set(cx-coreR-d(9f),cy-coreR-d(9f),cx+coreR+d(9f),cy+coreR+d(9f))
        drawPower(c,cx,cy-d(5f),Color.rgb(3,19,25))
        p.textAlign=Paint.Align.CENTER;p.typeface=bold;p.textSize=d(8.8f);p.color=Color.argb(220,3,18,23)
        c.drawText(if(active)"DISCONNECT" else if(working)"CANCEL" else "CONNECT",cx,cy+d(37f),p)

        p.typeface=medium;p.textSize=d(10.3f);p.color=if(active)Color.rgb(125,244,215) else Color.rgb(140,166,183)
        val sub=when{
            state.phase==ConnPhase.ERROR&&state.error.isNotBlank()->state.error.take(52)
            active->"Protected • ${formatDuration(state.connectedSeconds)} • ${state.flag} ${state.server.take(24)}"
            working->"AWR is selecting and negotiating a live encrypted route"
            state.autoBest&&(state.tier==ServerTier.FREE||state.vip)->"SMART ROUTE • live quality scoring ready"
            state.tier==ServerTier.PRO&&!state.vip->"Activate VIP to unlock VPN PRO routes"
            else->"Touch the reactor to connect"
        }
        c.drawText(sub,cx,cy+d(127f),p);p.textAlign=Paint.Align.LEFT
    }

    private fun drawLiveRail(c:Canvas,w:Float,h:Float){
        val top=h*.575f;val margin=d(20f);val gap=d(7f);val cardW=(w-margin*2-gap*2)/3f;val cardH=d(68f)
        fun card(i:Int,label:String,value:String,sub:String,accent:Int,hit:RectF?=null){
            val x=margin+i*(cardW+gap);val r=RectF(x,top,x+cardW,top+cardH)
            p.color=Color.argb(205,7,22,36);c.drawRoundRect(r,d(19f),d(19f),p)
            stroke.strokeWidth=d(.8f);stroke.color=Color.argb(82,52,85,101);c.drawRoundRect(r,d(19f),d(19f),stroke)
            p.typeface=bold;p.textSize=d(7f);p.color=Color.rgb(92,124,145);c.drawText(label,x+d(11f),top+d(17f),p)
            p.typeface=bold;p.textSize=d(12.3f);p.color=accent;c.drawText(value,x+d(11f),top+d(40f),p)
            p.typeface=regular;p.textSize=d(7.2f);p.color=Color.rgb(82,111,130);c.drawText(sub,x+d(11f),top+d(56f),p)
            hit?.set(r)
        }
        card(0,"DOWNLOAD",formatBytes(state.downloadBytes),if(state.phase==ConnPhase.ON)"protected traffic" else "session total",Color.rgb(111,219,244))
        card(1,"UPLOAD",formatBytes(state.uploadBytes),if(state.phase==ConnPhase.ON)"encrypted traffic" else "session total",Color.rgb(71,243,198))
        card(2,"PROTOCOL",state.protocol,if(state.autoBest)"SMART route" else "manual route",Color.WHITE,protocolHit)
    }

    private fun drawServerCard(c:Canvas,w:Float,h:Float){
        val margin=d(20f);val top=h*.675f;val bottom=min(h-d(102f),top+d(126f))
        serverHit.set(margin,top,w-margin,bottom)
        p.shader=LinearGradient(serverHit.left,serverHit.top,serverHit.right,serverHit.bottom,
            intArrayOf(Color.rgb(8,23,39),if(state.tier==ServerTier.PRO)Color.rgb(23,19,48) else Color.rgb(7,37,39)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(serverHit,d(25f),d(25f),p);p.shader=null
        val col=if(state.tier==ServerTier.PRO)Color.rgb(174,131,255) else Color.rgb(71,243,198)
        stroke.strokeWidth=d(1f);stroke.color=Color.argb(90,Color.red(col),Color.green(col),Color.blue(col));c.drawRoundRect(serverHit,d(25f),d(25f),stroke)
        // Network tier capsules: PRO is always above FREE in the visual hierarchy.
        val capTop=top+d(12f);val capH=d(25f);val capGap=d(7f);val capW=(serverHit.width()-d(27f)-capGap)/2f
        fun tierCap(x:Float,label:String,count:Int,tier:ServerTier,accent:Int){
            val active=state.tier==tier;val r=RectF(x,capTop,x+capW,capTop+capH)
            p.color=Color.argb(if(active)48 else 18,Color.red(accent),Color.green(accent),Color.blue(accent));c.drawRoundRect(r,d(13f),d(13f),p)
            stroke.strokeWidth=d(.8f);stroke.color=Color.argb(if(active)145 else 55,Color.red(accent),Color.green(accent),Color.blue(accent));c.drawRoundRect(r,d(13f),d(13f),stroke)
            p.typeface=bold;p.textSize=d(7.4f);p.color=if(active)accent else Color.rgb(108,130,147);p.textAlign=Paint.Align.CENTER
            c.drawText("$label  •  ${if(count>0)count else "SYNC"}",r.centerX(),r.centerY()+d(2.6f),p)
        }
        tierCap(margin+d(10f),"VPN PRO",state.proCount,ServerTier.PRO,Color.rgb(174,131,255))
        tierCap(margin+d(10f)+capW+capGap,"VPN FREE",state.freeCount,ServerTier.FREE,Color.rgb(71,243,198));p.textAlign=Paint.Align.LEFT

        p.textSize=d(29f);c.drawText(state.flag,margin+d(16f),top+d(77f),p)
        p.typeface=bold;p.textSize=d(7.8f);p.color=col
        c.drawText(if(state.autoBest)"⚡ ${state.tier.label} • SMART ROUTE" else "${state.tier.label} • MANUAL",margin+d(62f),top+d(53f),p)
        p.typeface=medium;p.textSize=d(14.7f);p.color=Color.WHITE;c.drawText(state.server.take(28),margin+d(62f),top+d(76f),p)
        p.typeface=regular;p.textSize=d(9.3f);p.color=Color.rgb(115,145,163)
        val status=when{
            state.tier==ServerTier.PRO&&!state.vip->"AWR VIP authorization required"
            state.serverCount<=0->"Refreshing secure route catalog…"
            else->"${if(state.ping>0)"${state.ping} ms" else "LIVE"} • ${state.serverCount} endpoints • ${state.dns} DNS"
        }
        c.drawText(status,margin+d(62f),top+d(95f),p)
        if(state.tier==ServerTier.FREE||state.vip){
            val badge=if(state.verified)"✓ VERIFIED  •  Q${state.quality}" else "LIVE  •  Q${state.quality}"
            p.typeface=bold;p.textSize=d(7.5f);p.color=if(state.verified)Color.rgb(101,231,201) else Color.rgb(118,166,190)
            c.drawText(badge,margin+d(62f),top+d(114f),p)
        }
        val label=if(loadingRepository)"SCANNING…" else "NETWORKS  ›"
        p.typeface=bold;p.textSize=d(8.3f);p.color=col;p.textAlign=Paint.Align.RIGHT;c.drawText(label,w-margin-d(14f),top+d(76f),p);p.textAlign=Paint.Align.LEFT
    }

    private fun drawBottomNav(c:Canvas,w:Float,h:Float){
        val margin=d(16f);val top=h-d(79f);val bar=RectF(margin,top,w-margin,h-d(13f))
        p.color=Color.argb(238,4,16,28);c.drawRoundRect(bar,d(26f),d(26f),p)
        stroke.strokeWidth=d(.8f);stroke.color=Color.argb(86,42,73,89);c.drawRoundRect(bar,d(26f),d(26f),stroke)
        val labels=arrayOf("HOME","SERVERS","VIP","CONTROL");val icons=arrayOf("⌂","◎","✦","⚙");val cell=bar.width()/4f
        for(i in 0..3){
            val x=bar.left+cell*(i+.5f);p.textAlign=Paint.Align.CENTER;p.typeface=medium;p.textSize=d(16f)
            p.color=when(i){0->Color.rgb(71,243,198);2->if(state.vip)Color.rgb(71,243,198) else Color.rgb(255,205,111);else->Color.rgb(117,148,166)}
            c.drawText(icons[i],x,top+d(26f),p);p.typeface=bold;p.textSize=d(7f);p.color=if(i==0)Color.rgb(71,243,198) else Color.rgb(91,120,138);c.drawText(labels[i],x,top+d(49f),p)
        }
        settingsHit.set(bar.left+cell*3,bar.top,bar.right,bar.bottom);p.textAlign=Paint.Align.LEFT
    }

    private fun drawPower(c:Canvas,cx:Float,cy:Float,color:Int){
        stroke.color=color;stroke.strokeWidth=d(4.2f);stroke.strokeCap=Paint.Cap.ROUND;val r=d(18f);val rect=RectF(cx-r,cy-r,cx+r,cy+r)
        c.drawArc(rect,-48f,276f,false,stroke);c.drawLine(cx,cy-d(27f),cx,cy-d(5f),stroke);stroke.strokeCap=Paint.Cap.BUTT
    }

    private fun triggerTapBurst(){
        ValueAnimator.ofFloat(0f,1f).apply{duration=760L;interpolator=DecelerateInterpolator();addUpdateListener{tapBurst=it.animatedValue as Float;invalidate()};start()}
    }

    private fun triggerPhaseFlash(){
        ValueAnimator.ofFloat(1f,0f).apply{duration=650L;interpolator=DecelerateInterpolator();addUpdateListener{phaseFlash=it.animatedValue as Float;invalidate()};start()}
    }

    private fun animatePress(target:Float){
        val from=press
        ValueAnimator.ofFloat(from,target).apply{duration=if(target>from)100L else 190L;interpolator=DecelerateInterpolator();addUpdateListener{press=it.animatedValue as Float;invalidate()};start()}
    }

    override fun onTouchEvent(event:MotionEvent):Boolean{
        val inside=connectHit.contains(event.x,event.y)
        when(event.action){
            MotionEvent.ACTION_DOWN->{
                if(inside){pressedCore=true;animatePress(1f);performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}
                return true
            }
            MotionEvent.ACTION_CANCEL->{pressedCore=false;animatePress(0f);return true}
            MotionEvent.ACTION_UP->{
                val x=event.x;val y=event.y;val core=pressedCore&&inside;pressedCore=false;animatePress(0f)
                if(core){triggerTapBurst();actions.onConnect()}
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

    private fun formatBytes(v:Long):String=when{
        v>=1_073_741_824L->String.format("%.1f GB",v/1_073_741_824.0)
        v>=1_048_576L->String.format("%.1f MB",v/1_048_576.0)
        v>=1024L->String.format("%.0f KB",v/1024.0)
        else->"$v B"
    }

    private fun formatDuration(sec:Long):String{val hh=sec/3600;val mm=(sec%3600)/60;val ss=sec%60;return if(hh>0)String.format("%02d:%02d:%02d",hh,mm,ss) else String.format("%02d:%02d",mm,ss)}
    private fun lighten(color:Int,f:Float)=Color.rgb((Color.red(color)+(255-Color.red(color))*f).toInt().coerceIn(0,255),(Color.green(color)+(255-Color.green(color))*f).toInt().coerceIn(0,255),(Color.blue(color)+(255-Color.blue(color))*f).toInt().coerceIn(0,255))
    private fun darken(color:Int,f:Float)=Color.rgb((Color.red(color)*(1f-f)).toInt().coerceIn(0,255),(Color.green(color)*(1f-f)).toInt().coerceIn(0,255),(Color.blue(color)*(1f-f)).toInt().coerceIn(0,255))
}
