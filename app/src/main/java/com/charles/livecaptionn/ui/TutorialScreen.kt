package com.charles.livecaptionn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.livecaptionn.compatibility.DeviceSpecs
import com.charles.livecaptionn.ui.l10n.LocalUiStrings

data class TutorialStep(
    val titleZh: String,
    val subtitleZh: String,
    val detailsZh: List<String>,
    val titleEn: String,
    val subtitleEn: String,
    val detailsEn: List<String>
)

const val REMOTE_SETUP_DOC_URL = ""

val TUTORIAL_STEPS = listOf(
    TutorialStep(
        titleZh = "1. 欢迎使用《剧情英文字幕》",
        subtitleZh = "系统内录实时英文识别 · 双语剧本库精准纠偏",
        detailsZh = listOf(
            "全程本地离线运行（Whisper ONNX + Silero VAD），无需海外服务器。",
            "支持直接在游戏/视频画面上方显示悬浮字幕条。",
            "识别源与输出同设为 English (en -> en) 时，自动启用台词库顺序窗口匹配，输出官方英文原声台词。"
        ),
        titleEn = "1. Welcome to Story English Captions",
        subtitleEn = "Real-time system audio recognition & script alignment",
        detailsEn = listOf(
            "Runs fully offline on device with Sherpa-ONNX Whisper and Silero VAD.",
            "Renders a clean draggable subtitle bar on top of any game or video.",
            "Keep Source & Target set to en -> en to match and display clean script English."
        )
    ),
    TutorialStep(
        titleZh = "2. 模型安装（轻装包必看）",
        subtitleZh = "一次下载或导入，后续升级 APK 不用重传大模型",
        detailsZh = listOf(
            "完整版已内置 Whisper Small (int8, 358 MB)，安装即可直接启动。",
            "轻装更新版 (~93 MB) 首次需在首页选择在线镜像下载（推荐 Small，也可下 Base/Tiny 小模型）或导入本地 .zip 模型包。",
            "模型保存在软件私有目录内，后续覆盖安装新版轻装 APK 时会自动保留。"
        ),
        titleEn = "2. Preparing the Speech Model",
        subtitleEn = "Download or import once; preserved across lite APK updates",
        detailsEn = listOf(
            "The full APK bundles Whisper Small (int8, 358 MB) out of the box.",
            "The lite APK (~93 MB) lets you download Small/Base/Tiny from mirrors or import a local .zip once.",
            "External models remain preserved inside app filesDir across future lite updates."
        )
    ),
    TutorialStep(
        titleZh = "3. 权限与剧情台词库准备",
        subtitleZh = "两项权限 + 剧本导入（可选）",
        detailsZh = listOf(
            "先授予「悬浮窗」权限（系统内录可不授麦克风；系统询问录屏共享时选“共享整个屏幕”即可只采集声音）。",
            "在“剧情台词库”导入 .md / .txt 剧本，或点击“UTTU 在线选章”，打前选好起始章，过章会自动切章保存进度。",
            "若发现个别台词漏匹配，可在底部展开“高级设置与调试”导出日志排查。"
        ),
        titleEn = "3. Permissions & Story Script",
        subtitleEn = "Grant overlay access + optional script matching",
        detailsEn = listOf(
            "Grant Display over other apps. When starting System Audio, allow screen capture to capture playback audio.",
            "Import a bilingual .md/.txt script or fetch chapters via UTTU, and pick your starting chapter.",
            "Expand Advanced & Debug at the bottom to export runtime logs if needed."
        )
    ),
    TutorialStep(
        titleZh = "4. 游戏悬浮窗防误触技巧",
        subtitleZh = "电影字幕条模式 + 触摸穿透锁定",
        detailsZh = listOf(
            "打开“电影/游戏字幕条模式”可隐藏顶部控制栏，仅保留居中透明黑影字幕。",
            "调好位置后点击悬浮窗小锁图标（或在首页/通知栏切换“触摸穿透”），点击字幕区域将直接穿透到游戏内，不挡剧情推进！",
            "需要重新拖动或暂停时，从手机下拉通知栏点“穿透锁定”即可解锁。"
        ),
        titleEn = "4. In-Game Floating Subtitle Tips",
        subtitleEn = "Cinema subtitle mode + touch-through lock",
        detailsEn = listOf(
            "Enable Cinema/Game Subtitle Mode to hide chrome and keep clean centered captions.",
            "Tap the lock icon or enable Touch-Through Lock so taps pass straight through to the game.",
            "Use the persistent notification actions anytime to unlock, pause, or stop."
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    deviceSpecs: DeviceSpecs? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalUiStrings.current
    var currentStepIndex by remember { mutableIntStateOf(0) }
    val step = TUTORIAL_STEPS[currentStepIndex]

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(t.pick("使用指南", "User Guide"), fontWeight = FontWeight.Bold)
                        Text(
                            t.pick(
                                "第 ${currentStepIndex + 1} / ${TUTORIAL_STEPS.size} 步",
                                "Step ${currentStepIndex + 1} of ${TUTORIAL_STEPS.size}"
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = t["Close"])
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinearProgressIndicator(
                progress = { (currentStepIndex + 1).toFloat() / TUTORIAL_STEPS.size.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = t.pick(step.titleZh, step.titleEn),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = t.pick(step.subtitleZh, step.subtitleEn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    t.pick(step.detailsZh.joinToString("\n"), step.detailsEn.joinToString("\n"))
                        .lines()
                        .forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("• ", fontWeight = FontWeight.Bold)
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                }
            }

            if (deviceSpecs != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = t.pick("本机设备信息：${deviceSpecs.deviceModel}", "Device: ${deviceSpecs.deviceModel}"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "RAM ${deviceSpecs.totalRamMb} MB · ${deviceSpecs.cpuCores} Cores · " +
                                "${if (deviceSpecs.is64Bit) "64-bit" else "32-bit"} · " +
                                t.pick("可用空间 ${deviceSpecs.freeStorageMb} MB", "${deviceSpecs.freeStorageMb} MB free"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f, fill = false))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (currentStepIndex > 0) currentStepIndex-- },
                    enabled = currentStepIndex > 0,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t.pick("上一步", "Previous"))
                }

                if (currentStepIndex < TUTORIAL_STEPS.size - 1) {
                    Button(
                        onClick = { currentStepIndex++ },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(t.pick("下一步", "Next"))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(t.pick("完成并开始使用", "Done"))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
