/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.text;

import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import third.parties.fresco.BetterImageSpan;

public class Spans {

    public interface MentionSpan {
        String getId();

        CharSequence getLabel();
    }

    /**
     * 新样式：纯标记 span，不参与绘制。
     * 视觉由 ForegroundColorSpan（透明背景、无边框的彩色文字）呈现，长昵称完整显示并自动换行。
     */
    public static class MentionChipSpan implements MentionSpan {
        private String id;
        private CharSequence label;

        public MentionChipSpan(String id, CharSequence label) {
            this.id = id;
            this.label = label;
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
            Object thisId = getId();
            Object otherId = other.getId();
            if (thisId == null ? otherId != null : !thisId.equals(otherId)) {
                return false;
            }
            Object thisLabel = getLabel();
            Object otherLabel = other.getLabel();
            return thisLabel == null ? otherLabel == null : thisLabel.equals(otherLabel);
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

    // 旧样式：头像 drawable chip（单行原子，不换行），保持原样不变
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

    /**
     * 新样式统一装配：标记 span + 文字颜色（透明背景、无边框）。
     */
    public static void applyMentionChipStyle(
        @NonNull Spannable spannable,
        int start,
        int end,
        String id,
        CharSequence label,
        @ColorInt int textColor
    ) {
        spannable.setSpan(new MentionChipSpan(id, label), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(textColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 计算 @成员 的统一显示文本："@名字"；label 本身以 @ 开头（如 @all）时不重复加 @。
     */
    public static String mentionDisplayText(CharSequence label) {
        String text = label.toString();
        return text.startsWith("@") ? text : "@" + text;
    }
}