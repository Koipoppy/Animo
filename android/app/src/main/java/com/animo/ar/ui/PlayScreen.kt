package com.animo.ar.ui

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.animo.ar.Palette
import com.animo.ar.data.PetRecord
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import com.google.android.filament.LightManager
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import java.io.File
import java.nio.ByteBuffer

// Plane auto-attach tuning: 20x20cm is the smallest surface a pet can stand on, 2.5m the reach
// within which "in front of me" and "on that plane" are close enough for the swap to feel seamless.
private const val MIN_PLANE_AREA = 0.04f
private const val MAX_ANCHOR_DISTANCE = 2.5f
// The default plane sits on the camera's centre ray: the pet lands dead-centre at a constant
// apparent size no matter how the device is pitched, which makes debugging predictable.
// 0.7 m keeps the 12 cm pet nicely framed on a tablet screen.
private const val DEFAULT_PLANE_DISTANCE = 0.7f
// ~1.5 s of frames before the fallback anchor is allowed to jump onto a plane, so the pet does not
// teleport while the user is still absorbing where it landed.
private const val FALLBACK_GRACE_FRAMES = 45

// Locomotion: the clips are in-place, so the body is moved in code. 1.4 m/s is a human walking
// pace; scaled down to the pet's 12 cm it matches the stride cadence authored into Walk_Loop,
// which is what keeps the feet from visibly skating.
private const val PET_WALK_SPEED = 1.4f
// Patrol turns continuously, so the walk path is a smooth circle instead of a wall bounce.
private const val PET_TURN_RATE = 1.4f
// Lux3D exports face +Z while the heading math assumes -Z; half a turn aligns them.
private const val MODEL_FORWARD_OFFSET = PI.toFloat()
private const val REACTION_NANOS = 3_200_000_000L

// Battle tuning.
private const val BATTLE_REACH = 0.09f
private const val RIVAL_OFFSET_X = 0.30f
private const val BATTLE_DAMAGE = 20

/**
 * AR play screen. The camera feed fills the screen; UI only occupies the edges, so it never hides
 * the world the way an overlay canvas did in the Unity build.
 */
