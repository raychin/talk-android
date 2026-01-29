/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.models.json.clps.portal

import android.os.Parcelable
import com.bluelinelabs.logansquare.annotation.JsonField
import com.bluelinelabs.logansquare.annotation.JsonObject
import kotlinx.parcelize.Parcelize

@Parcelize
@JsonObject
data class PortalItemJson(
    @JsonField(name = ["sa_id"]) var sa_id: Long = 0,
    @JsonField(name = ["sa_order"]) var sa_order: Long = 0,
    @JsonField(name = ["sa_mob_name"]) var sa_mob_name: String? = null,
    @JsonField(name = ["sa_mob_url"]) var sa_mob_url: String? = null,
    @JsonField(name = ["sa_icon_url"]) var sa_icon_url: String? = null,
) : Parcelable
