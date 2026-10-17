// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 播放器弹幕层：多轨道从右向左滚动，纯 View + 属性动画实现（API 21 兼容）。
 * 同一轨道两条弹幕保持固定时间间隔，避免追尾重叠；热评（点赞多）加大加粗。
 * 字号 / 彩色（单色或渐变）/ 最大行数可在播放中随时调整，立即对后续弹幕生效。
 */
class DanmakuView(context: Context): FrameLayout(context) {
    /** 弹幕字号（sp），默认标准 20 */
    var textSizeSp=20f
    /** 彩色弹幕总开关；关闭时全部白色 */
    var colorful=false
    /** 渐变模式：每条弹幕文字沿横向做双色渐变，颜色随文本哈希分布 */
    var gradient=false
    /** 弹幕最大行数（3~8） */
    var maxLanes=5
    /** 弹幕快慢倍速：0.1（最慢）~ 1.0（标准），改后对已在飞的弹幕不生效 */
    var speed=1.0f
    private val laneHeight=dp(36)
    private val lanes=mutableListOf<Long>() // 每条轨道上一次发射的时刻
    private var laneCount=0

    init { clipChildren=false; clipToPadding=false }

    fun clear() { removeAllViews(); lanes.clear(); laneCount=0 }

    /** 发射一条弹幕；层未布局或轨道占满时静默丢弃 */
    fun emit(text: String,hot: Boolean) {
        if(text.isEmpty() || width<=0 || height<=0) return
        val targetLanes=maxLanes.coerceIn(3,8)
        if(laneCount==0) {
            laneCount=(((height*0.62f).toInt()/laneHeight).coerceIn(3,8)).coerceAtMost(targetLanes)
            repeat(laneCount) { lanes.add(0L) }
        } else if(laneCount>targetLanes) {
            laneCount=targetLanes
        } else if(laneCount<targetLanes && (height*0.62f).toInt()/laneHeight>laneCount) {
            lanes.add(0L); laneCount++
        }
        val now=android.os.SystemClock.elapsedRealtime()
        val rate=speed.coerceIn(0.1f,1.0f)
        // 同轨道发射间隔随倍速反向缩放：弹幕飞得越慢，在轨时间越长，间隔要越长才不堆叠
        val headway=(headwayMs/rate).toLong()
        // 找一条"上一次发射已超过间隔"的轨道（间隔随轨道号错开，避免同时起飞）
        var lane=-1
        for(i in 0 until laneCount) {
            val last=lanes.elementAt(i)
            if(now-last>=headway+i*120L) { lane=i; break }
        }
        if(lane<0) return
        val size=if(hot) textSizeSp*1.15f else textSizeSp
        val label=TextView(context).apply {
            this.text=text
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,size)
            setTypeface(null,if(hot) Typeface.BOLD else Typeface.NORMAL)
            setShadowLayer(dp(3).toFloat(),dp(1).toFloat(),dp(1).toFloat(),Color.argb(220,0,0,0))
            alpha=0.96f
        }
        val textWidth=label.paint.measureText(text).toInt()+label.paddingLeft+label.paddingRight+dp(8)
        if(colorful) {
            // 全色相 HSV 随机：同一句话永远同款配色，不同弹幕颜色尽量不同
            val start=(abs(text.hashCode())%360).toFloat()
            if(gradient) {
                // 横向多色渐变：沿文字方向色相 4 档递进（每档 +38°），过渡自然不单调
                val colors=IntArray(4) { Color.HSVToColor(floatArrayOf((start+it*38f)%360f,0.72f,1f)) }
                val positions=floatArrayOf(0f,1f/3f,2f/3f,1f)
                label.paint.shader=LinearGradient(0f,0f,textWidth.toFloat().coerceAtLeast(1f),0f,colors,positions,Shader.TileMode.CLAMP)
            } else {
                label.setTextColor(Color.HSVToColor(floatArrayOf(start,0.72f,1f)))
            }
        }
        addView(label,LayoutParams(textWidth.coerceAtMost(width),LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.START))
        label.translationY=(lane*laneHeight+dp(6)).toFloat()
        label.translationX=width.toFloat()
        lanes[lane]=now
        val exit=-(textWidth+dp(20)).toFloat()
        label.animate().translationX(exit).setDuration((travelMs/rate).toLong()).setInterpolator(LinearInterpolator())
            .withEndAction { removeView(label) }.start()
    }

    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()

    companion object {
        private const val travelMs=9_500L   // 横穿全屏时长
        private const val headwayMs=2_600L  // 同轨道最小发射间隔
    }
}
