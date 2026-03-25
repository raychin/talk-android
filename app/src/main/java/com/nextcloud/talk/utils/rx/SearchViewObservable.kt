/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.rx

import androidx.appcompat.widget.SearchView
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject

class SearchViewObservable {

    companion object {
        @JvmStatic
        fun observeSearchView(searchView: SearchView): Observable<String> {
            val subject: PublishSubject<String> = PublishSubject.create()
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    // searchView.clearFocus()
                    // subject.onComplete()
                    // fix: 清空搜索无法再次搜索问题 by ray on 2026/03/25
                    subject.onNext(query)
                    // subject.onComplete()
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    subject.onNext(newText)
                    return true
                }
            })
            return subject
        }
    }
}
