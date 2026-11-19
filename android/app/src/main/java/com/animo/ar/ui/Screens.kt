package com.animo.ar.ui

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add as FilledAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animo.ar.AppState
import com.animo.ar.Palette
import com.animo.ar.Screen
import com.animo.ar.data.GenerationTask
import com.animo.ar.data.PetRecord
import com.animo.ar.data.TaskStatus

/** Keeps white/mist copy readable now that the camera is no longer dimmed by a bottom scrim. */
// Material's core icon set has no eye/visibility glyph, so draw one.
private val EyeIcon: ImageVector = ImageVector.Builder(
    name = "Eye",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = PathData {
            moveTo(2.2f, 12f)
            curveTo(5.6f, 6.6f, 18.4f, 6.6f, 21.8f, 12f)
            curveTo(18.4f, 17.4f, 5.6f, 17.4f, 2.2f, 12f)
            close()
        },
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.7f,
    )
    addPath(
        pathData = PathData {
            moveTo(12f, 8.9f)
            arcTo(3.1f, 3.1f, 0f, true, true, 12f, 15.1f)
            arcTo(3.1f, 3.1f, 0f, true, true, 12f, 8.9f)
            close()
        },
        fill = SolidColor(Color.White),
    )
}.build()

private val OnCameraTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.78f),
    offset = Offset(0f, 1.5f),
    blurRadius = 4f,
)

@Composable
fun HomeScreen(
    state: AppState,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf("") }
    val promptFocus = remember { FocusRequester() }

    // Back closes the inline creation area before it leaves the app.
    BackHandler(enabled = createOpen) {
        if (textMode) textMode = false else createOpen = false
    }
    LaunchedEffect(textMode) {
        if (textMode) promptFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        // The camera feed is the background (hosted by MainActivity so it survives Manage);
        // scrims keep the title and the bottom bar readable over bright frames.
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Palette.BgTop.copy(alpha = 0.90f), Color.Transparent)
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .imePadding()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Eyebrow("ANIMO")
                Spacer(Modifier.height(6.dp))
                Title("灵动萌", size = 42)
                Spacer(Modifier.height(4.dp))
                Body("把你喜欢的角色，做成会动的手办", size = 13)
            }

            // Empty camera area doubles as "tap outside to collapse".
            Spacer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (createOpen) {
                            createOpen = false
                            textMode = false
                        }
                    }
            )

            Text(
                when {
                    !createOpen -> "对准桌面或地面，点「制作」开始"
                    textMode -> "输入描述，回车或点右侧按钮开始制作"
                    else -> "点粉色按钮拍照扫描 · 或选文字 / 相册"
                },
                color = Palette.Cream.copy(alpha = 0.92f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = OnCameraTextShadow),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            )

            if (state.status.isNotEmpty()) {
                Text(
                    state.status,
                    color = Palette.Coral,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = OnCameraTextShadow),
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 4.dp)
                )
            }

            AnimatedVisibility(
                visible = createOpen && textMode,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CapsuleField(
                            value = promptDraft,
                            onValueChange = { promptDraft = it },
                            placeholder = "描述它长什么样（2–200 字）",
                            modifier = Modifier.weight(1f),
                            focusRequester = promptFocus,
                            onSubmit = { state.submitText(promptDraft) },
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundButton(
                            Icons.Default.Send,
                            "开始制作",
                            48.dp,
                            onClick = { state.submitText(promptDraft) },
                            fill = Palette.Blossom,
                            tint = Color(0xFF2B0716),
                            iconSize = 20.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            AnimatedVisibility(
                visible = createOpen && !textMode,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickActionButton(Icons.Default.Edit, "文字描述") { textMode = true }
                        Spacer(Modifier.width(18.dp))
                        QuickActionButton(Icons.Default.FilledAdd, "相册导入") { onPickGallery() }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            BottomBar(
                onManage = { state.go(Screen.Manage) },
                onCreate = {
                    if (createOpen) {
                        onTakePhoto()
                    } else {
                        createOpen = true
                        textMode = false
                    }
                },
                onSettings = { state.go(Screen.Settings) },
                createExpanded = createOpen,
            )
        }
    }
}

@Composable
private fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.95f)
    Row(
        Modifier
            .scale(scale)
            .liquidGlass(capsule(), base = Color(0xFF171319), strength = 0.95f, elevated = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Palette.Blossom, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = Palette.Cream,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(shadow = OnCameraTextShadow),
        )
    }
}

