#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用现有 Word 的封面、样式、页眉页脚和页面设置，重生成本目录两份交付文档。"""

import re
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

from content_tech import BLOCKS as TECH_BLOCKS
from content_user import BLOCKS as USER_BLOCKS

# 目录页码来自 LibreOffice 对当前正文的排版。改动正文后需重新导出 PDF 再更新。
PAGE_NUMBERS = {
    "DeliveryGlossary": "3",
    "DeliveryTChapter1": "5",
    "DeliveryTChapter2": "6",
    "DeliveryTChapter3": "8",
    "DeliveryTChapter4": "10",
    "DeliveryTChapter5": "13",
    "DeliveryTChapter6": "14",
    "DeliveryTChapter7": "16",
    "DeliveryTChapter8": "18",
    "DeliveryUChapter1": "5",
    "DeliveryUChapter2": "7",
    "DeliveryUChapter3": "10",
    "DeliveryUChapter4": "14",
    "DeliveryUChapter5": "16",
    "DeliveryUChapter6": "18",
    "DeliveryUChapter7": "20",
    "DeliveryUChapter8": "21",
    "DeliveryUChapter9": "22",
    "DeliveryUChapter10": "24",
    "DeliveryUChapter11": "26",
    "DeliveryUChapter12": "28",
    "DeliveryUChapter13": "29",
    "DeliveryUChapter14": "30",
}

ROOT = Path(__file__).resolve().parent
FIG = ROOT / "figures"
EMU_WIDTH = 5938520
NS_DECL = None

FONTS = (
    '<w:rFonts w:ascii="{ascii}" w:hAnsi="{ascii}" w:eastAsia="宋体" w:cs="宋体"/>'
)


def fonts(ascii_name: str) -> str:
    return FONTS.format(ascii=ascii_name)


def run(text: str, ascii_font="宋体", size=None, bold=False, color=None) -> str:
    props = [fonts(ascii_font)]
    if bold:
        props.append("<w:b/>")
    if color:
        props.append(f'<w:color w:val="{color}"/>')
    if size:
        props.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    body = escape(text)
    space = ' xml:space="preserve"' if text[:1].isspace() or text[-1:].isspace() else ""
    return f"<w:r><w:rPr>{''.join(props)}</w:rPr><w:t{space}>{body}</w:t></w:r>"


def paragraph(text, *, style=None, jc=None, indent=True, after="240", before="0",
              size=None, bold=False, ascii_font="宋体", color=None, center=False,
              page_break=False, outline=None, bookmark=None, bookmark_id=None) -> str:
    ppr = []
    if style:
        ppr.append(f'<w:pStyle w:val="{style}"/>')
    if page_break:
        ppr.append("<w:keepNext/><w:keepLines/><w:pageBreakBefore/>")
    if outline is not None:
        ppr.append(f'<w:outlineLvl w:val="{outline}"/>')
    spacing = f'<w:spacing w:before="{before}" w:after="{after}"/>'
    ppr.append(spacing)
    if indent:
        ppr.append('<w:ind w:firstLine="480" w:firstLineChars="200"/>')
    align = "center" if center else jc
    if align:
        ppr.append(f'<w:jc w:val="{align}"/>')
    ppr.append(f"<w:rPr>{fonts('宋体')}</w:rPr>")
    mark_s = mark_e = ""
    if bookmark:
        mark_s = f'<w:bookmarkStart w:id="{bookmark_id}" w:name="{bookmark}"/>'
        mark_e = f'<w:bookmarkEnd w:id="{bookmark_id}"/>'
    return f"<w:p><w:pPr>{''.join(ppr)}</w:pPr>{mark_s}{run(text, ascii_font, size, bold, color)}{mark_e}</w:p>"


def code_line(text: str) -> str:
    return (
        "<w:p><w:pPr><w:spacing w:after=\"60\"/><w:jc w:val=\"left\"/>"
        f"<w:rPr>{fonts('Consolas')}</w:rPr></w:pPr>"
        f"{run(text, 'Consolas', 20)}</w:p>"
    )


