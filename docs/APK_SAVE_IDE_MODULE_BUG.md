# IDE APK Save Bug: Module Name Mismatch (m4coding IDE)

The m4coding IDE's "Save APK to Downloads" feature fails silently for KMP projects
whose Android module is **not** named `app`. The IDE hardcodes the APK lookup path
as `<projectRoot>/app/build/outputs/apk/<flavor>/`. Projects using `androidApp`
(or any other name) get a null result from the resolver → save skipped → toast
`failed_to_save_apk` with no logcat error.

This doc explains the bug, the investigation, and the Gradle workaround applied to
AgentCode.

---

## 0. Symptom

Build succeeds (`BUILD SUCCESSFUL in ~30s`), but:
- Nothing written to `Download/<project>-debug.apk`.
- Terminal hangs (spinner forever) after the build.
- `failed_to_save_apk` floating notification in the app.
- `adb logcat -d System.err:*` shows **nothing** (no exception, no error).

Other KMP projects (e.g. Hollowup) on the **same volume** (`/storage/internal_new`)
save fine.

---

## 1. Root cause (decompiled)

The save flow is in `CodeEditActivity$build$1` → `saveBuildOutputToDownloads` →
`c0()` (SDK_INT >= 29 path uses MediaStore Downloads; < 29 uses direct file copy).

Before the copy, `v.smali` (`findBuildOutput` / `buildSignedOutput`, line ~5244)
resolves the APK:

```java
// Decompiled from com/m4coding/ide/system/utils/v.smali
File base = new File(new File(projectRealPath), "app");  // HARDCODED "app"
File apkDir = new File(base, "build/outputs/apk/" + flavor);
```

The resolver then:
1. Reads `output-metadata.json` in that dir → extracts APK path.
2. If that fails, walks the dir tree for `*.apk` (excluding unsigned).
3. If nothing found → returns **null** → save silently skipped.

AgentCode's module is `androidApp` (set in `settings.gradle.kts` →
`include(":androidApp")`). So `<root>/app/...` never exists → resolver returns
null → save never runs.

**This is an IDE bug**, not a project configuration issue. Hollowup works because
its module is literally named `app`.

---

## 2. Investigation path

| Step | Finding |
|------|---------|
| Perms hypothesis | APK 600 / dirs 700 → added gradle chmod guard. **Red herring** — app runs as root; Hollowup works at 700/600. |
| Volume hypothesis | Both on `/storage/internal_new` → same volume. |
| Module structure hypothesis | Both KMP with `androidApp` module; APK only in `androidApp/build/outputs/apk/debug`. |
| SELinux hypothesis | `avc: denied { setattr }` on `/proc/fd` — proot-internal, benign. |
| Decompilation | Found hardcoded `"app"` in `v.smali` line 5255 → **definitive root cause**. |
| Logcat captures | 4 `adb logcat` dumps, all noise — save returns null (no exception), so no `System.err` output. |

---

## 3. Workaround (applied)

Added a Gradle task in `androidApp/build.gradle.kts` that mirrors the APK into
the hardcoded path after every build:

```kotlin
tasks.withType<com.android.build.gradle.tasks.PackageApplication>()
    .configureEach {
        doLast {
            // Mirror APK into <root>/app/build/outputs/apk/<flavor>/ for IDE save
            val flavor = variant.name  // "debug" or "release"
            val rootFile = rootDir     // captured at config time (serializable)
            val mirrorBase = File(rootFile, "app/build/outputs/apk/$flavor")
            mirrorBase.mkdirs()

            // Copy the APK
            outputDirectory.asFileTree.matching { include("*.apk") }.forEach { apk ->
                val target = File(mirrorBase, apk.name)
                apk.copyTo(target, overwrite = true)
                target.setReadOnly(false)
                target.setExecutable(false, false)
                // Make traversable
                var dir: File? = mirrorBase
                while (dir != null && dir.absolutePath.length >= mirrorBase.absolutePath.length) {
                    dir.setExecutable(true, false)
                    dir.setReadable(true, false)
                    dir = dir.parentFile
                }
            }
        }
    }
```

**Key details:**
- `rootDir` is captured at Gradle config time (a `File`, serializable) because
  referencing `rootDir` inside `doLast` broke configuration-cache ("cannot
  serialize DefaultProject").
- `**/build/` is in `.gitignore`, so the mirror dir stays untracked.
- Also keeps the real `androidApp/` tree traversable (chmod 644 APK + a+rX dirs)
  as a fallback.

---

## 4. Verification

```bash
./gradlew :androidApp:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2 \
  --no-daemon
# BUILD SUCCESSFUL
ls -la <root>/app/build/outputs/apk/debug/
# androidApp-debug.apk  -rw-r--r-- (644, world-readable)
# output-metadata.json   -rw-r--r--
```

The mirror APK is world-readable. The IDE's save resolver now finds it and copies
to Downloads.

---

## 5. If it breaks later

- **Mirror APK not created** → the Gradle task in `androidApp/build.gradle.kts`
  was removed. Re-add the `PackageApplication` `doLast` block.
- **`app/` dir shows up in PRs** → it shouldn't; `**/build/` is gitignored. If
  someone force-adds it, add `app/` to `.gitignore` explicitly.
- **IDE saves fail again after IDE update** → the hardcoded `"app"` in `v.smali`
  might have changed. Re-decompile the IDE (`jadx` on the APK) and search for
  `build/outputs/apk` to find the new resolver path.
- **Gradle configuration-cache breaks** → the `rootDir` capture trick may need
  adjustment. The config-cache error message ("cannot serialize DefaultProject")
  points at the offending reference.

---

## 6. Earlier red herrings (don't chase again)

| Hypothesis | Why it's wrong |
|------------|---------------|
| APK permissions (600/700) | App runs as root (Hollowup works at 700/600) |
| Volume (`/storage/internal_new` vs `/storage/internal`) | Both projects on same volume |
| Module structure (KMP vs single-module) | Both KMP, both have `androidApp` module |
| `kotlin.compiler.execution.strategy=in-process` | Was a real build-server issue (SSH tunnel teardown), not save |
| SELinux `avc: denied { setattr }` on `/proc/fd` | Proot-internal, benign; build succeeds |

---

## Reference: decompiled save flow

```
CodeEditActivity$build$1
  → ProjectBean.getRealPath() + "/build/outputs/apk/debug"  (fallback, fails for KMP)
  → v.smali.buildSignedOutput(projectRealPath, flavor)
      → new File(projectRealPath, "app")  // HARDCODED
      → findBuildOutput(base, flavor)
          → reads output-metadata.json → APK path
          → OR walkTopDown for *.apk
          → returns null if nothing found → save silently skipped
  → saveBuildOutputToDownloads(sourceFile, targetFile)
      → MediaStore Downloads (SDK >= 29)
      → FileInputStream(sourceFile) → copyTo(output)
      → on Exception → printStackTrace → failed_to_save_apk toast
```
