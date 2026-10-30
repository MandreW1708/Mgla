package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline converters for Office / OpenDocument / text → sandboxed HTML.
 */
public final class MglaOfficeHtml {

    public static final class Result {
        public final String html;
        public final String plainText;
        public final File mediaDir;
        public final boolean legacyUnsupported;

        public Result(String html, String plainText, File mediaDir, boolean legacyUnsupported) {
            this.html = html;
            this.plainText = plainText != null ? plainText : "";
            this.mediaDir = mediaDir;
            this.legacyUnsupported = legacyUnsupported;
        }
    }

    private MglaOfficeHtml() {
    }

    public static Result convert(File file, String fileName, String mimeType, File cacheRoot, boolean dark) throws Exception {
        int kind = MglaDocumentHelper.kindOf(file, fileName, mimeType);
        String ext = MglaDocumentHelper.extensionOf(fileName);
        File mediaDir = new File(cacheRoot, "media_" + Integer.toHexString(file.getAbsolutePath().hashCode()));
        // Clear previous media for this file
        deleteRecursive(mediaDir);
        //noinspection ResultOfMethodCallIgnored
        mediaDir.mkdirs();

        if ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext)) {
            String body = legacyNoticeHtml(fileName);
            return new Result(wrap(body, dark, fileName), stripTags(body), mediaDir, true);
        }

        switch (kind) {
            case MglaDocumentHelper.KIND_WORD:
                if ("odt".equals(ext)) {
                    return convertOdt(file, mediaDir, dark, fileName);
                }
                return convertDocx(file, mediaDir, dark, fileName);
            case MglaDocumentHelper.KIND_SHEET:
                if ("ods".equals(ext)) {
                    return convertOds(file, mediaDir, dark, fileName);
                }
                return convertXlsx(file, mediaDir, dark, fileName);
            case MglaDocumentHelper.KIND_SLIDES:
                if ("odp".equals(ext)) {
                    return convertOdp(file, mediaDir, dark, fileName);
                }
                return convertPptx(file, mediaDir, dark, fileName);
            case MglaDocumentHelper.KIND_CSV:
                return convertCsv(file, dark, fileName, "tsv".equals(ext) ? '\t' : ',');
            case MglaDocumentHelper.KIND_HTML:
                return convertHtmlFile(file, dark, fileName);
            case MglaDocumentHelper.KIND_TEXT:
            default:
                return convertText(file, dark, fileName, ext);
        }
    }

    private static Result convertText(File file, boolean dark, String title, String ext) throws Exception {
        String raw = readTextLimited(file, 2 * 1024 * 1024);
        if ("rtf".equals(ext)) {
            raw = stripRtf(raw);
        }
        String escaped = escape(raw);
        String body;
        if ("md".equals(ext) || "markdown".equals(ext)) {
            body = "<article class='md'><pre class='plain'>" + escaped + "</pre></article>";
        } else if ("json".equals(ext) || "xml".equals(ext) || "yaml".equals(ext) || "yml".equals(ext)
                || "java".equals(ext) || "kt".equals(ext) || "py".equals(ext) || "js".equals(ext)
                || "ts".equals(ext) || "css".equals(ext) || "c".equals(ext) || "cpp".equals(ext)
                || "h".equals(ext) || "swift".equals(ext) || "go".equals(ext) || "rs".equals(ext)
                || "sh".equals(ext) || "bat".equals(ext) || "ps1".equals(ext)) {
            body = "<pre class='code'>" + escaped + "</pre>";
        } else {
            body = "<pre class='plain'>" + escaped + "</pre>";
        }
        return new Result(wrap(body, dark, title), raw, null, false);
    }

    private static Result convertHtmlFile(File file, boolean dark, String title) throws Exception {
        String raw = readTextLimited(file, 2 * 1024 * 1024);
        // Strip scripts for safety
        String safe = raw.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "");
        String body = "<div class='html-host'>" + safe + "</div>";
        return new Result(wrap(body, dark, title), stripTags(safe), null, false);
    }

    private static Result convertCsv(File file, boolean dark, String title, char sep) throws Exception {
        String raw = readTextLimited(file, 2 * 1024 * 1024);
        StringBuilder table = new StringBuilder();
        table.append("<div class='sheet'><table>");
        StringBuilder plain = new StringBuilder();
        String[] lines = raw.split("\\r?\\n");
        int row = 0;
        for (String line : lines) {
            if (line.isEmpty() && row > 0) continue;
            ArrayList<String> cols = splitCsvLine(line, sep);
            table.append(row == 0 ? "<thead><tr>" : "<tr>");
            for (String c : cols) {
                table.append(row == 0 ? "<th>" : "<td>").append(escape(c)).append(row == 0 ? "</th>" : "</td>");
                plain.append(c).append('\t');
            }
            table.append(row == 0 ? "</tr></thead><tbody>" : "</tr>");
            plain.append('\n');
            row++;
            if (row > 5000) {
                table.append("<tr><td colspan='99'>… таблица обрезана</td></tr>");
                break;
            }
        }
        table.append("</tbody></table></div>");
        return new Result(wrap(table.toString(), dark, title), plain.toString(), null, false);
    }

    private static Result convertDocx(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            extractMedia(zip, "word/media/", mediaDir);
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new IllegalStateException("word/document.xml missing");
            }
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            parseWordXml(zip.getInputStream(entry), mediaDir, body, plain);
            return new Result(wrap(body.toString(), dark, title), plain.toString(), mediaDir, false);
        }
    }

    private static Result convertXlsx(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ArrayList<String> shared = readSharedStrings(zip);
            LinkedHashMap<String, String> sheets = readWorkbookSheets(zip);
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            if (sheets.isEmpty()) {
                body.append("<p class='muted'>Листы не найдены</p>");
            } else {
                body.append("<div class='tabs'>");
                int i = 0;
                for (Map.Entry<String, String> e : sheets.entrySet()) {
                    body.append("<button class='tab").append(i == 0 ? " active" : "")
                            .append("' onclick=\"showSheet(").append(i).append(")\">")
                            .append(escape(e.getKey())).append("</button>");
                    i++;
                }
                body.append("</div>");
                i = 0;
                for (Map.Entry<String, String> e : sheets.entrySet()) {
                    body.append("<div class='sheet' id='sheet").append(i).append("' style='")
                            .append(i == 0 ? "" : "display:none").append("'>");
                    plain.append("=== ").append(e.getKey()).append(" ===\n");
                    parseSheetXml(zip, e.getValue(), shared, body, plain);
                    body.append("</div>");
                    i++;
                }
            }
            String html = wrap(body.toString(), dark, title);
            // inject tiny sheet switcher
            html = html.replace("</body>",
                    "<script>function showSheet(i){var n=document.querySelectorAll('.sheet');" +
                            "for(var k=0;k<n.length;k++){n[k].style.display=k===i?'':'none';}" +
                            "var t=document.querySelectorAll('.tab');" +
                            "for(var k=0;k<t.length;k++){t[k].className=k===i?'tab active':'tab';}}</script></body>");
            return new Result(html, plain.toString(), mediaDir, false);
        }
    }

    private static Result convertPptx(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            extractMedia(zip, "ppt/media/", mediaDir);
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            int slide = 1;
            while (true) {
                ZipEntry e = zip.getEntry("ppt/slides/slide" + slide + ".xml");
                if (e == null) break;
                body.append("<section class='slide'><div class='slide-badge'>Слайд ")
                        .append(slide).append("</div>");
                plain.append("--- Слайд ").append(slide).append(" ---\n");
                parseSlideXml(zip.getInputStream(e), mediaDir, body, plain);
                body.append("</section>");
                slide++;
                if (slide > 200) break;
            }
            if (slide == 1) {
                body.append("<p class='muted'>Слайды не найдены</p>");
            }
            return new Result(wrap(body.toString(), dark, title), plain.toString(), mediaDir, false);
        }
    }

    private static Result convertOdt(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            extractMedia(zip, "Pictures/", mediaDir);
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) throw new IllegalStateException("content.xml missing");
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            parseOdfText(zip.getInputStream(entry), mediaDir, body, plain);
            return new Result(wrap(body.toString(), dark, title), plain.toString(), mediaDir, false);
        }
    }

    private static Result convertOds(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) throw new IllegalStateException("content.xml missing");
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            parseOdfSheet(zip.getInputStream(entry), body, plain);
            return new Result(wrap(body.toString(), dark, title), plain.toString(), mediaDir, false);
        }
    }

    private static Result convertOdp(File file, File mediaDir, boolean dark, String title) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            extractMedia(zip, "Pictures/", mediaDir);
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) throw new IllegalStateException("content.xml missing");
            StringBuilder body = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            parseOdfSlides(zip.getInputStream(entry), mediaDir, body, plain);
            return new Result(wrap(body.toString(), dark, title), plain.toString(), mediaDir, false);
        }
    }

    private static void parseWordXml(InputStream in, File mediaDir, StringBuilder body, StringBuilder plain) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, "UTF-8");
        boolean inParagraph = false;
        boolean inTable = false;
        boolean inRow = false;
        boolean bold = false;
        boolean italic = false;
        StringBuilder para = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("tbl".equals(name)) {
                    flushPara(body, plain, para, inParagraph, bold, italic);
                    inParagraph = false;
                    body.append("<table class='doc-table'>");
                    inTable = true;
                } else if ("tr".equals(name)) {
                    body.append("<tr>");
                    inRow = true;
                } else if ("tc".equals(name)) {
                    body.append("<td>");
                } else if ("p".equals(name)) {
                    inParagraph = true;
                    para.setLength(0);
                    bold = false;
                    italic = false;
                } else if ("br".equals(name) || "cr".equals(name)) {
                    para.append('\n');
                } else if ("tab".equals(name)) {
                    para.append('\t');
                } else if ("b".equals(name)) {
                    String val = p.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
                    bold = val == null || !"0".equals(val) && !"false".equalsIgnoreCase(val);
                } else if ("i".equals(name)) {
                    String val = p.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
                    italic = val == null || !"0".equals(val) && !"false".equalsIgnoreCase(val);
                } else if ("drawing".equals(name) || "pict".equals(name)) {
                    // skip nested, images via blip below
                } else if ("blip".equals(name)) {
                    String embed = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                    // Best-effort: dump all media already extracted; pick by index later if needed
                } else if ("t".equals(name)) {
                    // text follows
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inParagraph) {
                    String t = p.getText();
                    if (t != null && t.length() > 0) {
                        if (bold) para.append("<b>");
                        if (italic) para.append("<i>");
                        para.append(escape(t));
                        if (italic) para.append("</i>");
                        if (bold) para.append("</b>");
                        plain.append(t);
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("p".equals(name)) {
                    if (inTable && inRow) {
                        body.append("<div>").append(para).append("</div>");
                    } else {
                        body.append("<p>").append(para.length() == 0 ? "<br>" : para).append("</p>");
                    }
                    plain.append('\n');
                    inParagraph = false;
                    para.setLength(0);
                } else if ("tc".equals(name)) {
                    body.append("</td>");
                } else if ("tr".equals(name)) {
                    body.append("</tr>");
                    inRow = false;
                } else if ("tbl".equals(name)) {
                    body.append("</table>");
                    inTable = false;
                }
            }
            event = p.next();
        }
        // Append image gallery of extracted media at end if any
        File[] media = mediaDir.listFiles();
        if (media != null && media.length > 0) {
            body.append("<div class='media-gallery'>");
            for (File m : media) {
                String n = m.getName().toLowerCase(Locale.US);
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".webp")) {
                    body.append("<img src='file://").append(m.getAbsolutePath()).append("' alt=''/>");
                }
            }
            body.append("</div>");
        }
    }

    private static void flushPara(StringBuilder body, StringBuilder plain, StringBuilder para, boolean inParagraph, boolean bold, boolean italic) {
        if (!inParagraph) return;
        body.append("<p>").append(para.length() == 0 ? "<br>" : para).append("</p>");
        plain.append('\n');
        para.setLength(0);
    }

    private static ArrayList<String> readSharedStrings(ZipFile zip) throws Exception {
        ArrayList<String> out = new ArrayList<>();
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) return out;
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(zip.getInputStream(e), "UTF-8");
        StringBuilder cur = new StringBuilder();
        boolean inSi = false;
        boolean inT = false;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("si".equals(name)) {
                    inSi = true;
                    cur.setLength(0);
                } else if ("t".equals(name) && inSi) {
                    inT = true;
                }
            } else if (event == XmlPullParser.TEXT && inT) {
                cur.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if ("t".equals(name)) inT = false;
                if ("si".equals(name)) {
                    out.add(cur.toString());
                    inSi = false;
                }
            }
            event = p.next();
        }
        return out;
    }

    private static LinkedHashMap<String, String> readWorkbookSheets(ZipFile zip) throws Exception {
        LinkedHashMap<String, String> sheets = new LinkedHashMap<>();
        HashMap<String, String> rels = new HashMap<>();
        ZipEntry relEntry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (relEntry != null) {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(zip.getInputStream(relEntry), "UTF-8");
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                    String id = p.getAttributeValue(null, "Id");
                    String target = p.getAttributeValue(null, "Target");
                    if (id != null && target != null) {
                        if (!target.startsWith("/")) {
                            target = "xl/" + target.replaceFirst("^/", "");
                        } else {
                            target = target.substring(1);
                        }
                        rels.put(id, target);
                    }
                }
                event = p.next();
            }
        }
        ZipEntry wb = zip.getEntry("xl/workbook.xml");
        if (wb == null) return sheets;
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(zip.getInputStream(wb), "UTF-8");
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "sheet".equals(p.getName())) {
                String name = p.getAttributeValue(null, "name");
                if (name == null) {
                    name = p.getAttributeValue("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "name");
                }
                String rid = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                String path = rid != null ? rels.get(rid) : null;
                if (path == null && name != null) {
                    path = "xl/worksheets/sheet" + (sheets.size() + 1) + ".xml";
                }
                if (name != null && path != null) {
                    sheets.put(name, path);
                }
            }
            event = p.next();
        }
        if (sheets.isEmpty()) {
            // fallback first sheets
            for (int i = 1; i <= 20; i++) {
                String path = "xl/worksheets/sheet" + i + ".xml";
                if (zip.getEntry(path) != null) {
                    sheets.put("Лист " + i, path);
                } else break;
            }
        }
        return sheets;
    }

    private static void parseSheetXml(ZipFile zip, String path, ArrayList<String> shared,
                                      StringBuilder body, StringBuilder plain) throws Exception {
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            body.append("<p class='muted'>Лист недоступен</p>");
            return;
        }
        // Collect cells: map "r" -> value, find max row/col
        HashMap<String, String> cells = new HashMap<>();
        int maxRow = 0;
        int maxCol = 0;
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(zip.getInputStream(e), "UTF-8");
        String ref = null;
        String type = null;
        boolean inV = false;
        boolean inIs = false;
        StringBuilder v = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("c".equals(name)) {
                    ref = p.getAttributeValue(null, "r");
                    if (ref == null) ref = p.getAttributeValue("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "r");
                    type = p.getAttributeValue(null, "t");
                    if (type == null) type = p.getAttributeValue("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "t");
                    v.setLength(0);
                } else if ("v".equals(name)) {
                    inV = true;
                } else if ("is".equals(name)) {
                    inIs = true;
                } else if ("t".equals(name) && inIs) {
                    inV = true;
                }
            } else if (event == XmlPullParser.TEXT && inV) {
                v.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if ("v".equals(name) || ("t".equals(name) && inIs)) {
                    inV = false;
                } else if ("is".equals(name)) {
                    inIs = false;
                } else if ("c".equals(name) && ref != null) {
                    String val = v.toString();
                    if ("s".equals(type)) {
                        try {
                            int idx = Integer.parseInt(val);
                            if (idx >= 0 && idx < shared.size()) val = shared.get(idx);
                        } catch (Exception ignored) {
                        }
                    }
                    cells.put(ref, val);
                    int[] rc = cellRefToRowCol(ref);
                    maxRow = Math.max(maxRow, rc[0]);
                    maxCol = Math.max(maxCol, rc[1]);
                    ref = null;
                    type = null;
                }
            }
            event = p.next();
        }
        int rows = Math.min(maxRow + 1, 500);
        int cols = Math.min(maxCol + 1, 40);
        body.append("<table>");
        body.append("<thead><tr><th></th>");
        for (int c = 0; c < cols; c++) {
            body.append("<th>").append(colName(c)).append("</th>");
        }
        body.append("</tr></thead><tbody>");
        for (int r = 0; r < rows; r++) {
            body.append("<tr><th>").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                String key = colName(c) + (r + 1);
                String val = cells.get(key);
                body.append("<td>").append(val != null ? escape(val) : "").append("</td>");
                if (val != null) plain.append(val);
                plain.append('\t');
            }
            body.append("</tr>");
            plain.append('\n');
        }
        body.append("</tbody></table>");
        if (maxRow + 1 > rows || maxCol + 1 > cols) {
            body.append("<p class='muted'>Показаны первые ").append(rows).append("×").append(cols).append(" ячеек</p>");
        }
    }

    private static void parseSlideXml(InputStream in, File mediaDir, StringBuilder body, StringBuilder plain) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, "UTF-8");
        boolean inT = false;
        int event = p.getEventType();
        body.append("<div class='slide-body'>");
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG && "t".equals(name)) {
                inT = true;
            } else if (event == XmlPullParser.TEXT && inT) {
                String t = p.getText();
                if (!TextUtils.isEmpty(t)) {
                    body.append("<p>").append(escape(t)).append("</p>");
                    plain.append(t).append('\n');
                }
            } else if (event == XmlPullParser.END_TAG && "t".equals(name)) {
                inT = false;
            }
            event = p.next();
        }
        body.append("</div>");
        File[] media = mediaDir.listFiles();
        // images already global; skip per-slide for simplicity
    }

    private static void parseOdfText(InputStream in, File mediaDir, StringBuilder body, StringBuilder plain) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, "UTF-8");
        boolean capture = false;
        StringBuilder cur = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("p".equals(name) || "h".equals(name)) {
                    capture = true;
                    cur.setLength(0);
                } else if ("line-break".equals(name) && capture) {
                    cur.append('\n');
                } else if ("tab".equals(name) && capture) {
                    cur.append('\t');
                }
            } else if (event == XmlPullParser.TEXT && capture) {
                cur.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if ("p".equals(name) || "h".equals(name)) {
                    body.append("<p>").append(escape(cur.toString())).append("</p>");
                    plain.append(cur).append('\n');
                    capture = false;
                }
            }
            event = p.next();
        }
        File[] media = mediaDir.listFiles();
        if (media != null) {
            for (File m : media) {
                String n = m.getName().toLowerCase(Locale.US);
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")) {
                    body.append("<img src='file://").append(m.getAbsolutePath()).append("' alt=''/>");
                }
            }
        }
    }

    private static void parseOdfSheet(InputStream in, StringBuilder body, StringBuilder plain) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, "UTF-8");
        boolean inTable = false;
        boolean inRow = false;
        boolean inCell = false;
        StringBuilder cell = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("table".equals(name)) {
                    String tname = p.getAttributeValue("urn:oasis:names:tc:opendocument:xmlns:table:1.0", "name");
                    body.append("<h3>").append(escape(tname != null ? tname : "Лист")).append("</h3><div class='sheet'><table><tbody>");
                    plain.append("=== ").append(tname != null ? tname : "Лист").append(" ===\n");
                    inTable = true;
                } else if ("table-row".equals(name) && inTable) {
                    body.append("<tr>");
                    inRow = true;
                } else if (("table-cell".equals(name) || "covered-table-cell".equals(name)) && inRow) {
                    body.append("<td>");
                    inCell = true;
                    cell.setLength(0);
                }
            } else if (event == XmlPullParser.TEXT && inCell) {
                cell.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if (("table-cell".equals(name) || "covered-table-cell".equals(name)) && inCell) {
                    body.append(escape(cell.toString())).append("</td>");
                    plain.append(cell).append('\t');
                    inCell = false;
                } else if ("table-row".equals(name) && inRow) {
                    body.append("</tr>");
                    plain.append('\n');
                    inRow = false;
                } else if ("table".equals(name) && inTable) {
                    body.append("</tbody></table></div>");
                    inTable = false;
                }
            }
            event = p.next();
        }
    }

    private static void parseOdfSlides(InputStream in, File mediaDir, StringBuilder body, StringBuilder plain) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(in, "UTF-8");
        int slide = 0;
        boolean inPage = false;
        boolean capture = false;
        StringBuilder cur = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("page".equals(name)) {
                    slide++;
                    inPage = true;
                    body.append("<section class='slide'><div class='slide-badge'>Слайд ")
                            .append(slide).append("</div><div class='slide-body'>");
                    plain.append("--- Слайд ").append(slide).append(" ---\n");
                } else if (("p".equals(name) || "span".equals(name) || "h".equals(name)) && inPage) {
                    capture = true;
                    cur.setLength(0);
                }
            } else if (event == XmlPullParser.TEXT && capture) {
                cur.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if (("p".equals(name) || "h".equals(name)) && capture) {
                    if (cur.length() > 0) {
                        body.append("<p>").append(escape(cur.toString())).append("</p>");
                        plain.append(cur).append('\n');
                    }
                    capture = false;
                } else if ("span".equals(name)) {
                    capture = false;
                } else if ("page".equals(name) && inPage) {
                    body.append("</div></section>");
                    inPage = false;
                }
            }
            event = p.next();
        }
    }

    private static void extractMedia(ZipFile zip, String prefix, File mediaDir) {
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name == null || e.isDirectory()) continue;
                if (!name.startsWith(prefix) && !name.contains("/media/") && !name.startsWith("Pictures/")) {
                    continue;
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.isEmpty()) continue;
                String lower = base.toLowerCase(Locale.US);
                if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp"))) {
                    continue;
                }
                File out = new File(mediaDir, base);
                try (InputStream in = zip.getInputStream(e); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String wrap(String body, boolean dark, String title) {
        String bg = dark ? "#1C1B1F" : "#FFFBFE";
        String surface = dark ? "#2B2930" : "#F3EDF7";
        String text = dark ? "#E6E1E5" : "#1C1B1F";
        String muted = dark ? "#CAC4D0" : "#49454F";
        String primary = dark ? "#D0BCFF" : "#6750A4";
        String outline = dark ? "#49454F" : "#CAC4D0";
        String thBg = dark ? "#36343B" : "#E8DEF8";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'/>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=3'/>" +
                "<style>" +
                "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}" +
                "html,body{margin:0;padding:0;background:" + bg + ";color:" + text + ";" +
                "font-family:'Roboto',system-ui,-apple-system,sans-serif;font-size:15px;line-height:1.55}" +
                "body{padding:16px 16px 96px}" +
                "p{margin:0 0 12px}" +
                "h1,h2,h3{color:" + text + ";font-weight:600;letter-spacing:0}" +
                "pre.plain,pre.code{white-space:pre-wrap;word-break:break-word;background:" + surface + ";" +
                "padding:16px;border-radius:16px;border:1px solid " + outline + ";font-size:13.5px}" +
                "pre.code{font-family:ui-monospace,Consolas,monospace}" +
                "table{border-collapse:collapse;width:100%;margin:8px 0 20px;background:" + surface + ";" +
                "border-radius:16px;overflow:hidden;border:1px solid " + outline + "}" +
                "th,td{border:1px solid " + outline + ";padding:8px 10px;text-align:left;vertical-align:top}" +
                "th{background:" + thBg + ";color:" + text + ";font-weight:600;font-size:12px}" +
                ".sheet{overflow:auto;-webkit-overflow-scrolling:touch;margin-bottom:16px;" +
                "border-radius:16px;border:1px solid " + outline + "}" +
                ".sheet table{margin:0;border:0;border-radius:0}" +
                ".tabs{display:flex;gap:8px;overflow:auto;padding:4px 0 12px;position:sticky;top:0;" +
                "background:" + bg + ";z-index:2}" +
                ".tab{border:0;background:" + surface + ";color:" + muted + ";padding:8px 14px;" +
                "border-radius:999px;font-weight:600;font-size:13px}" +
                ".tab.active{background:" + primary + ";color:" + (dark ? "#381E72" : "#FFFFFF") + "}" +
                ".slide{background:" + surface + ";border-radius:24px;padding:20px;margin:0 0 16px;" +
                "border:1px solid " + outline + ";box-shadow:0 8px 24px rgba(0,0,0," + (dark ? "0.35" : "0.06") + ")}" +
                ".slide-badge{display:inline-block;background:" + thBg + ";color:" + primary + ";" +
                "font-size:12px;font-weight:700;padding:4px 10px;border-radius:999px;margin-bottom:12px}" +
                ".doc-table{margin:12px 0}" +
                "img{max-width:100%;border-radius:12px;margin:8px 0;display:block}" +
                ".media-gallery{display:grid;gap:12px;margin-top:16px}" +
                ".muted{color:" + muted + "}" +
                ".legacy{background:" + surface + ";border-radius:28px;padding:28px 22px;text-align:center;" +
                "border:1px solid " + outline + "}" +
                ".legacy h2{margin:0 0 8px;font-size:20px}" +
                ".legacy p{color:" + muted + "}" +
                "mark.search-hit{background:#F9DEDC;color:#410E0B;border-radius:4px;padding:0 2px}" +
                "mark.search-hit.current{background:#EFB8C8;outline:2px solid " + primary + "}" +
                "</style></head><body>" + body + "</body></html>";
    }

    private static String legacyNoticeHtml(String fileName) {
        return "<div class='legacy'><h2>Формат «." + escape(MglaDocumentHelper.extensionOf(fileName)) +
                "»</h2><p>Старые файлы Office (.doc / .xls / .ppt) открываются через внешнее приложение. " +
                "Современные .docx / .xlsx / .pptx поддерживаются встроенно.</p></div>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripRtf(String rtf) {
        StringBuilder out = new StringBuilder();
        boolean slash = false;
        boolean group = false;
        for (int i = 0; i < rtf.length(); i++) {
            char c = rtf.charAt(i);
            if (slash) {
                // skip control word
                while (i < rtf.length() && (Character.isLetter(rtf.charAt(i)) || rtf.charAt(i) == '-')) i++;
                if (i < rtf.length() && rtf.charAt(i) == ' ') { /* skip space after word */ }
                else i--;
                slash = false;
                continue;
            }
            if (c == '\\') {
                if (i + 1 < rtf.length()) {
                    char n = rtf.charAt(i + 1);
                    if (n == '{' || n == '}' || n == '\\') {
                        out.append(n);
                        i++;
                        continue;
                    }
                    if (n == '\'') {
                        // hex char \'hh
                        if (i + 3 < rtf.length()) {
                            try {
                                int v = Integer.parseInt(rtf.substring(i + 2, i + 4), 16);
                                out.append((char) v);
                                i += 3;
                            } catch (Exception ignored) {
                            }
                        }
                        continue;
                    }
                }
                slash = true;
                continue;
            }
            if (c == '{' || c == '}') continue;
            out.append(c);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static ArrayList<String> splitCsvLine(String line, char sep) {
        ArrayList<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    q = !q;
                }
            } else if (c == sep && !q) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols;
    }

    private static int[] cellRefToRowCol(String ref) {
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        col = Math.max(0, col - 1);
        int row = 0;
        try {
            row = Integer.parseInt(ref.substring(i)) - 1;
        } catch (Exception ignored) {
        }
        return new int[]{Math.max(0, row), col};
    }

    private static String colName(int index) {
        StringBuilder s = new StringBuilder();
        int n = index;
        do {
            s.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return s.toString();
    }

    private static String readTextLimited(File file, int maxBytes) throws Exception {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(file); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = fis.read(buf)) >= 0) {
                int write = Math.min(n, maxBytes - total);
                if (write <= 0) break;
                bos.write(buf, 0, write);
                total += write;
                if (total >= maxBytes) break;
            }
            data = bos.toByteArray();
        }
        Charset cs = detectCharset(data);
        String s = new String(data, cs);
        if (data.length >= maxBytes) {
            s += "\n\n… файл обрезан для просмотра";
        }
        return s;
    }

    private static Charset detectCharset(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        // Heuristic: if mostly valid UTF-8, use it; else Windows-1251 for RU docs
        int bad = 0;
        for (int i = 0; i < Math.min(data.length, 4096); ) {
            int c = data[i] & 0xFF;
            if (c <= 0x7F) {
                i++;
            } else if ((c >> 5) == 0x6 && i + 1 < data.length && (data[i + 1] & 0xC0) == 0x80) {
                i += 2;
            } else if ((c >> 4) == 0xE && i + 2 < data.length && (data[i + 1] & 0xC0) == 0x80 && (data[i + 2] & 0xC0) == 0x80) {
                i += 3;
            } else {
                bad++;
                i++;
            }
        }
        if (bad > 8) {
            try {
                return Charset.forName("windows-1251");
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
