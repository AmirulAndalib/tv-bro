package com.phlox.tvwebbrowser.model

import com.phlox.tvwebbrowser.TVBro
import org.json.JSONObject

class HomePageLink(
    val title: String,
    val url: String,
    val favicon: String?
) {
    fun toJson(): String {
        return "{\"title\":\"$title\",\"url\":\"$url\",\"favicon\":\"$favicon\"}"
    }

    fun toJsonObj(): JSONObject {
        return JSONObject().apply {
            put("title", title)
            put("url", url)
            put("favicon", favicon)
        }
    }

    companion object {
        fun fromHistoryItem(item: HistoryItem): HomePageLink {
            val favicon = item.favicon?.let { "file:///" + TVBro.instance.cacheDir.absolutePath + "/favicons/" + it }
            return HomePageLink(item.title, item.url, favicon)
        }

        fun fromBookmarkItem(item: FavoriteItem): HomePageLink {
            val favicon = item.favicon?.let { "file:///" + TVBro.instance.cacheDir.absolutePath + "/favicons/" + it }
            return HomePageLink(item.title?: "", item.url?: "", favicon)
        }
    }
}