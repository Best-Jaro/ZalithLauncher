package com.movtery.zalithlauncher.ui.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.angcyo.tablayout.DslTabLayout
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent
import com.movtery.zalithlauncher.event.value.LocalLoginEvent
import com.movtery.zalithlauncher.event.value.OtherLoginEvent
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.accounts.AccountsManager
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.CheckResultListener
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.Companion.checkUsageAllowed
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.Companion.openDialog
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.login.OtherLoginApi
import com.movtery.zalithlauncher.feature.login.Servers
import com.movtery.zalithlauncher.feature.login.Servers.Server
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.OtherLoginDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.layout.AnimRelativeLayout
import com.movtery.zalithlauncher.ui.subassembly.account.AccountAdapter
import com.movtery.zalithlauncher.ui.subassembly.account.AccountAdapter.AccountUpdateListener
import com.movtery.zalithlauncher.ui.subassembly.account.AccountViewWrapper
import com.movtery.zalithlauncher.ui.subassembly.account.SelectAccountListener
import com.movtery.zalithlauncher.utils.PathAndUrlManager
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.PojavProfile
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.databinding.FragmentAccountBinding
import net.kdt.pojavlaunch.databinding.ItemOtherServerBinding
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

class AccountFragment : FragmentWithAnim(R.layout.fragment_account), View.OnClickListener {
    companion object {
        const val TAG = "AccountFragment"
    }

    private lateinit var binding: FragmentAccountBinding
    private lateinit var mAccountViewWrapper: AccountViewWrapper
    private val mAccountManager = AccountsManager.getInstance()
    private val mAccountsData: MutableList<MinecraftAccount> = mAccountManager.allAccount
    private val mAccountAdapter = AccountAdapter(mAccountsData)