def caption(text: str) -> str:
    return paragraph(text, indent=False, after="160", center=True, size=20)


def placeholder(caption_text: str) -> str:
    gap = (
        "<w:p><w:pPr><w:spacing w:before=\"200\" w:after=\"2800\"/><w:jc w:val=\"center\"/>"
        f"<w:rPr>{fonts('宋体')}</w:rPr></w:pPr>"
        f"{run('（现场截图预留，约 6 厘米。按上一段采集说明补入后，更新目录页码。）', size=21, color='666666')}</w:p>"
    )
    return gap + caption(caption_text)


def image_paragraph(rel_id: str, name: str, cx: int, cy: int, doc_id: int) -> str:
    return f'''<w:p><w:pPr><w:spacing w:before="120" w:after="80"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="{doc_id}" name="{escape(name)}" descr="{escape(name)}"/><wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/></wp:cNvGraphicFramePr><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="{doc_id}" name="{escape(name)}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="{rel_id}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>'''


def cell(text: str, width: int, header: bool) -> str:
    fill = '<w:shd w:val="clear" w:color="auto" w:fill="E7E6E6"/>' if header else ""
    paras = []
    for line in str(text).split("\n"):
        paras.append(
            "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"260\" w:lineRule=\"auto\"/>"
            "<w:jc w:val=\"left\"/></w:pPr>"
            f"{run(line, size=21, bold=header)}</w:p>"
        )
    return (
        f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/>{fill}'
        f'<w:vAlign w:val="center"/></w:tcPr>{"".join(paras)}</w:tc>'
    )


def table(rows, widths=None) -> str:
    cols = len(rows[0])
    if widths is None:
        base = 9360 // cols
        widths = [base] * cols
        widths[-1] = 9360 - base * (cols - 1)
    if sum(widths) != 9360:
        scale = 9360 / sum(widths)
        widths = [int(w * scale) for w in widths]
        widths[-1] = 9360 - sum(widths[:-1])
    grid = "".join(f'<w:gridCol w:w="{w}"/>' for w in widths)
    body = []
    for r_index, row in enumerate(rows):
        if len(row) != cols:
            raise SystemExit(f"表格列数不一致: {row!r}")
        header = r_index == 0
        trpr = "<w:trPr><w:tblHeader/></w:trPr>" if header else ""
        cells = "".join(cell(value, widths[i], header) for i, value in enumerate(row))
        body.append(f"<w:tr>{trpr}{cells}</w:tr>")
    borders = """<w:tblBorders>
      <w:top w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
      <w:left w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
      <w:bottom w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
      <w:right w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
      <w:insideH w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
      <w:insideV w:val="single" w:color="D9D9D9" w:sz="4" w:space="0"/>
    </w:tblBorders>"""
    return (
        '<w:tbl><w:tblPr><w:tblStyle w:val="15"/><w:tblW w:w="9360" w:type="dxa"/>'
        '<w:tblInd w:w="0" w:type="dxa"/>'
        f"{borders}<w:tblLayout w:type=\"fixed\"/>"
        '<w:tblCellMar><w:top w:w="90" w:type="dxa"/><w:left w:w="120" w:type="dxa"/>'
        '<w:bottom w:w="90" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tblCellMar>'
        f"</w:tblPr><w:tblGrid>{grid}</w:tblGrid>{''.join(body)}</w:tbl>"
    )