@Composable
fun ManageScreen(state: AppState) {
    var editingPet by remember { mutableStateOf<PetRecord?>(null) }
    var editName by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Keep the preview pointed at a live pet, including after deletes / new generations.
    LaunchedEffect(state.pets) {
        if (state.pets.none { it.id == selectedId }) {
            selectedId = state.pets.firstOrNull()?.id
        }
    }
    val previewPet = state.pets.firstOrNull { it.id == selectedId } ?: state.pets.firstOrNull()

    editingPet?.let { target ->
        AlertDialog(
            onDismissRequest = { editingPet = null },
            title = { Text("编辑手办", color = Palette.Cream) },
            text = {
                Column {
                    Text("现在是「${target.name}」", color = Palette.Mist, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    CapsuleField(editName, { editName = it }, "新的名字")
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = {
                            state.removePet(target)
                            editingPet = null
                        }
                    ) { Text("删除这只手办", color = Palette.Coral) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) {
                        state.renamePet(target, editName)
                        editingPet = null
                    }
                }) { Text("保存", color = Palette.Blossom) }
            },
            dismissButton = {
                TextButton(onClick = { editingPet = null }) { Text("取消", color = Palette.Cream) }
            },
            shape = squircle(28.dp),
            containerColor = Color(0xF21A0D15),
        )
    }

    Box(Modifier.fillMaxSize()) {
        // The ARCore camera stays alive behind this screen; only soft scrims sit on top of it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Palette.BgTop.copy(alpha = 0.92f), Color.Transparent)
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(WindowInsets.navigationBars.asPaddingValues())
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(
                    Icons.Default.ArrowBack,
                    "返回",
                    46.dp,
                    onClick = { state.go(Screen.Home) },
                    fill = Color(0xCC160B13),
                )
                Spacer(Modifier.width(14.dp))
                Title("手办管理", size = 26)
            }

            if (previewPet != null) {
                PetPreviewCard(
                    pet = previewPet,
                    state = state,
                    onOpen = { state.takeOut(previewPet) },
                    onEdit = {
                        editName = previewPet.name
                        editingPet = previewPet
                    },
                )
                Spacer(Modifier.height(10.dp))
            }

            val runningTasks = state.tasks.count { it.status == TaskStatus.RUNNING }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${state.pets.size} 只手办",
                    color = Palette.Cream,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(shadow = OnCameraTextShadow),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (runningTasks > 0) "$runningTasks 个任务生成中" else "点一只查看 3D 预览",
                    color = if (runningTasks > 0) Palette.Blossom else Palette.Mist,
                    fontSize = 12.sp,
                    style = TextStyle(shadow = OnCameraTextShadow),
                )
            }

            if (state.pets.isEmpty() && state.tasks.isEmpty()) {
                EmptyPetList(Modifier.weight(1f)) { state.go(Screen.Home) }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
                ) {
                    if (state.tasks.isNotEmpty()) {
                        item { SectionLabel("生成任务") }
                        items(state.tasks.reversed(), key = { "task-${it.id}" }) { task ->
                            val retryable =
                                (task.inputKind == "text" && !task.prompt.isNullOrBlank()) ||
                                    task.sources.isNotEmpty()
                            GenerationTaskRow(
                                task = task,
                                onCancel = { state.cancelTask(task) },
                                onRetry = if (retryable) {
                                    { state.retryTask(task) }
                                } else {
                                    null
                                },
                                onDelete = { state.removeTask(task) },
                            )
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    if (state.pets.isNotEmpty()) {
                        item { SectionLabel("我的手办") }
                        items(state.pets, key = { it.id }) { pet ->
                            ManagePetRow(
                                pet = pet,
                                selected = previewPet?.id == pet.id,
                                onClick = { selectedId = pet.id },
                                onOpen = { state.takeOut(pet) },
                                onEdit = {
                                    editName = pet.name
                                    editingPet = pet
                                },
                            )
                        }
                    }
                }
            }

            PrimaryButton(
                "创建新AR手办",
                Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
            ) { state.go(Screen.Home) }
        }
    }
}

