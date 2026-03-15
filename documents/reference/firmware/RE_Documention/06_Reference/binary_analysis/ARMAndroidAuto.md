# ARMAndroidAuto Binary Analysis

**Purpose:** Comprehensive reverse-engineering documentation of the CPC200-CCPA adapter's Android Auto protocol handler
**Binary:** `/usr/sbin/ARMAndroidAuto`
**Last Updated:** 2026-03-15
**Sources:** Ghidra static analysis, extracted strings (10,800+), TTY session logs (12,058 lines), runtime observation (Pixel 10 wireless AA)

---

## 1. Binary Identity

| Property | Value |
|----------|-------|
| Location on adapter | `/usr/sbin/ARMAndroidAuto` |
| Packed size | 489,800 bytes |
| Unpacked size | 1,488,932 bytes (3:1 ratio) |
| Architecture | ELF 32-bit LSB ARM, EABI5, stripped |
| Packer | Custom LZMA (magic `0x55225522`), NOT standard UPX |
| Decompressor stub | 5,844 bytes at offset `0x76274` |
| Entropy | 8.00 bits/byte (maximum) |
| Build path | `/home/hcw/M6PackTools/HeweiPackTools/AndroidAuto_Wireless/openauto-v2/` |
| SDK path | `/home/hcw/M6PackTools/HeweiPackTools/AndroidAuto_Wireless/AndroidAutoSdk-v2/` |
| Build date (embedded) | `20210301` (March 2021) |
| Linker | `/lib/ld-linux.so.3` (dynamically linked) |

### Unpacking

The decompressor stub chain: `readlink("/proc/self/exe")` → `mmap2()` → LZMA decompress (lc=2, lp=0, pb=3) → `open("/dev/hwas", O_RDWR)` → `ioctl(fd, 0xC00C6206, ...)` → `mprotect()` → jump to decompressed code.

Successfully unpacked (2026-02-28) using UPX with header fix. No ELF section headers in output.

### Linked Libraries

`libc`, `libpthread`, `libstdc++`, `libm`, `librt`, `libdl`, `libcrypto`, `libssl`, `libusb-1.0.so.0`

Note: `libboxtrans.so` and `libdmsdp.so` are NOT referenced in ARMAndroidAuto strings. These libraries are used by ARMadb-driver and AppleCarPlay, not by this binary. ARMAndroidAuto communicates with ARMadb-driver via raw Unix domain sockets using its own `CRiddleUnixSocketClient` class.

---

## 2. Origin and Framework

ARMAndroidAuto is a **modified fork of OpenAuto** (open-source Android Auto head unit implementation) built by **HeWei (DongGuan HeWei Communication Technologies Co. Ltd.)**. It implements the AA protocol using:

- **aasdk** — Android Auto SDK (protocol, transport, messenger, channels)
- **protobuf 2.5.0** — AA message serialization
- **boost 1.66.0** — boost::asio for async I/O
- **OpenSSL** — TLS handshake with phone

Platform variants: `A15F`, `A15HW`, `A15U`, `A15W`, `A15X`

Product types supporting AA: A15W, A15X, A15U, Auto_Box, U2AW, U2AC, U2OW, UC2AW, UC2CA, O2W

---

## 3. C++ Namespace Architecture

```
aasdk::
├── transport::     USBTransport, TCPTransport, SSLWrapper
├── messenger::     Messenger, Cryptor, MessageInStream, MessageOutStream
├── channel::       Service channels (av, input, sensor, control, bluetooth,
│                   navigation, phonestatus, notification)
├── usb::           USBHub, USBEndpoint, USBWrapper, AOAPDevice,
│                   AccessoryModeQueryChain
└── tcp::           TCPEndpoint, TCPWrapper

openauto::
├── App             Main application class
├── projection::    BoxVideoOutput, BoxAudioInput, BoxAudioOutput,
│                   BoxInputDevice, BoxRFCOMMService, RemoteBluetoothDevice
├── service::       AndroidAutoEntity, AndroidAutoInterface,
│                   AndroidAutoEntityFactory, VideoService, AudioService,
│                   AudioInputService, InputService, SensorService,
│                   BluetoothService, NavigationStatusService,
│                   PhoneStatusService, MediaStatusService,
│                   GenericNotificationService, Pinger
└── configuration:: Configuration
```

---

## 4. Service Channels

ARMAndroidAuto exposes **13 AA protocol service channels**:

| Service | Audio Format | Purpose |
|---------|-------------|---------|
| VideoService | H.264 Baseline Profile | Phone screen projection (1920x1080) |
| AudioService (MEDIA_AUDIO) | 48kHz/16bit/stereo | Music, podcasts |
| AudioService (SPEECH_AUDIO) | 16kHz/16bit/mono | Google Assistant, nav prompts |
| AudioService (SYSTEM_AUDIO) | 16kHz/16bit/mono | System sounds, alerts |
| AudioInputService | 8kHz or 16kHz/16bit/mono | Microphone input from host |
| InputService | — | Touch input (1920x1080 coordinate space) |
| SensorService | — | Night mode, GPS, driving status |
| NavigationStatusService | — | Turn-by-turn navigation events |
| BluetoothService | — | BT pairing coordination |
| MediaStatusService | — | Playback state, metadata, album art |
| GenericNotificationService | — | System notifications |
| AVInputChannel | — | AV input |
| VendorExtensionChannel | — | Vendor extensions |

### Service Discovery Keys