@Composable
fun PlayScreen(
    pet: PetRecord,
    modelFile: File,
    onBond: () -> Unit,
    onExit: () -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // ARCore's HDR environment alone leaves the pets flat in dim rooms; a soft key light gives
    // them shape without blowing out the camera feed.
    val keyLight = remember(engine) {
        LightNode(engine = engine, type = LightManager.Type.DIRECTIONAL) {
            direction(-0.35f, -1f, -0.55f)
            intensity(70_000f)
            castShadows(false)
        }
    }

    val anchors = remember { mutableStateListOf<Anchor>() }
    var frame by remember { mutableStateOf<Frame?>(null) }
    var session by remember { mutableStateOf<com.google.ar.core.Session?>(null) }
    var planeCount by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    // false while the pet sits on the default-plane anchor; once a real horizontal plane
    // converges we re-anchor automatically so the pet stops floating.
    var placedOnPlane by remember { mutableStateOf(false) }
    var fallbackFrames by remember { mutableIntStateOf(0) }
    // Battle staging: split-screen framing first, then the real plane takes over.
    var battleOnPlane by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var battleMenu by remember { mutableStateOf(false) }
    var rivalName by remember { mutableStateOf("对手") }

    val loaded = remember(modelLoader, modelFile.path, modelFile.length()) {
        runCatching { loadModel(modelLoader, modelFile) }
            .onFailure { Log.e("TL", "model load failed", it) }
            .getOrNull()
    }
    val modelMissing = loaded == null
    val animationCount = loaded?.animationCount ?: 0

    // Per-frame runtime (actors, timers). Plain object: mutated 60x/s, never read for
    // recomposition; only the compose states below drive the UI.
    val stage = remember { ArRuntime() }
    var battle by remember { mutableStateOf<BattleRuntime?>(null) }
    var bubble by remember {
        mutableStateOf(
            when {
                modelMissing -> "模型没加载出来，回收藏重新制作一次"
                animationCount > 0 -> "正在找桌面…"
                else -> "这个模型还没有动作，正在找桌面…"
            }
        )
    }
    var reaction by remember { mutableIntStateOf(0) }
    var playingClip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reaction, loaded) {
        if (reaction > 0 && loaded != null) {
            delay(3200)
            if (battle == null) {
                stage.playerActor?.let { actor ->
                    actor.playLoop(actor.pet.defaultClip)
                    playingClip = actor.pet.clipLabel(actor.pet.defaultClip)
                }
            }
        }
    }

    fun startLocalBattle() {
        val player = stage.playerActor
        if (player == null) {
            bubble = "先把手办放到桌面上"
            return
        }
        if (player.pet.attackClip < 0 || player.pet.hurtClip < 0) {
            bubble = "这只手办还没有对战动作，用新模型重新制作一只吧"
            return
        }
        val rival = spawnRival(modelLoader, modelFile, stage)
        if (rival == null) {
            bubble = "对手没加载出来"
            return
        }
        val currentFrame = frame
        val anchorPose = anchors.firstOrNull()?.pose
        battleOnPlane = false
        if (currentFrame != null && anchorPose != null) {
            stageBattleActors(currentFrame, anchorPose, landscape, player, rival)
        }
        val faceoff = if (planeCount > 0) 1_200_000_000L else 5_000_000_000L
        rivalName = "对手"
        battle = BattleRuntime(player, rival) { bubble = it }
            .also { it.start(System.nanoTime(), faceoff) }
        if (planeCount == 0) bubble = "移动平板对准桌面，找到后就在桌上开打"
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            mainLightNode = keyLight,
            planeRenderer = true,
            sessionConfiguration = { sessionConfig, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.focusMode = Config.FocusMode.AUTO
                // Process the newest camera frame instead of queueing stale ones: on slower
                // tablets backpressure delays plane discovery by whole seconds.
                runCatching { config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE }
                // Raw depth (motion or ToF) sharpens plane boundaries where supported.
                runCatching {
                    if (sessionConfig.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
            },
            onSessionUpdated = { active, updated ->
                session = active
                frame = updated
                // Only planes that are tracking, horizontal and large enough to stand on
                // count — PAUSED or sliver-sized planes would make the count lie.
                val usable = usablePlanes(active)
                if (usable.size != planeCount) planeCount = usable.size
                // Auto-attach: the default-plane anchor floats with the camera. Keep scanning
                // after placement and move the pet onto the first real plane. During a battle
                // the plane is handled by moveBattleToPlane instead — re-anchoring would rebuild
                // the node under the battle runtime.
                if (placed && !placedOnPlane && battle == null) {
                    fallbackFrames += 1
                    if (fallbackFrames >= FALLBACK_GRACE_FRAMES && usable.isNotEmpty() &&
                        updated.camera.trackingState == com.google.ar.core.TrackingState.TRACKING
                    ) {
                        val cameraPose = updated.camera.pose
                        val best = usable
                            .filter { planeInFrontOf(cameraPose, it) }
                            .maxByOrNull { it.extentX * it.extentZ }
                        val anchor = best?.let { plane ->
                            // Translation only: heading stays in the node quaternion, so the
                            // plane's own orientation cannot tilt the pet.
                            val c = plane.centerPose
                            translationAnchor(
                                active,
                                com.google.ar.core.Pose.makeTranslation(c.tx(), c.ty(), c.tz()),
                            )
                        }
                        if (anchor != null) {
                            stage.playerActor?.let { stage.pendingHeading = it.headingRad }
                            stage.playerActor = null
                            anchors.forEach { it.detach() }
                            anchors.clear()
                            anchors.add(anchor)
                            placedOnPlane = true
                            bubble = "已贴上平面"
                        }
                    }
                }
                val activeBattleForPlane = battle
                if (activeBattleForPlane != null && !battleOnPlane && usable.isNotEmpty() &&
                    updated.camera.trackingState == com.google.ar.core.TrackingState.TRACKING
                ) {
                    val anchorPose = anchors.firstOrNull()?.pose
                    val plane = usable
                        .filter { planeInFrontOf(updated.camera.pose, it) }
                        .maxByOrNull { it.extentX * it.extentZ }
                    if (anchorPose != null && plane != null) {
                        val player = stage.playerActor
                        val rival = stage.rivalActor
                        if (player != null && rival != null) {
                            moveBattleToPlane(plane, anchorPose, updated.camera.pose, player, rival)
                            battleOnPlane = true
                            bubble = "已转移到桌面上对战"
                        }
                    }
                }
                // Locomotion + battle run on the frame clock.
                val now = System.nanoTime()
                val dt = if (stage.lastFrameNanos == 0L) 0f
                else ((now - stage.lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                stage.lastFrameNanos = now
                stage.playerActor?.applyKnock(dt)
                stage.rivalActor?.applyKnock(dt)
                val activeBattle = battle
                if (activeBattle != null) {
                    activeBattle.update(now, dt)
                } else {
                    stage.playerActor?.let { actor ->
                        if (placed && actor.pet.defaultClip >= 0 && now >= stage.idleUntilNanos) {
                            actor.playLoop(actor.pet.defaultClip)
                            val label = actor.pet.clipLabel(actor.pet.defaultClip)
                            if (playingClip != label) playingClip = label
                        }
                    }
                }
            },
            // SceneView resolves node hits for us: hit != null means the tap landed on the pet.
            onTouchEvent = { event, hit ->
                if (event.actionMasked != android.view.MotionEvent.ACTION_UP) {
                    false
                } else if (hit?.node != null) {
                    if (battle == null) {
                        bubble = "好舒服…"
                        onBond()
                        stage.playerActor?.let { actor ->
                            actor.playOnce(actor.pet.idleClip)
                            playingClip = actor.pet.clipLabel(actor.pet.idleClip)
                            stage.idleUntilNanos = System.nanoTime() + REACTION_NANOS
                            reaction += 1
                        }
                    } else {
                        bubble = "对战进行中，打完再摸～"
                    }
                    true
                } else {
                    val current = frame ?: return@ARSceneView false
                    val active = session ?: return@ARSceneView false
                    val results = current.hitTest(event.x, event.y)
                    // Only a TRACKING plane whose polygon actually contains the hit is a
                    // "table under the finger"; PAUSED planes or hits outside the polygon
                    // would put the pet in mid-air.
                    val planeHit = results.firstOrNull { result ->
                        val plane = result.trackable as? Plane
                        plane != null &&
                            plane.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                            plane.isPoseInPolygon(result.hitPose) &&
                            plane.isPoseInExtents(result.hitPose)
                    } ?: results.firstOrNull { it.trackable is InstantPlacementPoint }

                    val anchor = when {
                        planeHit != null -> translationAnchor(active, planeHit.hitPose)
                        else -> defaultPlaneAnchor(current, active)
                    } ?: return@ARSceneView false

                    // Face the camera when placed: heading is anchor-local, and the default
                    // plane anchor is translation-only, so world yaw == node yaw.
                    val anchorPose = anchor.pose
                    stage.pendingHeading = yawTowards(
                        current.camera.pose.tx() - anchorPose.tx(),
                        current.camera.pose.tz() - anchorPose.tz(),
                    )
                    stage.playerActor = null
                    anchors.forEach { it.detach() }
                    anchors.clear()
                    anchors.add(anchor)
                    placed = true
                    if (planeHit != null) {
                        placedOnPlane = true
                        bubble = "我在这里"
                    } else {
                        placedOnPlane = false
                        fallbackFrames = 0
                        bubble = "先放在默认平面，找到桌面会自动贴上"
                    }
                    true
                }
            },
            content = {
                anchors.forEach { anchor ->
                    AnchorNode(
                        anchor = anchor,
                        apply = {
                            stage.anchorNode = this
                            if (stage.playerActor == null) {
                                loaded?.let { pet ->
                                    // autoAnimate=false: the GLB has Idle and Walk; letting
                                    // SceneView start both would blend two skeletons at once.
                                    val node = ModelNodeImpl(pet.instance, false, null)
                                    node.scale = Float3(pet.scale, pet.scale, pet.scale)
                                    node.position = Float3(0f, pet.lift, 0f)
                                    val actor = PetActor(pet, node)
                                    actor.setHeading(stage.pendingHeading)
                                    actor.playLoop(pet.defaultClip)
                                    playingClip = pet.clipLabel(pet.defaultClip)
                                    stage.playerActor = actor
                                    addChildNode(node)
                                }
                            }
                        }
                    )
                }
            }
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier
                    .clip(capsule())
                    .background(Color(0xCC12060C))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(pet.name, color = Palette.Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(12.dp))
                repeat(5) { index ->
                    Box(
                        Modifier
                            .padding(end = 3.dp)
                            .size(6.dp)
                            .clip(capsule())
                            .background(
                                if (index < pet.bond.coerceIn(0, 5)) Palette.Coral else Color(0x33FFFFFF)
                            )
                    )
                }
            }

            val activeBattle = battle
            if (activeBattle != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HpPill(pet.name, activeBattle.playerHp.intValue, Palette.Coral)
                    Text("VS", color = Palette.Mist, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    HpPill(rivalName, activeBattle.rivalHp.intValue, Color(0xFF8FA7FF))
                }
            }

            Spacer(Modifier.height(12.dp))
            Bubble(bubble)
            if (animationCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "动作 $animationCount 段 · ${playingClip ?: "准备中"}",
                    color = Palette.Mist.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
        }

        // KO result panel: the fight is over, so make it unmistakable instead of leaving
        // only a one-line bubble, and let the player rematch without leaving AR.
        val finishedBattle = battle
        if (finishedBattle != null && finishedBattle.finished) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(300.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF212060C))
                        .padding(horizontal = 26.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (finishedBattle.winnerIsPlayer) "胜利！" else "惜败…",
                        color = Palette.Blossom,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (finishedBattle.winnerIsPlayer) "${pet.name} 赢下了这场对决"
                        else "对手赢下了这场对决，再来一次？",
                        color = Palette.Mist,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(22.dp))
                    PrimaryButton("再来一局", Modifier.width(220.dp)) {
                        val player = stage.playerActor
                        val rival = stage.rivalActor
                        if (player != null && rival != null) {
                            battle = BattleRuntime(player, rival) { bubble = it }
                                .also { it.start(System.nanoTime()) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("结束对战", Modifier.width(220.dp)) {
                        endBattle(stage, modelLoader)
                        battle = null
                        battleOnPlane = false
                        bubble = "对战结束"
                    }
                }
            }
        }

        if (battleMenu) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF212060C))
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("动作对战", color = Palette.Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("开始对战", Modifier.width(220.dp)) {
                        battleMenu = false
                        startLocalBattle()
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("取消", Modifier.width(220.dp)) { battleMenu = false }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!placed) {
                val hint = when {
                    modelMissing -> "模型没加载出来，回收藏重新制作一次"
                    planeCount > 0 -> "已找到桌面（${planeCount} 处），点一下把它放下"
                    else -> "点一下先放到默认平面；对准桌面缓慢平移，会自动贴上真实桌面"
                }
                Text(
                    hint,
                    color = if (planeCount > 0) Palette.Blossom else Palette.Cream,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            } else {
                val hint = when {
                    !placedOnPlane && planeCount > 0 -> "已找到桌面（${planeCount} 处）· 马上自动贴上平面"
                    !placedOnPlane -> "正在扫描桌面… 找到后会自动贴上平面"
                    else -> "点空处可以挪位置，点它本身是摸摸"
                }
                Text(
                    hint,
                    color = Palette.Mist,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Row(
                Modifier
                    .clip(capsule())
                    .background(Color(0xCC12060C))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ActionItem(Icons.Default.Favorite, "摸摸") {
                    if (battle != null) {
                        bubble = "对战进行中，打完再摸～"
                    } else {
                        bubble = "好舒服…"
                        onBond()
                        stage.playerActor?.let { actor ->
                            actor.playOnce(actor.pet.idleClip)
                            playingClip = actor.pet.clipLabel(actor.pet.idleClip)
                            stage.idleUntilNanos = System.nanoTime() + REACTION_NANOS
                            reaction += 1
                        }
                    }
                }
                ActionItem(Icons.Default.Star, "喂食") {
                    if (battle != null) {
                        bubble = "对战进行中，打完再吃～"
                    } else {
                        bubble = "再来一口！"
                        onBond()
                        stage.playerActor?.let { actor ->
                            actor.playOnce(actor.pet.idleClip)
                            playingClip = actor.pet.clipLabel(actor.pet.idleClip)
                            stage.idleUntilNanos = System.nanoTime() + REACTION_NANOS
                            reaction += 1
                        }
                    }
                }
                ActionItem(Icons.Default.PlayArrow, if (battle == null) "对战" else "停战") {
                    if (battle == null) {
                        battleMenu = true
                    } else {
                        endBattle(stage, modelLoader)
                        battle = null
                        battleOnPlane = false
                        bubble = "对战结束"
                    }
                }
                ActionItem(Icons.Default.Place, "挪一挪") {
                    bubble = "点一下新位置"
                }
                ActionItem(Icons.Default.List, "收藏", onExit)
            }
        }
    }
}

/**
 * Default plane anchor: on the camera's centre ray at a fixed distance.
 *
 * ARCore needs camera motion to triangulate planes, so a device held still can stay at "0 planes"
 * indefinitely. Instead of parking the pet somewhere awkward while waiting, treat the centre-ray
 * point as a virtual plane — the pet is always centred, upright and the same apparent size, which
 * makes the debug view deterministic. The real plane takes over via auto-attach.
 */
private fun defaultPlaneAnchor(frame: Frame, session: com.google.ar.core.Session): Anchor? {
    val pose = frame.camera.pose
    val forward = cameraForward(pose)
    val position = floatArrayOf(
        pose.tx() + forward[0] * DEFAULT_PLANE_DISTANCE,
        pose.ty() + forward[1] * DEFAULT_PLANE_DISTANCE,
        pose.tz() + forward[2] * DEFAULT_PLANE_DISTANCE,
    )
    return translationAnchor(session, com.google.ar.core.Pose.makeTranslation(position[0], position[1], position[2]))
}

/** Anchors are translation-only so the pet's heading lives in one place: the node quaternion. */
private fun translationAnchor(session: com.google.ar.core.Session, pose: com.google.ar.core.Pose): Anchor? =
    runCatching {
        session.createAnchor(com.google.ar.core.Pose.makeTranslation(pose.tx(), pose.ty(), pose.tz()))
    }.getOrNull()

/**
 * Camera forward direction in world space: (0,0,-1) rotated by the camera quaternion. Hand-rolled
 * because Pose helpers collide with the math library imported for Float3.
 */
private fun cameraForward(pose: com.google.ar.core.Pose): FloatArray {
    val q = pose.rotationQuaternion
    val x = q[0]
    val y = q[1]
    val z = q[2]
    val w = q[3]
    return floatArrayOf(
        -2f * (x * z + w * y),
        -2f * (y * z - w * x),
        -(1f - 2f * (x * x + y * y)),
    )
}

/** Camera right / up axes in world space, used to frame battle actors in screen space. */
private fun cameraRight(pose: com.google.ar.core.Pose): FloatArray {
    val q = pose.rotationQuaternion
    val x = q[0]
    val y = q[1]
    val z = q[2]
    val w = q[3]
    return floatArrayOf(
        1f - 2f * (y * y + z * z),
        2f * (x * y + w * z),
        2f * (x * z - w * y),
    )
}

private fun cameraUp(pose: com.google.ar.core.Pose): FloatArray {
    val q = pose.rotationQuaternion
    val x = q[0]
    val y = q[1]
    val z = q[2]
    val w = q[3]
    return floatArrayOf(
        2f * (x * y - w * z),
        1f - 2f * (x * x + z * z),
        2f * (y * z + w * x),
    )
}

/**
 * Split-screen staging before any plane exists: the player's pet is placed bottom-left and the
 * enemy top-right in landscape (bottom / top in portrait). Positions are computed on camera rays,
 * then converted to anchor-local coordinates; the anchor itself is translation-only.
 */
private fun stageBattleActors(
    frame: Frame,
    anchorPose: com.google.ar.core.Pose,
    landscape: Boolean,
    player: PetActor,
    rival: PetActor,
) {
    val cam = frame.camera.pose
    val forward = cameraForward(cam)
    val right = cameraRight(cam)
    val up = cameraUp(cam)
    val side = if (landscape) 0.33f else 0f
    val lift = if (landscape) 0.16f else 0.26f
    fun world(distance: Float, sx: Float, sy: Float): FloatArray = floatArrayOf(
        cam.tx() + forward[0] * distance + right[0] * sx + up[0] * sy,
        cam.ty() + forward[1] * distance + right[1] * sx + up[1] * sy,
        cam.tz() + forward[2] * distance + right[2] * sx + up[2] * sy,
    )
    val playerWorld = world(0.75f, -side, -lift)
    val rivalWorld = world(0.95f, side, lift)
    player.placeAt(
        playerWorld[0] - anchorPose.tx(),
        playerWorld[1] - anchorPose.ty() + player.pet.lift,
        playerWorld[2] - anchorPose.tz(),
    )
    rival.placeAt(
        rivalWorld[0] - anchorPose.tx(),
        rivalWorld[1] - anchorPose.ty() + rival.pet.lift,
        rivalWorld[2] - anchorPose.tz(),
    )
}

/** Moves an in-progress battle onto a real plane, both pets standing on it 30 cm apart. */
private fun moveBattleToPlane(
    plane: Plane,
    anchorPose: com.google.ar.core.Pose,
    cameraPose: com.google.ar.core.Pose,
    player: PetActor,
    rival: PetActor,
) {
    val center = plane.centerPose
    val right = cameraRight(cameraPose)
    player.placeAt(
        center.tx() - anchorPose.tx() - right[0] * 0.15f,
        center.ty() - anchorPose.ty() + player.pet.lift,
        center.tz() - anchorPose.tz() - right[2] * 0.15f,
    )
    rival.placeAt(
        center.tx() - anchorPose.tx() + right[0] * 0.15f,
        center.ty() - anchorPose.ty() + rival.pet.lift,
        center.tz() - anchorPose.tz() + right[2] * 0.15f,
    )
    player.faceTowards(rival.node.position.x, rival.node.position.z)
    rival.faceTowards(player.node.position.x, player.node.position.z)
}

/** Planes a pet can actually stand on: tracking, horizontal, and at least 20 x 20 cm. */
private fun usablePlanes(session: com.google.ar.core.Session): List<Plane> =
    runCatching {
        session.getAllTrackables(Plane::class.java).filter { plane ->
            plane.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.extentX * plane.extentZ >= MIN_PLANE_AREA
        }
    }.getOrDefault(emptyList())

/** The plane must be roughly in front of the camera and within arm's reach to auto-attach. */
private fun planeInFrontOf(cameraPose: com.google.ar.core.Pose, plane: Plane): Boolean {
    val forward = cameraForward(cameraPose)
    val dx = plane.centerPose.tx() - cameraPose.tx()
    val dy = plane.centerPose.ty() - cameraPose.ty()
    val dz = plane.centerPose.tz() - cameraPose.tz()
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    if (distance > MAX_ANCHOR_DISTANCE) return false
    return forward[0] * dx + forward[1] * dy + forward[2] * dz > 0f
}

/** Yaw (radians) so that the model's -Z forward points along (dx, dz). */
private fun yawTowards(dx: Float, dz: Float): Float = atan2(-dx, -dz)

private fun headingQuat(radians: Float): Quaternion {
    val half = radians / 2f
    return Quaternion(0f, sin(half), 0f, cos(half))
}

private fun quatMul(a: Quaternion, b: Quaternion): Quaternion = Quaternion(
    a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
)

/**
 * Per-frame runtime. Not compose state: mutated on every AR frame, never read for
 * recomposition. Actors are dropped and rebuilt when the anchor changes.
 */
private class ArRuntime {
    var lastFrameNanos = 0L
    var idleUntilNanos = 0L
    var pendingHeading = 0f
    var anchorNode: AnchorNode? = null
    var playerActor: PetActor? = null
    var rivalActor: PetActor? = null
}

/**
 * One instance of a pet model on stage: owns its heading (world yaw), its walking speed
 * (scaled from the authored human pace so the stride cadence matches) and its clips.
 */
private class PetActor(val pet: LoadedPet, val node: ModelNodeImpl) {
    val animator = pet.instance.animator
    var headingRad = 0f
    private val baseQuat = node.quaternion
    private val walkSpeed = PET_WALK_SPEED * pet.scale
    private var knock = Float3(0f, 0f, 0f)
    private var lastClip = Int.MIN_VALUE
    private var homeX = node.position.x
    private var homeY = node.position.y
    private var homeZ = node.position.z

    init {
        setHeading(0f)
    }

    fun setHeading(radians: Float) {
        headingRad = radians
        node.quaternion = quatMul(headingQuat(radians + MODEL_FORWARD_OFFSET), baseQuat)
    }

    fun faceTowards(tx: Float, tz: Float) {
        setHeading(yawTowards(tx - node.position.x, tz - node.position.z))
    }

    /** Smooth heading change; the snap version is only used at placement. */
    fun turnTowards(tx: Float, tz: Float, dt: Float) {
        val target = yawTowards(tx - node.position.x, tz - node.position.z)
        var diff = target - headingRad
        val twoPi = 2f * PI.toFloat()
        while (diff > PI.toFloat()) diff -= twoPi
        while (diff < -PI.toFloat()) diff += twoPi
        setHeading(headingRad + diff * min(1f, 10f * dt))
    }

    fun advance(dt: Float) {
        val fx = -sin(headingRad)
        val fz = -cos(headingRad)
        node.position = Float3(
            node.position.x + fx * walkSpeed * dt,
            node.position.y,
            node.position.z + fz * walkSpeed * dt,
        )
    }

    /** Walk in a smooth circle: continuous turn, no turning animation needed. */
    fun patrol(dt: Float) {
        setHeading(headingRad + PET_TURN_RATE * dt)
        advance(dt)
    }

    fun distanceTo(other: PetActor): Float {
        val dx = node.position.x - other.node.position.x
        val dz = node.position.z - other.node.position.z
        return sqrt(dx * dx + dz * dz)
    }

    /** Move the spawn spot (battle framing / plane transfer) and teleport there. */
    fun placeAt(x: Float, y: Float, z: Float) {
        homeX = x
        homeY = y
        homeZ = z
        node.position = Float3(x, y, z)
        knock = Float3(0f, 0f, 0f)
    }

    /** Back to the spawn spot, used at the start of every battle round. */
    fun resetHome() {
        node.position = Float3(homeX, homeY, homeZ)
        knock = Float3(0f, 0f, 0f)
    }

    fun knockBack(dirX: Float, dirZ: Float, strength: Float = 0.35f) {
        knock = Float3(dirX * strength, 0f, dirZ * strength)
    }

    fun applyKnock(dt: Float) {
        if (knock.x == 0f && knock.z == 0f) return
        node.position = Float3(
            node.position.x + knock.x * dt,
            node.position.y,
            node.position.z + knock.z * dt,
        )
        val decay = (1f - 6f * dt).coerceAtLeast(0f)
        knock = Float3(knock.x * decay, 0f, knock.z * decay)
    }

    fun playLoop(index: Int) {
        if (index < 0 || lastClip == index) return
        playOnce(index)
    }

    fun playOnce(index: Int) {
        if (index < 0) return
        playOnly(node, pet, index)
        lastClip = index
    }

    /** Looping walk clip guard: Approach calls this per frame; restart would stutter. */
    fun ensureWalk() = playLoop(pet.walkClip)

    fun clipLabel(): String = pet.clipLabel(lastClip.coerceAtLeast(0))
}

/**
 * Two-pet auto-battle. The choreography runs on the AR frame clock: face off, walk into
 * reach, trade Punch_Jab / Hit_Chest (with knockback), and finish with Death01 on the KO.
 */
private class BattleRuntime(
    private val player: PetActor,
    private val rival: PetActor,
    private val onBubble: (String) -> Unit,
) {
    val playerHp = mutableIntStateOf(100)
    val rivalHp = mutableIntStateOf(100)
    var finished = false
    var winnerIsPlayer = false

    private enum class Phase { FACEOFF, APPROACH, ATTACK, RECOVER, KO }
    private var phase = Phase.FACEOFF
    private var attacker = player
    private var defender = rival
    private var deadline = 0L
    private var hitAt = 0L
    private var hitApplied = false
    private var attackDuration = 0f

    fun start(now: Long, faceoffNanos: Long = 1_200_000_000L) {
        // Fixed spawn spots and a face-off beat make the fight readable: both pets reset
        // to where they started, turn to each other, then close in. Without a plane the
        // standoff runs longer so the player has time to pan the device.
        player.resetHome()
        rival.resetHome()
        player.faceTowards(rival.node.position.x, rival.node.position.z)
        rival.faceTowards(player.node.position.x, player.node.position.z)
        phase = Phase.FACEOFF
        deadline = now + faceoffNanos
        player.playLoop(player.pet.idleClip)
        rival.playLoop(rival.pet.idleClip)
        onBubble("对战开始！")
    }

    fun update(now: Long, dt: Float) {
        if (phase == Phase.KO) return
        player.applyKnock(dt)
        rival.applyKnock(dt)
        when (phase) {
            Phase.FACEOFF -> {
                player.turnTowards(rival.node.position.x, rival.node.position.z, dt)
                rival.turnTowards(player.node.position.x, player.node.position.z, dt)
                if (now >= deadline) phase = Phase.APPROACH
            }
            Phase.APPROACH -> {
                player.turnTowards(rival.node.position.x, rival.node.position.z, dt)
                rival.turnTowards(player.node.position.x, player.node.position.z, dt)
                player.ensureWalk()
                rival.ensureWalk()
                if (player.distanceTo(rival) <= BATTLE_REACH) {
                    player.playLoop(player.pet.idleClip)
                    rival.playLoop(rival.pet.idleClip)
                    beginAttack(now)
                } else {
                    player.advance(dt)
                    rival.advance(dt)
                }
            }
            Phase.ATTACK -> {
                if (!hitApplied && now >= hitAt) applyHit(now)
                if (phase == Phase.ATTACK && now >= deadline) beginRecover(now)
            }
            Phase.RECOVER -> if (now >= deadline) swapTurn()
            Phase.KO -> {}
        }
    }

    private fun beginAttack(now: Long) {
        phase = Phase.ATTACK
        hitApplied = false
        val clip = attacker.pet.attackClip
        attackDuration = attacker.animator.getAnimationDuration(clip).takeIf { it > 0.05f } ?: 0.8f
        attacker.playOnce(clip)
        defender.playLoop(defender.pet.idleClip)
        hitAt = now + (attackDuration * 0.55f * 1_000_000_000L).toLong()
        deadline = now + (attackDuration * 1_000_000_000L).toLong()
        onBubble(if (attacker === player) "你的手办 出手！" else "对手 出手！")
    }

    private fun applyHit(now: Long) {
        hitApplied = true
        val dx = defender.node.position.x - attacker.node.position.x
        val dz = defender.node.position.z - attacker.node.position.z
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
        defender.knockBack(dx / len, dz / len)
        val hp = when (attacker) {
            player -> {
                rivalHp.intValue = (rivalHp.intValue - BATTLE_DAMAGE).coerceAtLeast(0)
                rivalHp.intValue
            }
            else -> {
                playerHp.intValue = (playerHp.intValue - BATTLE_DAMAGE).coerceAtLeast(0)
                playerHp.intValue
            }
        }
        if (hp <= 0) {
            ko(winnerIsPlayer = attacker === player)
            return
        }
        defender.playOnce(defender.pet.hurtClip)
        attacker.playLoop(attacker.pet.idleClip)
        deadline = now + ((attackDuration * 0.5f + 0.45f) * 1_000_000_000L).toLong()
        phase = Phase.RECOVER
        onBubble(
            if (attacker === player) "对手 受击 -$BATTLE_DAMAGE" else "你的手办 受击 -$BATTLE_DAMAGE"
        )
    }

    private fun beginRecover(now: Long) {
        phase = Phase.RECOVER
        attacker.playLoop(attacker.pet.idleClip)
        deadline = now + 600_000_000L
    }

    private fun swapTurn() {
        attacker = if (attacker === player) rival else player
        defender = if (attacker === player) rival else player
        phase = Phase.APPROACH
    }

    private fun ko(winnerIsPlayer: Boolean) {
        phase = Phase.KO
        finished = true
        this.winnerIsPlayer = winnerIsPlayer
        val winner = if (winnerIsPlayer) player else rival
        val loser = if (winnerIsPlayer) rival else player
        if (loser.pet.deathClip >= 0) loser.playOnce(loser.pet.deathClip) else loser.playOnce(loser.pet.hurtClip)
        winner.playLoop(winner.pet.idleClip)
        onBubble(if (winnerIsPlayer) "你的手办 胜利！" else "对手 获胜…")
    }
}

/** Loads a second copy of the model as the battle rival (one glTF instance per asset). */
private fun spawnRival(loader: ModelLoader, file: File, stage: ArRuntime): PetActor? {
    stage.rivalActor?.let { return it }
    val rivalPet = runCatching { loadModel(loader, file) }
        .onFailure { Log.e("TL", "rival load failed", it) }
        .getOrNull() ?: return null
    val node = ModelNodeImpl(rivalPet.instance, false, null)
    node.scale = Float3(rivalPet.scale, rivalPet.scale, rivalPet.scale)
    node.position = Float3(RIVAL_OFFSET_X, rivalPet.lift, 0f)
    val actor = PetActor(rivalPet, node)
    actor.faceTowards(0f, 0f)
    actor.playLoop(rivalPet.idleClip)
    stage.anchorNode?.addChildNode(node)
    stage.rivalActor = actor
    return actor
}

private fun endBattle(stage: ArRuntime, loader: ModelLoader) {
    stage.rivalActor?.let { rival ->
        stage.anchorNode?.removeChildNode(rival.node)
        runCatching { loader.destroyModel(rival.pet.instance.asset) }
    }
    stage.rivalActor = null
    stage.idleUntilNanos = 0L
    stage.playerActor?.let { actor ->
        actor.playLoop(actor.pet.defaultClip)
    }
}

@Composable
private fun HpPill(name: String, hp: Int, tint: Color) {
    Row(
        Modifier
            .clip(capsule())
            .background(Color(0xCC12060C))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = Palette.Cream, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(56.dp)
                .height(5.dp)
                .clip(capsule())
                .background(Color(0x22FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(hp / 100f)
                    .height(5.dp)
                    .clip(capsule())
                    .background(tint)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$hp", color = Palette.Mist, fontSize = 11.sp)
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(capsule())
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Palette.Cream, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = Palette.Mist, fontSize = 10.sp)
    }
}

/** A model plus the numbers needed to stand it on the anchor rather than sink it. */
private class LoadedPet(
    val instance: com.google.android.filament.gltfio.FilamentInstance,
    val scale: Float,
    val lift: Float,
    val animationCount: Int,
    val idleClip: Int,
    val walkClip: Int,
    val defaultClip: Int,
    val attackClip: Int,
    val hurtClip: Int,
    val deathClip: Int,
    val animationNames: List<String>,
) {
    fun clipLabel(index: Int): String = animationNames.getOrNull(index) ?: "clip $index"
}

/**
 * Plays exactly one clip. SceneView keeps a map of playing animations, so
 * starting a second one without stopping the first blends two skeletons.
 */
private fun playOnly(node: ModelNodeImpl, pet: LoadedPet, index: Int) {
    if (pet.animationCount == 0 || index < 0) return
    for (other in 0 until pet.animationCount) {
        if (other != index) node.stopAnimation(other)
    }
    node.playAnimation(index, 1f, true)
}

/**
 * Loads a GLB off disk and works out its scale and vertical lift.
 *
 * glTF nodes are usually authored around the geometric centre, so anchoring at the origin buries
 * the lower half in the floor. Taking the asset's bounding box lets the node be lifted by exactly
 * the distance from its lowest point to y = 0 after scaling.
 */
private fun loadModel(loader: ModelLoader, file: File): LoadedPet? {
    if (!file.exists()) return null
    return loadModel(loader, file.readBytes())
}

/** Same as [loadModel] but for a peer pet fetched from the battle room. */
private fun loadModel(loader: ModelLoader, bytes: ByteArray): LoadedPet? {
    val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        rewind()
    }
    // Keep the source buffer alive: releasing it before the animator binds can
    // leave the instance with zero animations even though the GLB defines them.
    val asset = loader.createModel(buffer, false) ?: return null
    val box = asset.boundingBox
    val half = box.halfExtent
    val center = box.center
    val maxDim = maxOf(half[0], half[1], half[2]).coerceAtLeast(1e-5f) * 2f
    val scale = 0.12f / maxDim
    val bottom = center[1] - half[1]

    // Lux3D retarget exports clips as Idle_Loop / Walk_Loop / Punch_Jab / Hit_Chest / Death01.
    // Fall back to the first clip for older local-rig models with one unnamed animation.
    val animator = asset.instance.animator
    val count = animator.animationCount
    val names = (0 until count).map { animator.getAnimationName(it) ?: "" }
    val idle = names.indexOfFirst { it.contains("idle", ignoreCase = true) }
    val walk = names.indexOfFirst { it.contains("walk", ignoreCase = true) }
    val default = when {
        idle >= 0 -> idle
        walk >= 0 -> walk
        count > 0 -> 0
        else -> -1
    }
    val attack = names.indexOfFirst { it.contains("punch", true) || it.contains("attack", true) }
    val hurt = names.indexOfFirst { it.contains("hit", true) || it.contains("hurt", true) }
    val death = names.indexOfFirst { it.contains("death", true) }
    return LoadedPet(
        instance = asset.instance,
        scale = scale,
        lift = -bottom * scale,
        animationCount = count,
        idleClip = if (idle >= 0) idle else default,
        walkClip = if (walk >= 0) walk else default,
        defaultClip = default,
        attackClip = attack,
        hurtClip = hurt,
        deathClip = death,
        animationNames = names,
    )
}
