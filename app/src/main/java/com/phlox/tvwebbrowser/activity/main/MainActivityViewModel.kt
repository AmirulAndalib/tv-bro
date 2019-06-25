package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.app.Activity
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.speech.RecognizerIntent
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.AndroidJSInterface
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.utils.StringUtils
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.*

class MainActivityViewModel: ViewModel() {
    companion object {
        const val STATE_JSON = "state.json"
        var TAG: String = MainActivityViewModel::class.java.simpleName
    }

    var geckoSession: GeckoSession
    val asql by lazy { ASQL.getDefault(TVBro.instance) }
    val currentTab = MutableLiveData<WebTabState>()
    val tabsStates = ArrayList<WebTabState>()
    var lastHistoryItem: HistoryItem? = null
    val jsInterface = AndroidJSInterface()
    private var urlToDownload: String? = null
    private var originalDownloadFileName: String? = null
    private var userAgentForDownload: String? = null
    private var operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP

    init {
        geckoSession = GeckoSession()
        geckoSession.open(TVBro.instance.geckoRuntime)
    }

    fun saveState() {
        //WebTabState.saveTabs(TVBro.instance, tabsStates)
        val tabsCopy = tabsStates.map { it.copy() }//clone list

        GlobalScope.launch(Dispatchers.IO) {
            val store = JSONObject()
            val tabsStore = JSONArray()
            for (tab in tabsCopy) {
                val tabJson = tab.toJson(TVBro.instance, true)
                tabsStore.put(tabJson)
            }
            try {
                store.put("tabs", tabsStore)
                val fos = TVBro.instance.openFileOutput(STATE_JSON, Context.MODE_PRIVATE)
                try {
                    fos.write(store.toString().toByteArray())
                } finally {
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadState() = GlobalScope.launch(Dispatchers.Main) {
        launch (Dispatchers.IO) { initHistory() }
        val tabsStatesLoaded = async (Dispatchers.IO){
            val tabsStates = ArrayList<WebTabState>()
            try {
                val fis = TVBro.instance.openFileInput(STATE_JSON)
                val storeStr = StringUtils.streamToString(fis)
                val store = JSONObject(storeStr)
                val tabsStore = store.getJSONArray("tabs")
                for (i in 0 until tabsStore.length()) {
                    val tab = WebTabState(TVBro.instance, tabsStore.getJSONObject(i))
                    tabsStates.add(tab)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            tabsStates
        }.await()

        tabsStates.addAll(tabsStatesLoaded)
    }

    private fun initHistory() {
        val count = asql.count(HistoryItem::class.java)
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            asql.db.delete("history", "time < ?", arrayOf(java.lang.Long.toString(c.time.time)))
        }
        try {
            val result = asql.queryAll<HistoryItem>(HistoryItem::class.java, "SELECT * FROM history ORDER BY time DESC LIMIT 1")
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val frequentlyUsedUrls = asql.queryAll<HistoryItem>(HistoryItem::class.java,
                    "SELECT title, url, favicon, count(url) as cnt , max(time) as time FROM history GROUP BY title, url, favicon ORDER BY cnt DESC, time DESC LIMIT 8")
            jsInterface.setSuggestions(TVBro.instance, frequentlyUsedUrls)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logVisitedHistory(title: String?, url: String?, faviconHash: String?) {
        if (url != null && (lastHistoryItem != null && url == lastHistoryItem!!.url || url == WebViewEx.HOME_URL)) {
            return
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        asql.execInsert("INSERT INTO history (time, title, url, favicon) VALUES (:time, :title, :url, :favicon)", lastHistoryItem) { lastInsertRowId, exception ->
            exception?.printStackTrace()
        }
    }

    fun onDownloadRequested(activity: MainActivity, url: String, originalDownloadFileName: String?, userAgent: String?,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        this.urlToDownload = url
        this.originalDownloadFileName = originalDownloadFileName
        this.userAgentForDownload = userAgent
        this.operationAfterDownload = operationAfterDownload
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    MainActivity.MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(activity)
        }
    }

    fun startDownload(activity: MainActivity) {
        val url = this.urlToDownload
        val originalFileName = this.originalDownloadFileName
        val userAgent = this.userAgentForDownload
        val operationAfterDownload = this.operationAfterDownload
        this.urlToDownload = null
        this.originalDownloadFileName = null
        this.userAgentForDownload = null
        this.operationAfterDownload = Download.OperationAfterDownload.NOP
        if (url == null || originalFileName == null) {
            Log.w(TAG, "Can not download without url or originalFileName")
            return
        }
        val extPos = originalFileName.lastIndexOf(".")
        val hasExt = extPos != -1
        var ext: String? = null
        var prefix: String? = null
        if (hasExt) {
            ext = originalFileName.substring(extPos + 1)
            prefix = originalFileName.substring(0, extPos)
        }
        var fileName = originalFileName
        var i = 0
        while (File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName).exists()) {
            i++
            if (hasExt) {
                fileName = prefix + "_(" + i + ")." + ext
            } else {
                fileName = originalFileName + "_(" + i + ")"
            }
        }

        if (Environment.MEDIA_MOUNTED != Environment.getExternalStorageState()) {
            Toast.makeText(activity, R.string.storage_not_mounted, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            Toast.makeText(activity, R.string.can_not_create_downloads, Toast.LENGTH_SHORT).show()
            return
        }
        val fullDestFilePath = downloadsDir.toString() + File.separator + fileName
        DownloadService.startDownloading(activity, url, fullDestFilePath, fileName!!, userAgent!!, operationAfterDownload)

        activity.onDownloadStarted(fileName)
    }

    fun initiateVoiceSearch(activity: MainActivity) {
        val pm = activity.packageManager
        val activities = pm.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        if (activities.size == 0) {
            AlertDialog.Builder(activity)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.voice_search_not_found)
                    .setPositiveButton(android.R.string.ok) { _, _ ->  }
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.speak))
            try {
                activity.startActivityForResult(intent, MainActivity.VOICE_SEARCH_REQUEST_CODE)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}