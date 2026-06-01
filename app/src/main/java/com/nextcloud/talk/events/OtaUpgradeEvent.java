/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.events;

import com.nextcloud.talk.models.json.ota.OtaUpgradeData;

import java.util.concurrent.atomic.AtomicBoolean;

public class OtaUpgradeEvent {

    private final OtaUpgradeData otaUpgradeData;
    private final AtomicBoolean consumed;

    public OtaUpgradeEvent(OtaUpgradeData otaUpgradeData) {
        this.otaUpgradeData = otaUpgradeData;
        this.consumed = new AtomicBoolean(false);
    }

    public OtaUpgradeData getOtaUpgradeData() {
        return otaUpgradeData;
    }

    /**
     * 尝试消费此事件（原子操作），防止多个Activity同时处理
     * @return 如果成功消费返回true，如果已被其他Activity消费返回false
     */
    public boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }
}
