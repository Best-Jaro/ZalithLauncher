package com.movtery.zalithlauncher.feature.version

import android.os.Parcel
import android.os.Parcelable
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.stringutils.StringUtils.getStringNotNull
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileWriter

class VersionConfig(private var versionPath: File) : Parcelable {
    private var isolation: Boolean = false
    private var javaDir: String = ""
    private var javaArgs: String = ""
    private var renderer: String = ""
    private var control: String = ""
    private var customPath: String = ""

    constructor(
        filePath: File,
        isolation: Boolean = false,
        javaDir: String = "",
        javaArgs: String = "",
        renderer: String = "",
        control: String = "",
        customPath: String = ""
    ) : this(filePath) {
        this.isolation = isolation
        this.javaDir = javaDir
        this.javaArgs = javaArgs
        this.renderer = renderer
        this.control = control
        this.customPath = customPath
    }

    fun copy(): VersionConfig = VersionConfig(versionPath, isolation,
        getStringNotNull(javaDir),
        getStringNotNull(javaArgs),
        getStringNotNull(renderer),
        getStringNotNull(control),
        getStringNotNull(customPath)
    )

    fun save() {
        runCatching {
            saveWithThrowable()
        }.getOrElse { e ->
            Logging.e("Save Version Config", "$this\n${Tools.printToString(e)}")
        }
    }

    @Throws(Throwable::class)
    fun saveWithThrowable() {
        Logging.i("Save Version Config", "Trying to save: $this")
        val zalithVersionPath = VersionsManager.getZalithVersionPath(versionPath)
        val configFile = File(zalithVersionPath, "VersionConfig.json")
        if (!zalithVersionPath.exists()) zalithVersionPath.mkdirs()

        FileWriter(configFile, false).use {
            val json = Tools.GLOBAL_GSON.toJson(this)
            it.write(json)
        }
        Logging.i("Save Version Config", "Saved: $this")
    }

    fun getVersionPath() = versionPath

    fun setVersionPath(versionPath: File) {
        this.versionPath = versionPath
    }

    fun isIsolation() = isolation

    fun setIsolation(isolation: Boolean) {
        this.isolation = isolation
    }

    fun getJavaDir(): String = getStringNotNull(javaDir)

    fun setJavaDir(dir: String) { this.javaDir = dir }

    fun getJavaArgs(): String = getStringNotNull(javaArgs)

    fun setJavaArgs(args: String) { this.javaArgs = args }

    fun getRenderer(): String = getStringNotNull(renderer)

    fun setRenderer(renderer: String) { this.renderer = renderer }

    fun getControl(): String = getStringNotNull(control)

    fun setControl(control: String) { this.control = control }

    fun getCustomPath(): String = getStringNotNull(customPath)

    fun setCustomPath(customPath: String) { this.customPath = customPath }

    fun checkDifferent(otherConfig: VersionConfig): Boolean {
        return !(this.isolation == otherConfig.isolation &&
                this.javaDir == otherConfig.javaDir &&
                this.javaArgs == otherConfig.javaArgs &&
                this.renderer == otherConfig.renderer &&
                this.control == otherConfig.control &&
                this.customPath == otherConfig.customPath)
    }

    override fun toString(): String {
        return "VersionConfig{" +
                "isolation=$isolation, " +
                "versionPath='$versionPath', " +
                "javaDir='${getStringNotNull(javaDir)}', " +
                "javaArgs='${getStringNotNull(javaArgs)}', " +
                "renderer='${getStringNotNull(renderer)}', " +
                "control='${getStringNotNull(control)}', " +
                "customPath='${getStringNotNull(customPath)}'}"
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(versionPath.absolutePath)
        dest.writeInt(if (isolation) 1 else 0)
        dest.writeString(getStringNotNull(javaDir))
        dest.writeString(getStringNotNull(javaArgs))
        dest.writeString(getStringNotNull(renderer))
        dest.writeString(getStringNotNull(control))
        dest.writeString(getStringNotNull(customPath))
    }

    companion object CREATOR : Parcelable.Creator<VersionConfig> {
        override fun createFromParcel(parcel: Parcel): VersionConfig {
            val versionPath = File(parcel.readString().orEmpty())
            val isolation = parcel.readInt() > 0
            val javaDir = parcel.readString().orEmpty()
            val javaArgs = parcel.readString().orEmpty()
            val renderer = parcel.readString().orEmpty()
            val control = parcel.readString().orEmpty()
            val customPath = parcel.readString().orEmpty()
            return VersionConfig(versionPath, isolation, javaDir, javaArgs, renderer, control, customPath)
        }

        override fun newArray(size: Int): Array<VersionConfig?> {
            return arrayOfNulls(size)
        }

        fun createIsolation(versionPath: File): VersionConfig {
            val config = VersionConfig(versionPath)
            config.setIsolation(true)
            return config
        }
    }
}