    private val selectAccountListener = object : SelectAccountListener {
        override fun onSelect(account: MinecraftAccount) {
            if (!isTaskRunning()) {
                PojavProfile.setCurrentProfile(requireActivity(), account.username)
            } else {
                Tools.runOnUiThread {
                    Toast.makeText(
                        requireActivity(),
                        R.string.tasks_ongoing,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val mLocalNamePattern = Pattern.compile("[^a-zA-Z0-9_]")
    private var mOtherServerConfig: Servers? = null
    private val mOtherServerConfigFile = File(PathAndUrlManager.DIR_GAME_HOME, "servers.json")
    private val mOtherServerList: MutableList<Server> = ArrayList()
    private val mOtherServerViewList: MutableList<View> = ArrayList()

    private lateinit var mProgressDialog: AlertDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAccountBinding.inflate(layoutInflater)
        mAccountViewWrapper = AccountViewWrapper(mainView = binding.viewAccount.root)
        mProgressDialog = AlertDialog.Builder(binding.root.context)
            .setView(R.layout.view_task_running)
            .setCancelable(false)
            .create()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireActivity()

        mAccountAdapter.setAccountUpdateListener(object : AccountUpdateListener {
            override fun onViewClick(account: MinecraftAccount) {
                selectAccountListener.onSelect(account)
            }

            override fun onRefresh(account: MinecraftAccount) {
                if (!isTaskRunning()) {
                    mAccountManager.performLogin(account, true)
                } else {
                    Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDelete(account: MinecraftAccount) {
                TipDialog.Builder(context)
                    .setMessage(R.string.account_remove)
                    .setConfirm(R.string.generic_delete)
                    .setConfirmClickListener {
                        val accountFile =
                            File(PathAndUrlManager.DIR_ACCOUNT_NEW, account.username + ".json")
                        val userSkinFile =
                            File(PathAndUrlManager.DIR_USER_SKIN, account.username + ".png")
                        if (accountFile.exists()) FileUtils.deleteQuietly(accountFile)
                        if (userSkinFile.exists()) FileUtils.deleteQuietly(userSkinFile)
                        reloadAccounts()
                    }.buildDialog()
            }
        })

        binding.apply {
            accountsRecycler.layoutManager = LinearLayoutManager(context)
            accountsRecycler.setLayoutAnimation(
                LayoutAnimationController(
                    AnimationUtils.loadAnimation(
                        context,
                        R.anim.fade_downwards
                    )
                )
            )
            accountsRecycler.adapter = mAccountAdapter

            accountTypeTab.observeIndexChange { _, toIndex, _, fromUser ->
                fun nonMicrosoftLogin(message: Int, login: () -> Unit) {
                    checkUsageAllowed(object : CheckResultListener {
                        override fun onUsageAllowed() {
                            login()
                        }

                        override fun onUsageDenied() {
                            if (!AllSettings.localAccountReminders) {
                                login()
                            } else {
                                openDialog(
                                    context,
                                    TipDialog.OnConfirmClickListener { login() },
                                    getString(message) + getString(
                                        R.string.account_purchase_minecraft_account_tip
                                    ),
                                    R.string.account_no_microsoft_account_continue
                                )
                            }
                        }
                    })
                }

                if (fromUser) { //需要判断是否为用户手动点击的，否则会一直进入微软登录界面
                    when (toIndex) {
                        //微软账户
                        0 -> ZHTools.swapFragmentWithAnim(
                            this@AccountFragment,
                            MicrosoftLoginFragment::class.java,
                            MicrosoftLoginFragment.TAG,
                            null
                        )
                        //离线账户
                        1 -> {
                            nonMicrosoftLogin(
                                R.string.account_no_microsoft_account_local
                            ) { localLogin() }
                        }
                        //外置账户
                        else -> {
                            nonMicrosoftLogin(
                                R.string.account_no_microsoft_account_other
                            ) { otherLogin(toIndex - 2) /* Server索引需要从0开始 */ }
                        }
                    }
                }
            }

            addOtherServer.setOnClickListener(this@AccountFragment)
            returnButton.setOnClickListener(this@AccountFragment)
        }

        reloadAccounts()
        refreshOtherServer()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun reloadRecyclerView() {
        this.mAccountsData.clear()
        mAccountsData.addAll(mAccountManager.allAccount)

        this.mAccountAdapter.notifyDataSetChanged()
        binding.accountsRecycler.scheduleLayoutAnimation()
    }

    private fun reloadAccounts() {
        mAccountManager.reload()
        reloadRecyclerView()
        mAccountViewWrapper.refreshAccountInfo()
    }

    private fun localLogin() {
        fun checkEditText(text: String, editText: EditText): Boolean {
            if (text.isBlank() || text.isEmpty()) {
                editText.error = getString(R.string.account_local_account_empty)
                return false
            }

            val exists = File(PathAndUrlManager.DIR_ACCOUNT_NEW + "/" + text + ".json").exists()
            if (exists) {
                editText.error = getString(R.string.account_local_account_exists)
            }
            return !(exists)
        }

        fun startLogin(name: String) {
            EventBus.getDefault().post(LocalLoginEvent(name.trim()))
        }

        EditTextDialog.Builder(requireActivity())
            .setTitle(R.string.account_login_local_name)
            .setConfirmText(R.string.generic_login)
            .setConfirmListener { editText ->
                val string = editText.text.toString()

                if (!checkEditText(string, editText)) return@setConfirmListener false

                val matcher = mLocalNamePattern.matcher(string)

                if (matcher.find()) {
                    TipDialog.Builder(requireContext())
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.account_local_account_invalid)
                        .setCenterMessage(false)
                        .setConfirmClickListener { startLogin(string) }
                        .buildDialog()
                } else startLogin(string)

                true
            }.buildDialog()
    }

    private fun otherLogin(index: Int) {
        val server = mOtherServerList[index]
        OtherLoginDialog(requireActivity(), server,
            object : OtherLoginDialog.OnLoginListener {
                override fun onLoading() {
                    mProgressDialog.show()
                }

                override fun unLoading() {
                    mProgressDialog.dismiss()
                }

                override fun onSuccess(account: MinecraftAccount) {
                    EventBus.getDefault().post(OtherLoginEvent(account))
                }

                override fun onFailed(error: String) {
                    TipDialog.Builder(requireActivity())
                        .setTitle(R.string.generic_warning)
                        .setMessage(getString(R.string.other_login_error) + error)
                        .setCancel(android.R.string.copy)
                        .setCancelClickListener {
                            StringUtils.copyText(
                                "error",
                                error,
                                requireActivity()
                            )
                        }
                        .buildDialog()
                }
            }).show()
    }

    private fun refreshOtherServer() {
        mOtherServerList.clear()
        if (mOtherServerConfigFile.exists()) {
            runCatching {
                val serverConfig = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(mOtherServerConfigFile.absolutePath),
                    Servers::class.java
                )
                mOtherServerConfig = serverConfig
                serverConfig.server.forEach { server ->
                    mOtherServerList.add(server)
                }
            }
        }

        addOtherServerView()
    }

    private fun showServerTypeSelectDialog(stringId: Int, type: Int) {
        EditTextDialog.Builder(requireActivity())
            .setTitle(stringId)
            .setConfirmListener { editText ->
                if (editText.text.toString().isEmpty()) {
                    editText.error = getString(R.string.generic_error_field_empty)
                    return@setConfirmListener false
                }

                addOtherServer(editText, type)
                true
            }.buildDialog()
    }

    private fun checkServerConfig() {
        mOtherServerConfig ?: run {
            val servers = Servers()
            servers.server = ArrayList()
            mOtherServerConfig = servers
        }
    }

    private fun addOtherServer(editText: EditText, type: Int) {
        Task.runTask {
            val editString = editText.text.toString()
            val serverUrl =
                if (type == 0) AccountUtils.tryGetFullServerUrl(editString) else editString
            OtherLoginApi.getServeInfo(
                requireActivity(),
                if (type == 0) serverUrl else "https://auth.mc-user.com:233/$serverUrl"
            )?.let { data ->
                val server = Server()
                JSONObject(data).optJSONObject("meta")?.let { meta ->
                    server.serverName = meta.optString("serverName")
                    server.baseUrl = serverUrl
                    if (type == 0) {
                        server.register =
                            meta.optJSONObject("links")?.optString("register") ?: ""
                    } else {
                        server.baseUrl = "https://auth.mc-user.com:233/$serverUrl"
                        server.register = "https://login.mc-user.com:233/$serverUrl"
                    }
                    checkServerConfig()
                    mOtherServerConfig?.server?.apply addServer@{
                        forEach {
                            //确保服务器不重复
                            if (it.baseUrl == server.baseUrl) return@addServer
                        }
                        add(server)
                    }
                    Tools.write(
                        mOtherServerConfigFile.absolutePath,
                        Tools.GLOBAL_GSON.toJson(mOtherServerConfig, Servers::class.java)
                    )

                    Tools.runOnUiThread {  }
                }
            }
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            mProgressDialog.show()
        }.ended(TaskExecutors.getAndroidUI()) {
            refreshOtherServer()
            mProgressDialog.dismiss()
        }.onThrowable { e ->
            Logging.e("Add Other Server", Tools.printToString(e))
        }.execute()
    }

    private fun deleteOtherServer(server: Server) {
        checkServerConfig()
        mOtherServerConfig?.server?.remove(server)
        Tools.write(
            mOtherServerConfigFile.absolutePath,
            Tools.GLOBAL_GSON.toJson(mOtherServerConfig, Servers::class.java)
        )
        refreshOtherServer()
    }

    //将外置服务器添加到账号类别选择栏上
    private fun addOtherServerView() {
        mOtherServerViewList.forEach { view ->
            binding.accountTypeTab.removeView(view)
        }
        mOtherServerViewList.clear()

        val activity = requireActivity()
        val layoutInflater = activity.layoutInflater

        fun createView(server: Server): AnimRelativeLayout {
            val p8 = Tools.dpToPx(8f).toInt()
            val view = ItemOtherServerBinding.inflate(layoutInflater)
            view.text.text = server.serverName
            view.delete.setOnClickListener { deleteOtherServer(server) }
            view.root.layoutParams = DslTabLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return view.root.apply {
                setPadding(p8, 0, p8, 0)
            }
        }

        mOtherServerList.forEach { server ->
            val view = createView(server)
            mOtherServerViewList.add(view)
        }

        mOtherServerViewList.forEach { view -> binding.accountTypeTab.addView(view) }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @Subscribe
    fun event(event: AccountUpdateEvent) {
        mAccountViewWrapper.refreshAccountInfo()
        reloadRecyclerView()
    }

    override fun onClick(v: View) {
        val activity = requireActivity()
        binding.apply {
            when (v) {
                returnButton -> ZHTools.onBackPressed(activity)
                addOtherServer -> TipDialog.Builder(activity)
                    .setMessage(R.string.other_login_add_server)
                    .setCancel(R.string.other_login_server)
                    .setConfirm(R.string.other_login_uniform_pass)
                    .setCancelClickListener {
                        showServerTypeSelectDialog(
                            R.string.other_login_yggdrasil_api,
                            0
                        )
                    }
                    .setConfirmClickListener {
                        showServerTypeSelectDialog(
                            R.string.other_login_32_bit_server,
                            1
                        )
                    }
                    .buildDialog()

                else -> {}
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, Animations.BounceInLeft))
                .apply(AnimPlayer.Entry(accountTypeTab, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(addOtherServer, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(accountsRecycler, Animations.BounceInUp))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, Animations.FadeOutRight))
                .apply(AnimPlayer.Entry(accountTypeTab, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(addOtherServer, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(accountsRecycler, Animations.FadeOutDown))
        }
    }
}