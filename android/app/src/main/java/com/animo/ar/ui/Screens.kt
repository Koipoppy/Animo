package com.animo.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animo.ar.AppState
import com.animo.ar.BuildConfig
import com.animo.ar.CreateTab
import com.animo.ar.Palette
import com.animo.ar.Screen
import com.animo.ar.data.PetRecord

@Composable
fun HomeScreen(state: AppState) {
    var pendingDelete by remember { mutableStateOf<PetRecord?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${target.name}？", color = Palette.Cream) },
            text = { Text("本地缓存的模型会一起删掉，需要重新生成才能找回。", color = Palette.Mist) },
            confirmButton = {
                TextButton(onClick = {
                    state.removePet(target)
                    pendingDelete = null
                }) { Text("删除", color = Palette.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = Palette.Cream) }
            },
            containerColor = Palette.BgBottom,
        )
    }

    Box(Modifier.fillMaxSize()) {
        DuskBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(WindowInsets.navigationBars.asPaddingValues())
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
                Body("把喜欢的形象，养成会呼吸的伙伴", size = 13)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("我的伙伴", color = Palette.Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(capsule())
                        .background(Palette.Blossom.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        petsLabel(state.pets.size),
                        color = Palette.Blossom,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (state.pets.isEmpty()) {
                EmptyPetList(Modifier.weight(1f)) { state.openCreate(CreateTab.Gallery) }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                ) {
                    items(state.pets, key = { it.id }) { pet ->
                        PetRow(
                            pet = pet,
                            onClick = { state.takeOut(pet) },
                            onDelete = { pendingDelete = pet }
                        )
                    }
                }
            }

            BottomBar(
                onCollection = { state.go(Screen.Home) },
                onCreate = { state.openCreate(CreateTab.Photo) },
                onSettings = { state.go(Screen.Settings) },
            )
        }
    }
}

private fun petsLabel(count: Int) = if (count < 10) "0$count" else "$count"

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
            Text("还没有伙伴", color = Palette.Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "拍下身边的玩偶、手作或小物件，\n或者贴一张网上的角色图。",
                color = Palette.Mist,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("它会成为只属于你的 3D 小生命", color = Palette.Mist.copy(alpha = 0.8f), fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
            GhostButton("试试从相册选一张", Modifier.width(210.dp), onStart)
        }
    }
}

