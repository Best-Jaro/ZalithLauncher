package com.movtery.zalithlauncher.ui.fragment.download

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.download.InfoAdapter
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.utils.CategoryUtils
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.utils.file.FileTools.Companion.copyFileInBackground
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import java.io.File

class ResourcePackDownloadFragment() : AbstractResourceDownloadFragment(
    Classify.RESOURCE_PACK,
    CategoryUtils.getResourcePackCategory(),
    false
) {
    private var openDocumentLauncher: ActivityResultLauncher<Any>? = null
    private val mResourcePackPath = File(sGameDir, "/resourcepacks")

    constructor(parentFragment: Fragment): this() {
        this.mInfoAdapter = InfoAdapter(parentFragment, this, mResourcePackPath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension("zip", true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = AlertDialog.Builder(requireContext())
                    .setView(R.layout.view_task_running)
                    .setCancelable(false)
                    .show()
                Task.runTask {
                    uriList.forEach { uri ->
                        copyFileInBackground(requireActivity(), uri, mResourcePackPath.absolutePath)
                    }
                }.ended(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }
    }

    override fun initInstallButton(installButton: Button) {
        installButton.setOnClickListener {
            val suffix = ".zip"
            Toast.makeText(
                requireActivity(),
                String.format(getString(R.string.file_add_file_tip), suffix),
                Toast.LENGTH_SHORT
            ).show()
            openDocumentLauncher?.launch(suffix)
        }
    }
}