Static members of `AndroidAutoEntity`:
- Vehicle info: `cCarYearKey`, `cCarModelKey`, `cCarSerialKey`, `cSwBuildKey`, `cSwVersionKey`
- HU identity: `cHeadUnitNameKey`, `cHeadUnitModelKey`, `cHeadUnitManufacturerKey`, `cDisPlayNameKey`
- Capabilities: `cHaveAvChannelKey`, `cHaveWifiChannelKey`, `cHaveInputChannelKey`, `cHaveRadioChannelKey`, `cHaveSensorChannelKey`, `cHaveAvInputChannelKey`, `cHaveBluetoothChannelKey`, `cHaveMediaInfoChannelKey`, `cHaveNavigationChannelKey`, `cHavePhoneStatusChannelKey`, `cHaveMediaBrowserChannelKey`, `cHaveWifiProjectionChannelKey`, `cHaveVendorExtensionChannelKey`, `cHaveGenericNotificationChannelKey`
- Config: `cDriverPositionKey` (LEFT/RIGHT/CENTER/UNKNOWN), `cSessionConfigKey`, `cHideClockKey`, `cCanPlayNativeMediaDuringVrKey`

---

## 5. Configuration System

### Config Library Access (CORRECTED 2026-03-15)

**Correction:** Earlier analysis (key_binaries.md, 2026-02-28) claimed ARMAndroidAuto had "zero config system strings." String verification (2026-03-15) found this is **wrong**. The binary contains: `GetBoxConfig`, `SetBoxConfig`, `ResetBoxConfig`, `SetBoxConfigStr`, `GetJsonTypeBoxConfig`, `riddleConfigNameValue`, `riddleConfigNameStringValue`, `BoxConfig_DelayStart`, `BoxConfig_preferSPSPPSType`, `BoxConfig_UI_Lang`, plus direct references to `AndroidAutoWidth`, `AndroidAutoHeight`, `AndroidWorkMode`.

ARMAndroidAuto **does** read and write the riddle configuration system, including AA-specific keys. It reads `riddle.conf` and `riddle_default.conf` directly. The `android_work_mode` file path is NOT in the AA binary strings — that file is managed exclusively by ARMadb-driver.

### Configuration Delivery Mechanisms

| Mechanism | Data | Source |
|-----------|------|--------|
| `riddle.conf` / `riddle_default.conf` | All adapter config keys | Read directly via `riddleConfigNameValue` / `riddleConfigNameStringValue` |
| `BOX_CFG_AndroidAuto Width/Height` | AA video dimensions | Read via `GetBoxConfig` at startup |
| `gLinkParam` IPC message | `iWidth`, `iHeight`, `iFps`, `bWireless`, `btAddr` | ARMadb-driver via Unix socket |
| `/tmp/screen_dpi` | DPI value (e.g., 160) | File on disk |
| `/etc/box_product_type` | Product type (e.g., A15W) | Read for feature gating |
| `/etc/android_work_mode` | 4-byte file, value `1` for AA | Managed by ARMadb-driver (NOT read by ARMAndroidAuto) |

### Configuration Flow (from TTY log)

```
[OpenAuto] [Configuration] /tmp setScreenDPI = 160
[OpenAuto] [Configuration] getCarName = Exit
[OpenAuto] [Configuration] setVideoFPS = 2
[OpenAuto] GetBoxConfig BOX_CFG_AndroidAuto Width: 1920, Height: 630
[OpenAuto] [Configuration] setVideoResolution = 3
[OpenAuto] [Configuration] set margin w = 0
[OpenAuto] [Configuration] set margin h = 450
AndroidAuto iWidth: 1920, iHeight: 1080
```

**Margin system:** Height 630 + margin 450 = 1080. The margin is the difference between configured AA height and actual output height.

### AA-Specific BoxSettings JSON Fields

```json
{
  "androidAutoSizeW": 1920,
  "androidAutoSizeH": 630,
  "androidWorkMode": 1
}
```

These are written to `AndroidAutoWidth`/`AndroidAutoHeight` in `riddle.conf` by ARMadb-driver when received from host.

---

## 6. IPC with ARMadb-driver

### MiddleMan Interface

| Property | Value |
|----------|-------|
| Socket | `/var/run/adb-driver` (Unix domain) |
| MiddleMan type | 5 = AndroidAuto (`CAndroidAuto_MiddleManInterface`) |
| Client class | `CRiddleUnixSocketClient` |

Note: `sendTransferData`, `MiddleManClient_SendData`, `libboxtrans.so`, and `_SendPhoneCommandToCar` are NOT in the ARMAndroidAuto binary strings. These are ARMadb-driver constructs. ARMAndroidAuto uses its own `CRiddleUnixSocketClient` for IPC. The command names below are logged by ARMadb-driver when it receives and forwards them from ARMAndroidAuto.

### Commands Forwarded via IPC

ARMAndroidAuto sends phone commands to the host (logged by ARMadb-driver as `_SendPhoneCommandToCar`):

| Command | ID | Direction | Purpose |
|---------|-----|-----------|---------|
| RequestVideoFocus | 500 | Adapter→Host | AA video started, request display |
| ReleaseVideoFocus | 501 | Adapter→Host | AA video stopped |
| RequestAudioFocus | 502 | Adapter→Host | AA audio started |
| ReleaseAudioFocus | 505 | Adapter→Host | AA audio stopped |
| RequestNaviFocus | 506 | Adapter→Host | Navigation active |
| ReleaseNaviFocus | 507 | Adapter→Host | Navigation inactive |
| StartRecordMic | 1 | Adapter→Host | Begin mic capture |
| StopRecordMic | 2 | Adapter→Host | Stop mic capture |
| UseCarMic | 7 | Adapter→Host | Use car's microphone |
| SupportWifi | 1000 | Adapter→Host | WiFi capability |
| SupportAutoConnect | 1001 | Adapter→Host | AutoConnect capability |
| StartAutoConnect | 1002 | Adapter→Host | Begin auto-connect scan |
| ScaningDevices | 1003 | Adapter→Host | Scanning in progress |
| DeviceFound | 1004 | Adapter→Host | Device discovered |
| DeviceNotFound | 1005 | Adapter→Host | Scan failed |
| DeviceBluetoothConnected | 1007 | Adapter→Host | BT connected |
| DeviceBluetoothNotConnected | 1008 | Adapter→Host | BT disconnected |
| DeviceWifiConnected | 1009 | Adapter→Host | WiFi connected |
| SupportWifiNeedKo | 1012 | Adapter→Host | WiFi kernel module needed |

