/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val cornerRadius: Float,
    private val padding: Float
) : ReplacementSpan() {

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val backgroundPaint = Paint(paint).apply {
            color = backgroundColor
        }

        val textWidth = paint.measureText(text, start, end)
        val rect = RectF(
            x - padding,
            top.toFloat(),
            x + textWidth + 2 * padding,
            bottom.toFloat()
        )

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        paint.color = textColor
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return (paint.measureText(text, start, end) + padding * 2).toInt()
    }
}
