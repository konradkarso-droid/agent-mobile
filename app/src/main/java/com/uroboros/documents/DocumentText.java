package com.uroboros.documents;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Рабочая копия хранит текст абзацев. Оформление оригинала остаётся в исходном файле. */
public final class DocumentText {
    private DocumentText() {}
    // Объявленные пределы для телефона, включая распакованные данные.
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CHARS = 120000;
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("Превышен предел размера документа");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    public static String read(String format, byte[] bytes) throws Exception {
        if (bytes.length > MAX_BYTES) throw new IOException("Файл слишком велик");
        String text;
        if ("txt".equals(format)) {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
        } else if ("docx".equals(format)) {
            text = readWord(bytes);
        } else throw new IOException("Неизвестный формат");
        if (text.trim().isEmpty()) throw new IOException("В документе нет текста");
        if (text.length() > MAX_CHARS || text.indexOf(0) >= 0) throw new IOException("Текст слишком велик или повреждён");
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean wordNamespace(String ns) {
        return WORD.equals(ns) || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(ns);
    }

    private static String readWord(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int remaining = MAX_BYTES * 2;
            for (int entries = 0; entries < 512; entries++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                byte[] content = bounded(zip, remaining);
                remaining -= content.length;
                if (!entry.getName().equals("word/document.xml")) continue;
                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                // Разбираем только UTF-8 XML. Запрет DTD действует и на Android SAX.
                String xml = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
                if (xml.contains("<!DOCTYPE")) throw new IOException("DTD в документе");
                StringBuilder out = new StringBuilder();
                factory.newSAXParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
                    boolean inText;
                    public void startElement(String ns, String name, String q, Attributes a) {
                        if (!wordNamespace(ns)) return;
                        if (name.equals("t")) inText = true;
                        if (name.equals("br")) out.append('\n');
                        if (name.equals("tab")) out.append('\t');
                    }
                    public void characters(char[] chars, int start, int length) throws SAXException {
                        if (inText) out.append(chars, start, length);
                        if (out.length() > MAX_CHARS + 1) throw new SAXException("Текст слишком велик");
                    }
                    public void endElement(String ns, String name, String q) {
                        if (!wordNamespace(ns)) return;
                        if (name.equals("t")) inText = false;
                        if (name.equals("p")) out.append('\n');
                    }
                });
                if (out.length() > 0 && out.charAt(out.length()-1) == '\n') out.setLength(out.length()-1);
                return out.toString();
            }
        }
        throw new IOException("Не найден основной текст Word");
    }

    public static byte[] write(String format, String text) throws IOException {
        if (text.length() > MAX_CHARS) throw new IOException("Текст слишком велик");
        if ("txt".equals(format)) return text.getBytes(StandardCharsets.UTF_8);
        if (!"docx".equals(format)) throw new IOException("Неизвестный формат");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            StringBuilder body = new StringBuilder("<w:document xmlns:w=\"").append(WORD).append("\"><w:body>");
            for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</w:t></w:r></w:p>");
            }
            entry(zip, "word/document.xml", body.append("</w:body></w:document>").toString());
        }
        return file.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
