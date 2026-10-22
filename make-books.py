# /// script
# requires-python = ">=3.10"
# dependencies = ["pymupdf", "pillow"]
# ///
"""Turns the books in books/luisa, books/orlando and books/both into the app's simple format in books/shelf.

Run: double-click make-books.bat (or `uv run make-books.py`), then `npm run sync` in luisa-tablet/ for her tablet.

What you can drop in (the folder decides whose bookshelf it goes on):
  - StoryWeaver downloads: the .zip as downloaded (PDF + attribution), or just the .pdf
  - Any other PDF: each page becomes a page; the biggest picture is kept, the words go underneath
  - EPUB (e.g. Project Gutenberg "EPUB3 with images"): a new page starts at each picture
  - A folder of pictures: one page per picture, sorted by name. Optional text.txt (one line per page,
    blank line = no words) and credit.txt.

Output: books/shelf/<id>/01.jpg... and books/shelf/index.json, which lists every book with its page words.
Unchanged books are skipped, so re-running is quick.
"""
import io, json, re, shutil, sys, zipfile, posixpath
from html.parser import HTMLParser
from pathlib import Path
import pymupdf
from PIL import Image

ROOT = Path(__file__).parent / 'books'
SHELF = ROOT / 'shelf'
KIDS = {'luisa': ['luisa'], 'orlando': ['orlando'], 'both': ['luisa', 'orlando']}
MAX_W, MAX_H = 1024, 768  # the tablets' screens
IMG_EXT = ('.jpg', '.jpeg', '.png', '.webp', '.gif')
# Publisher / front-matter lines that aren't part of the story
JUNK = re.compile(r'project gutenberg|copyright|all rights reserved|printed in|publishing company|frederick warne|'
                  r'first published|william clowes|rastko|\*\*\*|printed and bound|e-text prepared|berne convention|new york|" ?etc|'
                  r'^\s*(london|by)\s*$|author of|^\s*\[?\d+\]?\s*$', re.I)


# StoryWeaver's PDF fonts map the fi/fl ligatures to these letters ("ýsh" = fish, "Þy" = fly)
LIGATURES = {ord('ý'): 'fi', ord('Þ'): 'fl', ord('ÿ'): 'ff'}


def slug(name):
    name = re.sub(r'^\d+-', '', name)  # StoryWeaver ids: "16411-too-big-too-small"
    return re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')[:40]


def tidy(text):
    return re.sub(r'\s+', ' ', text.replace('­', '')).strip()


def save_jpg(im, path):
    im = im.convert('RGB')
    im.thumbnail((MAX_W, MAX_H), Image.LANCZOS)
    im.save(path, 'JPEG', quality=80, optimize=True)


# ---------- PDF (StoryWeaver and others) ----------
def from_pdf(data, out):
    doc = pymupdf.open(stream=data, filetype='pdf')
    pages, credit = [], ''
    for page in doc:
        text = page.get_text()
        if re.search(r'made possible by Pratham Books|StoryWeaver platform|This is a Level \d book', text):
            break  # StoryWeaver's back pages: attribution and blurb
        text = tidy(re.sub(r'\n\d+/\d+\s*$', '', text.strip())).translate(LIGATURES)  # "2/14" page numbers
        text = re.sub(r'\s*Author:.*', '', text) if not pages else text  # cover: just the title
        # the biggest picture on the page, with the words taken off so they can sit underneath instead
        boxes = [pymupdf.Rect(i['bbox']) for i in page.get_image_info()]
        box = max(boxes, key=lambda r: r.get_area(), default=None)
        if box is not None and box.get_area() < page.rect.get_area() * 0.15:
            box = None
        img = None
        if box is not None:
            for b in page.get_text('dict')['blocks']:
                for line in b.get('lines', []):
                    for span in line['spans']:
                        page.add_redact_annot(span['bbox'], fill=False)
            page.apply_redactions(images=pymupdf.PDF_REDACT_IMAGE_NONE, graphics=pymupdf.PDF_REDACT_LINE_ART_NONE)
            zoom = min(MAX_W / box.width, MAX_H / box.height, 3)
            pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=box & page.rect)
            img = f'{len(pages) + 1:02d}.jpg'
            save_jpg(Image.open(io.BytesIO(pix.tobytes('png'))), out / img)
        if img or text:
            pages.append({'img': img, 'text': text})
    return pages, credit


