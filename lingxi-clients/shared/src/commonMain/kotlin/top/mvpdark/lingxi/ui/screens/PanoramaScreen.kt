package top.mvpdark.lingxi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import top.mvpdark.lingxi.ui.emoji.AnimatedEmoji
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lingxi.core.util.ImageSaver
import top.mvpdark.lingxi.core.util.UrlResolver
import top.mvpdark.lingxi.ui.components.PanoramaViewer
import top.mvpdark.lingxi.ui.imageedit.rememberImagePickerLauncher
import top.mvpdark.lingxi.ui.panorama.PanoramaViewModel

/**
 * 全景图生成页面。
 *
 * 流程：上传户型图 → 输入风格描述 → AI 生成 360 度全景图 → 查看结果。
 *
 * @param viewModel 全景图 ViewModel（Koin 注入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanoramaScreen(
    viewModel: PanoramaViewModel = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val launchPicker = rememberImagePickerLauncher { bytes ->
        if (bytes != null) viewModel.onPickImage(bytes)
    }

    // 图片保存：ImageSaver 由 Koin 注入，保存结果通过 Snackbar 反馈
    val imageSaver: ImageSaver = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saveResultImage: () -> Unit = {
        val url = state.resultUrl
        if (url != null) {
            scope.launch {
                val result = imageSaver.saveImage(
                    imageUrl = UrlResolver.resolveImageUrl(url),
                    suggestedName = "lingxi_panorama",
                )
                snackbarHostState.showSnackbar(
                    result.getOrElse { it.message ?: "保存失败" },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "全景",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (state.step != PanoramaViewModel.Step.Upload) {
                        IconButton(onClick = viewModel::resetAll) {
                            Icon(Icons.Default.Refresh, contentDescription = "换一张图")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues),
        ) {
            when (state.step) {
                PanoramaViewModel.Step.Upload -> UploadContent(
                    onPickImage = launchPicker,
                    error = state.error,
                )

                PanoramaViewModel.Step.Edit -> EditContent(
                    state = state,
                    onStyleChange = viewModel::onStyleChange,
                    onGenerate = viewModel::startGenerate,
                    error = state.error,
                )

                PanoramaViewModel.Step.Generating -> GeneratingContent()

                PanoramaViewModel.Step.Result -> ResultContent(
                    state = state,
                    onSaveImage = saveResultImage,
                    onResetEdit = viewModel::resetEdit,
                    onResetAll = viewModel::resetAll,
                )
            }
        }
    }
}

@Composable
private fun UploadContent(
    onPickImage: () -> Unit,
    error: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Panorama,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "上传户型图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "上传一张户型图或平面图，AI 将根据你的风格描述生成 360 度全景效果图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onPickImage,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("选择图片")
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EditContent(
    state: top.mvpdark.lingxi.ui.panorama.PanoramaUiState,
    onStyleChange: (String) -> Unit,
    onGenerate: () -> Unit,
    error: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "户型图预览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                state.imageDisplayUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "户型图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        item {
            Text(
                text = "风格描述",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            OutlinedTextField(
                value = state.styleDesc,
                onValueChange = onStyleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("例如：现代北欧风格、日式禅意、工业风...")
                },
                supportingText = {
                    Text("描述你想要的室内设计风格，AI 将据此生成全景图")
                },
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (error != null) {
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Button(
                onClick = onGenerate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "生成全景图",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun GeneratingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // APNG 动画表情：图像生成师工作中
        AnimatedEmoji(
            resourcePath = "files/emoji/agents/animated/image_generator/working.apng",
            size = 72.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "团团正在生成全景图...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "全景图生成需要约 30-60 秒，请耐心等待",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ResultContent(
    state: top.mvpdark.lingxi.ui.panorama.PanoramaUiState,
    onSaveImage: () -> Unit,
    onResetEdit: () -> Unit,
    onResetAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "全景图结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            // 360° 全景查看器：支持拖拽浏览 + 双指缩放
            state.resultUrl?.let { url ->
                PanoramaViewer(
                    imageUrl = url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                )
            }
        }
        item {
            Text(
                text = "👆 拖动画面查看 360° 视角，双指缩放调整远近",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSaveImage,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("保存图片")
                }
                Button(
                    onClick = onResetEdit,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("重新编辑")
                }
                Button(
                    onClick = onResetAll,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("换一张图")
                }
            }
        }
    }
}
