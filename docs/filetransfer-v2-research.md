# FileTransfer v2 — evidence and experiment plan

## Verified inputs (2026-11-27)
- K5FirmwareDump(3)(4).zip: K5FirmwareDump/capture-1795572810523/firmware/system/lib/libiap2session.so, MD5 fc7cb675b46a44bd893a1edc054c5e71.
- ios27-carplay-metadata.tar(2)(3).gz: searched extracted text entries below 15 MB for file transfer/session references; no explicit v2/version token in the narrower search of entries below 10 MB.
- Incremental IPSW archive not yet examined in this cycle.

## Native file-session evidence
ELF symbol values (Thumb bit included):
| Symbol | Value |
|---|---|
| iAP2FileSessionCreate | 0x16df9 |
| iAP2FileSessionCreateXfer | 0x1758d |
| iAP2FileSessionHandleMsg | 0x173e9 |
| iAP2FileSessionDestroy | 0x1705d |
| iAP2SessCtrlGetSupportedFileSession | 0x1b147 |
| _Z32iAP2ControlInfo_CheckFileSessionPv | 0x1dbe1 |
| _Z25iAP2FileSessionCheckSetupP17iAP2FileSession_thPhj | 0x171bd |
| _Z25iAP2FileSessionCheckStartP17iAP2FileSession_thP19iAP2FileTransfer_st | 0x174d1 |

Native strings also reference FileTransferCreate_cb, FileTransferCleanup_cb and FileTransferHandleRecv_cb, and media/telephony artwork transfer handlers. These are evidence of existing transfer infrastructure, not a demonstrated v2 capability. Initial objdump output decoded ARM instead of Thumb; do not use that output to infer control flow.

## iOS metadata evidence
- headers/System_Library_PrivateFrameworks_CarKit.framework_CarKit.objc.txt: _supportsFileTransfer, property and getter on CARSessionConfiguration.
- Corresponding CarKit symbols file: getter at 0x1c254bbf4.
- strings/System_Library_PrivateFrameworks_CarKit.framework_CarKit.strings.txt: fileTransfer and fileTransferInfo.
- strings/System_Library_PrivateFrameworks_AirPlaySender.framework_AirPlaySender.strings.txt: same strings.

These may describe AirPlay session configuration rather than iAP2 file-session version negotiation. Do not conflate the layers.

## Next evidence gates
1. Inspect relevant incremental archive metadata and binaries, preserving checksums; do not repeat unnecessary firmware extraction.
2. Decode native Thumb control flow, identify actual capability/version fields and stock ownership; distinguish existing file-session support from v2.
3. Determine whether the receiver advertises a version and whether phone-side metadata establishes its semantics.
4. Only then implement independent default-off, firmware-gated negotiation; preserve stock traffic and do not register competing native owners.
5. Add production-source fixtures for disabled/default path, unsupported firmware, conflicts, malformed input, and rollback. Log only non-sensitive negotiation state.
6. Keep CPUL checkpoint withheld until entire shared identity implementation is complete; vehicle verification follows RC.

No runtime changes in this research checkpoint. No claim of support for v2, no invented IDs, and no new firmware collection required yet.

## Incremental archive and native control-flow checkpoint
- Concatenated part-000 and part-001 in order and independently computed SHA-256: 0f420961d25265cbf7df6550b87eac8105bbd7616eabad588bd1229f1a927be4, matching supplied combined manifest. Individual part hashes not independently confirmed in this cycle.
- Re-extracted K5 libiap2session.so, MD5 fc7cb675b46a44bd893a1edc054c5e71. Disassembly with llvm-objdump -d --triple=thumbv7-linux-gnueabi.
- 0x1b146 iAP2SessCtrlGetSupportedFileSession tail-branches to 0x1dbe0 CheckFileSession. That function checks feature-list selectors 12 and 11; if neither is available returns 0. Otherwise tests NowPlayingMedia used flag 26, NowPlayingPlayback used flag 15 and MLAPlaylist used flag 5; any enabled returns 1. These are capability/feature checks, not a negotiated file-transfer version. Do not repurpose these internal selectors as protocol IDs.
- 0x171bc CheckSetup reads the low nibble of payload byte 1 and branches on 4, 6 and 2. The branch for 4 assembles bytes at offsets 2..9 into a 64-bit value. This is evidence of a stock setup parser, not a v2 discriminator. No parser changes justified yet.
- Partial streaming scan of incremental Mach-O entries matching CarKit, AirPlaySender, AccessoryiAP2Shim, AccessoryCommunications, iAP2 and CarPlay names, files <=100 MiB. Observed FileTransferInfo/fileTransferInfo in 17 entries, supportsFileTransfer in CarKit. No explicit version match in the observed output; scan timed out before completion, so absence is not established. Shared string pools may cause repetition across extracted binaries; strings alone cannot establish ownership.
- Next: finish targeted archive scan without broad regex, distinguish Mach-O string ownership via xrefs, examine session initialization and version-specific evidence before any opt-in advertisement. No runtime change.

