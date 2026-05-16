# Mindray CL-1000i Middleware — Developer Manual

> Extracted from: *Chemiluminescence Immunoassay Analyzer Host Interface Manual V1.0*  
> Source PDF: `document/Chemiluminescence Immunoassay Analyzer_LIS Manual_V1.0_EN.pdf`  
> Built from code analysis of `src/main/java/org/carecode/mw/lims/mw/mindrayCL1000i/`

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [ASTM Protocol Fundamentals](#2-astm-protocol-fundamentals)
3. [Frame Format](#3-frame-format)
4. [Record Types](#4-record-types)
5. [Communication Flows](#5-communication-flows)
6. [Configuration](#6-configuration)
7. [Troubleshooting — Connection Reset Bug](#7-troubleshooting--connection-reset-bug)
8. [Code Bug Inventory](#8-code-bug-inventory)

---

## 1. System Overview

The middleware acts as a **TCP/IP server** that the Mindray CL-1000i analyzer connects to as a client. It bridges the analyzer and the LIS (Laboratory Information System) over HTTP.

```
Analyzer (192.168.122.128)  <---TCP/IP--->  Middleware (port 7118)  <---HTTP--->  LIS Server
       ASTM E1394-97                         Java / carecode lib                   REST API
```

**Key classes:**

| Class | Role |
|---|---|
| `MindrayCL1000i` | Entry point, loads settings, starts server |
| `MindrayCL1000iServer` | TCP server, ASTM protocol handler |
| `AnalyzerCommunicator` | Simpler/older TCP server (not used in main flow) |
| `LISCommunicator` | HTTP client — pushes results to / pulls orders from LIS |
| `SettingsLoader` | Reads `config.json` via Gson |

---

## 2. ASTM Protocol Fundamentals

The analyzer uses **ASTM E1394-97** over TCP/IP. This is a half-duplex, turn-based protocol.

### 2.1 Control Characters

| Name | Hex | Purpose |
|---|---|---|
| `ENQ` | `0x05` | Enquiry — sender asks "ready to receive?" |
| `ACK` | `0x06` | Acknowledgement — "yes, send data" |
| `NAK` | `0x15` | Negative ACK — "not ready, try again" |
| `STX` | `0x02` | Start of text / frame |
| `ETX` | `0x03` | End of final frame |
| `ETB` | `0x17` | End of intermediate frame (more follows) |
| `EOT` | `0x04` | End of transmission — session done |
| `CR`  | `0x0D` | Carriage return |
| `LF`  | `0x0A` | Line feed |

### 2.2 Field Delimiters

Defined in the Header (`H`) record — second field is always `|\^&`:

| Character | Role |
|---|---|
| `\|` | Field separator |
| `\` | Repetition separator |
| `^` | Component separator |
| `&` | Escape / subcomponent separator |

---

## 3. Frame Format

### 3.1 End Frame (final or only frame)

```
<STX> FN <DATA><CR> <ETX> <CS> <CR><LF>
```

### 3.2 Intermediate Frame (message split across multiple frames)

```
<STX> FN <DATA><CR> <ETB> <CS> <CR><LF>
```

| Token | Meaning |
|---|---|
| `FN` | Frame Number, cycles 1–7 (wraps to 1 after 7) |
| `DATA` | ASTM record string (e.g. `H|\^&|||Mindray...`) |
| `CS` | Checksum — sum of ASCII values of FN through ETX/ETB, modulo 256, as 2 hex digits |

### 3.3 Checksum Calculation

```
Sum all bytes from FN up to and including ETX or ETB (but NOT STX, CR, LF).
Checksum = sum % 256, formatted as 2 uppercase hex digits.
```

The existing `calculateChecksum()` method in `MindrayCL1000iServer` implements this correctly.

---

## 4. Record Types

| Code | Record | Direction |
|---|---|---|
| `H` | Header | Both |
| `P` | Patient Information | Both |
| `O` | Test Order | Both |
| `R` | Result | Analyzer → Host |
| `C` | Comment | Both (optional) |
| `Q` | Query / Request Information | Analyzer → Host |
| `L` | Terminator | Both |

### 4.1 Header Record (H)

```
H|\^&|||Mindray^^|||||||PR|1394-97|20090910102501<CR>
```

Field 12 (Processing ID) values:

| Value | Meaning |
|---|---|
| `PR` | Patient test result |
| `QR` | QC test result |
| `CR` | Calibration result |
| `RQ` | Request query (from analyzer) |
| `QA` | Query response (from host) |
| `SA` | Sample request info (from host) |

### 4.2 Patient Record (P)

```
P|1||PATIENT111||Smith^^||19600315^45^Y|M||keshi...
```

Fields: Seq | Practice ID | Patient ID | Name | DOB | Sex | Address...

### 4.3 Order Record (O)

```
O|1|1^^|SAMPLE123|1^CA125^^\2^CA126^^|R|20090910135300|...|Urine|Dr.Who|...
```

Field 26: set to `Q` when host returns a query response; `O` when downloading samples.

### 4.4 Result Record (R)

```
R|1|1^CA125^^F|10000.000000^^^^|U/mL|^|N||F|1074051.8^^^^|0|20130715102938||Mindray^
```

Fields: Seq | TestCode^Name^^Flags | Value | Units | RefRange | AbnFlags | ... | ResultDateTime | Instrument

### 4.5 Query Record (Q)

```
Q|1|^SAMPLE123||||||||||O<CR>
```

Field 2 component 2 = sample barcode/ID.

### 4.6 Terminator Record (L)

```
L|1|N<CR>
```

Termination codes: `N` = normal, `T` = time limit, `E` = error.

---

## 5. Communication Flows

### 5.1 Result Sending (Analyzer → Host)

```
Analyzer          Middleware (Host)
   |---ENQ (0x05)-->|
   |<--ACK (0x06)---|
   |                |
   |---STX 1H..ETX CS CR LF-->|   (Header frame)
   |<--ACK----------|
   |---STX 2P..ETX CS CR LF-->|   (Patient frame)
   |<--ACK----------|
   |---STX 3O..ETX CS CR LF-->|   (Order frame)
   |<--ACK----------|
   |---STX 4R..ETX CS CR LF-->|   (Result frame, repeats per test)
   |<--ACK----------|
   |---STX 5L..ETX CS CR LF-->|   (Terminator frame)
   |<--ACK----------|
   |---EOT (0x04)-->|
   |                |    → middleware now calls LISCommunicator.pushResults()
```

### 5.2 Sample Query (Analyzer queries LIS for work orders)

```
Analyzer          Middleware (Host)
   |---ENQ-------->|
   |<--ACK---------|
   |---STX 1H RQ...ETX CS CR LF-->|  (Header: processing=RQ)
   |<--ACK---------|
   |---STX 2Q...ETX CS CR LF-->|     (Query record with sample barcode)
   |<--ACK---------|
   |---STX 3L...ETX CS CR LF-->|     (Terminator)
   |<--ACK---------|
   |---EOT-------->|
   |               |   → middleware calls LISCommunicator.pullTestOrdersForSampleRequests()
   |<--ENQ---------|   (host takes over as sender)
   |---ACK-------->|
   |<--STX 1H SA...ETX CS CR LF--|   (Header: processing=SA)
   |---ACK-------->|
   |<--STX 2P...ETX CS CR LF----|   (Patient)
   |---ACK-------->|
   |<--STX 3O...ETX CS CR LF----|   (Order with test list)
   |---ACK-------->|
   |<--STX 4L...ETX CS CR LF----|   (Terminator)
   |---ACK-------->|
   |<--EOT---------|
```

---

## 6. Configuration

Config file path (hardcoded in `SettingsLoader.java`):

```
D:\ccmv\settings\mindrayCL1000i\config.json
```

Expected JSON structure (based on `MiddlewareSettings` class usage):

```json
{
  "analyzerDetails": {
    "analyzerName": "MindrayCL1000i",
    "analyzerPort": 7118
  },
  "limsSettings": {
    "limsServerBaseUrl": "http://<lims-host>:<port>"
  }
}
```

LIS endpoints called:

| Method | URL | Purpose |
|---|---|---|
| POST | `{baseUrl}/test_orders_for_sample_requests` | Pull work orders |
| POST | `{baseUrl}/test_results` | Push results |

---

## 7. Troubleshooting — Connection Reset Bug

### Symptom

Log shows repeated pattern — analyzer connects but immediately drops:

```
INFO  - New client connected: 192.168.122.128
ERROR - Error during client communication
java.net.SocketException: Connection reset
```

The connection lasts approximately 4 seconds before the reset.

### Root Cause — **Critical Bug**

In `MindrayCL1000iServer.java` line 82, `handleClientTest1()` is called instead of `handleClient()`:

```java
// Line 81 (WRONG — currently active):
handleClientTest1(clientSocket);

// Line 80 (CORRECT — commented out):
// handleClient(clientSocket);
```

`handleClientTest1()` is a **diagnostic/logging-only mode** — it reads every incoming byte and logs it, but **never sends any response**.

The ASTM protocol requires the host to reply `ACK (0x06)` within a short timeout when the analyzer sends `ENQ (0x05)`. When no ACK arrives, the analyzer resets the connection and retries.

### Fix

In `MindrayCL1000iServer.java`, swap the active method call:

```java
// BEFORE (broken):
handleClientTest1(clientSocket);

// AFTER (correct):
handleClient(clientSocket);
```

### Secondary Issue — Socket Timeout Too Short

`handleClient()` sets a 5-second socket timeout:

```java
clientSocket.setSoTimeout(5000);
```

The ASTM spec allows significant pauses between frames. A 5-second inter-frame timeout may cause premature disconnection mid-session. Recommended: increase to **30–60 seconds**.

---

## 8. Code Bug Inventory

### Bug 1 — Test mode handler called in production (CRITICAL)

**File:** `MindrayCL1000iServer.java:82`  
**Effect:** Analyzer connects, gets no ACK, resets — no data ever flows.  
**Fix:** Uncomment `handleClient(clientSocket)`, remove `handleClientTest1(clientSocket)`.

---

### Bug 2 — `start()` ignores its `port` parameter

**File:** `MindrayCL1000iServer.java:71-72`

```java
public void start(int port) {
    port = SettingsLoader.getSettings().getAnalyzerDetails().getAnalyzerPort();  // overwrites parameter
```

The `port` parameter is immediately overwritten by the settings value. This makes `restartServer()` always use the settings port regardless of what was passed in — which is probably fine, but the parameter is misleading.

---

### Bug 3 — Result record parser assumes minimum field count

**File:** `MindrayCL1000iServer.java:657,674`

```java
String testCode = fields[2].split("\\^")[3];  // throws if fewer than 4 components
String resultDateTime = fields[12];            // throws ArrayIndexOutOfBoundsException if < 13 fields
```

The analyzer may send shorter records. Add bounds checks before accessing these indices.

---

### Bug 4 — Patient record parser assumes fixed field positions

**File:** `MindrayCL1000iServer.java:616-619`

```java
String additionalId = fields[3];    // may not exist
String patientName = fields[4];     // may not exist
String patientAddress = fields[11]; // may not exist
String patientPhoneNumber = fields[14];
String attendingDoctor = fields[15];
```

No length check before accessing these indices. A short patient record will throw `ArrayIndexOutOfBoundsException`.

---

### Bug 5 — Order record in `createLimsOrderRecord()` is truncated

**File:** `MindrayCL1000iServer.java:476-501`

Most fields (specimen type, collection date, priority, etc.) are commented out:

```java
return frameNumberAndRecordType + delimiter
        + sequenceNumber + delimiter
        + sampleID + delimiter
        + instrumentSpecimenID + delimiter
        + orderedTests + delimiter;
//  + specimenType + delimiter   ← commented out
//  + ...
```

The resulting O record sent to the analyzer is incomplete.

---

### Bug 6 — Header record timestamp is hardcoded

**File:** `MindrayCL1000iServer.java:524`

```java
header = hr1 + hr2 + fieldD + hr3 + fieldD + hr4 + fieldD + hr5 + ...  // Line 522 also built correctly
// then overwritten:
header = hr1 + hr2 + fieldD + ... + hr12;  // drops hr13 (empty) and hr14 (timestamp)
```

The ASTM Header record is built twice — the second build drops the datetime field (`hr14 = "20240508221500"` — a hardcoded date). Use `new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())` instead.

---

### Bug 7 — `sampleId` is a static field shared across connections

**File:** `MindrayCL1000iServer.java:57, 678`

```java
static String sampleId;
```

If two clients connect simultaneously (unlikely but possible), the static `sampleId` field will be overwritten by whichever thread processes last. Make this an instance variable.

---

## Quick Fix Priority

All bugs below have been fixed.

| Priority | Bug | File:Line | Status |
|---|---|---|---|
| **P1** | Test handler called instead of real handler | `MindrayCL1000iServer.java:82` | Fixed |
| **P2** | 5s socket timeout too short | `MindrayCL1000iServer.java:181` | Fixed (→ 30s) |
| P3 | Result parser — wrong testCode index + no bounds check | `MindrayCL1000iServer.java:657,674` | Fixed |
| P3 | Patient parser — no bounds check on fields | `MindrayCL1000iServer.java:616-619` | Fixed |
| P4 | Order record fields commented out | `MindrayCL1000iServer.java:481-501` | Fixed |
| P4 | Header timestamp hardcoded + duplicated build line | `MindrayCL1000iServer.java:522-524` | Fixed |
| P5 | Static sampleId shared across connections | `MindrayCL1000iServer.java:57` | Fixed (→ instance field) |
