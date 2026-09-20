# Third-party notices

The native Android renderer uses the official Live2D Cubism SDK for Java 5 R5:

- `Live2D/CubismJavaFramework`, pinned as the `Framework` submodule at tag `5-r.5` / commit `c2d420012d004b8e61d4c589bd5c34513122f0ea`.
- `Core/android/Live2DCubismCore.aar`, copied without modification from the official `CubismSdkForJava-5-r.5` distribution. SHA-256: `3f05da57ab855e803000e6353888dd561c47758598c6c0200dcd0109312705f8`.

The Framework remains governed by the Live2D Open Software License referenced in its source headers. Cubism Core remains governed by the Live2D Proprietary Software License; the official `Core/RedistributableFiles.txt` expressly lists `android/Live2DCubismCore.aar` as redistributable under those terms. See `Core/LICENSE.md` and `Core/RedistributableFiles.txt`.

No purchased Live2D model, textures, motions, expressions, VTube Studio configuration, or other Sen model assets are included in this repository or APK.

The optional Windows parameter capture tool uses `websocket-client` 1.8.0 under the Apache License 2.0 and is packaged with PyInstaller 6.16.0 under its bootloader exception. Its downloadable package includes a dedicated `THIRD_PARTY_NOTICES.txt`. It communicates only with the documented VTube Studio Public API and does not include or modify VTube Studio.

## E.V VTuber motion layer

The optional v0.5.24 motion experiment ports the motion-only VTuber performance layer bundled in
[`siaoic/E.V`](https://github.com/siaoic/E.V), reference commit
`473a3563d91cd4708dd3683d0cff0dc6f522fe52`, subtree `cortico-world-vtuber-main/`.
That upstream component is licensed under GNU AGPL-3.0-or-later.

The original `clips.json` and `vocab.json` are preserved byte-for-byte under
`app/src/main/assets/ev-vtuber-pack/`. The faithful Java engine ports the motion-related portions
of upstream `src/mixer.ts` and `src/clips.ts`; the Sen-adapted engine is kept as a separate
implementation. Source provenance and the full upstream license are under
`third_party/ev-vtuber-motion/`.