### HUD Commands (Adapter←→Host)

| Command | Purpose |
|---------|---------|
| `HUDComand_A_HeartBeat` | Keepalive |
| `HUDComand_A_Reboot` | Reboot adapter |
| `HUDComand_A_ResetUSB` | Reset USB stack |
| `HUDComand_A_UploadFile` | File upload (0x99) |
| `HUDComand_B_BoxSoftwareVersion` | Version query |
| `HUDComand_D_BluetoothName` | BT name |
| `HUDComand_D_Ready` | Ready signal |

### Control Command Format

Incoming commands from host arrive as `kControlCmdFormat` with keycode. Example: `Recv kControlCmdFormat: 12, keycode: 0` (keyframe request).

### BOX_TMP_DATA_AUDIO_TYPE States

Bitmask logged by ARMadb-driver (not an ARMAndroidAuto string) tracking active audio channels:

| Value | Meaning |
|-------|---------|
| `0x0000` | Silence (media stopped) |
| `0x0001` | Media playing |
| `0x0104` | Voice recognition starting |
| `0x0504` | Voice recognition + mic recording |
| `0x0501` | Phone call + mic recording |

---

## 7. Connection Lifecycle

### Daemon Management

Managed by `phone_link_deamon.sh`:

```bash
# Start sequence
phone_link_deamon.sh AndroidAuto start
  → copies ARMAndroidAuto, hfpd to /tmp/bin/
  → copies libssl.so* to /tmp/lib/
  → starts hfpd -y -E -f &  (HFP daemon for BT SCO bridge)
  → runs ARMAndroidAuto (blocking)
  → auto-restarts in loop while lockfile exists

# Stop sequence
phone_link_deamon.sh AndroidAuto stop
  → killall hfpd
  → killall ARMAndroidAuto
```

### Trigger Mechanism

1. Host sends `/etc/android_work_mode` (value `1`) via SendFile (0x99)
2. ARMadb-driver's `OnAndroidWorkModeChanged: 0 → 1` fires
3. ARMadb-driver starts `phone_link_deamon.sh AndroidAuto start &`
4. ARMAndroidAuto launches, creates MiddleMan client (type=5), connects to IPC socket
5. ARMAndroidAuto enters `waitForUSBDevice` / `Need Wait Start Link` state

### Wireless AA Connection Sequence (from TTY log, verified 2026-03-15)

```
T+0.0s   ARMAndroidAuto starts, MiddleMan client type=5 connects
T+0.0s   "Need Wait Start Link" — waiting for BT RFCOMM trigger
T+5.0s   BT AutoConnect finds Pixel 10, RFCOMM AAP socket accepted
T+5.0s   "Recv start linkParam: 2, B0:D5:FB:A3:7E:AA" (2=wireless)
T+5.1s   recv gLinkParam: iWidth=2400, iHeight=788, iFps=30, bWireless=1
T+5.1s   setAudioCacheMs: 1000, 300, 300 (media, navi, speech)
T+5.2s   BoxRFCOMMService start — sends WiFi credentials via RFCOMM:
           channelfreq=5180, ssid=carlink, passwd=12345678,
           bssid=00:E0:4C:98:0A:6C, securityMode=8, port=54321
T+5.3s   RFCOMM message exchange: WifiVersionRequest(4)→WifiVersionResponse(5)→
           WifiStartRequest(1)→WifiInfoRequest(2)→WifiInfoResponse(3)→
           WifiConnectStatus(6): 0 (success)
T+5.6s   "Wireless Device connected."
T+5.6s   BoxVideoOutput: maxVideoBitRate=0 Kbps (unlimited), bEnableTimestamp_=1
T+5.6s   AndroidAutoEntity start — all 13 services start
T+5.6s   "first package, send version request"
T+5.9s   "version response, version: 1.7, status: 0"
T+5.9s   "Begin handshake."
T+6.0s   SSL handshake: ECDHE-RSA-AES128-GCM-SHA256
T+6.0s   "Auth completed."
T+6.0s   "Discovery request, device name: Android, brand: Google Pixel 10"
T+6.0s   SaveIcon: aa_32x32.png (152B), aa_64x64.png (240B), aa_128x128.png (411B)
T+6.0s   Fill features for all services
T+6.3s   Audio focus request type 4 → ReleaseAudioFocus(505)
T+6.3s   Channel opens: AudioInput, Media/Speech/System Audio, Sensor, BT, Nav, Media Status, Input
T+6.3s   Video setup request config index 3
T+6.3s   "send video focus indication. isVideoHide: 0"
T+6.5s   onAVChannelStartIndication → video streaming begins
T+6.5s   RequestVideoFocus(500) sent to host
T+6.7s   First H264 SPS/PPS (30 bytes) + first I-frame received
T+6.7s   spsWidth=1920, spsHeight=1088
T+6.7s   "recv AndroidAuto size info: 1920 x 1080"
T+7.0s   Full connection established, video streaming
```

**Total connection time:** ~6-7 seconds from BT trigger to first video frame.

### Disconnect Sequence

