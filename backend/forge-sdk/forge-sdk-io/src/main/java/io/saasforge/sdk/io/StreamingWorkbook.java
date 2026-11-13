package io.saasforge.sdk.io;

import java.io.IOException;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 未写出（如导入成功或行数超限）的工作簿也先刷出行，再关闭临时文件，避免 POI close 时向已关闭的 writer 写入。 */
final class StreamingWorkbook extends SXSSFWorkbook {
    StreamingWorkbook() {
        super(100);
        setCompressTempFiles(true);
    }

    @Override
    public void close() throws IOException {
        try {
            for (var sheet : this) {
                ((SXSSFSheet) sheet).flushRows();
            }
        } finally {
            super.close();
        }
    }
}
