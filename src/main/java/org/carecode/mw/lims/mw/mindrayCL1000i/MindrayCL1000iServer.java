package org.carecode.mw.lims.mw.mindrayCL1000i;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.DataBundle;
import org.carecode.lims.libraries.OrderRecord;
import org.carecode.lims.libraries.PatientRecord;
import org.carecode.lims.libraries.QueryRecord;
import org.carecode.lims.libraries.ResultsRecord;

public class MindrayCL1000iServer {

    private static final Logger logger = LogManager.getLogger(MindrayCL1000iServer.class);

    private static final char ENQ = 0x05;
    private static final char ACK = 0x06;
    private static final char STX = 0x02;
    private static final char ETX = 0x03;
    private static final char EOT = 0x04;
    private static final char CR = 0x0D;  // Carriage Return
    private static final char LF = 0x0A;  // Line Feed
    private static final char NAK = 0x15;
    private static final char NAN = 0x00; // Line Feed

    static String fieldD = "|";
    static String repeatD = Character.toString((char) 92);
    static String componentD = "^";
    static String escapeD = "&";

    boolean receivingQuery;
    boolean receivingResults;
    boolean respondingQuery;
    boolean respondingResults;
    boolean testing;
    boolean needToSendHeaderRecordForQuery;
    boolean needToSendPatientRecordForQuery;
    boolean needToSendOrderingRecordForQuery;
    boolean needToSendEotForRecordForQuery;
    boolean needToSendFinalEot;  // set after empty H+L frame, signals next ACK should send EOT

    private DataBundle patientDataBundle = new DataBundle();

    String patientId;
    String sampleId;
    List<String> testNames;
    int frameNumber;
    char terminationCode = 'N';
    PatientRecord patientRecord;
    ResultsRecord resultRecord;
    OrderRecord orderRecord;
    QueryRecord queryRecord;

    private static ServerSocket serverSocket;
    private static int port; // Port needs to be static for restart

    int maxUnexpectedDataLimit = 1000;
    int unexpectedDataCount = 0;

    private static String safeField(String[] fields, int index) {
        return index < fields.length ? fields[index] : "";
    }

    private static String safeComponent(String[] components, int index) {
        return index < components.length ? components[index] : "";
    }

    public void start(int port) {
        port = SettingsLoader.getSettings().getAnalyzerDetails().getAnalyzerPort();
//        IndikoServer.port = port;  // Assign port to static variable for restart
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Server started on port " + port);
            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    logger.info("New client connected: " + clientSocket.getInetAddress().getHostAddress());

                    handleClient(clientSocket);
//                    handleClientTest1(clientSocket);

                } catch (IOException e) {
                    logger.error("Error handling client connection", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error starting server on port " + port, e);
        } finally {
            stop();
        }
    }