@Composable
private fun PetRow(pet: PetRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val deleteInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = capsule(),
        color = Palette.Glass,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PetAvatar(pet.name, 50.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pet.name, color = Palette.Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(sourceLabel(pet), color = Palette.Mist, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                BondPips(pet.bond)
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x14FFFFFF))
                    .clickable(interactionSource = deleteInteraction, indication = null, onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = Palette.Coral.copy(alpha = 0.9f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

private fun sourceLabel(pet: PetRecord): String {
    val kind = when (pet.inputKind) {
        "text" -> "描述生成"
        else -> "照片 / 图片生成"
    }
    return if (pet.source == "lux3d") kind else "示例伙伴"
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
private fun BottomBar(onCollection: () -> Unit, onCreate: () -> Unit, onSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 16.dp)
            .height(104.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            RoundButton(Icons.Default.List, "图鉴", 62.dp, onCollection)
            RoundButton(
                Icons.Default.Add,
                "创造",
                88.dp,
                onCreate,
                fill = Palette.Blossom,
                tint = Color(0xFF2B0716),
                iconSize = 38.dp
            )
            // Release builds talk to the deployed backend and never expose connection settings.
            if (BuildConfig.DEBUG) {
                RoundButton(Icons.Default.Settings, "设置", 62.dp, onSettings)
            }
        }
    }
}

@Composable
fun CreateScreen(state: AppState, onPickGallery: () -> Unit, onTakePhoto: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        DuskBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                RoundButton(Icons.Default.ArrowBack, "返回", 46.dp, onClick = { state.go(Screen.Home) })
                Spacer(Modifier.width(14.dp))
                Title("创造新伙伴", size = 26)
            }

            Spacer(Modifier.height(4.dp))
            Body("选一个来源，让它从平面变成会动的生命", size = 12)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile(Icons.Default.Add, "拍一张", "现实里的它", state.tab == CreateTab.Photo, Modifier.weight(1f)) {
                    state.tab = CreateTab.Photo
                    onTakePhoto()
                }
                SourceTile(Icons.Default.List, "相册", "存下的图片", state.tab == CreateTab.Gallery, Modifier.weight(1f)) {
                    state.tab = CreateTab.Gallery
                    onPickGallery()
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile(Icons.Default.Share, "网络图片", "网图或帖子", state.tab == CreateTab.Link, Modifier.weight(1f)) {
                    state.tab = CreateTab.Link
                }
                SourceTile(Icons.Default.Edit, "文字描述", "一句话生成", state.tab == CreateTab.Text, Modifier.weight(1f)) {
                    state.tab = CreateTab.Text
                }
            }

            Spacer(Modifier.height(16.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    when (state.tab) {
                        CreateTab.Photo -> {
                            Text(
                                if (state.pickedPhoto == null) "把镜头对准它" else "照片已拍好",
                                color = Palette.Cream,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Body("让主体占满画面，纯色背景效果最好", size = 12)
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(if (state.pickedPhoto == null) "打开相机" else "重拍一张") { onTakePhoto() }
                        }

                        CreateTab.Gallery -> {
                            Text(
                                if (state.pickedPhoto == null) "从相册里挑一张" else "图片已选好",
                                color = Palette.Cream,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Body("照片、表情包、插画、网图截图都可以", size = 12)
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(if (state.pickedPhoto == null) "打开相册" else "换一张") { onPickGallery() }
                        }

                        CreateTab.Link -> {
                            Text("贴一个链接", color = Palette.Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Body("图片直链，或带配图的帖子", size = 12)
                            Spacer(Modifier.height(10.dp))
                            CapsuleField(state.link, { state.link = it }, "https://…")
                        }

                        CreateTab.Text -> {
                            Text("描述它长什么样", color = Palette.Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Body("例如：圆滚滚的胖青蛙玩偶，浅绿，大圆眼，坐姿", size = 12)
                            Spacer(Modifier.height(10.dp))
                            CapsuleField(state.prompt, { state.prompt = it }, "写 2–200 字")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            CapsuleField(state.name, { state.name = it }, "给它起个名字（可留空）")

            if (state.tab != CreateTab.Text) {
                Spacer(Modifier.height(10.dp))
                Chip(
                    text = if (state.plush) "立体化：变成圆滚滚的毛绒公仔" else "原样还原：保留照片本来的样子",
                    active = state.plush,
                    modifier = Modifier.fillMaxWidth(),
                ) { state.plush = !state.plush }
            }

            Spacer(Modifier.weight(1f))

            if (state.status.isNotEmpty()) {
                Text(
                    state.status,
                    color = Palette.Coral,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            PrimaryButton("开始孵化", Modifier.padding(bottom = 18.dp)) { state.hatch() }
        }
    }
}

@Composable
fun HatchScreen(state: AppState) {
    Box(Modifier.fillMaxSize()) {
        DuskBackdrop()
        if (state.hatchSucceeded) {
            HatchSuccess(state)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.statusBars.asPaddingValues()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Star, null, tint = Palette.Blossom, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(22.dp))
                Text("正在孵化", color = Palette.Cream, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.job?.stage ?: state.status.ifEmpty { "正在准备" },
                    color = Palette.Mist,
                    fontSize = 14.sp
                )
                state.job?.let { job ->
                    val rig = when {
                        job.engine == "lux3d" && job.bones > 0 ->
                            "Lux3D 云端骨骼 · ${job.bones} 骨 · ${job.animations} 段动作"
                        job.animations > 0 -> "本地骨骼 · ${job.animations} 段动作"
                        else -> null
                    }
                    if (rig != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(rig, color = Palette.Coral, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                ProgressBar(state.job?.progress ?: 0f)
                Spacer(Modifier.height(10.dp))
                Text("3D 建模通常需要 1–3 分钟", color = Palette.Mist.copy(alpha = 0.7f), fontSize = 11.sp)
                Spacer(Modifier.height(26.dp))
                GhostButton("取消", Modifier.width(150.dp)) { state.go(Screen.Create) }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier
            .width(240.dp)
            .height(6.dp)
            .clip(capsule())
            .background(Color(0x22FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .height(6.dp)
                .clip(capsule())
                .background(Palette.Blossom)
        )
    }
}

@Composable
private fun HatchSuccess(state: AppState) {
    val pet = state.activePet
    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.statusBars.asPaddingValues()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Check, null, tint = Palette.Blossom, modifier = Modifier.size(58.dp))
        Spacer(Modifier.height(20.dp))
        Text("孵化成功！", color = Palette.Cream, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(pet?.name.orEmpty(), color = Palette.Blossom, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text("它已经住进你的图鉴", color = Palette.Mist, fontSize = 12.sp)
        state.job?.let { job ->
            if (job.animations > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (job.engine == "lux3d") "Lux3D 云端骨骼 · ${job.bones} 骨 · ${job.animations} 段动作"
                    else "本地骨骼 · ${job.animations} 段动作",
                    color = Palette.Coral,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(34.dp))
        PrimaryButton("放入现实世界", Modifier.width(260.dp)) {
            pet?.let { state.takeOut(it) }
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("先留在图鉴", Modifier.width(200.dp)) { state.go(Screen.Home) }
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    var url by remember { mutableStateOf(state.settings.serverUrl) }
    var secret by remember { mutableStateOf(state.settings.secret) }
    var message by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        DuskBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                RoundButton(Icons.Default.ArrowBack, "返回", 46.dp, onClick = { state.go(Screen.Home) })
                Spacer(Modifier.width(14.dp))
                Title("连接设置", size = 26)
            }

            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("后端地址", color = Palette.Blossom, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Body("USB 连电脑时先执行 adb reverse tcp:8787 tcp:8787，再保持默认地址即可。", size = 11)
                    Spacer(Modifier.height(10.dp))
                    CapsuleField(url, { url = it }, "http://127.0.0.1:8787")
                    Spacer(Modifier.height(10.dp))
                    CapsuleField(secret, { secret = it }, "访问密钥（可留空）")
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("保存并测试连接") {
                        state.settings.serverUrl = url
                        state.settings.secret = secret
                        state.testConnection { ok, text ->
                            message = text
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (message.isNotEmpty()) {
                Text(message, color = Palette.Mist, fontSize = 12.sp)
            }

            Spacer(Modifier.weight(1f))
            Text(
                "Lux3D 密钥只保存在后端，不会进入 APK。",
                color = Palette.Mist.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}