## Session initialization and version accessor (2026-11-27)
- Both individual incremental archive parts independently checked with sha256sum; part-000 1ba267e419ca6824890f62cf077feb6e7f299bde9249ccf08d0f227641367840; part-001 a446589ea26c45bcc3957655a99283429f67f9b755aff07f4fff5fad5089ad6b. Both match supplied parts manifest.
- Reverified native library MD5 fc7cb675b46a44bd893a1edc054c5e71. Thumb disassembly 0x16df8–0x16eb0: iAP2FileSessionCreate allocates 0x14 bytes, clears the structure, calls interface callback at +0x28 with 0x80 and 4, allocates resulting storage and initializes it. No explicit version argument observed in this routine.
- iAP2SessionGetVersion 0x178dc–0x17944 copies ten bytes of static data to stack, obtains string length, clears destination and copies only if buffer fits; static string at 0x27e89 is V200710.2. This is a static library version accessor, not evidence of negotiated file-transfer protocol version. Do not use it to advertise v2.
- Completed targeted extraction of incremental Objective-C headers for CarKit, AccessoryiAP2Shim, AccessoryCommunications and AirPlaySender. Search for file transfer, file session and transfer version yielded only CARSessionConfiguration supportsFileTransfer ivar/property/getter. This is limited to these header files, not an exhaustive binary or cross-reference search.
- Next: inspect protocol session-list construction and caller context in native firmware, then phone-side references and strings; no runtime capability changes yet.

## Link-layer session descriptors — verified companion library (2026-11-27)
Reopened actual archives in mounted workspace. K5FirmwareDump(3)(4).zip system/lib/libiap2session.so MD5 fc7cb675b46a44bd893a1edc054c5e71. The older cpul-report(3)(3).zip firmware-20261117-1409/system/lib/libiap2session.so has the same MD5, and contains libiap2client.so MD5 153a97fd6d2c62dc58286aa47c35feb3. The latter owns exported link/SYN routines and transfer callbacks; this is an older capture, not independent verification of its installed current version.
- Thumb 0x179f8 iAP2SessionSetFileSession stores r1 at context+4 when context is non-null. No version negotiation there.
- libiap2client.so strings explicitly describe SYN session descriptors as id/type/ver. Symbols: iAP2LinkSetDefaultSYNParam 0xdc75, iAP2LinkIsValidSynParam 0xdced, iAP2LinkValidateSynParam 0xddf1, iAP2LinkGetSessionInfo 0xf34d, iAP2LinkGetSessionForService 0xf6a1 (Thumb bit included).
- Thumb 0xf34c–0xf384 GetSessionInfo reads count at link+0x118, scans three-byte records at link+0x124, compares first byte with requested ID, returns matching descriptor pointer or null. This is direct evidence of a link-layer session record, rather than an identification message parameter. Strings alone do not prove which type/version values represent FTv2. No runtime edits.
- Other exported client functions include iAP2FileTransferCreate, HandleRecv, ValidateBufferID, Start and SendNext. Further review must trace SYN construction, validation and file-transfer callbacks to confirm whether v2 is actually supported before changing any advertisement.