```
1. "closeTransfer() called"
2. All services stop in reverse order
3. MiddleMan socket ClosedByPeer cascade
4. BT DisconnectRemoteDevice via D-Bus
5. HCI Disconnect reason 0x13 (remote user terminated)
6. ARMadb-driver: "Phone disconnected"
7. ARMAndroidAuto enters waitForUSBDevice/waitForLink again
   (or is killed by phone_link_deamon.sh if mode changes)
```

---

## 8. SSL/TLS Authentication

| Property | Value |
|----------|-------|
| Cipher | `ECDHE-RSA-AES128-GCM-SHA256` |
| CA Issuer | `C=US, ST=California, L=Mountain View, O=Google Automotive Link` |
| Subject | `C=US, ST=California, L=Mountain View, O=CarService` |
| Key | RSA 2048-bit |
| Validity | Jul 4, 2014 — Jun 24, 2026 |
| Protocol version | 1.7 (observed with Pixel 10) |
| Handshake flow | Version request → Version response → SSL_do_handshake (async, 2 rounds) → Auth completed → Service discovery |

The RSA private key and X.509 certificate are embedded statically in `aasdk::messenger::Cryptor::cPrivateKey` / `cCertificate`.

**WARNING:** Certificate expires Jun 24, 2026. After this date, AA connections may fail unless firmware is updated with a new certificate.

---

## 9. Video Pipeline

### Configuration

| Parameter | Value | Source |
|-----------|-------|--------|
| Output resolution | 1920x1080 | Computed: config height 630 + margin 450 |
| SPS/PPS resolution | 1920x1088 | 8-line alignment padding (standard H.264) |
| Codec | H.264 Baseline Profile (`MEDIA_CODEC_VIDEO_H264_BP`) |
| Max bitrate | 0 Kbps (unlimited) or 5000 Kbps | `maxVideoBitRate` from config |
| FPS enum | 2 (maps to 30fps) | `setVideoFPS` |
| Resolution enum | 3 (maps to 1080p) | `setVideoResolution` |
| Wireless cap | 1080p max | Firmware-enforced: "Wireless AndroidAuto max support 1080p" |
| Timestamp mode | Enabled (`bEnableTimestamp_=1`) | |

Supported resolution enums: `_480p`, `_720p`, `_720p_p`, `_1080p`, `_1080p_p`, `_1440p`, `_1440p_p`, `_2160p`, `_2160p_p`

### BoxVideoOutput State Machine

```
open() → "do nothing" (no-op, already initialized)
init() → "do nothing"
onVideoStateChanged(1) → video streaming started
    → RequestVideoFocus(500) sent to host (unless bIgnoreVideoFocus=1)
onVideoStateChanged(0) → video streaming stopped
    → ReleaseVideoFocus(501) sent (unless bIgnoreVideoFocus=1)
```

`bIgnoreVideoFocus` is set to 1 after the first video focus cycle. The adapter does NOT gate AA video on focus responses.

### Keyframe Behavior (CRITICAL — differs from CarPlay)

- `requestKeyFrame()` has a **1-second throttle**:
  - Within 1s of last request: `"requestKeyFrame too quickly, wait 1 second"`
  - Still within window on retry: `"requestKeyFrame too quickly, ignore it!!!"`
- `bCheckManualRequestKeyFrame` — global flag controlling manual keyframe requests
- Host should send **ONE** keyframe request at session start, then rely on natural IDRs
- Natural IDR interval: **60-68 seconds** (encoder-configured `sync-frame-interval=60s` + encoding delay)
- **NEVER send periodic keyframe requests to AA** — unlike CarPlay's 2s periodic requests

### Video Focus Modes

| Mode | Value | Meaning |
|------|-------|---------|
| VIDEO_FOCUS_NATIVE | 1 | Native HU content |
| VIDEO_FOCUS_NATIVE_TRANSIENT | 3 | Temporary native overlay |
| VIDEO_FOCUS_PROJECTED | — | AA projection active |

### Frame Cache and Timing

- `VIDEO_FRAME_CACHE_NUM` — frame cache size
- `VIDEO_TIMESTAMP_MODE` — timestamp handling mode
- `frame, delay ack` — frame acknowledgment delay (flow control)
- `SendEmptyFrame` — empty frame for keepalive
- `AltVideoFrame` — alternative video frame handling

### Observed Frame Rates (from TTY log)

During active content: 19-30 fps, 24-357 KB/s
During idle screen: 5-13 fps, ~0.5 KB/s

---

## 10. Audio Pipeline

### Audio Channel Configuration

| Channel | Sample Rate | Sample Size | Channels | Cache (ms) |
|---------|-------------|-------------|----------|------------|
| MEDIA_AUDIO | 48,000 Hz | 16-bit | 2 (stereo) | 1000 |
| SPEECH_AUDIO | 16,000 Hz | 16-bit | 1 (mono) | 300 |
| SYSTEM_AUDIO | 16,000 Hz | 16-bit | 1 (mono) | 300 |

### Audio Codecs

`MEDIA_CODEC_AUDIO_AAC_LC`, `MEDIA_CODEC_AUDIO_AAC_LC_ADTS`, `MEDIA_CODEC_AUDIO_PCM`

### BoxAudioOutput Members

- `mediaStatus_`, `naviStatus_`, `callStatus_`, `vrStatus_` — per-stream state
- `mediaCacheMs_`, `naviCacheMs_`, `speechCacheMs_` — configurable jitter buffer
- `setAudioCacheMs: 1000, 300, 300` — set at connection time from link params

### Audio Focus Flow

```
AudioFocusRequest type 1 → RequestAudioFocus(502) → audio playing
AudioFocusRequest type 4 → ReleaseAudioFocus(505) → audio stopped
NaviFocusChanged 2       → RequestNaviFocus(506)   → nav audio active
NaviFocusChanged 1       → ReleaseNaviFocus(507)   → nav audio inactive
VRStatusChanged 3        → voice recognition start
VRStatusChanged 4        → voice recognition end
```

