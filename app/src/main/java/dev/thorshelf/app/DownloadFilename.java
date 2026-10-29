package dev.thorshelf.app;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Resolves download metadata consistently on every supported Android version. */
public final class DownloadFilename {
    private static final int MAX_HEADER_LENGTH = 16384;

    private DownloadFilename() { }

    /** Returns a safe supported filename, or null when the response cannot be identified. */
    public static String resolve(String url, String contentDisposition, String mimeType) {
        if (!DownloadPolicy.isAllowedDownload(url)) return null;
        return resolveFilename(url, contentDisposition, mimeType, false);
    }

    /** DS downloads accept direct ROMs and ZIP bundles from their own public sources. */
    public static String resolveNds(String url, String contentDisposition, String mimeType) {
        if (!DownloadPolicy.isAllowedNdsDownload(url)) return null;
        String name = resolveFilename(url, contentDisposition, mimeType, true);
        String archiveName = DownloadPolicy.ndsArchiveFilename(url);
        return archiveName == null || GameFilename.clean(archiveName).equals(name) ? name : null;
    }

    /** GBA response metadata must identify the exact raw cartridge requested from the catalog. */
    public static String resolveGba(String url, String contentDisposition, String mimeType) {
        String expected = DownloadPolicy.gbaArchiveFilename(url);
        if (expected == null || isDocumentResponse(mimeType)) return null;
        String name = responseFilename(url, contentDisposition);
        // Compare before cleaning: different editions or long names can clean to the same name.
        if (!expected.equals(name) || !DownloadPolicy.isGbaContentFile(name)) return null;
        return GameFilename.clean(name);
    }

    private static String resolveFilename(String url, String contentDisposition, String mimeType,
                                          boolean nds) {
        if (isDocumentResponse(mimeType)) return null;
        String name = responseFilename(url, contentDisposition);
        // Check before sanitizing: safeFilename's generic default must not invent a CIA type.
        if (name == null || !isContentFile(name.trim(), nds)) return null;
        String safe = GameFilename.clean(name);
        return isContentFile(safe, nds) ? safe : null;
    }

    private static String responseFilename(String url, String contentDisposition) {
        if (contentDisposition != null && contentDisposition.length() > MAX_HEADER_LENGTH) return null;
        HeaderNames names = parseHeader(contentDisposition);
        if (names.ambiguous) return null;
        String name = names.extended != null ? names.extended : names.plain;
        if (name == null && !names.hasFilename) {
            String path = URI.create(url).getRawPath();
            if (path != null && !path.endsWith("/")) {
                name = decodePercent(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
            }
        }
        return name;
    }

    private static boolean isContentFile(String name, boolean nds) {
        return nds ? DownloadPolicy.isNdsContentFile(name) : DownloadPolicy.isContentFile(name);
    }

    private static boolean isDocumentResponse(String mimeType) {
        if (mimeType == null) return false;
        if (mimeType.length() > 1024) return true;
        String type = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.equals("application/json")
                || type.equals("application/xml") || type.endsWith("+json") || type.endsWith("+xml")
                || type.equals("application/javascript") || type.equals("application/x-javascript");
    }

    private static HeaderNames parseHeader(String header) {
        HeaderNames result = new HeaderNames();
        if (header == null) return result;
        int position = header.indexOf(';');
        if (position < 0) return result;
        boolean sawPlain = false;
        boolean sawExtended = false;
        while (position < header.length()) {
            while (position < header.length()
                    && (header.charAt(position) == ';' || Character.isWhitespace(header.charAt(position)))) {
                position++;
            }
            int keyStart = position;
            while (position < header.length() && header.charAt(position) != '='
                    && header.charAt(position) != ';' && !Character.isWhitespace(header.charAt(position))) {
                position++;
            }
            String key = header.substring(keyStart, position).toLowerCase(Locale.ROOT);
            while (position < header.length() && Character.isWhitespace(header.charAt(position))) position++;
            if (position == header.length() || header.charAt(position) != '=') {
                position = nextParameter(header, position);
                continue;
            }
            position++;
            while (position < header.length() && Character.isWhitespace(header.charAt(position))) position++;
            String value;
            if (position < header.length() && header.charAt(position) == '"') {
                position++;
                StringBuilder quoted = new StringBuilder();
                boolean closed = false;
                while (position < header.length()) {
                    char c = header.charAt(position++);
                    if (c == '"') {
                        closed = true;
                        break;
                    }
                    if (c == '\\' && position < header.length()) c = header.charAt(position++);
                    quoted.append(c);
                }
                value = closed ? quoted.toString() : null;
                while (position < header.length() && Character.isWhitespace(header.charAt(position))) position++;
                if (position < header.length() && header.charAt(position) != ';') {
                    value = null;
                    position = nextParameter(header, position);
                }
            } else {
                int valueStart = position;
                position = nextParameter(header, position);
                value = header.substring(valueStart, position).trim();
            }
            if ("filename*".equals(key)) {
                result.hasFilename = true;
                if (sawExtended) result.ambiguous = true;
                sawExtended = true;
                result.extended = decodeExtended(value);
            } else if ("filename".equals(key)) {
                result.hasFilename = true;
                if (sawPlain) result.ambiguous = true;
                sawPlain = true;
                result.plain = value == null || value.trim().isEmpty() ? null : value;
            }
        }
        return result;
    }

    private static int nextParameter(String header, int position) {
        int semicolon = header.indexOf(';', position);
        return semicolon < 0 ? header.length() : semicolon;
    }

    private static String decodeExtended(String value) {
        if (value == null) return null;
        int first = value.indexOf('\'');
        int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
        if (first < 0 || second < 0 || second == value.length() - 1) return null;
        String charsetName = value.substring(0, first);
        Charset charset;
        if (charsetName.equalsIgnoreCase("UTF-8")) charset = StandardCharsets.UTF_8;
        else if (charsetName.equalsIgnoreCase("ISO-8859-1")) charset = StandardCharsets.ISO_8859_1;
        else return null;
        return decodePercent(value.substring(second + 1), charset);
    }

    /** Percent encoding is not form encoding: literal plus signs must stay plus signs. */
    private static String decodePercent(String value, Charset charset) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char c = value.charAt(index);
            if (c == '%') {
                if (index + 2 >= value.length()) return null;
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) return null;
                bytes.write((high << 4) | low);
                index += 3;
            } else {
                int end = index + Character.charCount(value.codePointAt(index));
                byte[] literal = value.substring(index, end).getBytes(charset);
                bytes.write(literal, 0, literal.length);
                index = end;
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalidEncoding) {
            return null;
        }
    }

    private static final class HeaderNames {
        String plain;
        String extended;
        boolean hasFilename;
        boolean ambiguous;
    }
}