## SYN descriptor validation — independently inspected binaries
Both exact ZIP entries cpul-report/firmware-20261117-1409/system/lib/libiap2client.so and firmware-20261117-1411/system/lib/libiap2client.so were separately extracted and MD5 verified as 153a97fd6d2c62dc58286aa47c35feb3. Disassembled with llvm-objdump --triple=thumbv7-linux-gnueabi. Findings limited to these captured copies.
- iAP2LinkIsValidSynParam 0xdd48–0xdd64: descriptor ID at +0x28 nonzero, type at +0x29 <=2, stride 3, requires type 0 in list. No check of +0x2a version in this loop. Does not establish transfer engine version support.
- iAP2LinkValidateSynParam 0xdf00–0xdf50: checks ID/type, rejects repeated ID OR repeated type against accepted descriptors. 0xe012–0xe032 copies all three bytes including +0x2a unchanged. 0xdfbe–0xdfda constructs a missing control descriptor with type 0, version 1 when conditions permit. No reason to patch validation based on these findings.
- iAP2LinkGetSessionForService 0xf6a0–0xf6ce: reads descriptor count at link+0x118, scans type at +0x125, returns ID at +0x124 for matching type, or -1. Does not consult version.
- Next evidence gate: trace FileTransfer create/receive/send and negotiated session version consumers. Do not equate preservation of arbitrary version byte with actual v2 support. Runtime remains unchanged.

## Transfer engine inspection (actual archived binaries, 2026-11-27)
Extracted both November 17 libiap2client.so entries from cpul-report(3)(3).zip in this cycle; both MD5 153a97fd6d2c62dc58286aa47c35feb3. libiap2session.so from both and K5FirmwareDump(3)(4).zip matches fc7cb675b46a44bd893a1edc054c5e71. Ran llvm-objdump -d --triple=thumbv7-linux-gnueabi on client Create (0xc904–0xc998), Start (0xced8–0xcfcc), SendNext (0xcfcc–0xd0e4), and receive entry (0xd358–0xd440). Create initializes caller-provided state, stores context at +0, byte fields at +0x44/+0x45, flag bits at +0x46. Start checks state +0x40, initializes state 1, calls internal 0xca30 with r1=4. SendNext checks state 2/3 and updates buffer and 64-bit counters. Receive entry validates non-null context/input and length >=2, dispatches low nibble of byte 1 through a jump table for values 0–6, examines bit 7 in one branch. No direct negotiated-version dispatch observed in these inspected ranges; this does not exclude version handling elsewhere. Need full receive/send framing, session call sites and independent v2 schema/checksum evidence before enabling either experiment. Prior detailed SYN offsets and archive assertions are historical notes, not independently revalidated in this cycle; don't treat them as fresh evidence.

## Evidence access checkpoint
The repository notes were reopened at f5d076dc2e4c31110e00b7ce8d92174c6ac80b82. A Library title search for the original firmware ZIP, CPUL report ZIP and iOS metadata archive did not return those archives. Historical disassembly/offset assertions above were not independently revalidated here. Do not enable either FileTransfer experiment on the basis of those assertions alone. Required inputs: exact companion libiap2client and libiap2session binaries, incremental iOS metadata or a reviewed protocol implementation defining FTv2 framing and checksum. Runtime remains stock; avoid invented version bytes.

## Reopened user uploads — current evidence checkpoint
The newly supplied K5FirmwareDump(3)(6).zip contains libiap2session.so (214252 bytes, MD5 fc7cb675b46a44bd893a1edc054c5e71). Both 1409 and 1411 captures in cpul-report(3)(5).zip independently match this session hash and contain libiap2client.so (107888 bytes, MD5 153a97fd6d2c62dc58286aa47c35feb3). These hashes were computed from extracted ZIP entries in the current workspace. readelf confirms packet checksum exports iAP2PacketCalcChecksum 0x1060d, iAP2PacketCopyAndCalcChecksum 0x106c1, iAP2PacketCalcHeaderChecksum 0x1070d, iAP2PacketCalcPayloadChecksum 0x10791 (Thumb symbol values). Their names alone do not establish FTv2 checksum semantics. Actual Thumb disassembly of receive entry 0xd358–0xd48c confirms non-null input/context, minimum two bytes, low nibble dispatch 0–6 and bit 7 branch in case 0; this branch manipulates buffer state and obtains a pool buffer. No version-specific dispatch established in this range. Scanned original metadata archive text entries below 15 MB for FileTransfer within 40 characters of version/checksum/v2 (both version/checksum orderings); no matches. Incremental archive and remaining receive branches not examined in this cycle. Archive(1)(6).zip contains Iap2ServerApp APK/ODEX. Next: compare actual receive/send framing and packet checksum layer against independently established v2 framing; do not infer v2 checksum from link checksum exports. No runtime changes.

