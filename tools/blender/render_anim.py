"""Renders a sample of frames from a rigged GLB so the motion can be judged without a phone.

    blender --background --python render_anim.py -- model.glb out [step] [count]

Writes out_00.png, out_01.png, ...; the caller assembles them into a GIF.
"""
import math
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
src, out = argv[0], argv[1]
step = int(argv[2]) if len(argv) > 2 else 4
count = int(argv[3]) if len(argv) > 3 else 12

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

scene = bpy.context.scene
scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x = 400
scene.render.resolution_y = 500
scene.render.film_transparent = False
# Workbench defaults to a grey clay render; texture + standard view transform keeps the GLB's
# colours so the preview matches what the phone shows.
scene.display.shading.light = "STUDIO"
scene.display.shading.color_type = "TEXTURE"
scene.display.shading.show_shadows = True
scene.view_settings.view_transform = "Standard"

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
cam.location = centre + Vector((size * 0.7, -size * 1.6, size * 0.25))
cam.rotation_euler = (centre - cam.location).to_track_quat("-Z", "Y").to_euler()
scene.camera = cam

light_data = bpy.data.lights.new("Key", type="SUN")
light_data.energy = 3.0
light = bpy.data.objects.new("Key", light_data)
scene.collection.objects.link(light)
light.rotation_euler = (math.radians(55), 0, math.radians(35))

# A full idle loop is 48 frames; a walk cycle is 25, so the caller picks the sampling.
for i in range(count):
    frame = 1 + i * step
    scene.frame_set(frame)
    scene.render.filepath = f"{out}_{i:02d}.png"
    bpy.ops.render.render(write_still=True)
print(f"RENDERED {out}")
