/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.components;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.widget.EditText;
import androidx.appcompat.widget.SearchView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomSearchView extends SearchView {
    public CustomSearchView(Context context) {
        super(context);
        init();
    }

    public CustomSearchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomSearchView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        EditText editText = findViewById(androidx.appcompat.R.id.search_src_text);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                highlightText(editText, s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void highlightText(EditText editText, String text) {
        SpannableString ss = new SpannableString(text);
        // 替换为关键字模式或正则表达式
        Pattern pattern = Pattern.compile("RAY");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            ss.setSpan(new BackgroundColorSpan(Color.YELLOW), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        editText.setText(ss);
    }
}

