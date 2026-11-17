"""Renders a few frames of a rigged GLB so the deformation can be inspected visually.

    blender --background --python check_rig.py -- model.glb out_prefix
"""
import math
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
src, prefix = argv[0], argv[1]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

scene = bpy.context.scene
scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x = 512
scene.render.resolution_y = 640
scene.render.film_transparent = False

# Frame the model
meshes = [o for o in bpy.data.objects if o.type == "MESH"]
corners = []
for o in meshes:
    for c in o.bound_box:
        corners.append(o.matrix_world @ Vector(c))
lo = Vector((min(c.x for c in corners), min(c.y for c in corners), min(c.z for c in corners)))
hi = Vector((max(c.x for c in corners), max(c.y for c in corners), max(c.z for c in corners)))
centre = (lo + hi) / 2
size = max(hi.x - lo.x, hi.z - lo.z)

cam_data = bpy.data.cameras.new("Cam")
cam = bpy.data.objects.new("Cam", cam_data)
scene.collection.objects.link(cam)
cam.location = centre + Vector((size * 0.9, -size * 1.5, size * 0.15))
direction = centre - cam.location
cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
scene.camera = cam

light_data = bpy.data.lights.new("Key", type="SUN")
light_data.energy = 3.0
light = bpy.data.objects.new("Key", light_data)
scene.collection.objects.link(light)
light.rotation_euler = (math.radians(55), 0, math.radians(35))

for frame in (1, 12, 24, 36):
    scene.frame_set(frame)
    scene.render.filepath = f"{prefix}_{frame:02d}.png"
    bpy.ops.render.render(write_still=True)
    print(f"RENDERED {scene.render.filepath}")
