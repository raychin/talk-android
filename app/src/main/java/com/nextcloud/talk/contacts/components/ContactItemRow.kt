/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Sowjanya Kota <sowjanya.kch@gmail.com>
 * SPDX-FileCopyrightText: 2025 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.contacts.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.contacts.CompanionClass
import com.nextcloud.talk.contacts.ContactsViewModel
import com.nextcloud.talk.contacts.RoomUiState
import com.nextcloud.talk.models.json.autocomplete.AutocompleteUser
import com.nextcloud.talk.utils.bundle.BundleKeys
import androidx.core.net.toUri
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ContactItemRow(contact: AutocompleteUser, contactsViewModel: ContactsViewModel, context: Context) {
    var isSelected by remember { mutableStateOf(contactsViewModel.selectedParticipantsList.value.contains(contact)) }
    val roomUiState by contactsViewModel.roomViewState.collectAsState()
    val isAddParticipants = contactsViewModel.isAddParticipantsView.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    if (contact.source == "portal") {
                        // 使用用系统浏览器打开链接 add by ray on 2026/01/30
                        val intent = Intent(Intent.ACTION_VIEW, contact.portalItemJson?.sa_mob_url?.toUri())
                        context.startActivity(intent)
                        return@clickable
                    }
                    if (!isAddParticipants.value) {
                        contactsViewModel.createRoom(
                            CompanionClass.ROOM_TYPE_ONE_ONE,
                            contact.source!!,
                            contact.id!!,
                            null
                        )
                    } else {
                        isSelected = !isSelected
                        if (isSelected) {
                            contactsViewModel.selectContact(contact)
                            contactsViewModel.updateAddButtonState()
                        } else {
                            contactsViewModel.deselectContact(contact)
                            contactsViewModel.updateAddButtonState()
                        }
                    }
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val roundCornerShape = if (contact.source == "portal") {
            RoundedCornerShape(8)
        } else {
            CircleShape
        }
        val imageUri = if (contact.source == "portal") {
            contact.portalItemJson?.sa_icon_url?.takeIf { it.isNotEmpty() }
        } else {
            contact.id?.let { contactsViewModel.getImageUri(it, true) }
        }

        val errorPlaceholderImage: Int = R.drawable.account_circle_96dp
        // val imageUriNew = generateImageUrl(imageUri.toString())
        // val loadedImage = loadImage(imageUriNew, context, errorPlaceholderImage)
        // AsyncImage(
        //     model = loadedImage,
        //     contentDescription = stringResource(R.string.user_avatar),
        //     modifier = Modifier.size(width = 45.dp, height = 45.dp),
        //     onError = { result ->
        //         Log.e("Ray", "AsyncImage${imageUri}")
        //         Log.e("Ray", result.toString())
        //     }
        // )
        GlideImage(
            model = imageUri,
            contentDescription = stringResource(R.string.user_avatar),
            modifier = Modifier
                .size(width = 45.dp, height = 45.dp)
                // .clip(CircleShape),
                .clip(roundCornerShape),
            // requestBuilderTransform = { builder ->
            //     // 圆形裁剪
            //     builder.circleCrop()
            // },
            // 裁剪填充
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            alpha = 0.9f,
            // 加载中占位图
            loading = placeholder(errorPlaceholderImage),
            // 加载失败占位图
            failure = placeholder(errorPlaceholderImage)
        )
        Text(modifier = Modifier.padding(16.dp), text = contact.label!!)
        if (isAddParticipants.value) {
            if (isSelected) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_check_circle),
                    contentDescription = "Selected",
                    tint = Color.Blue,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
    when (roomUiState) {
        is RoomUiState.Success -> {
            val conversation = (roomUiState as RoomUiState.Success).conversation
            val bundle = Bundle()
            bundle.putString(BundleKeys.KEY_ROOM_TOKEN, conversation?.token)
            val chatIntent = Intent(context, ChatActivity::class.java)
            chatIntent.putExtras(bundle)
            chatIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(chatIntent)
        }
        is RoomUiState.Error -> {
            val errorMessage = (roomUiState as RoomUiState.Error).message
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Error: $errorMessage", color = Color.Red)
            }
        }
        is RoomUiState.None -> {}
    }
}
