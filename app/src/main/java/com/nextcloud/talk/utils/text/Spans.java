/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import third.parties.fresco.BetterImageSpan;

public class Spans {

    public interface MentionSpan {
        String getId();

        CharSequence getLabel();
    }

    public static class MentionChipSpanWithHead extends BetterImageSpan implements MentionSpan {
        public String id;
        public CharSequence label;

        public MentionChipSpanWithHead(@NonNull Drawable drawable, int verticalAlignment, String id, CharSequence label) {
            super(drawable, verticalAlignment);
            this.id = id;
            this.label = label;
        }

        public String getId() {
            return this.id;
        }

        public CharSequence getLabel() {
            return this.label;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setLabel(CharSequence label) {
            this.label = label;
        }

        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof MentionChipSpanWithHead)) {
                return false;
            }
            final MentionChipSpanWithHead other = (MentionChipSpanWithHead) o;
            if (!other.canEqual((Object) this)) {
                return false;
            }
            final Object this$id = this.getId();
            final Object other$id = other.getId();
            if (this$id == null ? other$id != null : !this$id.equals(other$id)) {
                return false;
            }
            final Object this$label = this.getLabel();
            final Object other$label = other.getLabel();

            return this$label == null ? other$label == null : this$label.equals(other$label);
        }

        protected boolean canEqual(final Object other) {
            return other instanceof MentionChipSpanWithHead;
        }

        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            final Object $id = this.getId();
            result = result * PRIME + ($id == null ? 43 : $id.hashCode());
            final Object $label = this.getLabel();
            return result * PRIME + ($label == null ? 43 : $label.hashCode());
        }

        public String toString() {
            return "Spans.MentionChipSpanWithHead(id=" + this.getId() + ", label=" + this.getLabel() + ")";
        }
    }

    public static class MentionChipSpan extends ReplacementSpan implements MentionSpan {
        private String id;
        private CharSequence label;
        private final String prefix;                       // 显示前缀，默认为 @
        private final float cornerRadius;
        private final float paddingLeft;
        private final float paddingRight;
        private final int backgroundColor;
        private final int textColor;
        private final Paint textPaint;
        private final Paint bgPaint;
        private final RectF rectF;
        private final Rect textBounds;

        // 构造方法：默认前缀为 "@"
        public MentionChipSpan(String id, CharSequence label) {
            this(id, label, "@", 8f, 12f, 12f, 0xFFE0E0E0, 0xFF000000);
        }

        // 完整构造方法，支持自定义前缀
        public MentionChipSpan(String id, CharSequence label, String prefix,
                               float cornerRadius, float paddingLeft, float paddingRight,
                               int backgroundColor, int textColor) {
            this.id = id;
            this.label = label;
            this.prefix = (prefix != null) ? prefix : "@";
            this.cornerRadius = cornerRadius;
            this.paddingLeft = paddingLeft;
            this.paddingRight = paddingRight;
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;

            this.textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.textPaint.setColor(textColor);
            this.textPaint.setTextSize(46f);
            this.bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.bgPaint.setColor(backgroundColor);
            this.rectF = new RectF();
            this.textBounds = new Rect();
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            String labelStr = label.toString();
            String displayText = labelStr.startsWith(prefix) ? labelStr : prefix + labelStr;
            textPaint.getTextBounds(displayText, 0, displayText.length(), textBounds);
            float textWidth = textBounds.width();
            return (int) (textWidth + paddingLeft + paddingRight);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            int originalColor = paint.getColor();
            Paint.Style originalStyle = paint.getStyle();

            String labelStr = label.toString();
            String displayText = labelStr.startsWith(prefix) ? labelStr : prefix + labelStr;
            textPaint.getTextBounds(displayText, 0, displayText.length(), textBounds);
            float textWidth = textBounds.width();

            float left = x;
            float right = x + textWidth + paddingLeft + paddingRight;
            float topF = top;
            float bottomF = bottom;
            rectF.set(left, topF, right, bottomF);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint);

            Paint.FontMetricsInt fm = textPaint.getFontMetricsInt();
            int baselineY = (top + bottom - fm.top - fm.bottom) / 2;
            float textX = x + paddingLeft;
            canvas.drawText(displayText, textX, baselineY, textPaint);

            paint.setColor(originalColor);
            paint.setStyle(originalStyle);
        }

        public String getId() {
            return id;
        }

        public CharSequence getLabel() {
            return label;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setLabel(CharSequence label) {
            this.label = label;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof MentionChipSpan)) {
                return false;
            }
            MentionChipSpan other = (MentionChipSpan) o;
            if (!other.canEqual(this)) {
                return false;
            }
            Object thisId = getId();
            Object otherId = other.getId();
            if (thisId == null ? otherId != null : !thisId.equals(otherId)) {
                return false;
            }
            Object thisLabel = getLabel();
            Object otherLabel = other.getLabel();
            return thisLabel == null ? otherLabel == null : thisLabel.equals(otherLabel);
        }

        protected boolean canEqual(Object other) {
            return other instanceof MentionChipSpan;
        }

        @Override
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            Object id = getId();
            result = result * PRIME + (id == null ? 43 : id.hashCode());
            Object label = getLabel();
            return result * PRIME + (label == null ? 43 : label.hashCode());
        }

        @Override
        public String toString() {
            return "MentionChipSpan(id=" + getId() + ", label=" + getLabel() + ")";
        }
    }
}