### Audio Ducking Rule

- `close media channel when call start` — phone call preempts media
- `close speech channel when call start` — phone call preempts speech
- `vr channel handle in onVRStatusChanned()` — VR routes through BoxAudioOutput

### Audio Resampling

`BoxAudioConvertInit ctx:%p, src format:%d-%d-%d, dst format:%d-%d-%d` — format conversion between phone codec output and adapter's internal audio pipeline.

### Jitter Buffer

The `BoxAudioUtils` module implements a jitter buffer for SPEECH_AUDIO:
- `Jitter Buffer first packet data.` — first packet received
- `Jitter Buffer is ready now.` — buffer filled, playback begins
- `JB buffer not enough: 1536` — underrun
- `JB buffer all-consumed.` — buffer empty

### Microphone (CRITICAL — AA Phone Call Fix, 2026-03-15)

| Mode | decodeType | Sample Rate | Chunk Size | Routing |
|------|-----------|-------------|------------|---------|
| Google Assistant | 5 | 16 kHz | 640B/20ms | AudioInputService |
| AA phone call (HFP/SCO) | 3 | 8 kHz | 320B/20ms | hfpd → BT SCO bridge |
| CarPlay phone call | 5 | 16 kHz | 640B/20ms | iAP2/WiFi (no SCO) |

**Root cause of original bug:** carlink_native hardcoded decodeType=5 (16kHz) for all mic modes. Adapter expected decodeType=3 for SCO routing. Fix: dynamic decodeType from `AUDIO_INPUT_CONFIG` command.

Mic lifecycle (from TTY log):
```
Voice session request, type: START
  → BoxAudioOutput onVRStatusChanned: 3
  → Set BOX_TMP_DATA_AUDIO_TYPE: 0x0104
  → AudioInputService input open request, open: 1
  → StartRecordMic(1)
  → Set BOX_TMP_DATA_AUDIO_TYPE: 0x0504
Voice session request, type: END
  → AudioInputService input open request, open: 0
  → StopRecordMic(2)
  → BoxAudioOutput onVRStatusChanned: 4
```

---

## 11. Wireless AA Setup

### RFCOMM WiFi Handoff

The phone connects to the adapter via BT RFCOMM (AAP service), then ARMAndroidAuto sends WiFi credentials for the phone to join the adapter's hotspot:

```
BoxRFCOMMService start: <phone BT addr>
  → sendRFCOMMData type 4 (WifiVersionRequest)
  ← recv type 5 (WifiVersionResponse): 0
  → sendRFCOMMData type 1 (WifiStartRequest)
  ← recv type 2 (WifiInfoRequest)
  → sendRFCOMMData type 3 (WifiInfoResponse)
  ← recv type 7 (WifiStartResponse): success
  ← recv type 6 (WifiConnectStatus): 0 (connected)
  → "Wireless Device connected."
```

### WiFi Configuration Sent via RFCOMM

| Parameter | Value |
|-----------|-------|
| channelfreq | 5180 (channel 36, 5GHz) |
| channeltype | 0 |
| ip | 192.168.43.1 |
| port | 54321 |
| ssid | carlink |
| passwd | 12345678 |
| bssid | 00:E0:4C:98:0A:6C (adapter WiFi MAC) |
| securityMode | 8 (WPA_PERSONAL) |

### WiFi Channel Support

`CHANNELS_24GHZ_ONLY`, `CHANNELS_5GHZ_ONLY`, `CHANNELS_DUAL_BAND`

WiFi chip detection: `BCM4335`, `BCM4354`, `BCM4358`, RTL8822CS — reads from `/sys/bus/sdio/devices/mmc0:0001:1/device`

### Network Info

- `/sys/class/net/wlan0/address` — WiFi MAC
- `/sys/class/net/wlan0/operstate` — WiFi state
- `/sys/class/bluetooth/hci0/address` — BT MAC

---

## 12. USB / AOA (Wired AA)

### Android Open Accessory Protocol

`AccessoryModeQueryChain` sequence: manufacturer → model → description → serial → URI → version → protocolVersion → start

```
AccessoryModeQueryChain startQuery, queryType = %d
Already in AOA Mode, Ignore          — skip setup if already in accessory mode
_isFakeiPhoneUsbBus, ignore this dev! — filter out iPhone USB devices
_isUSB_LinuxHUB, ignore this dev!     — filter out USB hubs
Configure AOA OK
```

### USB Transfer Config

- `USBTransMode` — Zero-Length Packet mode for AOA bulk transfers
- `USBConnectedMode` — connection mode indicator
- `AsyncWrite use time: %lld ms, iSize: %d, ret: %d` — transfer timing

---

## 13. Navigation

### Protobuf Messages

- `NavigationStatus` — ACTIVE / INACTIVE
- `NavigationTurnEvent` — turn type, street name, turn_side, turn_angle, turn_number, maneuver image
- `NavigationDistanceEvent` — distance in meters, time to turn in seconds
- `NavigationImageOptions` — maneuver icon dimensions
- `NavFocusType` — navigation focus enum

### NextTurn Enum → NaviOrderType Mapping (Live Verified 2026-03-15)

The aasdk protobuf defines 18 `NextTurn_Enum` values. The adapter firmware converts these to `NaviOrderType` values in NaviJSON with a **+2 offset** from the standard proto enum (with exceptions for DEPART and MERGE).

**Standard aasdk proto values** (from open-source `NextTurnEnum.proto`):

