package com.movtery.zalithlauncher.launch

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kdt.mcgui.ProgressLayout
import com.mio.util.AndroidUtil
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent
import com.movtery.zalithlauncher.feature.accounts.AccountType
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.accounts.AccountsManager
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.parser.ModInfo
import com.movtery.zalithlauncher.feature.mod.parser.ModParser
import com.movtery.zalithlauncher.feature.mod.parser.ModParserListener
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.renderer.Renderers
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.AllStaticSettings
import com.movtery.zalithlauncher.setting.unit.StringSettingUnit
import com.movtery.zalithlauncher.support.touch_controller.ControllerProxy
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.LifecycleAwareTipDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.http.NetworkUtils
import com.movtery.zalithlauncher.utils.path.LibPath
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.plugins.FFmpegPlugin
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.services.GameService
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import net.kdt.pojavlaunch.utils.JREUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.greenrobot.eventbus.EventBus
import java.io.File

class LaunchGame {
    companion object {
        /**
         * 改为启动游戏前进行的操作
         * - 进行登录，同时也能及时的刷新账号的信息（这明显更合理不是吗，PojavLauncher？）
         * - 复制 options.txt 文件到游戏目录
         * @param version 选择的版本
         */
        @JvmStatic
        fun preLaunch(context: Context, version: Version) {
            fun launch(setOfflineAccount: Boolean = false) {
                version.offlineAccountLogin = setOfflineAccount

                val versionName = version.getVersionName()
                val mcVersion = AsyncMinecraftDownloader.getListedVersion(versionName)
                MinecraftDownloader().start(
                    mcVersion, versionName, ContextAwareDoneListener(context, version)
                )
            }

            fun setGameProgress(pull: Boolean) {
                if (pull) ProgressKeeper.submitProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_downloading_game_files, 0, 0, 0)
                else ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT)
            }

            if (!NetworkUtils.isNetworkAvailable(context)) {
                // 网络未链接，无法登录，但是依旧允许玩家启动游戏 (临时创建一个同名的离线账号启动游戏)
                Toast.makeText(context, context.getString(R.string.account_login_no_network), Toast.LENGTH_SHORT).show()
                launch(true)
                return
            }

            if (AccountUtils.isNoLoginRequired(AccountsManager.currentAccount)) {
                launch()
                return
            }