# ---------- EPUB (Gutenberg, StoryWeaver, ...) ----------
class Walker(HTMLParser):
    """Walks one XHTML file: each picture starts a page, words collect onto the current page."""
    VOID = {'img', 'br', 'hr', 'meta', 'link', 'input', 'image', 'col', 'source'}
    BLOCK = {'p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'br', 'tr', 'section', 'figure', 'figcaption'}

    def __init__(self, on_img, on_text):
        super().__init__(convert_charrefs=True)
        self.on_img, self.on_text, self.stack, self.skip, self.buf = on_img, on_text, [], None, []

    def flush(self):
        t = tidy(''.join(self.buf)); self.buf = []
        if t: self.on_text(t)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if self.skip is None and (tag in ('script', 'style', 'head') or re.search(r'pg-(header|footer)|pageno', (a.get('id') or '') + ' ' + (a.get('class') or ''))):
            if tag not in self.VOID: self.skip = len(self.stack)
        if tag in self.BLOCK: self.flush()
        if self.skip is None and tag in ('img', 'image'):
            src = a.get('src') or a.get('xlink:href') or a.get('href')
            if src: self.flush(); self.on_img(src)
        if tag not in self.VOID: self.stack.append(tag)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID and self.stack: self.stack.pop()

    def handle_endtag(self, tag):
        if tag in self.VOID or tag not in self.stack: return
        while self.stack:
            if self.stack.pop() == tag: break
        if self.skip is not None and len(self.stack) <= self.skip: self.skip = None; self.buf = []
        if tag in self.BLOCK: self.flush()

    def handle_data(self, data):
        if self.skip is None: self.buf.append(data)


def from_epub(data, out):
    z = zipfile.ZipFile(io.BytesIO(data))
    opf_path = re.search(r'full-path="([^"]+)"', z.read('META-INF/container.xml').decode()).group(1)
    opf = z.read(opf_path).decode('utf8', 'replace')
    base = posixpath.dirname(opf_path)
    items = dict(re.findall(r'<item\b[^>]*?id="([^"]+)"[^>]*?href="([^"]+)"', opf))
    items.update({i: h for h, i in re.findall(r'<item\b[^>]*?href="([^"]+)"[^>]*?id="([^"]+)"', opf)})
    spine = re.findall(r'<itemref\b[^>]*idref="([^"]+)"', opf)
    credit = ', '.join(tidy(c) for c in re.findall(r'<dc:creator[^>]*>([^<]+)', opf))
    tokens = []  # ('i', picture) and ('t', paragraph) in reading order

    for idref in spine:
        href = items.get(idref)
        if not href or 'wrap' in href or 'toc' in href: continue
        path = posixpath.normpath(posixpath.join(base, href))
        here = posixpath.dirname(path)

        def on_img(src):
            p = posixpath.normpath(posixpath.join(here, src.split('#')[0]))
            try: im = Image.open(io.BytesIO(z.read(p)))
            except Exception: return
            if min(im.size) < 140: return  # little decorations
            tokens.append(('i', im))

        def on_text(t):
            if not JUNK.search(t): tokens.append(('t', t))

        w = Walker(on_img, on_text)
        w.feed(z.read(path).decode('utf8', 'replace')); w.flush()

    # Old picture books put the words before their picture in some books and after it in others,
    # so try both and keep whichever leaves fewer pictures without words.
    a, b = pair_up(tokens, before=False), pair_up(tokens, before=True)
    blank = lambda ps: sum(not p['text'] for p in ps[1:])
    pages = a if blank(a) < blank(b) else b
    for i, p in enumerate(pages):
        if p['img']:
            name = f'{i + 1:02d}.jpg'
            save_jpg(p['img'], out / name); p['img'] = name
    return pages, credit


def pair_up(tokens, before):
    """Pictures + paragraphs -> pages. before=True: words belong to the next picture; False: to the last one.
    A run of n pictures next to a run of n paragraphs (a two-page spread) is matched one to one."""
    runs = []
    for kind, v in tokens:
        if runs and runs[-1][0] == kind: runs[-1][1].append(v)
        else: runs.append((kind, [v]))
    pages, pending = [], []
    for i, (kind, items) in enumerate(runs):
        if kind == 'i':
            new = [{'img': im, 'text': ''} for im in items]
            words = pending if before else (runs[i + 1][1] if i + 1 < len(runs) else [])
            if len(words) == len(new) > 1:
                for p, t in zip(new, words): p['text'] = t
            elif words:
                (new[0] if before else new[-1])['text'] = ' '.join(words)
            pages += new; pending = []
        elif before:
            pending = items
    if before and pending and pages:  # words after the last picture
        pages[-1]['text'] = (pages[-1]['text'] + ' ' + ' '.join(pending)).strip()
    return pages


# ---------- Folder of pictures ----------
def from_folder(src, out):
    pics = sorted(f for f in src.iterdir() if f.suffix.lower() in IMG_EXT)
    lines = (src / 'text.txt').read_text('utf8').splitlines() if (src / 'text.txt').exists() else []
    pages = []
    for i, f in enumerate(pics):
        name = f'{i + 1:02d}.jpg'
        save_jpg(Image.open(f), out / name)
        pages.append({'img': name, 'text': tidy(lines[i]) if i < len(lines) else ''})
    credit = (src / 'credit.txt').read_text('utf8').strip() if (src / 'credit.txt').exists() else ''
    return pages, credit


def signature(src):
    files = [src] if src.is_file() else sorted(src.iterdir())
    return [[f.name, f.stat().st_size, int(f.stat().st_mtime)] for f in files]


def convert(src, kids):
    out = SHELF / slug(src.stem if src.is_file() else src.name)
    sig = signature(src)
    try:
        old = json.loads((out / 'book.json').read_text('utf8'))
        if old['sig'] == sig:
            old['for'] = kids
            return old
    except Exception: pass
    shutil.rmtree(out, ignore_errors=True); out.mkdir(parents=True)
    title, credit = src.stem if src.is_file() else src.name, ''
    ext = src.suffix.lower() if src.is_file() else ''
    if ext == '.zip':  # StoryWeaver download
        z = zipfile.ZipFile(src)
        pdf = next(n for n in z.namelist() if n.lower().endswith('.pdf'))
        pages, _ = from_pdf(z.read(pdf), out)
        txt = next((z.read(n).decode('utf8', 'replace') for n in z.namelist() if n.lower().endswith('.txt')), '')
        m = re.search(r'Title:\s*(.+)', txt); title = m.group(1).strip() if m else title
        m = re.search(r'Attribution Text:\s*(.+)', txt); credit = tidy(m.group(1)) if m else ''
    elif ext == '.pdf':
        pages, credit = from_pdf(src.read_bytes(), out)
        title = pymupdf.open(src).metadata.get('title') or title
    elif ext == '.epub':
        pages, credit = from_epub(src.read_bytes(), out)
        if pages: pages[0]['text'] = title  # cover
        credit = (f'By {credit}. ' if credit else '') + ('From Project Gutenberg (public domain).' if 'gutenberg' in src.read_bytes()[:400000].decode('latin1').lower() else '')
    else:
        pages, credit = from_folder(src, out)
    book = {'id': out.name, 'title': title, 'for': kids, 'credit': credit, 'pages': pages, 'sig': sig}
    (out / 'book.json').write_text(json.dumps(book, indent=1, ensure_ascii=False), 'utf8')
    return book


def main():
    SHELF.mkdir(parents=True, exist_ok=True)
    books = []
    for folder, kids in KIDS.items():
        d = ROOT / folder
        if not d.exists(): continue
        for src in sorted(d.iterdir()):
            if src.name.startswith('.') or (src.is_file() and src.suffix.lower() not in ('.pdf', '.epub', '.zip')): continue
            try:
                b = convert(src, kids)
                books.append(b)
                print(f'  {folder:8} {b["title"][:44]:44} {len(b["pages"]):3} pages')
            except Exception as e:
                print(f'  !! {src.name}: {e}')
    keep = {b['id'] for b in books}
    for d in SHELF.iterdir():
        if d.is_dir() and d.name not in keep: shutil.rmtree(d); print('  removed', d.name)
    index = {'books': [{k: v for k, v in b.items() if k != 'sig'} for b in sorted(books, key=lambda b: b['title'].lower())]}
    (SHELF / 'index.json').write_text(json.dumps(index, ensure_ascii=False, separators=(',', ':')), 'utf8')
    print(f'{len(books)} books ready in books/shelf')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
