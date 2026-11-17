"""Automatic rigging for Lux3D output.

Lux3D returns a single skinned-less mesh. This script:
  1. imports the GLB and normalises its size,
  2. fits a humanoid template skeleton to the model's bounding box,
  3. binds with automatic weights,
  4. bakes an idle loop (breathing + head sway),
  5. exports a GLB that already contains skin + animation.

Run headless:
    blender --background --python auto_rig.py -- input.glb output.glb [idle|walk]
"""
import math
import sys

import bpy
from mathutils import Vector, Quaternion

TARGET_HEIGHT = 1.0  # normalised; the client scales to real-world size


def parse_args():
    argv = sys.argv
    if "--" in argv:
        argv = argv[argv.index("--") + 1:]
    else:
        argv = []
    if len(argv) < 2:
        raise SystemExit("usage: auto_rig.py -- input.glb output.glb [idle|walk]")
    mode = argv[2] if len(argv) > 2 else "idle"
    return argv[0], argv[1], mode


def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def import_glb(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    meshes = [o for o in bpy.data.objects if o not in before and o.type == "MESH"]

    # Lux3D wraps the mesh in an empty "world" node. A skinned mesh must be a scene root, so detach
    # every mesh (keeping its world transform) and drop the leftover empties.
    for obj in meshes:
        world = obj.matrix_world.copy()
        obj.parent = None
        obj.matrix_world = world
    for obj in [o for o in bpy.data.objects if o.type == "EMPTY"]:
        bpy.data.objects.remove(obj, do_unlink=True)
    return meshes


def normalise(meshes):
    """Scale every imported mesh so the tallest one is TARGET_HEIGHT, feet at z=0, centred."""
    corners = []
    for obj in meshes:
        for corner in obj.bound_box:
            corners.append(obj.matrix_world @ Vector(corner))
    lo = Vector((min(c.x for c in corners), min(c.y for c in corners), min(c.z for c in corners)))
    hi = Vector((max(c.x for c in corners), max(c.y for c in corners), max(c.z for c in corners)))
    height = max(hi.z - lo.z, 1e-5)
    scale = TARGET_HEIGHT / height

    for obj in meshes:
        obj.scale = (obj.scale.x * scale, obj.scale.y * scale, obj.scale.z * scale)
    bpy.context.view_layer.update()

    # Re-measure after scaling to sit the feet on z = 0 and centre on x/y.
    corners = []
    for obj in meshes:
        for corner in obj.bound_box:
            corners.append(obj.matrix_world @ Vector(corner))
    lo = Vector((min(c.x for c in corners), min(c.y for c in corners), min(c.z for c in corners)))
    hi = Vector((max(c.x for c in corners), max(c.y for c in corners), max(c.z for c in corners)))
    centre = Vector(((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, 0))
    for obj in meshes:
        obj.location -= Vector((centre.x, centre.y, lo.z))
    bpy.context.view_layer.update()

    for obj in meshes:
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.transform_apply(location=True, rotation=False, scale=True)
        obj.select_set(False)

    return Vector((hi.x - lo.x, hi.y - lo.y, TARGET_HEIGHT))


def build_skeleton(dims):
    """A chibi-proportioned humanoid: big head, short limbs, feet at z=0."""
    h = TARGET_HEIGHT
    hip_z = h * 0.46
    chest_z = h * 0.62
    neck_z = h * 0.70
    head_z = h * 0.78
    top_z = h * 0.98

    arm_y = 0.0
    shoulder_x = dims.x * 0.22
    hip_x = dims.x * 0.13
    foot_y = dims.x * 0.10

    armature = bpy.data.armatures.new("AnimoRig")
    rig = bpy.data.objects.new("AnimoRig", armature)
    bpy.context.collection.objects.link(rig)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")

    def bone(name, head, tail, parent=None, connect=False):
        b = armature.edit_bones.new(name)
        b.head = head
        b.tail = tail
        if parent:
            b.parent = armature.edit_bones[parent]
            b.use_connect = connect
        return b

    bone("hips", (0, 0, hip_z), (0, 0, chest_z))
    bone("spine", (0, 0, chest_z), (0, 0, neck_z), "hips", True)
    bone("neck", (0, 0, neck_z), (0, 0, head_z), "spine", True)
    bone("head", (0, 0, head_z), (0, 0, top_z), "neck", True)

    for side, sign in (("L", 1), ("R", -1)):
        bone(f"shoulder.{side}", (sign * shoulder_x * 0.4, arm_y, neck_z - h * 0.03),
             (sign * shoulder_x, arm_y, neck_z - h * 0.05), "spine")
        bone(f"upper_arm.{side}", (sign * shoulder_x, arm_y, neck_z - h * 0.05),
             (sign * shoulder_x * 1.25, arm_y, chest_z), f"shoulder.{side}", True)
        bone(f"forearm.{side}", (sign * shoulder_x * 1.25, arm_y, chest_z),
             (sign * shoulder_x * 1.35, arm_y, hip_z + h * 0.05), f"upper_arm.{side}", True)

        bone(f"thigh.{side}", (sign * hip_x, 0, hip_z),
             (sign * hip_x, 0, hip_z * 0.5), "hips")
        bone(f"shin.{side}", (sign * hip_x, 0, hip_z * 0.5),
             (sign * hip_x, 0, h * 0.03), f"thigh.{side}", True)
        bone(f"foot.{side}", (sign * hip_x, 0, h * 0.03),
             (sign * hip_x, -foot_y, h * 0.02), f"shin.{side}", True)

    bpy.ops.object.mode_set(mode="OBJECT")
    return rig


def seg_distance(p, a, b):
    """Shortest distance from point p to segment a-b."""
    ab = b - a
    length_sq = ab.length_squared
    if length_sq < 1e-9:
        return (p - a).length
    t = max(0.0, min(1.0, (p - a).dot(ab) / length_sq))
    return (p - (a + ab * t)).length


def bind(meshes, rig):
    """
    Distance-weighted skinning.

    Blender's ARMATURE_AUTO (heat diffusion) assigns zero weights on Lux3D meshes — they are
    frequently multi-shell and non-manifold, which the solver cannot diffuse through. Computing the
    weights directly from vertex-to-bone distance always produces a usable result.
    """
    bones = [
        (b.name, rig.matrix_world @ b.head_local, rig.matrix_world @ b.tail_local)
        for b in rig.data.bones
    ]

    for obj in meshes:
        # Deliberately no parenting: glTF requires a skinned mesh to be a root node, and nesting it
        # under the armature produces NODE_SKINNED_MESH_NON_ROOT, which breaks animation playback.
        for mod in [m for m in obj.modifiers if m.type == "ARMATURE"]:
            obj.modifiers.remove(mod)
        mod = obj.modifiers.new("Armature", "ARMATURE")
        mod.object = rig

        obj.vertex_groups.clear()
        groups = {name: obj.vertex_groups.new(name=name) for name, _, _ in bones}

        # Collect (vertex, weight) per bone, then write in one pass per bone.
        buckets = {name: ([], []) for name, _, _ in bones}
        world = obj.matrix_world
        for v in obj.data.vertices:
            p = world @ v.co
            scored = sorted(((seg_distance(p, h, t), name) for name, h, t in bones))[:2]
            weights = [(name, 1.0 / (d * d + 1e-4)) for d, name in scored]
            total = sum(w for _, w in weights)
            for name, w in weights:
                buckets[name][0].append(v.index)
                buckets[name][1].append(w / total)

        for name, (indices, weights) in buckets.items():
            # Group by quantised weight so add() runs a few hundred times instead of per vertex.
            by_weight = {}
            for idx, w in zip(indices, weights):
                key = round(w, 3)
                by_weight.setdefault(key, []).append(idx)
            for w, ids in by_weight.items():
                groups[name].add(ids, w, "REPLACE")

        weighted = sum(1 for v in obj.data.vertices if any(g.weight > 0 for g in v.groups))
        print(f"BIND {obj.name}: vgroups={len(obj.vertex_groups)} "
              f"weightedVerts={weighted}/{len(obj.data.vertices)}")


def iter_fcurves(action):
    """Blender 4.4+ stores curves in layered actions; older builds expose action.fcurves."""
    if action is None:
        return []
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    curves = []
    for layer in getattr(action, "layers", []):
        for strip in getattr(layer, "strips", []):
            for bag in getattr(strip, "channelbags", []):
                curves.extend(bag.fcurves)
    return curves


def key_pose(rig, frame, poses):
    """poses: {bone: (axis, degrees)} applied as a local rotation."""
    bpy.context.scene.frame_set(frame)
    for name, (axis, degrees) in poses.items():
        pose_bone = rig.pose.bones.get(name)
        if pose_bone is None:
            continue
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = Quaternion(axis, math.radians(degrees))
        pose_bone.keyframe_insert(data_path="rotation_quaternion", frame=frame)


def make_idle(rig):
    """Two-second breathing loop: the body rises, the spine tilts and the head sways."""
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = 48

    up = (1.0, 0.0, 0.0)
    sway = (0.0, 1.0, 0.0)
    hips = rig.pose.bones["hips"]

    # (frame, body lift in local Y, spine tilt deg, head sway deg, arm swing deg)
    beats = [
        (1, 0.0, 0, 0, 0),
        (12, 0.014, -12, 15, 10),
        (24, 0.0, 0, 0, 0),
        (36, 0.010, 10, -12, -8),
        (48, 0.0, 0, 0, 0),
    ]

    for frame, lift, spine_deg, head_deg, arm_deg in beats:
        scene.frame_set(frame)
        hips.location = (0.0, lift, 0.0)
        hips.keyframe_insert(data_path="location", frame=frame)
        key_pose(rig, frame, {
            "spine": (up, spine_deg),
            "head": (sway, head_deg),
            "upper_arm.L": (up, arm_deg),
            "upper_arm.R": (up, -arm_deg),
        })

    for fcurve in iter_fcurves(rig.animation_data.action):
        for keyframe in fcurve.keyframe_points:
            keyframe.interpolation = "BEZIER"
        fcurve.modifiers.new("CYCLES")


def make_walk(rig):
    """
    A one-second walk cycle driven by foot IK targets, then baked to FK so the exported GLB needs
    no constraints. The feet stay pinned to z = 0, which is what makes the walk read as grounded.
    """
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = 24

    ik_targets = {}
    for side, sign in (("L", 1), ("R", -1)):
        target = bpy.data.objects.new(f"IK_foot.{side}", None)
        target.empty_display_size = 0.05
        scene.collection.objects.link(target)
        ik_targets[side] = target

        shin = rig.pose.bones[f"shin.{side}"]
        ik = shin.constraints.new("IK")
        ik.target = target
        ik.chain_count = 2
        ik.use_tail = True

    hip_x = rig.data.bones["thigh.L"].head_local.x
    stride = 0.09
    lift = 0.05

    def foot_key(side, frame, y, z):
        target = ik_targets[side]
        target.location = (hip_x if side == "L" else -hip_x, y, z)
        target.keyframe_insert(data_path="location", frame=frame)

    # Explicit four-beat cycle: left leads, right leads, and two passing poses.
    for frame, l_y, l_z, r_y, r_z in (
        (1,  stride, 0.0, -stride, 0.0),
        (7,  0.0,    lift, 0.0,    0.0),
        (13, -stride, 0.0, stride, 0.0),
        (19, 0.0,    0.0,  0.0,    lift),
        (25, stride, 0.0, -stride, 0.0),
    ):
        foot_key("L", frame, l_y, l_z)
        foot_key("R", frame, r_y, r_z)

    # Body bob and counter-swinging arms.
    root = rig.pose.bones["hips"]
    for frame, z, twist in ((1, 0.0, 4), (7, 0.018, 0), (13, 0.0, -4), (19, 0.018, 0), (25, 0.0, 4)):
        scene.frame_set(frame)
        root.location = (0, 0, z)
        root.rotation_mode = "QUATERNION"
        root.rotation_quaternion = Quaternion((0, 0, 1), math.radians(twist * 0.25))
        root.keyframe_insert(data_path="location", frame=frame)
        root.keyframe_insert(data_path="rotation_quaternion", frame=frame)

        for side, sign in (("L", 1), ("R", -1)):
            arm = rig.pose.bones[f"upper_arm.{side}"]
            arm.rotation_mode = "QUATERNION"
            arm.rotation_quaternion = Quaternion((1, 0, 0), math.radians(sign * 12 * (1 if frame in (1, 25) else -1)))
            arm.keyframe_insert(data_path="rotation_quaternion", frame=frame)

    # Bake IK down to plain bone rotations.
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="POSE")
    bpy.ops.pose.select_all(action="SELECT")
    bpy.ops.nla.bake(
        frame_start=1,
        frame_end=25,
        only_selected=True,
        visual_keying=True,
        clear_constraints=True,
        clear_parents=False,
        use_current_action=True,
        bake_types={"POSE"},
    )
    bpy.ops.object.mode_set(mode="OBJECT")

    for target in ik_targets.values():
        bpy.data.objects.remove(target, do_unlink=True)

    if rig.animation_data and rig.animation_data.action:
        for fcurve in iter_fcurves(rig.animation_data.action):
            for keyframe in fcurve.keyframe_points:
                keyframe.interpolation = "BEZIER"


def export(path):
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        export_animations=True,
        # Without this the exporter treats the in-between keys as redundant (the first and last
        # poses are identical) and collapses the curve to two keys — a motionless "animation".
        export_optimize_animation_size=False,
        export_optimize_animation_keep_anim_armature=True,
        export_apply=False,
        export_yup=True,
    )


def main():
    src, dst, mode = parse_args()
    clear_scene()
    meshes = import_glb(src)
    if not meshes:
        raise SystemExit("no mesh in source glb")
    dims = normalise(meshes)
    rig = build_skeleton(dims)
    bind(meshes, rig)
    if mode == "walk":
        make_walk(rig)
    else:
        make_idle(rig)
    export(dst)
    print(f"RIGGED {dst} bones={len(rig.data.bones)} mode={mode}")


main()
