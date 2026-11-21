package com.charles.livecaptionn.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.charles.livecaptionn.story.ParsedStoryDocument
import com.charles.livecaptionn.story.StoryCatalogItem
import com.charles.livecaptionn.story.StoryCategory
import com.charles.livecaptionn.story.StoryChapter
import com.charles.livecaptionn.story.StoryScriptStore
import com.charles.livecaptionn.story.UttuStoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StoryScriptCard(
    ui: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val store = remember { StoryScriptStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var importedDoc by remember { mutableStateOf<ParsedStoryDocument?>(null) }
    var txtStatus by remember { mutableStateOf("未导入本地剧本") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { runCatching { store.loadParsed() }.getOrNull() }
        importedDoc = loaded
        txtStatus = formatImportedStatus(loaded)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                txtStatus = "正在导入并解析…"
                val res = withContext(Dispatchers.IO) { runCatching { store.importFile(uri) } }
                val debugLog = (context.applicationContext as? com.charles.livecaptionn.LiveCaptionApp)?.container?.debugLog
                res.fold(
                    onSuccess = { doc ->
                        importedDoc = doc
                        debugLog?.log("Import", "Imported OK: chapters=${doc.chapters.size} lines=${doc.lines.size} en=${doc.hasEnglish} zh=${doc.hasChinese}")
                        txtStatus = formatImportedStatus(doc) + "（重启服务生效）"
                    },
                    onFailure = { err ->
                        debugLog?.log("Import", "Import FAILED: ${err.message}")
                        txtStatus = "导入失败：${err.message ?: "请检查 UTF-8 文本"}"
                    }
                )
            }
        }
    }

    var storyUrl by remember { mutableStateOf(UttuStoryRepository.DEFAULT_ATLANTIS_URL) }
    var preferEnglish by remember { mutableStateOf(true) }
    var showCatalogDialog by remember { mutableStateOf(false) }
    var showManualUrl by remember { mutableStateOf(false) }
    var showChapterMenu by remember { mutableStateOf(false) }
    var showLlmConfig by remember { mutableStateOf(false) }

    var baseUrlDraft by remember { mutableStateOf(ui.settings.llmApiBaseUrl) }
    var apiKeyDraft by remember { mutableStateOf(ui.settings.llmApiKey) }
    var modelDraft by remember { mutableStateOf(ui.settings.llmModel) }

    LaunchedEffect(ui.settings.llmApiBaseUrl, ui.settings.llmApiKey, ui.settings.llmModel) {
        baseUrlDraft = ui.settings.llmApiBaseUrl
        apiKeyDraft = ui.settings.llmApiKey
        modelDraft = ui.settings.llmModel
    }

    val uttu = ui.uttuFetchState
    val bundle = uttu.bundle
    val activeChapters: List<StoryChapter> = when {
        importedDoc != null && importedDoc!!.chapters.size > 1 -> importedDoc!!.chapters
        bundle != null -> bundle.chapters
        importedDoc != null -> importedDoc!!.chapters
        else -> emptyList()
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("剧情台词库与大模型纠偏（可选）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "推荐直接导入电脑生成的双语对照 Markdown（story_bilingual_reference.md）或纯英文 TXT；也支持从 UTTU 选章节拉取。支持顺序窗口匹配，打完一章自动切下一章。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. 导入本地 MD / TXT（优先推荐）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    txtStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importedDoc != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream", "*/*")) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("导入 MD/TXT")
                    }
                    if (importedDoc != null || store.exists()) {
                        TextButton(onClick = {
                            store.clear()
                            importedDoc = null
                            txtStatus = "已清除本地剧本"
                        }) {
                            Text("清除")
                        }
                    }
                }
            }

            // 2. 章节选择与自动切章状态（兼容导入的 MD 与 UTTU 拉取）
            if (activeChapters.isNotEmpty()) {
                val selectedCh = activeChapters.firstOrNull { it.number == ui.settings.activeStoryChapter }
                    ?: activeChapters.first()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "当前章节：第 ${selectedCh.number} 章 ${selectedCh.title}（${selectedCh.lines.size} 条）",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "跨章时会自动切换到下一章并保存进度",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        OutlinedButton(onClick = { showChapterMenu = true }, shape = RoundedCornerShape(8.dp)) {
                            Text("选章")
                        }
                        DropdownMenu(expanded = showChapterMenu, onDismissRequest = { showChapterMenu = false }) {
                            activeChapters.forEach { ch ->
                                DropdownMenuItem(
                                    text = { Text("${ch.number}. ${ch.title}") },
                                    onClick = {
                                        showChapterMenu = false
                                        viewModel.updateActiveStoryChapter(ch.number)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // 3. 在线 UTTU 剧情浏览器
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        showCatalogDialog = true
                        if (uttu.catalogItems.isEmpty() && !uttu.catalogLoading) {
                            viewModel.loadStoryCatalog(uttu.selectedCategory)
                        }
                    },
                    enabled = !uttu.loading,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (uttu.loading) "拉取中…" else "UTTU 在线选章")
                }
                TextButton(onClick = { showManualUrl = !showManualUrl }) {
                    Text(if (showManualUrl) "收起 URL" else "手动 URL")
                }
            }

            if (uttu.error != null) {
                Text(uttu.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (bundle != null) {
                val langBadge = if (bundle.languageCode == "en") "UTTU 英文原稿" else "UTTU 中文剧本"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "在线缓存：${bundle.eventTitle}（$langBadge · ${bundle.chapters.size} 章）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearUttuStory() }) { Text("清除缓存") }
                }
            }

            if (showManualUrl) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "优先拉取英文原稿",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = preferEnglish, onCheckedChange = { preferEnglish = it })
                }
                OutlinedTextField(
                    value = storyUrl,
                    onValueChange = { storyUrl = it },
                    label = { Text("自定义 UTTU 剧情 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.fetchUttuStory(storyUrl, preferEnglish = preferEnglish) },
                    enabled = !uttu.loading,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("从该 URL 拉取")
                }
            }

            HorizontalDivider()

            // 4. 大模型上下文纠偏（仅在本地未命中且开启时使用）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("大模型上下文纠偏", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (ui.settings.llmApiKey.isBlank()) "未填写 API Key（仅使用免费本地 Whisper + 脚本匹配）"
                        else "命中台词库时自动跳过，仅未命中句子消耗少量 token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = ui.settings.llmCorrectionEnabled,
                    onCheckedChange = viewModel::updateLlmCorrectionEnabled
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "已调用 ${ui.llmStats.calls} 次 · 消耗 ${ui.llmStats.totalTokens} tokens" +
                        (ui.llmStats.lastError?.let { " · 最近异常: $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showLlmConfig = !showLlmConfig }) {
                    Text(if (showLlmConfig) "收起 API" else "配置百炼 Key")
                }
            }

            if (showLlmConfig) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    label = { Text("API Base URL（默认阿里云百炼）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    label = { Text("模型名（推荐 qwen3.8-omni-flash / qwen-turbo）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("API Key（仅保存在本机）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateLlmConfig(baseUrlDraft, apiKeyDraft, modelDraft) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("保存配置")
                    }
                    OutlinedButton(
                        onClick = {
                            baseUrlDraft = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                            modelDraft = "qwen3.8-omni-flash"
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("百炼 Omni 默认")
                    }
                }
            }
        }
    }

    if (showCatalogDialog) {
        StoryCatalogPickerDialog(
            selectedCategory = uttu.selectedCategory,
            items = uttu.catalogItems,
            loading = uttu.catalogLoading,
            error = uttu.catalogError,
            onSelectCategory = { cat -> viewModel.loadStoryCatalog(cat) },
            onRefresh = { viewModel.loadStoryCatalog(uttu.selectedCategory, forceRefresh = true) },
            onPickItem = { item ->
                showCatalogDialog = false
                storyUrl = "${UttuStoryRepository.BASE_ORIGIN}${item.storyPath}"
                viewModel.fetchUttuStory(item.storyPath, preferEnglish = preferEnglish)
            },
            onDismiss = { showCatalogDialog = false }
        )
    }
}

private fun formatImportedStatus(doc: ParsedStoryDocument?): String {
    if (doc == null) return "未导入本地剧本（支持 .md / .txt）"
    val langs = when {
        doc.hasEnglish && doc.hasChinese -> "英文+中文双语"
        doc.hasEnglish -> "纯英文"
        doc.hasChinese -> "中文"
        else -> "未知"
    }
    return "已导入：${doc.chapters.size} 章 · ${doc.lines.size} 条（$langs）"
}

@Composable
private fun StoryCatalogPickerDialog(
    selectedCategory: StoryCategory,
    items: List<StoryCatalogItem>,
    loading: Boolean,
    error: String?,
    onSelectCategory: (StoryCategory) -> Unit,
    onRefresh: () -> Unit,
    onPickItem: (StoryCatalogItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter { it.title.contains(q, ignoreCase = true) || it.storyPath.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("选择 UTTU 剧情章节", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text("刷新")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = cat == selectedCategory,
                            onClick = { onSelectCategory(cat) },
                            label = { Text(cat.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索章节/角色/活动名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在获取${selectedCategory.label}目录…")
                        }
                    }
                    error != null -> {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    filtered.isEmpty() -> {
                        Text("暂无匹配条目", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.storyPath }) { item ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    tonalElevation = 2.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPickItem(item) }
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(
                                            item.storyPath,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