| aasdk Enum | Proto Value | → NaviOrderType | NaviTurnSide | Live Verified | Notes |
|------------|-------------|-----------------|-------------|---------------|-------|
| UNKNOWN | 0 | 0? | 0 | — | Alias for unspecified |
| DEPART | 1 | **1** | 0 | **YES** | toward Monkshood Ln |
| NAME_CHANGE | 2 | **4** (predicted) | ? | — | proto+2 pattern |
| SLIGHT_TURN | 3 | **5** (predicted) | 1=L, 2=R | — | proto+2 pattern |
| TURN | 4 | **6** | 1=L, 2=R | **YES** | Buzby Rd, AK-6 N, Laurance Rd |
| SHARP_TURN | 5 | **7** (predicted) | 1=L, 2=R | — | proto+2 pattern |
| U_TURN | 6 | **8** (predicted) | 1=L, 2=R | — | proto+2 pattern |
| ON_RAMP | 7 | **9** | 1=L, 2=R | **YES** | North Pole / Fairbanks |
| OFF_RAMP | 8 | **10** | 1=L, 2=R | **YES** | Badger Rd / Santa Claus Ln |
| FORK | 9 | **11** (predicted) | 1=L, 2=R | — | proto+2 pattern |
| MERGE | 10 | **0** | 0 | **YES** | AK-2 W (maps to none/straight) |
| ROUNDABOUT_ENTER | 11 | **13** (predicted) | 1=CW, 2=CCW | — | proto+2 pattern |
| ROUNDABOUT_EXIT | 12 | **14** (predicted) | 1=CW, 2=CCW | — | proto+2 pattern |
| ROUNDABOUT_ENTER_AND_EXIT | 13 | **15** (predicted) | 1=CW, 2=CCW | — | proto+2 pattern |
| STRAIGHT | 14 | **16** (predicted) | 0 | — | proto+2 pattern |
| (gap) | 15 | — | — | — | Not defined in aasdk |
| FERRY_BOAT | 16 | **18** (predicted) | 0 | — | proto+2 pattern |
| FERRY_TRAIN | 17 | **19** (predicted) | 0 | — | proto+2 pattern |
| (gap) | 18 | — | — | — | Not defined in aasdk |
| DESTINATION | 19 | **21** (predicted) | 0 | — | proto+2 pattern |

**Conversion pattern:** `NaviOrderType = aasdk_proto_value + 2` for values ≥ 2. Exceptions: DEPART(1→1) identity, MERGE(10→0) maps to none.

**NaviTurnSide values:** 0=unspecified, 1=left, 2=right, 3=unspecified (observed in DEPART)

**NaviTurnAngle values:** 0=none, 2=observed with TURN (meaning TBD — possibly moderate angle indicator)

### NaviJSON Conversion

ARMAndroidAuto converts AA navigation protobuf to NaviJSON and sends it via MediaData 0x2A subtype 200 to the host. The conversion happens inside the packed code (no `_SendNaviJSON` log in ttyLog for AA — only CarPlay logs this call). NaviJSON fields:

| Field | Present in AA | Present in CarPlay | Notes |
|-------|--------------|-------------------|-------|
| `NaviOrderType` | **YES** | no | AA maneuver type — see enum table above |
| `NaviManeuverType` | **no** | YES | CarPlay maneuver type (CPManeuverType 0-53) |
| `NaviRoadName` | YES | YES | Street name (identical field) |
| `NaviRemainDistance` | YES | YES | Distance to next maneuver in meters |
| `NaviNextTurnTimeSeconds` | **YES** | **no** | Time to next turn in seconds (AA-only) |
| `NaviTurnSide` | YES | YES | 0=unspecified, 1=left, 2=right |
| `NaviTurnAngle` | YES | YES | Turn angle indicator |
| `NaviRoundaboutExit` | YES | YES | Roundabout exit number |
| `NaviStatus` | YES | YES | 1=active, 2=inactive |
| `NaviDistanceToDestination` | no | YES | CarPlay-only |
| `NaviTimeToDestination` | no | YES | CarPlay-only |
| `NaviDestinationName` | no | YES | CarPlay-only |
| `NaviAPPName` | no | YES | CarPlay-only |

**Critical for host apps:** Check `NaviManeuverType` first (CarPlay). If absent, fall back to `NaviOrderType` (AA) and apply the +2 offset mapping table above.

### Maneuver Icons

AA sends 1739-2542 byte PNG maneuver icons per turn event. These are logged on the adapter (`image size: NNNN`) but **NOT forwarded to host** via USB. The adapter drops them during NaviJSON conversion.

### Live Capture Evidence (2026-03-15)

**Adapter ttyLog** (AA protobuf side):
```
Turn Event, Street: Buzby Rd, turn_side: 2, event: NextTurn_Enum_TURN, image size: 2275
Turn Event, Street: North Pole / Fairbanks, turn_side: 1, event: NextTurn_Enum_ON_RAMP, image size: 2542
Turn Event, Street: Badger Rd / Santa Claus Ln, turn_side: 2, event: NextTurn_Enum_OFF_RAMP, image size: 2297
Turn Event, Street: AK-2 W, turn_side: 3, event: NextTurn_Enum_MERGE, image size: 3716
Distance Event, Distance (meters): 456, Time To Turn (seconds): 31
```

**Emulator logcat** (NaviJSON USB side, confirming data arrives):
```
[RECV] MediaData(type=NAVI_JSON)
NaviJSON: keys=[NaviRoadName, NaviTurnSide, NaviOrderType, NaviTurnAngle, NaviRoundaboutExit]
  values=NaviRoadName=Buzby Rd, NaviTurnSide=2, NaviOrderType=6, NaviTurnAngle=2, NaviRoundaboutExit=0
NaviJSON: keys=[NaviRemainDistance, NaviNextTurnTimeSeconds]
  values=NaviRemainDistance=440, NaviNextTurnTimeSeconds=30
```

