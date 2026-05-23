package org.carecode.mw.lims.mw.mindrayCL1000i;

import org.carecode.lims.libraries.ResultsRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.carecode.mw.lims.mw.mindrayCL1000i.MindrayCL1000i.logger;

public class ResultFileLogger {

    private static final String DIVIDER  = "+-----------------------+------------------+------------+-------------+------------+";
    private static final String HEADER_ROW = "| Sent At               | Sample ID        | Test Code  | Result      | Units      |";

    public static void logResults(List<ResultsRecord> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        ResultLogSettings cfg = SettingsLoader.getResultLogSettings();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(cfg.getDateFmt());
        DateTimeFormatter tsFmt   = DateTimeFormatter.ofPattern(cfg.getTimestampFmt());
        String today  = LocalDate.now().format(dateFmt);
        String sendAt = LocalDateTime.now().format(tsFmt);
        Path dir  = Paths.get(cfg.getLogDir());
        Path file = dir.resolve(today + ".txt");
        try {
            Files.createDirectories(dir);
            boolean isNewFile = !Files.exists(file) || Files.size(file) == 0;
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (isNewFile) {
                    w.write("================================================================");
                    w.newLine();
                    w.write("  RESULT LOG — " + today);
                    w.newLine();
                    w.write("================================================================");
                    w.newLine();
                    w.write(DIVIDER);
                    w.newLine();
                    w.write(HEADER_ROW);
                    w.newLine();
                    w.write(DIVIDER);
                    w.newLine();
                }
                for (ResultsRecord rr : results) {
                    String line = String.format("| %-21s | %-16s | %-10s | %-11s | %-10s |",
                            sendAt,
                            rr.getSampleId(),
                            rr.getTestCode(),
                            rr.getResultValueString(),
                            rr.getResultUnits());
                    w.write(line);
                    w.newLine();
                    w.write(DIVIDER);
                    w.newLine();
                }
            }
            logger.info("Result log written to {}", file);
        } catch (IOException e) {
            logger.error("Failed to write result log to {}", file, e);
        }
    }
}
