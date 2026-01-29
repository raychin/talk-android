/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models.json.autocomplete

import android.os.Parcelable
import com.bluelinelabs.logansquare.annotation.JsonField
import com.bluelinelabs.logansquare.annotation.JsonObject
import com.nextcloud.talk.models.json.clps.portal.PortalItemJson
import kotlinx.parcelize.Parcelize

@Parcelize
@JsonObject
data class AutocompleteUser(
    @JsonField(name = ["id"])
    var id: String?,
    @JsonField(name = ["label"])
    var label: String?,
    @JsonField(name = ["source"])
    var source: String?,
    // 门户数据体 add by ray on 2026/01/30
    var portalItemJson: PortalItemJson? = null
) : Parcelable {
    // This constructor is added to work with the 'com.bluelinelabs.logansquare.annotation.JsonObject'
    constructor() : this(null, null, null)
}