def toc_entry_tech(bookmark: str, title: str, first: bool) -> str:
    page = PAGE_NUMBERS[bookmark]
    link = (
        f'<w:hyperlink w:anchor="{bookmark}" w:history="1"><w:r>'
        f"<w:rPr>{fonts('宋体')}<w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/></w:rPr>"
        f"<w:t>{escape(title)}</w:t></w:r></w:hyperlink>"
    )
    ref = (
        '<w:r><w:tab/></w:r>'
        '<w:r><w:fldChar w:fldCharType="begin" w:dirty="0"/></w:r>'
        f'<w:r><w:instrText xml:space="preserve"> PAGEREF {bookmark} \\h </w:instrText></w:r>'
        '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        f'<w:r><w:rPr>{fonts("宋体")}<w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr>'
        f"<w:t>{page}</w:t></w:r>"
        '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
    )
    style = (
        '<w:pStyle w:val="DeliveryToc1"/>'
        '<w:spacing w:before="0" w:after="120" w:line="260" w:lineRule="auto"/>'
        '<w:jc w:val="left"/>'
    )
    if first:
        field = (
            '<w:r><w:fldChar w:fldCharType="begin" w:dirty="0"/></w:r>'
            '<w:r><w:instrText xml:space="preserve"> TOC \\o "1-1" \\h \\u </w:instrText></w:r>'
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        )
        closer = ""
        return f"<w:p><w:pPr>{style}</w:pPr>{field}{link}{ref}{closer}</w:p>"
    return f"<w:p><w:pPr>{style}</w:pPr>{link}{ref}</w:p>"


def toc_entry_user(bookmark: str, title: str, first: bool) -> str:
    style = (
        '<w:pStyle w:val="8"/>'
        '<w:spacing w:before="0" w:after="120" w:line="260" w:lineRule="auto"/>'
        '<w:jc w:val="left"/>'
    )
    link = (
        '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
        f'<w:r><w:instrText xml:space="preserve"> HYPERLINK \\l "{bookmark}" </w:instrText></w:r>'
        '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        f'<w:r><w:rPr>{fonts("宋体")}<w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr>'
        f"<w:t>{escape(title)}</w:t></w:r>"
        '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
    )
    ref = (
        '<w:r><w:tab/></w:r>'
        '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
        f'<w:r><w:instrText xml:space="preserve"> PAGEREF {bookmark} \\h </w:instrText></w:r>'
        '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        f'<w:r><w:rPr>{fonts("宋体")}<w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr>'
        f"<w:t>{PAGE_NUMBERS[bookmark]}</w:t></w:r>"
        '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
    )
    field = ""
    if first:
        field = (
            '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
            '<w:r><w:instrText xml:space="preserve"> TOC \\o "1-1" \\h \\u </w:instrText></w:r>'
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
        )
    return f"<w:p><w:pPr>{style}</w:pPr>{field}{link}{ref}</w:p>"


def toc_block(entries, kind: str) -> str:
    if kind == "tech":
        title = (
            '<w:p><w:pPr><w:pStyle w:val="DeliveryTocTitle"/>'
            '<w:spacing w:before="0" w:after="120" w:line="260" w:lineRule="auto"/>'
            '<w:jc w:val="left"/></w:pPr>'
            f'<w:r><w:rPr>{fonts("宋体")}<w:sz w:val="36"/><w:szCs w:val="36"/><w:b/></w:rPr>'
            "<w:t>目录</w:t></w:r></w:p>"
        )
        rows = [toc_entry_tech(b, t, i == 0) for i, (b, t) in enumerate(entries)]
        rows[-1] = rows[-1].replace("</w:p>", '<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>')
    else:
        title = (
            '<w:p><w:pPr><w:pStyle w:val="23"/>'
            '<w:spacing w:before="0" w:after="120" w:line="260" w:lineRule="auto"/>'
            '<w:jc w:val="center"/></w:pPr>'
            f'<w:r><w:rPr>{fonts("宋体")}<w:b/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr>'
            "<w:t>目录</w:t></w:r></w:p>"
        )
        rows = [toc_entry_user(b, t, i == 0) for i, (b, t) in enumerate(entries)]
        rows[-1] = rows[-1].replace("</w:p>", '<w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>')
    return (
        "<w:sdt><w:sdtPr><w:docPartObj>"
        '<w:docPartGallery w:val="Table of Contents"/><w:docPartUnique/>'
        f"</w:docPartObj></w:sdtPr><w:sdtContent>{title}{''.join(rows)}</w:sdtContent></w:sdt>"
    )