    public static void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logger.info("Server stopped.");
            }
        } catch (IOException e) {
            logger.error("Error stopping server", e);
        }
    }

    public static void restartServer() {
        try {
            logger.info("Stopping server...");
            stop();

            logger.info("Waiting before restart...");
            Thread.sleep(2000);  // Pause for 2 seconds before restarting

            logger.info("Restarting server on port " + port + "...");
            MindrayCL1000iServer server = new MindrayCL1000iServer();  // Create a new instance of IndikoServer
            server.start(port);  // Restart server on the same port
            logger.info("Server restarted successfully.");

        } catch (InterruptedException e) {
            logger.error("Error while restarting the server", e);
            Thread.currentThread().interrupt();
        }
    }

    private void handleClientTest1(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        logger.info("Handling new client connection from: " + clientAddress);

        try (InputStream in = new BufferedInputStream(clientSocket.getInputStream())) {
            clientSocket.setSoTimeout(55000);
            logger.info("Socket timeout set to 55000ms for client: " + clientAddress);

            boolean sessionActive = true;
            boolean receivedData = false; // Track if any data was received

            while (sessionActive) {
                try {
                    int data = in.read();
                    if (data == -1) {
                        logger.warn("Client {} cleanly closed the connection.", clientAddress);
                        sessionActive = false;
                    } else {
                        // Log at INFO level to ensure visibility
                        logger.info("Received byte: 0x{} ({}) | ASCII: {}",
                                Integer.toHexString(data).toUpperCase(),
                                data,
                                (data >= 32 && data <= 126) ? (char) data : "[non-printable]");
                        receivedData = true;
                    }
                } catch (SocketTimeoutException e) {
                    logger.warn("Socket timeout for client {}. No data received in 55s.", clientAddress);
                    sessionActive = false;
                } catch (IOException e) {
                    // Differentiate between connection resets and other errors
                    if (e.getMessage().contains("Connection reset")) {
                        logger.warn("Client {} forcibly closed the connection. Received data before error: {}",
                                clientAddress, receivedData ? "YES" : "NO");
                    } else {
                        logger.error("I/O error with client {}: {}", clientAddress, e.getMessage(), e);
                    }
                    sessionActive = false;
                }
            }
        } catch (IOException e) {
            logger.error("General I/O error for client {}: {}", clientAddress, e.getMessage(), e);
        } finally {
            try {
                clientSocket.close();
                logger.info("Closed connection with client: {}", clientAddress);
            } catch (IOException e) {
                logger.error("Error closing socket for client {}: {}", clientAddress, e.getMessage());
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        logger.info("Handling new client connection from: " + clientSocket.getInetAddress().getHostAddress());

        try (InputStream in = new BufferedInputStream(clientSocket.getInputStream()); OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            clientSocket.setSoTimeout(30000); // 30s inter-frame timeout per ASTM spec
            logger.info("Socket timeout set to 30000ms");

            // CL-1000i waits for the host to initiate — send ENQ as a greeting.
            // handleAck() will send EOT immediately if no orders are pending,
            // putting both sides into neutral mode and keeping the connection alive.
            out.write(ENQ);
            out.flush();
            logger.info("Sent ENQ greeting to analyzer on connect.");

            boolean sessionActive = true;
            while (sessionActive) {
                int data;
                try {
                    data = in.read(); // Read next byte
                    if (data == -1) {
                        logger.warn("End of stream reached. Client may have disconnected unexpectedly.");
                        sessionActive = false;
                        break;
                    }
                    logger.info("Received byte: 0x{} ({}) | ASCII: {}",
                            String.format("%02X", data), data,
                            (data >= 32 && data <= 126) ? String.valueOf((char) data) : "[ctrl]");
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Connection reset")) {
                        logger.info("Connection reset by analyzer — session ended normally.");
                    } else {
                        logger.error("IOException while reading from socket. Client may have disconnected.", e);
                    }
                    break;
                }

                switch (data) {
                    case ENQ:
                        logger.info("Received ENQ from client.");
                        out.write(ACK);
                        out.flush();
                        logger.info("Sent ACK in response to ENQ.");
                        break;

                    case ACK:
                        logger.info("Received ACK from client.");
                        handleAck(clientSocket, out);
                        break;

                    case STX:
                        logger.info("Received STX, waiting for message...");
                        StringBuilder message = new StringBuilder();
                        while ((data = in.read()) != ETX && data != 0x17 /* ETB */) {
                            if (data == -1) {
                                logger.error("Unexpected end of stream while reading message.");
                                sessionActive = false;
                                break;
                            }
                            message.append((char) data);
                        }
                        // Consume checksum (2 bytes) + CR + LF that follow ETX/ETB
                        in.read(); in.read(); in.read(); in.read();

                        logger.info("Complete message received: " + message);
                        processMessage(message.toString(), clientSocket);
                        out.write(ACK);
                        out.flush();
                        logger.info("Sent ACK after processing message.");
                        break;

                    case EOT:
                        logger.info("Received EOT (End of Transmission).");
                        handleEot(out);
                        // Keep session open if we sent ENQ in handleEot and are waiting for ACK.
                        if (!respondingQuery) {
                            sessionActive = false;
                        }
                        break;

                    default:
                        logger.warn("Unexpected byte received: " + data + " (ASCII: " + (char) data + ")");
                        break;
                }
            }
        } catch (IOException e) {
            logger.error("IOException in client communication loop.", e);
        } finally {
            logger.info("Client session ended.");
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.error("Error closing client socket.", e);
            }
        }
    }

    private void handleAck(Socket clientSocket, OutputStream out) throws IOException {
        System.out.println("handleAck = ");
        System.out.println("needToSendHeaderRecordForQuery = " + needToSendHeaderRecordForQuery);
        if (needToSendHeaderRecordForQuery) {
            logger.debug("Sending Header");
            String hm = createLimsHeaderRecord();
            sendResponse(hm, clientSocket);
            frameNumber = 2;
            needToSendHeaderRecordForQuery = false;
            needToSendPatientRecordForQuery = true;
        } else if (needToSendPatientRecordForQuery) {
            logger.debug("Creating Patient record ");
            patientRecord = getPatientDataBundle().getPatientRecord();
            if (patientRecord != null) {
                if (patientRecord.getPatientName() == null) {
                    patientRecord.setPatientName("Buddhika");
                }
                patientRecord.setFrameNumber(frameNumber);
                String pm = createLimsPatientRecord(patientRecord);
                sendResponse(pm, clientSocket);
            }
            frameNumber = 3;
            needToSendPatientRecordForQuery = false;
            needToSendOrderingRecordForQuery = true;
        } else if (needToSendOrderingRecordForQuery) {
            logger.debug("Creating Order record ");
            if (testNames == null || testNames.isEmpty()) {
                testNames = Arrays.asList("Gluc GP");
            }
            orderRecord = getPatientDataBundle().getOrderRecords().get(0);
            orderRecord.setFrameNumber(frameNumber);
            String om = createLimsOrderRecord(orderRecord);
            sendResponse(om, clientSocket);
            frameNumber = 4;
            needToSendOrderingRecordForQuery = false;
            needToSendEotForRecordForQuery = true;
        } else if (needToSendEotForRecordForQuery) {
            System.out.println("Creating an End record = ");
            String tmq = createLimsTerminationRecord(frameNumber, terminationCode);
            sendResponse(tmq, clientSocket);
            needToSendEotForRecordForQuery = false;
            receivingQuery = false;
            receivingResults = false;
            respondingQuery = false;
            respondingResults = false;
        } else if (needToSendFinalEot) {
            // Instrument ACKed our empty H+L frame — now close the transmission
            out.write(EOT);
            out.flush();
            needToSendFinalEot = false;
            logger.info("Sent EOT after empty H+L handshake — connection now in neutral mode.");
        } else {
            // No pending orders: send a minimal H+L frame so the analyzer accepts the session
            // and enters neutral mode rather than resetting the connection.
            frameNumber = 1;
            String emptyContent = createLimsHeaderRecord() + CR + createLimsTerminationRecord(frameNumber, 'N');
            String emptyFrame = buildASTMMessage(emptyContent);
            OutputStream sessionOut = new BufferedOutputStream(clientSocket.getOutputStream());
            sessionOut.write(emptyFrame.getBytes());
            sessionOut.flush();
            needToSendFinalEot = true;
            logger.info("Sent empty H+L frame — awaiting ACK before EOT.");
        }
    }

    private void sendResponse(String response, Socket clientSocket) {
        String astmMessage = buildASTMMessage(response);
        try {
            OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
            out.write(astmMessage.getBytes());
            out.flush();
            logger.debug("Response sent: " + response);
        } catch (IOException e) {
            logger.error("Failed to send response", e);
        }
    }

    private void handleEot(OutputStream out) throws IOException {
        logger.debug("Handling eot");
        logger.debug(respondingQuery);
        if (respondingQuery) {
            patientDataBundle = LISCommunicator.pullTestOrdersForSampleRequests(patientDataBundle.getQueryRecords().get(0));
            logger.debug("Starting Transmission to send test requests");
            out.write(ENQ);
            out.flush();
            logger.debug("Sent ENQ");
        } else if (respondingResults) {
            final DataBundle bundleToSend = patientDataBundle;
            respondingResults = false;
            new Thread(() -> LISCommunicator.pushResults(bundleToSend), "lis-push").start();
        } else {
            logger.debug("Received EOT, ending session");
        }
    }

    public static String calculateChecksum(String frame) {
        String checksum = "00";
        int sumOfChars = 0;
        boolean complete = false;

        for (int idx = 0; idx < frame.length(); idx++) {
            int byteVal = frame.charAt(idx);

            switch (byteVal) {
                case 0x02: // STX
                    sumOfChars = 0;
                    break;
                case 0x03: // ETX
                case 0x17: // ETB
                    sumOfChars += byteVal;
                    complete = true;
                    break;
                default:
                    sumOfChars += byteVal;
                    break;
            }

            if (complete) {
                break;
            }
        }

        if (sumOfChars > 0) {
            checksum = Integer.toHexString(sumOfChars % 256).toUpperCase();
            return (checksum.length() == 1 ? "0" + checksum : checksum);
        }

        return checksum;
    }

    public String createHeaderMessage() {
        String headerContent = "1H|^&|||1^CareCode^1.0|||||||P|";
        return headerContent;
    }

    public String buildASTMMessage(String content) {
        String msdWithStartAndEnd = STX + content + CR + ETX;
        String checksum = calculateChecksum(msdWithStartAndEnd);
        String completeMsg = msdWithStartAndEnd + checksum + CR + LF;
        return completeMsg;
    }

    public String createLimsPatientRecord(PatientRecord patient) {
        // Delimiter used in the ASTM protocol
        String delimiter = "|";

        // Construct the start of the patient record, including frame number
        String patientStart = patient.getFrameNumber() + "P" + delimiter;

        // Concatenate patient information fields with actual patient data
        String patientInfo = "1" + delimiter
                + // Sequence Number
                patient.getPatientId() + delimiter
                + // Patient ID
                delimiter
                + // [Empty field for additional ID]
                delimiter
                + // [Empty field for more data]
                patient.getPatientName() + delimiter
                + // Patient Name
                delimiter
                + // [Empty field for more patient data]
                "U" + delimiter
                + // Sex (assuming 'U' for unspecified)
                delimiter
                + // [Empty field]
                delimiter
                + // [More empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [And more...]
                delimiter
                + // [And more...]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                patient.getAttendingDoctor() + delimiter;    // Attending Doctor

        // Construct the full patient record
        return patientStart + patientInfo;
    }

    public static String createLimsOrderRecord(int frameNumber, String sampleId, List<String> testNames, String specimenCode, Date collectionDate, String testInformation) {
        // Delimiter used in the ASTM protocol

        String delimiter = "|";

        // SimpleDateFormat to format the Date object to the required ASTM format
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String formattedDate = dateFormat.format(collectionDate);

        // Construct each field individually
        String frameNumberAndRecordType = frameNumber + "O"; // Combining frame number and Record Type 'O' without a delimiter
        String sequenceNumber = "1";
        String sampleID = sampleId;
        String instrumentSpecimenID = ""; // Instrument Specimen ID (Blank)
        StringBuilder orderedTests = new StringBuilder();
        for (int i = 0; i < testNames.size(); i++) {
            orderedTests.append("^^^").append(testNames.get(i));
            if (i < testNames.size() - 1) {
                orderedTests.append("\\"); // Append backslash except after the last test name
            }
        }
        String specimenType = specimenCode;
        String fillField = ""; // Fill field (Blank)
        String dateTimeOfCollection = formattedDate;
        String priority = ""; // Priority (Blank)

        String physicianID = testInformation;
        String physicianName = ""; // Physician Name (Blank)
        String userFieldNo1 = ""; // User Field No. 1 (Blank)
        String userFieldNo2 = ""; // User Field No. 2 (Blank)
        String labFieldNo1 = ""; // Laboratory Field No. 1 (Blank)
        String labFieldNo2 = ""; // Laboratory Field No. 2 (Blank)
        String dateTimeSpecimenReceived = ""; // Date/Time specimen received in lab (Blank)
        String specimenDescriptor = ""; // Specimen descriptor (Blank)
        String orderingMD = ""; // Ordering MD (Blank)
        String locationDescription = ""; // Location description (Blank)
        String ward = ""; // Ward (Blank)
        String invoiceNumber = ""; // Invoice Number (Blank)
        String reportType = ""; // Report type (Blank)
        String reservedField1 = ""; // Reserved Field (Blank)
        String reservedField2 = ""; // Reserved Field (Blank)
        String transportInformation = ""; // Transport information (Blank)

        // Concatenate all fields with delimiters
        return frameNumberAndRecordType + delimiter
                + sequenceNumber + delimiter
                + sampleID + delimiter
                + instrumentSpecimenID + delimiter
                + orderedTests + delimiter
                + specimenType + delimiter
                + fillField + delimiter
                + dateTimeOfCollection + delimiter
                + priority + delimiter
                + delimiter
                + physicianID + delimiter
                + physicianName + delimiter
                + userFieldNo1 + delimiter
                + userFieldNo2 + delimiter
                + labFieldNo1 + delimiter
                + labFieldNo2 + delimiter
                + dateTimeSpecimenReceived + delimiter
                + specimenDescriptor + delimiter
                + orderingMD + delimiter
                + locationDescription + delimiter
                + ward + delimiter
                + invoiceNumber + delimiter
                + reportType + delimiter
                + reservedField1 + delimiter
                + reservedField2 + delimiter
                + transportInformation;
    }

    public String createLimsHeaderRecord() {
        String analyzerNumber = "1";
        String analyzerName = "LIS host";
        String databaseVersion = "1.0";
        String hr1 = "1H";
        String hr2 = fieldD + repeatD + componentD + escapeD;
        String hr3 = "";
        String hr4 = "";
        String hr5 = analyzerNumber + componentD + analyzerName + componentD + databaseVersion;
        String hr6 = "";
        String hr7 = "";
        String hr8 = "";
        String hr9 = "";
        String hr10 = "";
        String hr11 = "";
        String hr12 = "SA";
        String hr13 = "1394-97";
        String hr14 = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        return hr1 + hr2 + fieldD + hr3 + fieldD + hr4 + fieldD + hr5 + fieldD + hr6 + fieldD + hr7 + fieldD + hr8 + fieldD + hr9 + fieldD + hr10 + fieldD + hr11 + fieldD + hr12 + fieldD + hr13 + fieldD + hr14;
    }

    public String createLimsOrderRecord(OrderRecord order) {
        return createLimsOrderRecord(order.getFrameNumber(), order.getSampleId(), order.getTestNames(), order.getSpecimenCode(), order.getOrderDateTime(), order.getTestInformation());
    }

    public String createLimsTerminationRecord(int frameNumber, char terminationCode) {
        String delimiter = "|";
        String terminationStart = frameNumber + "L" + delimiter;
        String terminationInfo = "1" + delimiter + terminationCode; // '1' is the record number, usually fixed
        return terminationStart + terminationInfo;
    }

    private void processMessage(String data, Socket clientSocket) {
        // A single ASTM frame may contain multiple CR-separated records (e.g., H, P, O, R, L).
        // Split and process each record individually.
        String[] records = data.split("\r");
        for (String record : records) {
            record = record.trim();
            if (!record.isEmpty()) {
                processSingleRecord(record, clientSocket);
            }
        }
    }

    private void processSingleRecord(String data, Socket clientSocket) {
        if (data.length() < 2) {
            logger.debug("Record too short to parse: " + data);
            return;
        }

        // Records may begin with a frame-number digit (e.g. "1H|...") or directly with
        // the record-type letter (e.g. "P|...", "R|...") when part of a multi-record frame.
        char recordType = Character.isDigit(data.charAt(0)) ? data.charAt(1) : data.charAt(0);

        switch (recordType) {
            case 'H': // Header Record
                patientDataBundle = new DataBundle();
                receivingQuery = false;
                receivingResults = false;
                respondingQuery = false;
                respondingResults = false;
                needToSendEotForRecordForQuery = false;
                needToSendOrderingRecordForQuery = false;
                needToSendPatientRecordForQuery = false;
                needToSendHeaderRecordForQuery = false;
                logger.debug("Header Record Received: " + data);
                break;
            case 'R': // Result Record
                logger.debug("Result Record Received: " + data);
                respondingResults = true;
                respondingQuery = false;
                resultRecord = parseResultsRecord(data);
                if (resultRecord != null) {
                    getPatientDataBundle().getResultsRecords().add(resultRecord);
                }
                logger.debug("Result Record Parsed: " + resultRecord);
                break;
            case 'Q': // Query Record
                logger.info("Query record received: " + data);
                receivingQuery = false;
                respondingQuery = true;
                needToSendHeaderRecordForQuery = true;
                logger.debug("Query Record Received: " + data);
                queryRecord = parseQueryRecord(data);
                getPatientDataBundle().getQueryRecords().add(queryRecord);
                logger.debug("Parsed the Query Record: " + queryRecord);
                break;
            case 'P': // Patient Record
                logger.debug("Patient Record Received: " + data);
                patientRecord = parsePatientRecord(data);
                getPatientDataBundle().setPatientRecord(patientRecord);
                logger.debug("Patient Record Parsed: " + patientRecord);
                break;
            case 'L': // Termination Record
                logger.debug("Termination Record Received: " + data);
                break;
            case 'C': // Comment Record
                logger.debug("Comment Record Received: " + data);
                break;
            case 'O': // Order Record
                logger.info("Order record received: " + data);
                String tmpSampleId = extractSampleIdFromOrderRecord(data);
                logger.info("Sample ID from O record: " + tmpSampleId);
                sampleId = tmpSampleId;
                break;
            default: // Unknown Record
                logger.debug("Unknown Record Received: " + data);
                break;
        }
    }

    public static PatientRecord parsePatientRecord(String patientSegment) {
        String[] fields = patientSegment.split("\\|");
        String fnStr = fields[0].replaceAll("[^0-9]", "");
        int frameNumber = fnStr.isEmpty() ? 0 : Integer.parseInt(fnStr);
        String patientId = safeField(fields, 1);
        String additionalId = safeField(fields, 3);
        String patientName = safeField(fields, 4);
        String patientSecondName = safeField(fields, 6);
        String patientSex = safeField(fields, 7);
        String race = "";
        String dob = safeField(fields, 7);
        String patientAddress = safeField(fields, 11);
        String patientPhoneNumber = safeField(fields, 14);
        String attendingDoctor = safeField(fields, 15);

        // Return a new PatientRecord object using the extracted data
        return new PatientRecord(
                frameNumber,
                patientId,
                additionalId,
                patientName,
                patientSecondName,
                patientSex,
                race,
                dob,
                patientAddress,
                patientPhoneNumber,
                attendingDoctor
        );
    }

    public ResultsRecord parseResultsRecord(String resultSegment) {
        // Split the segment into fields
        String[] fields = resultSegment.split("\\|");

        // R record needs at least: frame+type, seq, testId, value, units
        if (fields.length < 5) {
            logger.error("Insufficient fields in the result segment: {}", resultSegment);
            return null;
        }

        // Extract the frame number by removing non-numeric characters
        String fnStr = fields[0].replaceAll("[^0-9]", "");
        int frameNumber = fnStr.isEmpty() ? 0 : Integer.parseInt(fnStr);
        logger.debug("Frame number extracted: {}", frameNumber);

        // ASTM R field 3: channel^testName^^resultFlag — index 1 is the test name
        String[] testIdComponents = fields[2].split("\\^");
        String testCode = safeComponent(testIdComponents, 1);
        logger.debug("Test code extracted: {}", testCode);

        // Result value parsing — field 4. ASTM uses ^ as component separator within the field
        // (e.g. "13.184^^^^"), so take only the first component.
        String resultValueString = safeField(fields, 3).split("\\^")[0];

        // ASTM R field 5 = units, field 13 = observation datetime, field 14 = producer/instrument
        String resultUnits = safeField(fields, 4);
        logger.debug("Result units extracted: {}", resultUnits);
        // Fields are 1-based in the spec but 0-based in the split array; frame+type occupies index 0.
        // Layout: [0]=frameType [1]=seq [2]=testId [3]=value [4]=units [5]=refRange [6]=abnFlags
        //         [7]=prob [8]=resultStatus [9]=origResult [10]=rerunFlag [11]=obsDateTime
        //         [12]=producerID [13]=instrument
        String resultDateTime = safeField(fields, 11);
        logger.debug("Result date-time extracted: {}", resultDateTime);
        String instrumentName = safeField(fields, 13).split("\\^")[0];
        logger.debug("Instrument name extracted: {}", instrumentName);
        logger.debug("sampleId = {}", sampleId);
        ResultsRecord record = new ResultsRecord(
                frameNumber,
                testCode,
                resultValueString,
                resultUnits,
                resultDateTime,
                instrumentName,
                sampleId
        );
        try {
            record.setResultValue(Double.parseDouble(resultValueString));
        } catch (NumberFormatException e) {
            logger.warn("Could not parse result value '{}' as double — resultValue left as 0.0", resultValueString);
        }
        return record;

    }

    public static OrderRecord parseOrderRecord(String orderSegment) {

        String[] fields = orderSegment.split("\\|");

        // Extract frame number and remove non-numeric characters (<STX>, etc.)
        String fnStr = fields[0].replaceAll("[^0-9]", "");
        int frameNumber = fnStr.isEmpty() ? 0 : Integer.parseInt(fnStr);

        // Sample ID and associated data
        String[] sampleDetails = fields[1].split("\\^");
        String sampleId = sampleDetails[1]; // Adjust index based on your specific message structure

        // Extract test names, assuming they are separated by '^' inside a field like ^^^test1^test2
        List<String> testNames = Arrays.stream(fields[2].split("\\^"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Specimen code
        String specimenCode = fields[3];

        // Order date and time
        String orderDateTime = fields[4];

        // Test information
        String testInformation = fields[6]; // Assuming test information is in the 7th segment

        // Return a new OrderRecord object using the extracted data
        return new OrderRecord(
                frameNumber,
                sampleId,
                testNames,
                specimenCode,
                orderDateTime,
                testInformation
        );
    }

    public static String extractSampleIdFromQueryRecord(String astm2Message) {
        // Step 1: Discard everything before "Q|"
        int startIndex = astm2Message.indexOf("Q|");
        if (startIndex == -1) {
            return null; // "Q|" not found in the message
        }
        String postQ = astm2Message.substring(startIndex);

        // Step 2: Get the string between the second and third "|"
        String[] fields = postQ.split("\\|");
        if (fields.length < 3) {
            return null; // Not enough fields
        }
        String secondField = fields[2]; // Get the field after the second "|"

        // Step 3: Get the string between the first and second "^"
        String[] sampleDetails = secondField.split("\\^");
        if (sampleDetails.length < 2) {
            return null; // Not enough data within the field
        }
        return sampleDetails[1]; // This should be the sample ID
    }

    public static String extractSampleIdFromOrderRecord(String astm2Message) {
        // Split the message by the '|' delimiter
        String[] fields = astm2Message.split("\\|");

        // Assuming the sample ID is in the third field (index 2)
        if (fields.length > 2) {
            // Extract the sample ID field (third field)
            String tmpSampleId = fields[2];

            // Split the tmpSampleId by '^' and return the first part
            String[] sampleIdParts = tmpSampleId.split("\\^");
            return sampleIdParts[0]; // Return only the part before the first '^'
        } else {
            return null; // or throw an exception if you prefer
        }
    }

    public QueryRecord parseQueryRecord(String querySegment) {
        System.out.println("querySegment = " + querySegment);
        String tmpSampleId = extractSampleIdFromQueryRecord(querySegment);
        System.out.println("tmpSampleId = " + tmpSampleId);
        sampleId = tmpSampleId;
        System.out.println("Sample ID: " + tmpSampleId); // Debugging
        return new QueryRecord(
                0,
                tmpSampleId,
                "",
                ""
        );
    }

    public DataBundle getPatientDataBundle() {
        if (patientDataBundle == null) {
            patientDataBundle = new DataBundle();
        }
        return patientDataBundle;
    }

    public void setPatientDataBundle(DataBundle patientDataBundle) {
        this.patientDataBundle = patientDataBundle;
    }

}
