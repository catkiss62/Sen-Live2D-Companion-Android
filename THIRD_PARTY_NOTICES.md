# Third-party notices

The native Android renderer uses the official Live2D Cubism SDK for Java 5 R5:

- `Live2D/CubismJavaFramework`, pinned as the `Framework` submodule at tag `5-r.5` / commit `c2d420012d004b8e61d4c589bd5c34513122f0ea`.
- `Core/android/Live2DCubismCore.aar`, copied without modification from the official `CubismSdkForJava-5-r.5` distribution. SHA-256: `3f05da57ab855e803000e6353888dd561c47758598c6c0200dcd0109312705f8`.

The Framework remains governed by the Live2D Open Software License referenced in its source headers. Cubism Core remains governed by the Live2D Proprietary Software License; the official `Core/RedistributableFiles.txt` expressly lists `android/Live2DCubismCore.aar` as redistributable under those terms. See `Core/LICENSE.md` and `Core/RedistributableFiles.txt`.

No purchased Live2D model, textures, motions, expressions, VTube Studio configuration, or other Sen model assets are included in this repository or APK.