            AccountsManager.performLogin(
                context, AccountsManager.currentAccount!!,
                { _ ->
                    EventBus.getDefault().post(AccountUpdateEvent())
                    TaskExecutors.runInUIThread {
                        Toast.makeText(context, context.getString(R.string.account_login_done), Toast.LENGTH_SHORT).show()
                    }
                    //登录完成，正式启动游戏！
                    launch()
                },
                { exception ->
                    val errorMessage = if (exception is PresentedException) exception.toString(context)
                    else exception.message

                    TaskExecutors.runInUIThread {
                        TipDialog.Builder(context)
                            .setTitle(R.string.generic_error)
                            .setMessage("${context.getString(R.string.account_login_skip)}\r\n$errorMessage")
                            .setWarning()
                            .setConfirmClickListener { launch(true) }
                            .setCenterMessage(false)
                            .showDialog()
                    }

                    setGameProgress(false)
                }
            )
            setGameProgress(true)
        }

        @Throws(Throwable::class)
        @JvmStatic
        fun runGame(activity: AppCompatActivity, minecraftVersion: Version, version: JMinecraftVersionList.Version) {
            if (!Renderers.isCurrentRendererValid()) {
                Renderers.setCurrentRenderer(activity, AllSettings.renderer.getValue())
            }

            var account = AccountsManager.currentAccount!!
            if (minecraftVersion.offlineAccountLogin) {
                account = MinecraftAccount().apply {
                    this.username = account.username
                    this.accountType = AccountType.LOCAL.type
                }
            }

            val customArgs = minecraftVersion.getJavaArgs().takeIf { it.isNotBlank() } ?: ""

            val javaRuntime = getRuntime(activity, minecraftVersion, version.javaVersion?.majorVersion ?: 8)

            printLauncherInfo(
                minecraftVersion,
                customArgs.takeIf { it.isNotBlank() } ?: "NONE",
                javaRuntime,
                account
            )

            checkAllMods(minecraftVersion) { modInfoList ->
                var hasSodiumOrEmbeddium = false

                runCatching {
                    val modCheckSettings = mutableMapOf<StringSettingUnit, Pair<String, String>>()

                    if (modInfoList.isNotEmpty()) {
                        Logger.appendToLog("Mod Perception: ${modInfoList.size} Mods parsed successfully")
                    }

                    // 使用标志变量跟踪已处理的mod类型
                    var touchControllerProcessed = false
                    var physicsModProcessed = false
                    var mcefProcessed = false
                    var valkyrienSkiesProcessed = false

                    modInfoList.forEach { mod ->
                        when (mod.id) {
                            "touchcontroller" -> {
                                if (!touchControllerProcessed) {
                                    touchControllerProcessed = true
                                    Logger.appendToLog("Mod Perception: TouchController Mod found, attempting to automatically enable control proxy!")
                                    ControllerProxy.startProxy(activity)
                                    AllStaticSettings.useControllerProxy = true
                                    modCheckSettings[AllSettings.modCheckTouchController] = Pair(
                                        "1",
                                        activity.getString(R.string.mod_check_touch_controller, mod.file.name)
                                    )
                                }
                            }
                            "sodium", "embeddium" -> {
                                if (!hasSodiumOrEmbeddium) {
                                    hasSodiumOrEmbeddium = true
                                    Logger.appendToLog("Mod Perception: Sodium or Embeddium Mod found, attempting to load the disable warning tool later!")
                                    modCheckSettings[AllSettings.modCheckSodiumOrEmbeddium] = Pair(
                                        "1",
                                        activity.getString(R.string.mod_check_sodium_or_embeddium, mod.file.name)
                                    )
                                }
                            }
                            "physicsmod" -> {
                                if (!physicsModProcessed) {
                                    physicsModProcessed = true
                                    val arch = AndroidUtil.getElfArchFromZip(
                                        mod.file,
                                        "de/fabmax/physxjni/linux/libPhysXJniBindings_64.so"
                                    )
                                    if (arch.isBlank() or (!Architecture.isx86Device() and arch.contains("x86"))) {
                                        modCheckSettings[AllSettings.modCheckPhysics] = Pair(
                                            "1",
                                            activity.getString(R.string.mod_check_physics, mod.file.name)
                                        )
                                    }
                                }
                            }
                            "mcef" -> {
                                if (!mcefProcessed) {
                                    mcefProcessed = true
                                    modCheckSettings[AllSettings.modCheckMCEF] = Pair(
                                        "1",
                                        activity.getString(R.string.mod_check_mcef, mod.file.name)
                                    )
                                }
                            }
                            "valkyrienskies" -> {
                                if (!valkyrienSkiesProcessed) {
                                    valkyrienSkiesProcessed = true
                                    modCheckSettings[AllSettings.modCheckValkyrienSkies] = Pair(
                                        "1",
                                        activity.getString(R.string.mod_check_valkyrien_skies, mod.file.name)
                                    )
                                }
                            }
                            "yes_steve_model" -> {
                                val arch = AndroidUtil.getElfArchFromZip(
                                    mod.file,
                                    "META-INF/native/libysm-core.so"
                                )
                                if (arch.isNotBlank()) {
                                    modCheckSettings[AllSettings.modCheckYesSteveModel] = Pair(
                                        "1",
                                        activity.getString(R.string.mod_check_yes_steve_model, mod.file.name)
                                    )
                                }
                            }
                        }
                    }

                    if (modCheckSettings.isNotEmpty()) {
                        var index = 1
                        val messages = modCheckSettings
                            .filter { (setting, valuePair) -> setting.getValue() != valuePair.first }
                            .map { (_, valuePair) -> "${index++}. ${valuePair.second}" }

                        if (messages.isNotEmpty()) {
                            TaskExecutors.runInUIThread {
                                TipDialog.Builder(activity)
                                    .setTitle(R.string.mod_check_dialog_title)
                                    .setMessage(messages.joinToString("\r\n\r\n"))
                                    .setCheckBox(R.string.generic_no_more_reminders)
                                    .setShowCheckBox(true)
                                    .setCenterMessage(false)
                                    .setCancelable(false)
                                    .setShowCancel(false)
                                    .setConfirmClickListener { check ->
                                        if (check) {
                                            modCheckSettings.forEach { (setting, valuePair) ->
                                                setting.put(valuePair.first).save()
                                            }
                                        }
                                    }.showDialog()
                            }
                        }
                    }
                }.onFailure { e ->
                    Logging.e("LaunchGame", "An error occurred while trying to process existing mod information", e)
                }

                JREUtils.redirectAndPrintJRELog()

                launch(activity, account, minecraftVersion, javaRuntime, customArgs) { userArgs ->
                    if (hasSodiumOrEmbeddium) {
                        //尝试禁用Sodium或Embeddium模组对PojavLauncher的警告
                        userArgs.add("-javaagent:" + LibPath.MOD_TRIMMER.absolutePath)
                    }
                }

                //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
                GameService.setActive(false)
            }
        }

        private fun getRuntime(activity: Activity, version: Version, targetJavaVersion: Int): String {
            val versionRuntime = version.getJavaDir()
                .takeIf { it.isNotEmpty() && it.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX) }
                ?.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
                ?: ""

            if (versionRuntime.isNotEmpty()) return versionRuntime

            //如果版本未选择Java环境，则自动选择合适的环境
            var runtime = AllSettings.defaultRuntime.getValue()
            val pickedRuntime = MultiRTUtils.read(runtime)
            if (pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
                runtime = MultiRTUtils.getNearestJreName(targetJavaVersion) ?: run {
                    activity.runOnUiThread {
                        Toast.makeText(activity, activity.getString(R.string.game_autopick_runtime_failed), Toast.LENGTH_SHORT).show()
                    }
                    return runtime
                }
            }
            return runtime
        }

        private fun printLauncherInfo(
            minecraftVersion: Version,
            javaArguments: String,
            javaRuntime: String,
            account: MinecraftAccount
        ) {
            var mcInfo = minecraftVersion.getVersionName()
            minecraftVersion.getVersionInfo()?.let { info ->
                mcInfo = info.getInfoString()
            }

            Logger.appendToLog("--------- Start launching the game")
            Logger.appendToLog("Info: Launcher version: ${ZHTools.getVersionName()} (${ZHTools.getVersionCode()})")
            Logger.appendToLog("Info: Architecture: ${Architecture.archAsString(Tools.DEVICE_ARCHITECTURE)}")
            Logger.appendToLog("Info: Device model: ${StringUtils.insertSpace(Build.MANUFACTURER, Build.MODEL)}")
            Logger.appendToLog("Info: API version: ${Build.VERSION.SDK_INT}")
            Logger.appendToLog("Info: Renderer: ${Renderers.getCurrentRenderer().getRendererName()}")
            Logger.appendToLog("Info: Selected Minecraft version: ${minecraftVersion.getVersionName()}")
            Logger.appendToLog("Info: Minecraft Info: $mcInfo")
            Logger.appendToLog("Info: Game Path: ${minecraftVersion.getGameDir().absolutePath} (Isolation: ${minecraftVersion.isIsolation()})")
            Logger.appendToLog("Info: Custom Java arguments: $javaArguments")
            Logger.appendToLog("Info: Java Runtime: $javaRuntime")
            Logger.appendToLog("Info: Account: ${account.username} (${account.accountType})")
            Logger.appendToLog("---------\r\n")
        }

        @Throws(Throwable::class)
        @JvmStatic
        private fun launch(
            activity: AppCompatActivity,
            account: MinecraftAccount,
            minecraftVersion: Version,
            javaRuntime: String,
            customArgs: String,
            argsCallBack: UserArgsCallBack
        ) {
            checkMemory(activity)

            val runtime = MultiRTUtils.forceReread(javaRuntime)

            val versionInfo = Tools.getVersionInfo(minecraftVersion)
            val gameDirPath = minecraftVersion.getGameDir()

            //预处理
            Tools.disableSplash(gameDirPath)
            val launchClassPath = Tools.generateLaunchClassPath(versionInfo, minecraftVersion)

            val launchArgs = LaunchArgs(
                account,
                gameDirPath,
                minecraftVersion,
                versionInfo,
                minecraftVersion.getVersionName(),
                runtime,
                launchClassPath
            ).getAllArgs()

            FFmpegPlugin.discover(activity)

            JREUtils.launchWithUtils(activity, runtime, minecraftVersion, launchArgs, customArgs, argsCallBack)
        }

        private fun checkMemory(activity: AppCompatActivity) {
            var freeDeviceMemory = Tools.getFreeDeviceMemory(activity)
            val freeAddressSpace =
                if (Architecture.is32BitsDevice())
                    Tools.getMaxContinuousAddressSpaceSize()
                else -1
            Logging.i("MemStat",
                "Free RAM: $freeDeviceMemory Addressable: $freeAddressSpace")

            val stringId: Int = if (freeDeviceMemory > freeAddressSpace && freeAddressSpace != -1) {
                freeDeviceMemory = freeAddressSpace
                R.string.address_memory_warning_msg
            } else R.string.memory_warning_msg

            if (AllSettings.ramAllocation.value.getValue() > freeDeviceMemory) {
                val builder = TipDialog.Builder(activity)
                    .setTitle(R.string.generic_warning)
                    .setMessage(activity.getString(stringId, freeDeviceMemory, AllSettings.ramAllocation.value.getValue()))
                    .setWarning()
                    .setCenterMessage(false)
                    .setShowCancel(false)
                if (LifecycleAwareTipDialog.haltOnDialog(activity.lifecycle, builder)) return
                // If the dialog's lifecycle has ended, return without
                // actually launching the game, thus giving us the opportunity
                // to start after the activity is shown again
            }
        }

        /**
         * 获取当前版本的所有模组的模组信息，并实时打印至日志内，方便检查问题
         */
        private fun checkAllMods(minecraftVersion: Version, onEnded: (List<ModInfo>) -> Unit) {
            File(minecraftVersion.getGameDir(), "mods").apply {
                if (exists() && isDirectory && (listFiles()?.isNotEmpty() == true)) {
                    ModParser().parseAllMods(this, object : ModParserListener {
                        override fun onProgress(recentlyParsedModInfo: ModInfo) {
                            Logger.appendToLog(
                                "Mod Perception: Parsed Mod ${recentlyParsedModInfo.name} (Mod ID: ${recentlyParsedModInfo.id}, Mod Version: ${recentlyParsedModInfo.version})"
                            )
                        }

                        override fun onParseEnded(modInfoList: List<ModInfo>) {
                            onEnded(modInfoList)
                        }
                    })
                    return
                }
            }
            onEnded(emptyList())
        }
    }
}