**Cluster observation:** Road name and distance update correctly. Maneuver icon shows constant straight arrow because `NavigationStateManager` reads `NaviManeuverType` (absent in AA) → falls to 0 → `ManeuverMapper` returns `TYPE_STRAIGHT`.

> **Captures saved:** `analysis/aa_full_session_emulator_20260315.txt` (44,689 lines), `analysis/aa_full_session_adapter_20260315.txt` (15,297 lines)

---

## 14. Media Status

### MediaStatusService Output

```
Playback update, state: PLAYING, source: YouTube Music, progress: 175
Metadata update, track_name: Black Creek, artist_name: Brent Cobb,
  album_name: No Place Left to Leave (2006), album_art size: 163337,
  playlist: , duration_seconds: 212, rating: 0
```

Playback states: `STOPPED`, `PAUSED`, `PLAYING`

State changes are forwarded to ARMadb-driver as:
- `kRiddleAudioSignal_MEDIA_START` — when PLAYING
- `kRiddleAudioSignal_MEDIA_STOP` — when STOPPED/PAUSED

---

## 15. Sensor Data

### Supported Sensor Types

Vehicle sensors (protobuf messages):
- `Accel`, `Gyro`, `Compass`, `Speed`, `RPM`, `Odometer`, `FuelLevel`
- `Gear` (with gear enum), `NightMode`, `DrivingStatus`, `ParkingBrake`
- `SteeringWheel`, `TirePressure`, `Light`, `Door`, `HVAC`, `Passenger`, `Range`
- `GPSLocation`, `GpsSatellite`, `GpsSatelliteInner`

### GPS Location Fields

`latitude_e7`, `longitude_e7`, `elevation_e3`, `bearing_e6`, `altitude_e2`, `accuracy_e3`, `azimuth_e3`

Sensor fusion types: `ACCELEROMETER_FUSION`, `CAR_SPEED`, `CAR_SPEED_FUSION`, `RAW_GPS_ONLY`

### Night Mode

```
recv start night mode   → SensorService forwards night mode ON
recv stop night mode    → SensorService forwards night mode OFF
```

### GPS Bug

`std::stoi()`/`strtol()` truncates NMEA coordinate fractional minutes (e.g., "6447.9901" → integer 6447). GPS quantized to ~1.85km whole-minute grid.

**Runtime patcher** (`gps_patcher.c`): ptrace-based patch injects corrected NMEA→decimal_degrees conversion at GOT addresses. GOT: `strtol` at `0x1896f4`, `strtod` at `0x189920`, `strtof` at `0x189a4c`.

---

## 16. Bluetooth

### BT Service Registration Order

```
IAP2 service registered
NearBy service registered
HiChain service registered
AAP service registered        ← Android Auto Protocol
Serial Port service registered
```

### HFP (Hands-Free Profile) for Phone Calls

`hfpd` (HFP daemon) manages BT SCO bridge for phone calls:

```
hfpd -y -E -f
  → D-Bus: Exported /net/sf/nohands/hfpd
  → D-Bus: Exported /net/sf/nohands/hfpd/soundio
  → D-Bus: Exported /net/sf/nohands/hfpd/<BT_ADDR>
  → SCO MTU: 240:32 Voice: 0x0060
```

HFP AT command sequence (from TTY log):
```
<< AT+BRSF=63
>> +BRSF: 879
<< AT+CIND=?
>> +CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),...
<< AT+CMER=3,0,0,1
<< AT+CLIP=1
<< AT+CCWA=1
<< AT+CHLD=?
>> +CHLD: (0,1,2,3)
<< AT+CIND?
>> +CIND: 0,0,0,0,0,5,0
→ AG B0:D5:FB:A3:7E:AA: Connected
```

### BT Pairing

BluetoothService handles pairing coordination. On connection: `pairing request, address: <BT_ADDR>` → `pairing response`. If passkey unavailable: `"No passkey, try again!"` (retries every ~1-2s).

---

## 17. Media Transport (CORRECTED 2026-03-15)

**Correction:** Earlier analysis attributed DMSDP RTP stack usage to ARMAndroidAuto. String verification (2026-03-15) found that `DMSDPRtpSendQueueAVC`, `DMSDPRtpSendQueuePCM`, `DMSDPRtpSendQueueAAC`, `DMSDPServiceOpsTriggerIFrame`, and `libdmsdp` are **NOT present** in the ARMAndroidAuto binary strings. The DMSDP stack is used by AppleCarPlay and/or ARMadb-driver, not by ARMAndroidAuto.

ARMAndroidAuto uses its own aasdk/OpenAuto media pipeline: `aasdk::messenger::MessageInStream` / `MessageOutStream` with `aasdk::transport::USBTransport` or `TCPTransport` for media data transfer. Audio/video data flows through the `CRiddleUnixSocketClient` IPC to ARMadb-driver, which forwards it to the USB host.

---

## 18. Threading Model

| Thread | Purpose |
|--------|---------|
| Main thread | Lifecycle, configuration, initialization |
| IO Service workers | `startIOServiceWorkers` — boost::asio event loop runners |
| Transfer worker | `startTransferWorkers` — USB/TCP data transfer |
| USB worker | libusb event handling (`libusb_handle_events_timeout_completed`) |

Synchronization: pthread primitives (mutex, cond, thread create/join/detach)

Global exit flag: `gTransferExit`

Thread exit logging:
```
thread checkTransferWorker exit
thread transferWorker exit/start
thread usbWorker libusb_handle_events_timeout_completed exit
thread ioService run exit
```

---

## 19. Error Handling

