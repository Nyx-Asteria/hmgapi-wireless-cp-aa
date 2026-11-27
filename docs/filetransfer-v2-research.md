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
