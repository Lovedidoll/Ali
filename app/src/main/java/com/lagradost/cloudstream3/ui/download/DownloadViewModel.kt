package com.lagradost.cloudstream3.ui.download

import android.content.Context
import android.content.DialogInterface
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.VideoDownloadHelper
import com.lagradost.cloudstream3.utils.VideoDownloadManager.deleteFilesAndUpdateSettings
import com.lagradost.cloudstream3.utils.VideoDownloadManager.getDownloadFileInfoAndUpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadViewModel : ViewModel() {
    private val _headerCards =
        MutableLiveData<List<VisualDownloadHeaderCached>>().apply { listOf<VisualDownloadHeaderCached>() }
    val headerCards: LiveData<List<VisualDownloadHeaderCached>> = _headerCards

    private val _usedBytes = MutableLiveData<Long>()
    private val _availableBytes = MutableLiveData<Long>()
    private val _downloadBytes = MutableLiveData<Long>()

    private val _selectedIds = MutableLiveData<HashMap<Int, String>>(HashMap())

    val usedBytes: LiveData<Long> = _usedBytes
    val availableBytes: LiveData<Long> = _availableBytes
    val downloadBytes: LiveData<Long> = _downloadBytes

    val selectedIds: LiveData<HashMap<Int, String>> = _selectedIds

    private var previousVisual: List<VisualDownloadHeaderCached>? = null

    fun addSelected(id: Int, name: String) {
        val currentSelected = selectedIds.value ?: HashMap()
        currentSelected[id] = name
        _selectedIds.postValue(currentSelected)
    }

    fun setSelected(selected: HashMap<Int, String>) {
        _selectedIds.postValue(selected)
    }

    fun removeSelected(id: Int) {
        selectedIds.value?.let { selectedIds ->
            selectedIds.remove(id)
            _selectedIds.postValue(selectedIds)
        }
    }

    fun filterSelectedIds(updatedIds: Set<Int>) {
        val currentSelectedIds = selectedIds.value ?: return
        val filteredIds = currentSelectedIds.filterKeys { updatedIds.contains(it) }
        _selectedIds.postValue(HashMap(filteredIds))
    }

    fun clearSelectedIds() {
        _selectedIds.postValue(HashMap())
    }

    fun updateList(context: Context) = viewModelScope.launchSafe {
        val children = withContext(Dispatchers.IO) {
            context.getKeys(DOWNLOAD_EPISODE_CACHE)
                .mapNotNull { context.getKey<VideoDownloadHelper.DownloadEpisodeCached>(it) }
                .distinctBy { it.id } // Remove duplicates
        }

        // parentId : bytes
        val totalBytesUsedByChild = HashMap<Int, Long>()
        // parentId : bytes
        val currentBytesUsedByChild = HashMap<Int, Long>()
        // parentId : downloadsCount
        val totalDownloads = HashMap<Int, Int>()

        // Gets all children downloads
        withContext(Dispatchers.IO) {
            children.forEach { c ->
                val childFile = getDownloadFileInfoAndUpdateSettings(context, c.id) ?: return@forEach

                if (childFile.fileLength <= 1) return@forEach
                val len = childFile.totalBytes
                val flen = childFile.fileLength

                totalBytesUsedByChild[c.parentId] = totalBytesUsedByChild[c.parentId]?.plus(len) ?: len
                currentBytesUsedByChild[c.parentId] = currentBytesUsedByChild[c.parentId]?.plus(flen) ?: flen
                totalDownloads[c.parentId] = totalDownloads[c.parentId]?.plus(1) ?: 1
            }
        }

        val cached = withContext(Dispatchers.IO) { // Won't fetch useless keys
            totalDownloads.entries.filter { it.value > 0 }.mapNotNull {
                context.getKey<VideoDownloadHelper.DownloadHeaderCached>(
                    DOWNLOAD_HEADER_CACHE,
                    it.key.toString()
                )
            }
        }

        val visual = withContext(Dispatchers.IO) {
            cached.mapNotNull {
                val downloads = totalDownloads[it.id] ?: 0
                val bytes = totalBytesUsedByChild[it.id] ?: 0
                val currentBytes = currentBytesUsedByChild[it.id] ?: 0
                if (bytes <= 0 || downloads <= 0) return@mapNotNull null
                val movieEpisode =
                    if (!it.type.isMovieType()) null
                    else context.getKey<VideoDownloadHelper.DownloadEpisodeCached>(
                        DOWNLOAD_EPISODE_CACHE,
                        getFolderName(it.id.toString(), it.id.toString())
                    )
                VisualDownloadHeaderCached(
                    currentBytes = currentBytes,
                    totalBytes = bytes,
                    data = it,
                    child = movieEpisode,
                    currentOngoingDownloads = 0,
                    totalDownloads = downloads,
                )
            }.sortedBy {
                (it.child?.episode ?: 0) + (it.child?.season?.times(10000) ?: 0)
            } // Episode sorting by episode, lowest to highest
        }

        // Only update list if different from the previous one to prevent duplicate initialization
        if (visual != previousVisual) {
            previousVisual = visual
            updateStorageStats(visual)
            _headerCards.postValue(visual)
        }
    }

    private fun updateStorageStats(visual: List<VisualDownloadHeaderCached>) {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val localBytesAvailable = stat.availableBytes
            val localTotalBytes = stat.blockSizeLong * stat.blockCountLong
            val localDownloadedBytes = visual.sumOf { it.totalBytes }

            _usedBytes.postValue(localTotalBytes - localBytesAvailable - localDownloadedBytes)
            _availableBytes.postValue(localBytesAvailable)
            _downloadBytes.postValue(localDownloadedBytes)
        } catch (t: Throwable) {
            _downloadBytes.postValue(0)
            logError(t)
        }
    }

    fun handleMultiDelete(context: Context) = viewModelScope.launchSafe {
        val ids: List<Int> = selectedIds.value?.keys?.toList() ?: emptyList()
        val names: List<String> = selectedIds.value?.values?.toList() ?: emptyList()

        showDeleteConfirmationDialog(context, ids, names)
    }

    private fun showDeleteConfirmationDialog(context: Context, ids: List<Int>, names: List<String>) {
        val formattedNames = names.joinToString(separator = "\n") { "• $it" }
        val message = context.getString(R.string.delete_message_multiple).format(formattedNames)

        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        viewModelScope.launchSafe {
                            deleteFilesAndUpdateSettings(context, ids, this)
                            updateList(context)
                        }
                    }
                    DialogInterface.BUTTON_NEGATIVE -> {
                        // Do nothing on cancel
                    }
                }
            }

        try {
            builder.setTitle(R.string.delete_files)
                .setMessage(message)
                .setPositiveButton(R.string.delete, dialogClickListener)
                .setNegativeButton(R.string.cancel, dialogClickListener)
                .show().setDefaultFocus()
        } catch (e: Exception) {
            logError(e)
        }
    }
}