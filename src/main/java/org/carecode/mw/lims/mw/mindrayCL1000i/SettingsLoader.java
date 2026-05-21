package org.carecode.mw.lims.mw.mindrayCL1000i;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.MiddlewareSettings;

public class SettingsLoader {

    private static final Logger logger = LogManager.getLogger(SettingsLoader.class);
    private static MiddlewareSettings middlewareSettings;
    private static ResultLogSettings resultLogSettings;

    public static void loadSettings() {
        Gson gson = new Gson();
        try {
            String filePath = "D:\\ccmv\\settings\\mindrayCL1000i\\config.json";
            String jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
            System.out.println("Contents of config.json:");
            System.out.println(jsonContent);

            try (FileReader reader = new FileReader(filePath)) {
                middlewareSettings = gson.fromJson(reader, MiddlewareSettings.class);
                logger.info("Settings loaded from config.json");

                System.out.println("MiddlewareSettings loaded:");
                System.out.println(middlewareSettings);

                if (middlewareSettings.getAnalyzerDetails() != null) {
                    System.out.println("Analyzer Name: " + middlewareSettings.getAnalyzerDetails().getAnalyzerName());
                } else {
                    System.out.println("AnalyzerDetails is null");
                }

                if (middlewareSettings.getLimsSettings() != null) {
                    System.out.println("LIMS Server Base URL: " + middlewareSettings.getLimsSettings().getLimsServerBaseUrl());
                } else {
                    System.out.println("LimsSettings is null");
                }
            }

            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            if (root.has("resultLogSettings")) {
                resultLogSettings = gson.fromJson(root.get("resultLogSettings"), ResultLogSettings.class);
                logger.info("ResultLogSettings loaded — logDir={}", resultLogSettings.getLogDir());
            } else {
                resultLogSettings = new ResultLogSettings();
                logger.info("resultLogSettings not found in config.json — using defaults");
            }

        } catch (IOException e) {
            logger.error("Failed to load settings from config.json", e);
            System.out.println("Failed to load settings: " + e.getMessage());
        }
    }

    public static MiddlewareSettings getSettings() {
        if (middlewareSettings == null) {
            loadSettings();
        }
        return middlewareSettings;
    }

    public static ResultLogSettings getResultLogSettings() {
        if (resultLogSettings == null) {
            loadSettings();
        }
        return resultLogSettings;
    }
}