def png_size(path: Path):
    data = path.read_bytes()[:24]
    import struct
    return struct.unpack(">II", data[16:24])


def render_blocks(blocks, images: dict) -> tuple[str, list, dict]:
    parts = []
    entries = []
    used = {}
    bookmark_id = 100
    doc_id = 200
    for block in blocks:
        kind = block[0]
        if kind == "h1":
            _, title, bookmark = block
            entries.append((bookmark, title))
            parts.append(paragraph(
                title, style="2", indent=False, after="240", page_break=True,
                outline=0, bookmark=bookmark, bookmark_id=bookmark_id,
            ))
            bookmark_id += 1
        elif kind == "h2":
            parts.append(paragraph(block[1], style="3", indent=False, after="160", before="160"))
        elif kind == "p":
            parts.append(paragraph(block[1], jc="left"))
        elif kind == "lead":
            parts.append(paragraph(block[1], indent=False, after="120", jc="left", size=21))
        elif kind == "code":
            parts.append(code_line(block[1]))
        elif kind == "cap":
            parts.append(caption(block[1]))
        elif kind == "reserve":
            parts.append(placeholder(block[1]))
        elif kind == "img":
            key, name = block[1], block[2]
            rel = images[key]
            width, height = png_size(FIG / f"{key}.png")
            cy = int(EMU_WIDTH * height / width)
            parts.append(image_paragraph(rel, name, EMU_WIDTH, cy, doc_id))
            parts.append(caption(name))
            used[key] = rel
            doc_id += 1
        elif kind == "table":
            rows = block[1]
            widths = block[2] if len(block) > 2 else None
            parts.append(table(rows, widths))
            parts.append(paragraph("", indent=False, after="120"))
        elif kind == "bullets":
            for item in block[1]:
                parts.append(
                    "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>"
                    '<w:spacing w:before="60" w:after="60" w:line="240" w:lineRule="auto"/>'
                    '<w:ind w:left="720" w:hanging="360"/><w:jc w:val="both"/>'
                    f"<w:rPr>{fonts('宋体')}</w:rPr></w:pPr>{run(item)}</w:p>"
                )
        else:
            raise SystemExit(f"未知块 {kind}")
    return "".join(parts), entries, used


def document_xml(raw: str, blocks, images, kind: str, sect: str) -> str:
    match = re.search(r"<w:body[^>]*>", raw)
    if not match:
        raise SystemExit("找不到正文")
    cover = raw[match.end(): raw.find("<w:sdt")]
    old = "2026 年 10 月 13 日  文档版本 1.0"
    new = "2026 年 10 月 22 日  文档版本 1.1"
    if old in cover:
        cover = cover.replace(old, new, 1)
    elif new not in cover:
        raise SystemExit(f"{kind} 封面日期未找到")
    cover = cover.replace("基于AI的智能代码分析工具", "基于 AI 的智能代码分析工具")
    body, entries, _used = render_blocks(blocks, images)
    start = raw[: match.end()]
    return start + cover + toc_block(entries, kind) + body + sect + "</w:body></w:document>"


def load_sect(raw: str) -> str:
    match = re.search(r"<w:sectPr[\s\S]*?</w:sectPr>", raw)
    if not match:
        raise SystemExit("找不到 sectPr")
    return match.group(0)


def zip_text(path: Path, name: str) -> str:
    with zipfile.ZipFile(path) as zin:
        return zin.read(name).decode("utf-8")


