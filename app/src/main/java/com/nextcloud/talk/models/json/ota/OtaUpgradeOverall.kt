/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models.json.ota

import android.os.Parcelable
import com.bluelinelabs.logansquare.annotation.JsonField
import com.bluelinelabs.logansquare.annotation.JsonObject
import com.nextcloud.talk.models.json.generic.GenericMeta
import kotlinx.parcelize.Parcelize

@Parcelize
@JsonObject
data class OtaUpgradeData(
    @JsonField(name = ["needUpdate"])
    var needUpdate: Boolean = false,
    @JsonField(name = ["forceUpdate"])
    var forceUpdate: Boolean = false,
    @JsonField(name = ["downloadUrl"])
    var downloadUrl: String? = null,
    @JsonField(name = ["latestVersion"])
    var latestVersion: String? = null
) : Parcelable {
    constructor() : this(false, false, null, null)
}

@Parcelize
@JsonObject
data class OtaUpgradeOverall(
    @JsonField(name = ["ocs"])
    var ocs: OtaOCS? = null
) : Parcelable {
    constructor() : this(null)
}

@Parcelize
@JsonObject
data class OtaOCS(
    @JsonField(name = ["meta"])
    var meta: GenericMeta? = null,
    @JsonField(name = ["data"])
    var data: OtaUpgradeData? = null
) : Parcelable {
    constructor() : this(null, null)
}
