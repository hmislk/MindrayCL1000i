package org.carecode.mw.lims.mw.mindrayCL1000i;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.carecode.lims.libraries.DataBundle;
import org.carecode.lims.libraries.QueryRecord;
import org.carecode.lims.libraries.ResultsRecord;
import static org.carecode.mw.lims.mw.mindrayCL1000i.MindrayCL1000i.logger;

public class LISCommunicator {

//    static boolean testing = true;
    private static final Gson gson = new Gson();

    public static DataBundle pullTestOrdersForSampleRequests(QueryRecord queryRecord) {
        logger.info("pullTestOrdersForSampleRequests — sampleId={}", queryRecord.getSampleId());
        try {
            String endpoint = SettingsLoader.getSettings().getLimsSettings().getLimsServerBaseUrl() + "/test_orders_for_sample_requests";
            logger.info("Querying LIS for orders: {}", endpoint);
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            DataBundle databundle = new DataBundle();
            databundle.setMiddlewareSettings(SettingsLoader.getSettings());
            databundle.getQueryRecords().add(queryRecord);
            String jsonInputString = gson.toJson(databundle);
            logger.debug("Order query payload: {}", jsonInputString);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            logger.info("LIS order query response code: {}", responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                logger.debug("LIS order query response body: {}", response);
                DataBundle patientDataBundle = gson.fromJson(response.toString(), DataBundle.class);
                logger.info("Orders received from LIS: {}", patientDataBundle);
                return patientDataBundle;
            } else {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"));
                StringBuilder errBody = new StringBuilder();
                String line;
                while ((line = errReader.readLine()) != null) {
                    errBody.append(line);
                }
                errReader.close();
                logger.error("LIS order query FAILED — responseCode={}, body={}", responseCode, errBody);
            }
        } catch (Exception e) {
            logger.error("Exception while querying orders from LIS — check that the LIS server is reachable at: {}",
                    SettingsLoader.getSettings().getLimsSettings().getLimsServerBaseUrl(), e);
        }
        return null;
    }

    private static void fixResultSampleIds(DataBundle bundle) {
        if (bundle.getPatientRecord() == null) {
            logger.warn("fixResultSampleIds — no patientRecord in bundle, sampleIds unchanged");
            return;
        }
        String correctId = bundle.getPatientRecord().getAdditionalId();
        if (correctId == null || correctId.isEmpty()) {
            logger.warn("fixResultSampleIds — patientRecord.additionalId is blank, sampleIds unchanged");
            return;
        }
        for (ResultsRecord rr : bundle.getResultsRecords()) {
            logger.info("fixResultSampleIds — replacing sampleId '{}' with additionalId '{}'", rr.getSampleId(), correctId);
            rr.setSampleId(correctId);
        }
    }

    public static void pushResults(DataBundle patientDataBundle) {
        fixResultSampleIds(patientDataBundle);
        ResultFileLogger.logResults(patientDataBundle.getResultsRecords());
        logger.info("pushResults called");
        try {
            String pushResultsEndpoint = SettingsLoader.getSettings().getLimsSettings().getLimsServerBaseUrl() + "/test_results";
            logger.info("Pushing results to: {}", pushResultsEndpoint);

            for (ResultsRecord rr : patientDataBundle.getResultsRecords()) {
                logger.info("Result to push — sampleId={}, testCode={}, value={}, valueString={}",
                        rr.getSampleId(), rr.getTestCode(), rr.getResultValue(), rr.getResultValueString());
            }

            URL url = new URL(pushResultsEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            patientDataBundle.setMiddlewareSettings(SettingsLoader.getSettings());
            String jsonInputString = gson.toJson(patientDataBundle);
            logger.info("Sending JSON payload: {}", jsonInputString);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            logger.info("LIS response code: {}", responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                logger.info("LIS response body: {}", response);

                JsonObject responseObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                String status = responseObject.get("status").getAsString();
                logger.info("LIS status: {}", status);

                Gson gson = new Gson();
                JsonArray detailsArray = responseObject.getAsJsonArray("details");
                List<ResultsRecord> resultsRecords = new ArrayList<>();
                for (JsonElement element : detailsArray) {
                    resultsRecords.add(gson.fromJson(element, ResultsRecord.class));
                }
                for (ResultsRecord record : resultsRecords) {
                    logger.info("Result accepted — sampleId={}, testCode={}, status={}",
                            record.getSampleId(), record.getTestCode(), record.getStatus());
                }
            } else {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"));
                StringBuilder errBody = new StringBuilder();
                String line;
                while ((line = errReader.readLine()) != null) {
                    errBody.append(line);
                }
                errReader.close();
                logger.error("LIS push FAILED — responseCode={}, body={}", responseCode, errBody);
            }
        } catch (Exception e) {
            logger.error("Exception while pushing results to LIS — check that the LIS server is reachable at: {}",
                    SettingsLoader.getSettings().getLimsSettings().getLimsServerBaseUrl(), e);
        }
    }

}