def rewrite_zip(src: Path, dest: Path, replacements: dict, drop_prefixes: tuple[str, ...]) -> None:
    with zipfile.ZipFile(src) as zin:
        infos = zin.infolist()
        payload = {info.filename: zin.read(info.filename) for info in infos}
    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        written = set()
        for info in infos:
            name = info.filename
            if any(name.startswith(prefix) for prefix in drop_prefixes):
                continue
            data = replacements.get(name, payload[name])
            zout.writestr(name, data)
            written.add(name)
        for name, data in replacements.items():
            if name not in written:
                zout.writestr(name, data)


def patch_rels(xml: str, image_map: dict) -> str:
    xml = re.sub(r'<Relationship [^>]*relationships/image[^>]*/>', "", xml)
    insert = []
    for rel_id, filename in image_map.items():
        insert.append(
            f'<Relationship Id="{rel_id}" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
            f'Target="media/{filename}"/>'
        )
    return xml.replace("</Relationships>", "".join(insert) + "</Relationships>")


def patch_core(xml: str, title: str) -> str:
    if "<dc:title" in xml:
        xml = re.sub(r"<dc:title>.*?</dc:title>", f"<dc:title>{escape(title)}</dc:title>", xml)
    else:
        xml = xml.replace(
            "<cp:coreProperties",
            "<cp:coreProperties",
        )
        xml = xml.replace(
            "</cp:coreProperties>",
            f"<dc:title>{escape(title)}</dc:title><dc:description>dev 3025dfed67e7b7c013d88b77d579f9e7c43ebac9，文档版本 1.1</dc:description></cp:coreProperties>",
        )
    xml = re.sub(
        r"<dcterms:modified[^>]*>.*?</dcterms:modified>",
        '<dcterms:modified xsi:type="dcterms:W3CDTF">2026-10-22T16:00:00Z</dcterms:modified>',
        xml,
    )
    return xml


def build_one(kind: str, blocks, src_name: str, dest_name: str, image_keys: list[str]) -> None:
    src = ROOT / src_name
    dest = ROOT / dest_name
    tmp = ROOT / f".{dest_name}.tmp"
    images = {key: f"rId{20 + index}" for index, key in enumerate(image_keys)}
    filenames = {images[key]: f"{key}.png" for key in image_keys}
    raw_doc = zip_text(src, "word/document.xml")
    doc = document_xml(raw_doc, blocks, images, kind, load_sect(raw_doc))
    rels = patch_rels(
        zip_text(src, "word/_rels/document.xml.rels"),
        {rel: filenames[rel] for rel in images.values()},
    )
    core = patch_core(
        zip_text(src, "docProps/core.xml"),
        dest_name.replace(".docx", ""),
    )
    replacements = {
        "word/document.xml": doc.encode("utf-8"),
        "word/_rels/document.xml.rels": rels.encode("utf-8"),
        "docProps/core.xml": core.encode("utf-8"),
    }
    footer_name = "word/footer1.xml"
    with zipfile.ZipFile(src) as zin:
        footer_exists = footer_name in zin.namelist()
    if footer_exists:
        footer = zip_text(src, footer_name).replace(
            "基于AI的智能代码分析工具", "基于 AI 的智能代码分析工具"
        )
        replacements[footer_name] = footer.encode("utf-8")
    for key, rel in images.items():
        replacements[f"word/media/{filenames[rel]}"] = (FIG / f"{key}.png").read_bytes()
    rewrite_zip(src, tmp, replacements, ("word/media/",))
    tmp.replace(dest)
    print("wrote", dest, dest.stat().st_size)


def main() -> None:
    build_one(
        "tech",
        TECH_BLOCKS,
        "基于AI的智能代码分析工具-技术报告.docx",
        "基于AI的智能代码分析工具-技术报告.docx",
        ["architecture", "review-sequence"],
    )
    build_one(
        "user",
        USER_BLOCKS,
        "基于AI的智能代码分析工具-用户手册.docx",
        "基于AI的智能代码分析工具-用户手册.docx",
        ["deployment"],
    )


if __name__ == "__main__":
    main()
