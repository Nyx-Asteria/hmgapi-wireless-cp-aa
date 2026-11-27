# CPUL handoff: confirmed phone-side theme transfer channel

Source: iOS 27.0.1 build 24A446 carkitd, SHA256 87e352056b30cc29e206b265786dac43eeab8536e8cebeae14711e8e3fdaf8cd. Directly inspected binary, not inferred from symbol co-occurrence. Decoded LC_DYLD_CHAINED_FIXUPS format 12 ARM64E_USERLAND24 local rebase pointers, ObjC class_ro method lists and relative selector/IMP entries, then decoded ADRP/LDR selector stubs. Verified actual method ownership and call sequence.

| Instruction | Evidence |
|---|---|
| 0x100057b98 | CRThemeAssetLibrarian._mainQueue_evaluateAssetTransferIfConnected IMP |
| 0x100057bd0 / 0x100057bdc | sessionStatus / currentSession |
| 0x100057bf4 / 0x100057c00 | configuration / supportsFileTransfer |
| 0x100057c20 | branches on BOOL; false path logs session doesn't support file transfer |
| 0x100057f8c | allocates CARSessionChannel |
| 0x100057f98–0x100057fa4 | loads imported kFigEndpointRemoteControlSessionClientTypeUUIDKey_CarPlayUpdateData and calls initWithSession:channelType: |
| 0x100057fb0–0x100057fc0 | allocates CRFileSender (class at 0x10011ad40), calls initWithChannel:isPriority: with constructed channel |
| 0x100057fd0 | setFileSenderSession: |
| 0x10005801c | stagedClusterAssetURL |
| 0x100058128 | sendThemeAsset:completion: on constructed sender |

CRFileSender.sendThemeAsset:completion: IMP 0x10003c060; _chunkQueue_blockingSendChannelMessage: 0x10003da90; _internalQueue_handleFileAcceptMessage: 0x10003df04. CRFileReceiver has separate theme and log writer setup (0x10003b414 and 0x10003b87c). CarKit separately exposes supportsFileTransfer and supportsLogTransfer as readonly BOOLs. Its initializer symbol is 0x1c251df7c initWithSessionStatusOptions:propertySupplier:; body not recovered in this pass. No numeric UUID or capability dictionary value inferred.

**Implications:** Phone-to-accessory staged cluster asset transfer uses CARSessionChannel with CarPlayUpdateData client type. This is direct evidence at the channel API boundary, not proof of exact underlying wire encoding, K5 receiver support, or whether iAP2 FileTransfer v2 is involved lower down. Does not establish that theme selector eligibility or phone-side wallpaper downloads require this transfer. Preserve API v3 pinned identity integration; do not gate theme selector on this path or advertise support speculatively. CPUL can keep this as a diagnostic distinction: phone-side acquisition, staged cluster transfer, receiver acceptance/completion, selector eligibility are distinct. No CPUL source edits requested. Next trace CarKit capability initialization and AirPlaySender CarPlayUpdateData routing; confirm receiver implementation before any runtime changes. All experimental code remains off/unimplemented; no release.
