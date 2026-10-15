// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 播放器弹幕层：多轨道从右向左滚动，纯 View + 属性动画实现（API 21 兼容）。
 * 同一轨道两条弹幕保持固定时间间隔，避免追尾重叠；热评（点赞多）加大加粗。
 */
class DanmakuView(context: Context): FrameLayout(context) {
    private val laneHeight=dp(36)
    private val lanes=mutableListOf<Long>() // 每条轨道上一次发射的时刻
    private var laneCount=0

    init { clipChildren=false; clipToPadding=false }

    fun clear() { removeAllViews(); lanes.clear(); laneCount=0 }

    /** 发射一条弹幕；层未布局或轨道占满时静默丢弃 */
    fun emit(text: String,hot: Boolean) {
        if(text.isEmpty() || width<=0 || height<=0) return
        if(laneCount==0) {
            laneCount=((height*0.62f).toInt()/laneHeight).coerceIn(3,8)
            repeat(laneCount) { lanes.add(0L) }
        }
        val now=android.os.SystemClock.elapsedRealtime()
        // 找一条"上一次发射已超过间隔"的轨道（间隔随轨道号错开，避免同时起飞）
        var lane=-1
        for(i in 0 until laneCount) {
            val last=lanes.elementAt(i)
            if(now-last>=headwayMs+i*120L) { lane=i; break }
        }
        if(lane<0) return
        val label=TextView(context).apply {
            this.text=text
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,if(hot) 19f else 16.5f)
            setTypeface(null,if(hot) Typeface.BOLD else Typeface.NORMAL)
            setShadowLayer(dp(3).toFloat(),dp(1).toFloat(),dp(1).toFloat(),Color.argb(200,0,0,0))
            alpha=0.94f
        }
        val textWidth=label.paint.measureText(text).toInt()+label.paddingLeft+label.paddingRight+dp(8)
        addView(label,LayoutParams(textWidth.coerceAtMost(width),LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.START))
        label.translationY=(lane*laneHeight+dp(6)).toFloat()
        label.translationX=width.toFloat()
        lanes[lane]=now
        val exit=-(textWidth+dp(20)).toFloat()
        label.animate().translationX(exit).setDuration(travelMs).setInterpolator(LinearInterpolator())
            .withEndAction { removeView(label) }.start()
    }

    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()

    companion object {
        private const val travelMs=9_500L   // 横穿全屏时长
        private const val headwayMs=2_600L  // 同轨道最小发射间隔
    }
}
