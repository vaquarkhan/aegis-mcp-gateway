from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
import os
import zipfile

SRC = str(_REPO)
OUT = r"str(_REPO).zip"
TOP = os.path.dirname(SRC)

if os.path.exists(OUT):
    os.remove(OUT)

added = 0
skipped = 0
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dirpath, dirs, files in os.walk(SRC):
        # never descend into build output (already cleaned, belt and suspenders)
        dirs[:] = [d for d in dirs if d != "target"]
        for name in files:
            if name.startswith("~$"):
                skipped += 1
                continue
            full = os.path.join(dirpath, name)
            arc = os.path.relpath(full, TOP)
            try:
                with open(full, "rb"):
                    pass
                z.write(full, arc)
                added += 1
            except Exception:
                skipped += 1

size_mb = round(os.path.getsize(OUT) / 1024 / 1024, 1)
print("zip:", OUT)
print("files_added:", added, "skipped:", skipped)
print("zip_size_MB:", size_mb)