@Composable
private fun PetPreviewCard(
    pet: PetRecord,
    state: AppState,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    // Let the page transition finish before the WebView/WebGL context is created.
    var previewReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        previewReady = true
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(210.dp)
            .liquidGlass(squircle(30.dp), base = Color(0xFF0E0B10), strength = 1.05f)
    ) {
        if (state.store.hasModel(pet.id)) {
            if (previewReady) {
                ModelPreview(
                    modelFile = state.store.modelFile(pet.id),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PreviewPending(Modifier.fillMaxSize())
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("模型文件丢失", color = Palette.Mist, fontSize = 12.sp)
            }
        }

        // The WebView fills the card; only the bottom text scrim stays above it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD90B0509))
                    )
                )
        )

        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(pet.name, color = Palette.Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(petSubtitle(pet), color = Palette.Mist, fontSize = 11.sp)
        }

        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(capsule())
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC3A1A2B), Color(0xCC21101A))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("3D 预览", color = Palette.Blossom, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        MiniAction(
            Icons.Default.Edit,
            "编辑手办",
            onEdit,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )

        AccentPill(
            text = "放进现实",
            onClick = onOpen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
        )
    }
}

@Composable
private fun PreviewPending(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("正在准备 3D 预览…", color = Palette.Mist.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun AccentPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.95f)
    Box(
        modifier
            .scale(scale)
            .shadow(10.dp, capsule(), clip = false)
            .clip(capsule())
            // This pill sits over the 3D WebView, not the camera, so it uses a solid smoked
            // rose glass instead of the camera-sampling refraction.
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC3A1A2B), Color(0xCC21101A))
                )
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = Palette.Cream, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyPetList(modifier: Modifier = Modifier, onStart: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = Palette.Blossom,
                modifier = Modifier.size(46.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("还没有手办", color = Palette.Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "拍下身边的玩偶、手作，\n或者从相册里选一张角色图。",
                color = Palette.Mist,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("它会成为只属于你的 AR 手办", color = Palette.Mist.copy(alpha = 0.8f), fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
            GhostButton("创建新AR手办", Modifier.width(210.dp), onStart)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Palette.Mist.copy(alpha = 0.9f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        style = TextStyle(shadow = OnCameraTextShadow),
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun GenerationTaskRow(
    task: GenerationTask,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(capsule(), base = Color(0xFF141016), strength = 0.85f, elevated = false)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PetAvatar(task.name, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.name,
                    color = Palette.Cream,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                TaskStatusPill(task.status)
            }
            Spacer(Modifier.height(3.dp))
            Text(task.stage, color = Palette.Mist, fontSize = 12.sp)
            if (task.status != TaskStatus.RUNNING && task.durationMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "用时 ${formatDuration(task.durationMs)}",
                    color = Palette.Mist.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
            if (task.status == TaskStatus.RUNNING) {
                Spacer(Modifier.height(7.dp))
                TaskProgress(task.progress)
            }
            if (!task.error.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    task.error,
                    color = Palette.Coral,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                )
            }
        }
        when {
            task.status == TaskStatus.RUNNING -> {
                TextButton(onClick = onCancel) {
                    Text("取消", color = Palette.Coral, fontSize = 12.sp)
                }
            }
            onRetry != null -> {
                TextButton(onClick = onRetry) {
                    Text("重新生成", color = Palette.Blossom, fontSize = 12.sp)
                }
                MiniAction(Icons.Default.Delete, "删除任务", onDelete, tint = Palette.Coral)
            }
            else -> {
                MiniAction(Icons.Default.Delete, "删除任务", onDelete, tint = Palette.Coral)
            }
        }
    }
}

@Composable
private fun TaskStatusPill(status: TaskStatus) {
    val (text, tint) = when (status) {
        TaskStatus.RUNNING -> "生成中" to Palette.Blossom
        TaskStatus.FAILED -> "失败" to Palette.Coral
        TaskStatus.CANCELLED -> "已取消" to Palette.Mist
    }
    Box(
        Modifier
            .clip(capsule())
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.10f))
                )
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TaskProgress(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(capsule())
            .background(Color(0x22FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .height(4.dp)
                .clip(capsule())
                .background(
                    Brush.horizontalGradient(
                        listOf(Palette.Blossom, Palette.Amber)
                    )
                )
        )
    }
}

@Composable
private fun ManagePetRow(
    pet: PetRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.985f)
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // The pencil is 40dp; putting its center on the capsule's right-arc center makes the
    // right inset exactly (rowHeight - 40) / 2. Measuring avoids a magic padding that
    // drifts with font scale or a two-line subtitle.
    val endPadding = with(density) {
        val fallback = 8.dp.toPx()
        if (rowHeightPx <= 0) fallback.toDp() else {
            ((rowHeightPx - 40.dp.toPx()) / 2f).coerceAtLeast(fallback).toDp()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .liquidGlass(
                shape = capsule(),
                base = Color(0xFF141016),
                strength = if (selected) 1.05f else 0.85f,
                elevated = false,
                // Selection used to be a rose outline; keep it as a faint wash instead.
                tint = if (selected) Palette.Blossom else Color.Transparent,
                tintAlpha = 0.10f,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .onSizeChanged { rowHeightPx = it.height }
            .padding(start = 14.dp, end = endPadding, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PetAvatar(pet.name, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pet.name, color = Palette.Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(petSubtitle(pet), color = Palette.Mist, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            BondPips(pet.bond)
        }
        // Two well-separated touch targets: view in AR (eye) and edit (pencil).
        Spacer(Modifier.width(10.dp))
        MiniAction(
            EyeIcon,
            "放进现实",
            onOpen,
            tint = if (selected) Palette.Blossom else Palette.Cream,
        )
        Spacer(Modifier.width(10.dp))
        MiniAction(Icons.Default.Edit, "编辑手办", onEdit)
    }
}

@Composable
private fun MiniAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Palette.Cream,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.88f)
    Box(
        modifier
            .size(40.dp)
            .scale(scale)
            .liquidGlass(
                shape = CircleShape,
                base = Color(0xFF171319),
                strength = 0.9f,
                elevated = false,
                tint = tint,
                tintAlpha = 0.18f,
                lens = true,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint.copy(alpha = 0.92f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun petSubtitle(pet: PetRecord): String {
    val base = sourceLabel(pet)
    return if (pet.generationMs > 0) "$base · 用时 ${formatDuration(pet.generationMs)}" else base
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
}

private fun sourceLabel(pet: PetRecord): String {
    val kind = when (pet.inputKind) {
        "text" -> "描述生成"
        "scan" -> "拍照扫描"
        else -> "照片 / 图片生成"
    }
    return if (pet.source == "lux3d") kind else "内置示例"
}

@Composable
private fun BondPips(bond: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (index < bond.coerceIn(0, 5)) Palette.Coral else Color(0x22FFFFFF))
            )
        }
    }
}

private fun Modifier.borderRim(): Modifier = this.then(
    Modifier.background(Color.Transparent)
)

@Composable
private fun BottomBar(
    onManage: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    createExpanded: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .height(108.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            Modifier.padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Three independent lenses. The effect is only ever applied to circles.
            RoundButton(
                Icons.Default.List,
                "手办管理",
                62.dp,
                onManage,
                iconSize = 24.dp,
            )
            RoundButton(
                if (createExpanded) Icons.Rounded.Search else Icons.Rounded.Add,
                if (createExpanded) "拍照扫描" else "制作",
                86.dp,
                onCreate,
                fill = Palette.Blossom,
                tint = Palette.Cream,
                iconSize = 34.dp,
                label = if (createExpanded) "拍照扫描" else null,
                tintAlpha = 0.72f,
            )
            RoundButton(
                Icons.Default.Settings,
                "设置",
                62.dp,
                onSettings,
                iconSize = 24.dp,
            )
        }
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(state.settings.apiKey) }
    var message by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        // Live camera behind the page, same as Home/Manage; only a top scrim for the header.
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                RoundButton(
                    Icons.Default.ArrowBack,
                    "返回",
                    46.dp,
                    onClick = { state.go(Screen.Home) },
                    fill = Color(0xCC160B13),
                )
                Spacer(Modifier.width(14.dp))
                Title("设置", size = 26)
            }

            Spacer(Modifier.height(10.dp))
            AholoAccountCard()

            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Lux3D API Key", color = Palette.Blossom, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Body("登录上方 Aholo 账号即可创建；Key 只保存在这台手机上。", size = 11)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            "从剪贴板填入",
                            color = Palette.Blossom,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable {
                                    val clip = context
                                        .getSystemService(ClipboardManager::class.java)
                                        ?.primaryClip
                                    val raw = clip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        .orEmpty()
                                    val candidate = raw
                                        .lineSequence()
                                        .firstOrNull { it.isNotBlank() }
                                        ?.trim()
                                        .orEmpty()
                                    if (candidate.length !in 8..128) {
                                        message = "剪贴板里没有可用的 API Key"
                                    } else {
                                        key = candidate
                                        message = "已从剪贴板填入，点「保存并测试」"
                                    }
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CapsuleField(
                        value = key,
                        onValueChange = { key = it },
                        placeholder = "粘贴你的 Lux3D API Key",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (state.settings.usingDebugKey) {
                        Spacer(Modifier.height(8.dp))
                        Text("当前使用本地调试 Key", color = Palette.Coral, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("保存并测试") {
                        state.settings.apiKey = key.trim()
                        state.testApiKey { ok, text ->
                            message = text
                            if (ok) key = state.settings.apiKey
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("生成质量", color = Palette.Blossom, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Body("自动补全四个视角会更完整；关闭后更快，但背面只能靠猜。", size = 11)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            "自动多视角",
                            active = state.settings.autoMultiView,
                            modifier = Modifier.weight(1f),
                        ) { state.settings.autoMultiView = true }
                        Chip(
                            "单图直出",
                            active = !state.settings.autoMultiView,
                            modifier = Modifier.weight(1f),
                        ) { state.settings.autoMultiView = false }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "模型面数",
                        color = Palette.Cream,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    val faceOptions = listOf(
                        30_000 to "3 万",
                        100_000 to "10 万",
                        200_000 to "20 万",
                        300_000 to "30 万",
                    )
                    faceOptions.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (value, label) ->
                                Chip(
                                    "$label 面",
                                    active = state.settings.faceCount == value,
                                    modifier = Modifier.weight(1f),
                                ) { state.settings.faceCount = value }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("最高 30 万面，生成会更慢。", color = Palette.Mist, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = if (message.contains("可用")) Palette.Coral else Palette.Mist,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "你的手办只保存在这台手机上。",
                color = Palette.Mist.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
    }
}
