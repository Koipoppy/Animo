package com.animo.ar.ui

import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
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
import com.animo.ar.data.Lux3dPipeline
import com.animo.ar.data.PetRecord
import com.animo.ar.data.sanitizeGlb
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
// Lux3D exports face +Z while the heading math assumes -Z; the half-turn makes the model's
// face, not its back, point toward the camera on placement.
private const val MODEL_FORWARD_OFFSET = PI.toFloat()
private const val REACTION_NANOS = 3_200_000_000L

// Baseline camera-facing fill: on by default, this is the old "补光" look.
private const val CAMERA_FILL_INTENSITY = 30_000f
// Optional virtual flashlight: a spot light whose origin is the AR camera.
private const val CAMERA_LIGHT_INTENSITY = 12_000f

// Concentric-ring "Live Photo" mark, the familiar motion-photo glyph.
private val RecordDot: ImageVector = ImageVector.Builder(
    name = "LivePhoto",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = PathData {
            moveTo(12f, 3.2f)
            arcTo(8.8f, 8.8f, 0f, true, true, 12f, 20.8f)
            arcTo(8.8f, 8.8f, 0f, true, true, 12f, 3.2f)
            close()
        },
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.5f,
    )
    addPath(
        pathData = PathData {
            moveTo(12f, 6.6f)
            arcTo(5.4f, 5.4f, 0f, true, true, 12f, 17.4f)
            arcTo(5.4f, 5.4f, 0f, true, true, 12f, 6.6f)
            close()
        },
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.5f,
    )
    addPath(
        pathData = PathData {
            moveTo(12f, 9.8f)
            arcTo(2.2f, 2.2f, 0f, true, true, 12f, 14.2f)
            arcTo(2.2f, 2.2f, 0f, true, true, 12f, 9.8f)
            close()
        },
        fill = SolidColor(Color.White),
    )
}.build()

