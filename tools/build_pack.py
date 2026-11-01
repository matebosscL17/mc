#!/usr/bin/env python3
"""A pack/textures/*.png fájlokból resource packet épít: minden <id>.png egy smpcore:<id> tárgymodell lesz.
Kimenet: build/penthouse-pack.zip és build/pack.sha1"""
import hashlib
import json
import os
import zipfile

SRC = "pack/textures"
OUT = "build"
os.makedirs(OUT, exist_ok=True)
files = {}
files["pack.mcmeta"] = json.dumps({"pack": {"description": "Penthouse SMP", "min_format": 84, "max_format": 300}}).encode()
for name in sorted(os.listdir(SRC)):
    if not name.lower().endswith(".png"):
        continue
    item = name[:-4].lower().replace(" ", "_")
    files[f"assets/smpcore/textures/item/{item}.png"] = open(os.path.join(SRC, name), "rb").read()
    files[f"assets/smpcore/models/item/{item}.json"] = json.dumps(
        {"parent": "minecraft:item/handheld", "textures": {"layer0": f"smpcore:item/{item}"},
         "display": {
             "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.5, 0.5], "scale": [1.1, 1.1, 1.0]},
             "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.5, 0.5], "scale": [1.1, 1.1, 1.0]},
             "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.95, 0.95, 0.9]},
             "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.95, 0.95, 0.9]}}}).encode()
    files[f"assets/smpcore/items/{item}.json"] = json.dumps(
        {"model": {"type": "minecraft:model", "model": f"smpcore:item/{item}"}}).encode()
path = os.path.join(OUT, "penthouse-pack.zip")
with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
    for name in sorted(files):
        info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, files[name])
digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
open(os.path.join(OUT, "pack.sha1"), "w").write(digest)
print(len([f for f in files if f.endswith('.png')]), "textúra, sha1:", digest)
