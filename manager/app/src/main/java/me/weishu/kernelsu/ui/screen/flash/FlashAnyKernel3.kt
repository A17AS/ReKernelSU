package me.weishu.kernelsu.ui.screen.flash

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.util.reboot
import java.io.File
import java.util.zip.ZipFile

/**
 * AnyKernel3 刷入会话的状态。
 * active = true 时，安装页会改为显示刷入日志界面。
 */
data class AnyKernel3State(
    val active: Boolean = false,
    val log: String = "",
    val status: FlashingStatus = FlashingStatus.FLASHING,
)

/**
 * 在已获得 root 的管理器里直接刷入 AnyKernel3 zip。
 *
 * 做法：
 * 1. 把用户选的 zip 拷到缓存目录，检查里面有 anykernel.sh 和 update-binary；
 * 2. 取得一个 root shell；
 * 3. 设置 AKHOME 到 /data/local/tmp 下的可写目录，再按 recovery 的方式运行
 *    update-binary <版本号> <输出fd> <zip>，输出 fd 用 1（标准输出），
 *    这样 AK3 的 ui_print 内容会直接回到管理器里显示。
 *
 * 刷入在独立的协程作用域里运行，不跟界面的生命周期绑定，
 * 所以旋转屏幕等界面重建不会中断正在进行的刷入。
 */
object AnyKernel3Flasher {
    private const val ENTRY_SCRIPT = "anykernel.sh"
    private const val ENTRY_UPDATE_BINARY = "META-INF/com/google/android/update-binary"
    private const val WORK_DIR = "/data/local/tmp/anykernel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AnyKernel3State())
    val state: StateFlow<AnyKernel3State> = _state.asStateFlow()

    /** 刷入结束后关闭日志界面，回到安装页。刷入进行中忽略。 */
    fun reset() {
        if (_state.value.status != FlashingStatus.FLASHING) {
            _state.value = AnyKernel3State()
        }
    }

    fun start(context: Context, uri: Uri) {
        val current = _state.value
        if (current.active && current.status == FlashingStatus.FLASHING) return

        val app = context.applicationContext
        _state.value = AnyKernel3State(active = true)

        scope.launch {
            var ok = false
            var zipFile: File? = null
            var updateBinary: File? = null
            var shell: Shell? = null
            try {
                append("正在准备 AnyKernel3 包…")

                val zip = File(app.cacheDir, "ak3_flash.zip").also { zipFile = it }
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                input.use { src -> zip.outputStream().use { dst -> src.copyTo(dst) } }

                val ub = File(app.cacheDir, "ak3_update_binary").also { updateBinary = it }
                ZipFile(zip).use { zf ->
                    val scriptEntry = zf.getEntry(ENTRY_SCRIPT)
                    val ubEntry = zf.getEntry(ENTRY_UPDATE_BINARY)
                    if (scriptEntry == null || ubEntry == null) {
                        throw IllegalArgumentException("所选 zip 不是 AnyKernel3 包（缺少 anykernel.sh 或 update-binary）")
                    }
                    zf.getInputStream(ubEntry).use { src -> ub.outputStream().use { dst -> src.copyTo(dst) } }
                }
                ub.setReadable(true, false)

                append("正在获取 root 权限…")
                val rootShell = openRootShell(app)
                    ?: throw IllegalStateException("无法获取 root shell，请先确认管理器已获得 root")
                shell = rootShell

                append("开始刷入…")
                append("")
                val out = object : CallbackList<String>() {
                    override fun onAddElement(s: String?) {
                        if (s != null) handleLine(s)
                    }
                }
                val result = rootShell.newJob()
                    .add("export AKHOME=$WORK_DIR")
                    .add("sh '${ub.absolutePath}' 3 1 '${zip.absolutePath}' 2>&1")
                    .to(out)
                    .exec()
                // CallbackList 的回调可能被投递到主线程，这里等一下，保证日志都已写入
                withContext(Dispatchers.Main) { }

                ok = result.isSuccess
                if (ok) {
                    append("")
                    append("刷入完成，重启后生效。")
                } else {
                    append("")
                    append("刷入失败，退出码 ${result.code}。")
                }
                rootShell.newJob().add("rm -rf $WORK_DIR").exec()
            } catch (e: Throwable) {
                append("错误：${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { shell?.close() }
                runCatching { zipFile?.delete() }
                runCatching { updateBinary?.delete() }
                val finalStatus = if (ok) FlashingStatus.SUCCESS else FlashingStatus.FAILED
                _state.update { it.copy(status = finalStatus) }
            }
        }
    }

    /** 和官方管理器取 root shell 的顺序一致：先用 libksud.so su，不行再用 su。 */
    private fun openRootShell(context: Context): Shell? {
        val ksud = context.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
        val candidates = listOf(arrayOf(ksud, "su"), arrayOf("su"))
        for (cmd in candidates) {
            try {
                val shell = Shell.Builder.create().build(*cmd)
                if (shell.isRoot) return shell
                shell.close()
            } catch (_: Throwable) {
                // 试下一种方式
            }
        }
        return null
    }

    private fun append(line: String) {
        _state.update { it.copy(log = it.log + line + "\n") }
    }

    /**
     * AK3 通过 fd 输出的是 recovery 协议：
     * "ui_print 文字"、单独的 "ui_print"、"progress x y"。这里转成普通日志。
     * 其他行（例如 unzip 或 busybox 的输出）原样显示。
     */
    private fun handleLine(raw: String) {
        val line = raw.trimEnd()
        val t = line.trim()
        when {
            t == "ui_print" -> return
            t.startsWith("progress ") || t.startsWith("set_progress ") -> return
            t.startsWith("ui_print ") -> append(t.removePrefix("ui_print ").trimEnd())
            else -> append(line)
        }
    }
}

/**
 * AnyKernel3 刷入日志界面。直接复用现有刷入页的显示（FlashScreenMiuix / FlashScreenMaterial），
 * 所以样式、保存日志、重启按钮都和刷模块时一致。
 */
@Composable
fun AnyKernel3FlashScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by AnyKernel3Flasher.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val logContent = remember(session.log) { StringBuilder(session.log) }
    val finished = session.status != FlashingStatus.FLASHING

    // 刷入中拦截返回；结束后返回键用来关闭日志界面
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = { if (finished) onClose() },
    )

    val uiState = FlashUiState(
        text = session.log,
        showRebootAction = session.status == FlashingStatus.SUCCESS,
        flashingStatus = session.status,
        showJailbreakWarning = false,
        rebootLabelRes = R.string.reboot,
    )
    val actions = FlashScreenActions(
        onBack = { if (finished) onClose() },
        onSaveLog = saveLog(logContent, scope) {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        },
        onReboot = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    reboot("")
                }
            }
        },
        onConfirmJailbreakWarning = {},
        onDismissJailbreakWarning = {},
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> FlashScreenMiuix(uiState, actions)
        UiMode.Material -> FlashScreenMaterial(uiState, actions, snackbarHost)
    }
}