// The only clips Lux3D retargets; the menu is filtered against this list.
private val API_CLIP_LABELS = mapOf(
    "Idle_Loop" to "待机",
    "Walk_Loop" to "走路",
    "Jog_Fwd_Loop" to "小跑",
    "Dance_Loop" to "跳舞",
    "Yes" to "点头",
    "Jump_Start" to "跳跃",
    "Sitting_Idle_Loop" to "坐下",
    "Punch_Jab" to "出拳",
    "Sword_Attack" to "挥剑",
    "Hit_Chest" to "受击",
    "Death01" to "倒地",
)
private val LOOPING_CLIPS = setOf(
    "Idle_Loop",
    "Walk_Loop",
    "Jog_Fwd_Loop",
    "Dance_Loop",
    "Sitting_Idle_Loop",
)

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
    cameraLight: Boolean,
    onToggleCameraLight: (Boolean) -> Unit,
    onExit: () -> Unit,
    onGenerateClip: (String) -> Unit = {},
    clipJob: String? = null,
    clipStage: String = "",
    clipProgress: Float = 0f,
    clipError: String? = null,
    onExportModel: () -> Unit = {},
    onShareModel: (Boolean) -> Unit = {},
    recording: Boolean = false,
    onRecord: () -> Unit = {},
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
    var arReady by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<com.google.ar.core.Session?>(null) }
    var planeCount by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    // false while the pet sits on the default-plane anchor; once a real horizontal plane
    // converges we re-anchor automatically so the pet stops floating.
    var placedOnPlane by remember { mutableStateOf(false) }
    var fallbackFrames by remember { mutableIntStateOf(0) }
    // Battle staging stays in the runtime for plane transfer, but the HUD no longer exposes it.
    var battleOnPlane by remember { mutableStateOf(false) }
    var flashlight by remember { mutableStateOf(cameraLight) }
    var clipMenu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var adjustMode by remember { mutableStateOf(false) }
    val cameraFillRef = remember { LightSlot() }
    val cameraLightRef = remember { LightSlot() }
    val adjustModeState = rememberUpdatedState(adjustMode)
    LaunchedEffect(flashlight) {
        cameraLightRef.node?.intensity = if (flashlight) CAMERA_LIGHT_INTENSITY else 0f
    }

    val loaded = remember(modelLoader, pet.id, modelFile.path, modelFile.length()) {
        runCatching { loadModel(modelLoader, modelFile) }
            .onFailure { Log.e("TL", "model load failed", it) }
            .getOrNull()
    }
    val modelMissing = loaded == null
    val animationCount = loaded?.animationCount ?: 0

    // Only the clips Lux3D actually documents and retargets: the menu never invents animations.
    val apiClips = remember(loaded) {
        val names = loaded?.animationNames.orEmpty()
        Lux3dPipeline.ANIMATION_PRESETS.mapNotNull { id ->
            val index = names.indexOfFirst { it.equals(id, ignoreCase = true) }
            if (index >= 0) {
                ApiClip(
                    id = id,
                    label = API_CLIP_LABELS[id] ?: id,
                    index = index,
                    loop = id in LOOPING_CLIPS,
                )
            } else {
                null
            }
        }
    }

    val presentClipIds = remember(apiClips) { apiClips.map { it.id }.toSet() }
    val missingClips = remember(presentClipIds) {
        Lux3dPipeline.ANIMATION_PRESETS.filterNot { presentClipIds.contains(it) }
    }

    // Per-frame runtime (actors, timers). Plain object: mutated 60x/s, never read for
    // recomposition; only the compose states below drive the UI.
    val stage = remember { ArRuntime() }
    var battle by remember { mutableStateOf<BattleRuntime?>(null) }
    var bubble by remember {
        mutableStateOf(
            when {
                modelMissing -> "模型没加载出来，回手办管理重新制作一次"
                animationCount > 0 -> "正在找桌面…"
                else -> "这个模型还没有动作，正在找桌面…"
            }
        )
    }
    var playingClip by remember { mutableStateOf<String?>(null) }

    fun selectClip(clip: ApiClip) {
        clipMenu = false
        val actor = stage.playerActor
        if (actor == null) {
            bubble = "先把手办放到桌面上，再切换动作"
            return
        }
        stage.idleUntilNanos = 0L
        stage.manualClip = clip.index
        if (clip.loop) {
            stage.oneShotUntilNanos = 0L
            actor.playLoop(clip.index)
        } else {
            val duration = actor.animator.getAnimationDuration(clip.index).takeIf { it > 0.05f } ?: 1f
            actor.playClip(clip.index, loop = false)
            stage.oneShotUntilNanos = System.nanoTime() + (duration * 1_000_000_000L).toLong()
        }
        playingClip = clip.id
        bubble = "动作：${clip.label}"
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
                if (!arReady) arReady = true
                // Baseline: a soft directional fill that always follows the camera ray.
                val cameraPose = updated.camera.pose
                val forward = cameraForward(cameraPose)
                cameraFillRef.node?.lightDirection = Float3(forward[0], forward[1], forward[2])

                // Optional flashlight: a real spot light positioned at the camera, aimed at the
                // placed figure (falling back to the camera ray when nothing is out yet). Its
                // transform is refreshed even while off so enabling it never flashes from origin.
                val camX = cameraPose.tx()
                val camY = cameraPose.ty()
                val camZ = cameraPose.tz()
                val target = stage.playerActor?.node?.worldPosition
                val dx: Float
                val dy: Float
                val dz: Float
                if (target != null) {
                    dx = target.x - camX
                    dy = target.y + 0.06f - camY
                    dz = target.z - camZ
                } else {
                    dx = forward[0]
                    dy = forward[1]
                    dz = forward[2]
                }
                val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
                cameraLightRef.node?.lightPosition = Float3(camX, camY, camZ)
                cameraLightRef.node?.lightDirection = Float3(
                    dx / length,
                    dy / length,
                    dz / length,
                )
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
                            stage.playerActor?.let {
                                stage.pendingHeading = it.headingRad
                                stage.pendingScale = it.userScale
                            }
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
                // One-shot clips (Punch_Jab / Hit_Chest / Death01) return to Idle when finished.
                if (stage.oneShotUntilNanos > 0L && now >= stage.oneShotUntilNanos) {
                    stage.oneShotUntilNanos = 0L
                    stage.manualClip = -1
                    stage.playerActor?.let { actor ->
                        actor.playLoop(actor.pet.defaultClip)
                        playingClip = actor.pet.clipLabel(actor.pet.defaultClip)
                    }
                }
                val activeBattle = battle
                if (activeBattle != null) {
                    activeBattle.update(now, dt)
                } else {
                    stage.playerActor?.let { actor ->
                        if (placed && actor.pet.defaultClip >= 0 && now >= stage.idleUntilNanos &&
                            stage.manualClip < 0
                        ) {
                            actor.playLoop(actor.pet.defaultClip)
                            val label = actor.pet.clipLabel(actor.pet.defaultClip)
                            if (playingClip != label) playingClip = label
                        }
                    }
                }
            },
            // SceneView resolves node hits for us: hit != null means the tap landed on the pet.
            onTouchEvent = { event, hit ->
                val gestureActor = stage.playerActor
                // Two fingers in adjust mode scale the figure and turn it around the plane
                // normal (world Y, because only horizontal planes are tracked).
                if (adjustModeState.value && gestureActor != null && event.pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val distance = sqrt(dx * dx + dy * dy)
                    val angle = atan2(dy, dx)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            stage.gestureDistance = distance
                            stage.gestureAngle = angle
                            stage.suppressTap = true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (stage.gestureDistance > 1f) {
                                gestureActor.scaleBy(distance / stage.gestureDistance)
                                var delta = angle - stage.gestureAngle
                                while (delta > PI.toFloat()) delta -= (2f * PI).toFloat()
                                while (delta < -PI.toFloat()) delta += (2f * PI).toFloat()
                                if (abs(delta) > 1e-4f) {
                                    gestureActor.setHeading(gestureActor.headingRad - delta)
                                }
                            }
                            stage.gestureDistance = distance
                            stage.gestureAngle = angle
                            stage.suppressTap = true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            stage.gestureDistance = 0f
                            stage.gestureAngle = 0f
                            stage.suppressTap = true
                        }
                    }
                    return@ARSceneView true
                }
                // The finger left after a pinch must not be mistaken for a placement tap.
                if (event.actionMasked == MotionEvent.ACTION_UP && stage.suppressTap) {
                    stage.suppressTap = false
                    stage.gestureDistance = 0f
                    return@ARSceneView true
                }
                if (event.actionMasked != MotionEvent.ACTION_UP) {
                    false
                } else if (hit?.node != null) {
                    // The figure itself is no longer a "pet me" target; consume the tap.
                    true
                } else {
                    // Repositioning is a deliberate mode: with 挪一挪 off, an empty-space tap
                    // must not yank the figure away from where the user put it.
                    if (placed && !adjustModeState.value) return@ARSceneView false
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
                    stage.playerActor?.let { stage.pendingScale = it.userScale }
                    stage.manualClip = -1
                    stage.oneShotUntilNanos = 0L
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
                // Baseline fill: always on, follows the camera direction every frame.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    intensity = CAMERA_FILL_INTENSITY,
                    direction = Float3(0f, 0f, -1f),
                    nodeApply = { cameraFillRef.node = this },
                )
                // Optional flashlight: a spot light whose origin tracks the camera position.
                // Keeping the node alive with zero intensity avoids rebuilding the AR graph.
                LightNode(
                    type = LightManager.Type.SPOT,
                    intensity = if (flashlight) CAMERA_LIGHT_INTENSITY else 0f,
                    direction = Float3(0f, 0f, -1f),
                    position = Float3(0f, 0f, 0f),
                    apply = {
                        spotLightCone(0.32f, 0.60f)
                        falloff(1.5f)
                    },
                    nodeApply = { cameraLightRef.node = this },
                )
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
                                    actor.setUserScale(stage.pendingScale)
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

        if (!arReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "正在打开相机…",
                    color = Palette.Cream,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .liquidGlass(
                            capsule(),
                            base = Color(0xFF100D12),
                            strength = 0.95f,
                            elevated = false,
                        )
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }

        if (!recording) {
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

        // Exit lives in the top-left corner; the bottom bar is only for pet actions.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(start = 16.dp, top = 10.dp)
        ) {
            RoundButton(
                Icons.Default.ArrowBack,
                "退出",
                46.dp,
                onClick = onExit,
                fill = Color(0xCC160B13),
            )
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(end = 16.dp, top = 10.dp)
        ) {
            RoundButton(
                Icons.Default.Share,
                "分享",
                46.dp,
                onClick = { shareMenu = true },
                fill = Color(0xCC160B13),
            )
        }

        if (shareMenu) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF212060C))
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("分享手办", color = Palette.Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    GhostButton("分享手办", Modifier.fillMaxWidth()) {
                        shareMenu = false
                        onShareModel(false)
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("录制 6 秒动态照片", Modifier.fillMaxWidth()) {
                        shareMenu = false
                        onRecord()
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("关闭", Modifier.fillMaxWidth()) { shareMenu = false }
                }
            }
        }

        if (clipMenu) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(330.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF212060C))
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("切换动作", color = Palette.Cream, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "选择要播放的动作，或按需生成新动作",
                        color = Palette.Mist,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    // Only this list scrolls; the card, title and close button stay fixed.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (apiClips.isEmpty()) {
                            Text(
                                "这个模型还没有动作，可以在下方生成",
                                color = Palette.Mist,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                        apiClips.forEach { clip ->
                            Chip(
                                text = "${clip.label} · ${clip.id}",
                                active = playingClip == clip.id,
                                modifier = Modifier.fillMaxWidth(),
                            ) { selectClip(clip) }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (pet.rigPending) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "走路动作还在后台生成，完成后可继续生成其他动作",
                                color = Palette.Mist,
                                fontSize = 11.sp,
                            )
                        }
                        if (missingClips.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "生成更多动作",
                                color = Palette.Cream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            missingClips.forEach { id ->
                                val busy = clipJob == id
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(capsule())
                                        .background(
                                            if (busy) Palette.Blossom.copy(alpha = 0.16f)
                                            else Color.White.copy(alpha = 0.06f)
                                        )
                                        .clickable(enabled = clipJob == null && !pet.rigPending) {
                                            onGenerateClip(id)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        API_CLIP_LABELS[id] ?: id,
                                        color = Palette.Cream,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (busy) "生成中…" else "生成",
                                        color = if (busy) Palette.Blossom else Palette.Mist,
                                        fontSize = 11.sp,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            if (clipJob != null) {
                                Text(
                                    "$clipStage ${(clipProgress * 100).toInt()}%",
                                    color = Palette.Mist,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            clipError?.let {
                                Text(it, color = Palette.Coral, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("关闭", Modifier.fillMaxWidth()) { clipMenu = false }
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
                    modelMissing -> "模型没加载出来，回手办管理重新制作一次"
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
                    adjustMode -> "双指缩放 / 绕桌面旋转 · 点空处挪位置"
                    !placedOnPlane && planeCount > 0 -> "已找到桌面（${planeCount} 处）· 马上自动贴上平面"
                    !placedOnPlane -> "正在扫描桌面… 找到后会自动贴上平面"
                    else -> "点空处可以挪位置"
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
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(capsule())
                    .background(Color(0xE612060C))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionItem(RecordDot, "动态照片", modifier = Modifier.weight(1f)) {
                    onRecord()
                }
                ActionItem(
                    Icons.Default.PlayArrow,
                    "动作",
                    modifier = Modifier.weight(1f),
                ) { clipMenu = true }
                ActionItem(
                    Icons.Default.Place,
                    "挪一挪",
                    active = adjustMode,
                    modifier = Modifier.weight(1f),
                ) {
                    adjustMode = !adjustMode
                    bubble = if (adjustMode) {
                        "双指缩放 / 旋转 · 点空处挪位置"
                    } else {
                        "点空处可以挪位置"
                    }
                }
                ActionItem(
                    Icons.Default.Star,
                    "补光",
                    active = flashlight,
                    modifier = Modifier.weight(1f),
                ) {
                    flashlight = !flashlight
                    onToggleCameraLight(flashlight)
                    bubble = if (flashlight) "虚拟手电筒已开启" else "虚拟手电筒已关闭"
                }
            }
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
    var pendingScale = 1f
    var manualClip = -1
    var oneShotUntilNanos = 0L
    var gestureDistance = 0f
    var gestureAngle = 0f
    var suppressTap = false
    var anchorNode: AnchorNode? = null
    var playerActor: PetActor? = null
    var rivalActor: PetActor? = null
}

private data class ApiClip(
    val id: String,
    val label: String,
    val index: Int,
    val loop: Boolean,
)

/** Plain holder: the light node is not Compose state and must not trigger recomposition. */
private class LightSlot {
    var node: LightNode? = null
}

/**
 * One instance of a pet model on stage: owns its heading (world yaw), its walking speed
 * (scaled from the authored human pace so the stride cadence matches) and its clips.
 */
private class PetActor(val pet: LoadedPet, val node: ModelNodeImpl) {
    val animator = pet.instance.animator
    var headingRad = 0f
    var userScale = 1f
        private set
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

    /** User scale around the plane normal; keeps the feet glued to the plane. */
    fun setUserScale(value: Float) {
        userScale = value.coerceIn(0.35f, 3f)
        val scale = pet.scale * userScale
        node.scale = Float3(scale, scale, scale)
        node.position = Float3(node.position.x, pet.lift * userScale, node.position.z)
    }

    fun scaleBy(factor: Float) {
        if (factor > 0f) setUserScale(userScale * factor)
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

    fun playClip(index: Int, loop: Boolean) {
        if (index < 0) return
        playOnly(node, pet, index, loop)
        lastClip = index
    }

    fun playOnce(index: Int) = playClip(index, loop = true)

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
private fun ActionItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "action-press",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .scale(scale)
            .clip(capsule())
            .background(
                when {
                    active -> Palette.Blossom.copy(alpha = 0.16f)
                    pressed -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Palette.Blossom else Palette.Cream,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) Palette.Blossom else Palette.Mist, fontSize = 10.sp)
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
private fun playOnly(node: ModelNodeImpl, pet: LoadedPet, index: Int, loop: Boolean = true) {
    if (pet.animationCount == 0 || index < 0) return
    for (other in 0 until pet.animationCount) {
        if (other != index) node.stopAnimation(other)
    }
    node.playAnimation(index, 1f, loop)
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
    // Older pets were saved before the KHR_materials_specular workaround; sanitising on load
    // fixes their black Filament material without a migration pass.
    return loadModel(loader, sanitizeGlb(file.readBytes()))
}

/** Same as [loadModel] but for a peer pet fetched from the battle room. */
private fun loadModel(loader: ModelLoader, rawBytes: ByteArray): LoadedPet? {
    val bytes = sanitizeGlb(rawBytes)
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
