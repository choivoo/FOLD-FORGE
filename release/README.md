# release/

`SHA256SUMS` lists the checksums of the APKs built and verified in the 1.0.0 build environment.
The APK binaries (≈94 MB debug, ≈70 MB release) are too large to commit to git; they are
published as the **FoldForge-1.0.0-apks** artifact of the GitHub Actions workflow
(`.github/workflows/android.yml`), or can be rebuilt with `./gradlew assembleDebug assembleRelease`.
