from pathlib import Path
_DECK = Path(__file__).resolve().parent
_TM = _DECK.parent
_REPO = _TM.parent
import fitz, math

_BASE = str(_DECK)
doc = fitz.open(_BASE + r"\aegis-deck.pdf")
print("pages:", doc.page_count)
for i in [0, 5, 6, 7, 8, 17, 22, 25]:
    if i < doc.page_count:
        p = doc[i]
        print(f"page {i+1}: {int(p.rect.width)}x{int(p.rect.height)}  '{p.get_text().strip().replace(chr(10),' ')[:52]}'")

# contact sheet of a representative spread
idxs = [0, 5, 6, 7, 8, 14, 17, 22]
idxs = [i for i in idxs if i < doc.page_count]
thumbs = [doc[i].get_pixmap(matrix=fitz.Matrix(0.42, 0.42)) for i in idxs]
cols = 2
rows = math.ceil(len(thumbs) / cols)
w = max(t.width for t in thumbs); h = max(t.height for t in thumbs)
sheet = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, w * cols, h * rows))
sheet.set_rect(sheet.irect, (235, 235, 235))
for k, t in enumerate(thumbs):
    t.set_origin((k % cols) * w, (k // cols) * h)
    sheet.copy(t, t.irect)
sheet.save(str(_DECK / r"_contact.png"))
# complete-control plane slide (page 9, index 8), full size
doc[8].get_pixmap(matrix=fitz.Matrix(1.05, 1.05)).save(str(_DECK / r"_cc.png"))
print("wrote _contact.png and _cc.png")