### Protocol Errors

| Error | Meaning |
|-------|---------|
| `STATUS_OK` / `STATUS_SUCCESS` | Success |
| `STATUS_AUTHENTICATION_FAILURE` | SSL auth failed |
| `STATUS_CERTIFICATE_ERROR` | Certificate issue |
| `STATUS_NO_COMPATIBLE_VERSION` | Version mismatch |
| `STATUS_FRAMING_ERROR` | Protocol framing error |
| `STATUS_INVALID_CHANNEL` | Bad channel ID |
| `STATUS_MEDIA_CONFIG_MISMATCH` | Audio/video config mismatch |
| `STATUS_BLUETOOTH_UNAVAILABLE` | No BT adapter |
| `STATUS_BLUETOOTH_HFP_CONNECTION_FAILURE` | HFP connect failed |
| `STATUS_WIFI_DISABLED` | WiFi off |
| `STATUS_WIFI_INCORRECT_CREDENTIALS` | Bad WiFi password |
| `STATUS_PROJECTION_ALREADY_STARTED` | Already projecting |
| `ERROR_INCOMPATIBLE_PHONE_PROTOCOL_VERSION` | Phone protocol too old/new |
| `ERROR_BT_CLOSED_BEFORE_START` / `AFTER_START` | BT dropped |
| `ERROR_PHONE_UNABLE_TO_CONNECT_WIFI` | Phone WiFi connect failed |
| `ERROR_REQUEST_TIMEOUT` | Timeout |

### Signal Handling

```
Catch signal kill, process will exit!!!
```

ARMAndroidAuto catches SIGTERM (15) for graceful shutdown.

### Observed Crash

```
Segmentation fault
```

Observed once during startup (line 525) when the binary was killed and restarted rapidly. The daemon script auto-restarts it.

---

## 20. File Paths Referenced

### Configuration Files

| Path | Purpose |
|------|---------|
| `/etc/riddle.conf` | Main adapter configuration |
| `/etc/riddle_default.conf` | Default configuration |
| `/etc/android_work_mode` | Android work mode (1=AA) |
| `/etc/box_product_type` | Product type identifier |
| `/etc/box_version` | Firmware version |
| `/etc/software_version` | Software version |
| `/etc/serial_number` | Serial number |
| `/etc/uuid` | Device UUID |
| `/etc/deviceinfo` | Device information |
| `/etc/bluetooth_name` | BT adapter name |
| `/etc/airplay_brand.conf` | Brand config |
| `/etc/default_wifi_channel` | WiFi channel |
| `/etc/hostapd.conf` | WiFi AP config |

### Runtime Files

| Path | Purpose |
|------|---------|
| `/tmp/screen_dpi` | Screen DPI setting |
| `/tmp/rfcomm_AAP` | RFCOMM socket for AA wireless |
| `/tmp/aa_32x32.png` | AA app icon (32px) |
| `/tmp/aa_64x64.png` | AA app icon (64px) |
| `/tmp/aa_128x128.png` | AA app icon (128px) |
| `/tmp/app.log` | Application log |
| `/tmp/box.log` | Box log |
| `/tmp/bluetooth_status` | BT state |
| `/tmp/wifi_status` | WiFi state |
| `/tmp/wifi_connection_list` | Connected devices |
| `/var/run/adb-driver` | IPC Unix socket |

### System/Hardware

| Path | Purpose |
|------|---------|
| `/dev/i2c-1` | I2C bus |
| `/dev/mem` | Memory device |
| `/proc/meminfo` | Memory info |
| `/sys/class/gpio/gpio2/value` | GPIO control |
| `/sys/class/gpio/gpio9/value` | GPIO control |
| `/sys/class/thermal/thermal_zone0/temp` | CPU temperature |

---

## 21. Car Brand Support

Embedded brand strings: `AUDI`, `Bentley`, `BUICK`, `CADILLAC`, `CHEVROLET`, `DODGE`, `FIAT`, `FORD`, `HONDA`, `HYUNDAI`, `JEEP`, `LEXUS`, `LINCOLN`, `MASERATI`, `MERCEDES_BENZ`, `NISSAN`, `PORSCHE`, `SUBARU`, `TOYOTA`, `Alfa_Romeo`

---

## 22. Daily Active Info

ARMadb-driver collects connection telemetry:

```json
{
  "phone": {
    "model": "Google Pixel 10",
    "linkT": "AndroidAuto",
    "conSpd": 6,
    "conRate": 0.9,
    "conNum": 194,
    "success": 174
  },
  "box": {
    "uuid": "651ede982f0a99d7f9138131ec5819fe",
    "model": "A15W",
    "hw": "YMA0-WR2C-0003",
    "ver": "2025.10.15.1127",
    "mfd": "20240119"
  }
}
```

90% success rate (174/194 attempts), 6-second average connection speed.

---

## 23. Known Issues

1. **Certificate expiry:** Google Automotive Link CA cert expires Jun 24, 2026
2. **GPS precision:** `strtol()` truncation loses NMEA fractional minutes (~1.85km error)
3. **GPS timestamp:** `timestamp: 0` — adapter clock stuck at 2020-01-02, cannot derive epoch from NMEA
4. **Keyframe throttle:** 1-second minimum between IDR requests, may cause initial decoder corruption on host
5. **Boot-screen poisoning:** First IDR is often a tiny boot-screen frame; host must skip initial decoded frames
6. **WiFi default password:** `12345678` sent in cleartext via RFCOMM
8. **No NaviManeuverType:** AA uses `NaviOrderType` in NaviJSON, causing host ManeuverMapper fallback to type 0
9. **Maneuver icons not forwarded:** 1739-byte PNG icons are logged but not sent to USB host