## Incremental archive targeted inventory
Reopened the two supplied incremental parts (2)(5). A complete concatenated gzip/tar name listing returned the AccessoryiAP2Shim Mach-O and its refs, Objective-C headers, Swift metadata, symbols, strings, dependencies, function list, extraction log and decoded Info.plist, plus com.apple.accessories.core.iap2 logging subsystem plist and decoded JSON. No filename explicitly named FileTransfer was returned by the targeted filename filter. This is an inventory only, not a content scan or proof of absent FTv2 support. Next inspect those exact entries and related AccessoryCommunications/CarKit/AirPlaySender content. No speculative version or checksum changes.

## Targeted incremental metadata content pass
Read the concatenated supplied (2)(5) parts as a streaming gzip tar to completion. Filtered text/JSON entries <=30 MB with paths containing AccessoryiAP2Shim, AccessoryCommunications, CarKit or AirPlaySender. Case-insensitive search for file transfer/session, theme asset and wallpaper returned 192 CarKit Objective-C header lines, 248 refs lines, 214 symbol lines, 71 CarKit strings lines and four AirPlaySender strings lines. No lines in those matches contained file transfer within 55 characters of version, checksum or v2 (either order). CarKit exposes CRVehicle supportsThemeAssets, CARSessionConfiguration supportsFileTransfer, CARThemeAssetVersion initWithIdentifier:iOSContentVersion:accessoryContentVersion:, and theme librarian download/update observer methods; carkitd decoded launch plist declares com.apple.carkit.theme-asset-library. This suggests a distinct theme asset library/download lifecycle, not proof that iAP2 FTv2 is required for theme availability. No explicit v2 wire schema established in this bounded scan. Preserve the independent experimental paths, but do not enable guessed protocol bytes. Binary xrefs and other frameworks are outside this scan.

## Priority override — independent transport investigation
User explicitly removed FTv2 from the primary CPUL checkpoint gate. First establish whether theme librarian acquisition/transfer occurs through iAP2, AirPlay or another transport. Existing CarKit and AirPlay metadata is evidence of distinct layers, not a confirmed causal dependency. Trace library lifecycle call sites and corroborate transport-specific logs before choosing an implementation. No experimental runtime enabled; future paths default off and mutually exclusive. See docs/cpul-api-v3-integration.md.

## Theme acquisition versus transport: original metadata and vehicle log cross-check
Directly reopened ios27-carplay-metadata.tar(2)(5).gz. CarKit Objective-C header and symbol metadata expose CARThemeAsset baseURL, layoutURL and assetsArchiveURL, initWithBaseURL:version: (0x1c254c514), layoutURL (0x1c254c944), assetsArchiveURL (0x1c254c9a0), and baseURL (0x1c254ccb8). CARThemeAssetLibraryAgent has attempting/failed/completed download notification methods at 0x1c254f47c, 0x1c254f6a0, 0x1c254f920 respectively. Observer proxy receives corresponding service notifications. CARSessionConfiguration supportsFileTransfer getter is 0x1c254bbf4; CarKit and AirPlaySender string metadata contain fileTransferInfo. These are separate named surfaces; no caller/callee relationship or underlying URL scheme established by the metadata. Asset archive URL may describe local storage, not necessarily a remote download endpoint. No proof of iAP2 FTv2, AirPlay transfer or internet acquisition as the actual transport.

Cross-checked supplied logdump rc9(1)(7).zip (40 .txt/.log/.json/.csv entries), rc10 logdump(4).zip (22), and cpul-report(3)(5).zip (6). Case-insensitive literal searches for file.?transfer|file.?session and mobileasset|theme.?librar found zero matching lines in these text entries. theme.?asset|wallpaper matched 3, 1 and 4 lines respectively; examples are diagnostic settings summary and firmware WallpaperCropper inventory, not transfer events. This is a limited text search, not exhaustive binary/nested archive analysis or proof that transfer did not occur. No private log values copied. Next: identify concrete librarian implementation (not just agent/observer) and map its archive URL production/download requests to the responsible framework, then correlate actual session events. Primary checkpoint unaffected; no experimental runtime installed.
