package io.saasforge.sdk.io;

import io.saasforge.sdk.core.api.ForgeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** SAX 逐行读取；共享字符串的内容和索引都在磁盘，避免高基数字符串占满堆。 */
final class XlsxRows {
    private XlsxRows() {}

    static void read(Path path, int width, BiConsumer<Integer, List<String>> consumer) throws Exception {
        try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ);
                var strings = new Strings()) {
            var reader = new XSSFReader(pkg);
            try (var shared = reader.getSharedStringsData()) {
                if (shared != null) {
                    strings.load(shared);
                }
            }
            var sheets = reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new ForgeException(IoErrorCode.INVALID_FILE);
            }
            try (var sheet = sheets.next()) {
                parse(sheet, new DefaultHandler() {
                    private final List<String> row = new ArrayList<>(Collections.nCopies(width, ""));
                    private final StringBuilder text = new StringBuilder();
                    private String cellType;
                    private int column;
                    private int rowIndex;
                    private boolean reading;
                    private boolean formula;

                    @Override
                    public void startElement(String uri, String local, String q, Attributes attrs) {
                        if ("row".equals(local)) {
                            Collections.fill(row, "");
                            rowIndex =
                                    attrs.getValue("r") == null ? rowIndex + 1 : Integer.parseInt(attrs.getValue("r"));
                        }
                        if ("c".equals(local)) {
                            String address = attrs.getValue("r");
                            column = 0;
                            if (address != null) {
                                for (char value : address.toCharArray()) {
                                    if (value < 'A' || value > 'Z') {
                                        break;
                                    }
                                    column = column * 26 + value - 'A' + 1;
                                }
                                column--;
                            }
                            if (column < 0 || column >= width) {
                                throw new ForgeException(IoErrorCode.INVALID_FILE);
                            }
                            cellType = attrs.getValue("t");
                            text.setLength(0);
                            formula = false;
                        }
                        if ("f".equals(local)) {
                            formula = true;
                        }
                        if ("v".equals(local) || "t".equals(local)) {
                            reading = true;
                        }
                    }

                    @Override
                    public void characters(char[] chars, int start, int length) {
                        if (reading) {
                            if (text.length() + length > 32767) {
                                throw new ForgeException(IoErrorCode.INVALID_FILE);
                            }
                            text.append(chars, start, length);
                        }
                    }

                    @Override
                    public void endElement(String uri, String local, String q) {
                        if ("v".equals(local) || "t".equals(local)) {
                            reading = false;
                        }
                        if ("c".equals(local)) {
                            if (formula || "e".equals(cellType)) {
                                throw new ForgeException(IoErrorCode.INVALID_FILE);
                            }
                            String value = text.toString();
                            if ("s".equals(cellType)) {
                                try {
                                    value = strings.get(Long.parseLong(value));
                                } catch (IOException error) {
                                    throw new UncheckedIOException(error);
                                }
                            }
                            row.set(column, value);
                        }
                        if ("row".equals(local)) {
                            consumer.accept(rowIndex, List.copyOf(row));
                        }
                    }
                });
            }
        }
    }

    private static void parse(InputStream input, DefaultHandler handler) throws Exception {
        var parser = XMLHelper.newXMLReader();
        parser.setContentHandler(handler);
        parser.parse(new InputSource(input));
    }

    private static final class Strings implements AutoCloseable {
        private final Path dataPath = Files.createTempFile("forge-strings-", ".data");
        private final Path indexPath = Files.createTempFile("forge-strings-", ".index");
        private final RandomAccessFile data = new RandomAccessFile(dataPath.toFile(), "rw");
        private final RandomAccessFile index = new RandomAccessFile(indexPath.toFile(), "rw");

        Strings() throws IOException {}

        void load(InputStream input) throws Exception {
            parse(input, new DefaultHandler() {
                private final StringBuilder text = new StringBuilder();
                private boolean reading;

                @Override
                public void startElement(String uri, String local, String q, Attributes attrs) {
                    if ("si".equals(local)) {
                        text.setLength(0);
                    }
                    if ("t".equals(local)) {
                        reading = true;
                    }
                }

                @Override
                public void characters(char[] chars, int start, int length) {
                    if (reading) {
                        if (text.length() + length > 32767) {
                            throw new ForgeException(IoErrorCode.INVALID_FILE);
                        }
                        text.append(chars, start, length);
                    }
                }

                @Override
                public void endElement(String uri, String local, String q) {
                    if ("t".equals(local)) {
                        reading = false;
                    }
                    if ("si".equals(local)) {
                        try {
                            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                            index.writeLong(data.getFilePointer());
                            data.writeInt(bytes.length);
                            data.write(bytes);
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }
                }
            });
        }

        String get(long id) throws IOException {
            if (id < 0 || id >= index.length() / 8) {
                throw new ForgeException(IoErrorCode.INVALID_FILE);
            }
            index.seek(id * 8);
            data.seek(index.readLong());
            byte[] value = new byte[data.readInt()];
            data.readFully(value);
            return new String(value, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            try {
                data.close();
            } finally {
                try {
                    index.close();
                } finally {
                    Files.deleteIfExists(dataPath);
                    Files.deleteIfExists(indexPath);
                }
            }
        }
    }
}
