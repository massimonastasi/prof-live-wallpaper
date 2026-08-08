import java.util.Properties

// Imported explicitly: inside the Gradle Kotlin DSL, `java` resolves to the plugin
// accessor rather than the package, so a fully qualified java.io.* reference fails.
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.massimonastasi.proflw"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.massimonastasi.proflw"
        // minSdk 31: Material You (onComputeColors -> system theme) arrived with Android 12.
        minSdk = 31
        targetSdk = 36
        /*
         * Semantic versioning from here on: MAJOR.MINOR.PATCH. Only versionName is edited;
         * versionCode is derived from it, because Play and every installed device order
         * updates by that integer alone and two numbers kept by hand drift apart. The
         * arithmetic leaves room for 100 patches and 100 minors per major, and is monotonic
         * as long as the name only ever goes up.
         */
        versionName = "1.0.1"
        versionCode = versionName!!.split(".").map { it.toInt() }
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    }

    /**
     * The release signing key, read from keystore.properties beside this file.
     *
     * That file and the key it points at are both outside version control: the key is this
     * application's identity, and an installation can only ever be updated by a build signed
     * with the same one. Losing it means every device that has the app has to uninstall it.
     *
     * When the file is absent - a fresh clone, or a machine that is not the release one - the
     * release build simply goes unsigned rather than failing, so the project still builds.
     */
    val keystore = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // ponytail: no compression on .wad files - they are read with random access from assets.
    androidResources {
        noCompress += "wad"
    }

    // Enabled for BuildConfig.DEBUG alone, which gates the on-screen debug overlay. Tying it
    // to the build type rather than to a constant is what stops the overlay ever shipping.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The wallpaper engine itself still uses nothing but the framework: WallpaperService and
// Canvas are platform classes, and no dependency here is reachable from the draw loop.
// These serve the settings screen, which needs a preference list and a document picker.
dependencies {
    // Material 3 for the settings screen only. minSdk 31 means dynamic colour is always
    // available, so the screen takes the system's palette rather than carrying its own.
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

/**
 * Copies the licence texts into assets so the application can show them.
 *
 * The BSD notice covering the bundled Freedoom assets has to be reproduced "in the
 * documentation and/or other materials provided with the distribution", and an APK ships
 * with no documentation: the licences screen is that material, and it is a redistribution
 * requirement rather than a nicety. The GPL text goes with it for the same reason.
 *
 * Copied rather than duplicated so the repository files stay the single source of truth.
 */
val copyLicences by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyLicences) }

// ForeignWadTest reads a WAD this project cannot ship, named on the command line. Gradle
// applies -D to its own JVM, not to the forked test one, so it has to be handed across.
tasks.withType<Test>().configureEach {
    providers.systemProperty("foreignWad").orNull?.let { systemProperty("foreignWad", it) }
}

/**
 * Writes the bundled asset WAD, keeping only the lumps this wallpaper reads.
 *
 * The full Freedoom IWAD is 27.5 MB and we use 3.2% of it: the rest is maps, wall textures,
 * menu graphics, music and sound effects. The output stays a WAD rather than a folder of
 * PNGs so the app keeps a single loader: the patch format carries the anchor offsets that
 * stop sprites jittering, and the rotation and mirroring convention lives in the lump names.
 * Flattening to images would need a side table for both and a second load path.
 *
 * Run with: gradlew reduceWad
 * Input:  app/wad/freedoom-full.wad          (downloaded, not in the repo)
 * Output: app/src/main/assets/freedoom2.wad  (what ships)
 *
 * The input deliberately sits outside assets. Left in there it would be packaged as well,
 * and the APK would carry both copies.
 *
 * WadFileTest asserts the shipped WAD still satisfies everything the code asks for, so a
 * drift between this list and GameData fails the build rather than the wallpaper.
 */
tasks.register("reduceWad") {
    val source = layout.projectDirectory.file("wad/freedoom-full.wad").asFile
    val target = layout.projectDirectory.file("src/main/assets/freedoom2.wad").asFile

    doLast {
        require(source.exists()) { "missing ${source.name}: download Freedoom and rename the IWAD to it" }

        // Sprite prefixes, mirroring GameData: creatures, the marine, projectiles, pickups
        // and effects. Diagonal views are dropped because nothing moves diagonally.
        val spritePrefixes = listOf(
            // Every creature the bestiary can name. Five of them exist only in a Phase 2
            // IWAD, so they simply match nothing in the bundled source - the reducer keeps
            // what it finds, and the same list is what filters an imported WAD at runtime.
            "POSS", "SPOS", "TROO", "CPOS", "SKUL", "SARG", "SKEL", "HEAD",
            "BOS2", "BSPI", "FATT", "BOSS", "SPID", "CYBR", "PLAY",
            "BAL1", "BAL7", "PLSS", "MISL", "FATB", "MANF", "APLS", "BLUD", "TFOG",
            "STIM", "MEDI", "ARM1", "ARM2", "SHOT", "MGUN", "SGN2", "PLAS", "LAUN",
        )
        val exactNames = buildSet {
            // Freedoom's own identifying lump. Seven bytes, and without it the reduced
            // asset no longer says what it is derived from.
            add("FREEDOOM")
            add("PLAYPAL")                    // palette, and the damage flash ramp
            add("F_START"); add("F_END")      // flat markers: flatIndex searches between them
            for (d in 0..9) add("STTNUM$d")   // readout numerals
            // Floors are chosen further down by measuring them, not named here. The app does
            // the same at runtime through FloorPicker; this cannot call that code, because
            // GameData is not on the build script's classpath, so WadFileTest checks the
            // shipped asset against the app's own choice and fails if the two disagree.
        }
        val keptRotations = charArrayOf('0', '1', '3', '5', '7')

        fun needed(name: String): Boolean {
            if (name in exactNames) return true
            val prefix = spritePrefixes.firstOrNull { name.startsWith(it) } ?: return false
            if (name.length != prefix.length + 2 && name.length != prefix.length + 4) return false
            // Keep the lump if it covers any rotation still in use; a mirrored pair carries
            // two, and rotation 0 means it serves every angle.
            return name.drop(prefix.length).filterIndexed { i, _ -> i % 2 == 1 }.any { it in keptRotations }
        }

        val bytes = source.readBytes()
        fun int(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        val count = int(4)
        val dir = int(8)
        fun nameAt(i: Int) = String(bytes, dir + i * 16 + 8, 8, Charsets.US_ASCII).trimEnd('\u0000')

        // The floors, measured the way FloorPicker measures them at runtime: mean luminance
        // inside a narrow band, so the backdrop never competes with launcher icons, then
        // spread across the colourfulness order so the ladder climbs by hue rather than
        // brightness. Naming flats here would mean naming Freedoom's, which is exactly the
        // dependency the app has just dropped.
        val palAt = int(dir + (0 until count).first { nameAt(it) == "PLAYPAL" } * 16)
        val fStart = (0 until count).first { nameAt(it) == "F_START" }
        val fEnd = (0 until count).first { nameAt(it) == "F_END" }

        // Every flat a backdrop could be, rather than the handful the ladder currently uses.
        //
        // This used to reproduce FloorPicker's selection here, and that was the wrong shape:
        // two copies of one rule, in two languages, that only disagree when somebody changes
        // one of them. It disagreed the moment the ladder went from five rungs to nine, and
        // the shipped WAD could then offer four floors to a picker asking for nine.
        //
        // Now the build applies only the *filter* - which is a threshold, not a policy - and
        // the choosing stays in FloorPicker, where it also runs on an imported WAD. It costs
        // about 150 KB of flats that may go unused, against a class of bug that cannot happen
        // any more: whatever the app asks for, the asset can answer.
        val floors = (fStart + 1 until fEnd).mapNotNull { i ->
            val e = dir + i * 16
            if (int(e + 4) != 64 * 64) return@mapNotNull null
            val at = int(e)
            var r = 0L; var g = 0L; var b = 0L
            var sum = 0.0; var squares = 0.0
            for (q in 0 until 64 * 64) {
                val c = palAt + (bytes[at + q].toInt() and 0xFF) * 3
                val pr = bytes[c].toInt() and 0xFF
                val pg = bytes[c + 1].toInt() and 0xFF
                val pb = bytes[c + 2].toInt() and 0xFF
                r += pr; g += pg; b += pb
                val l = 0.299 * pr + 0.587 * pg + 0.114 * pb
                sum += l; squares += l * l
            }
            val n = (64 * 64).toDouble()
            val mr = r / n; val mg = g / n; val mb = b / n
            val mean = sum / n
            val luminance = 0.299 * mr + 0.587 * mg + 0.114 * mb
            val peak = maxOf(mr, mg, mb)
            val contrast = Math.sqrt(maxOf(0.0, squares / n - mean * mean))
            // The same three thresholds FloorPicker states and explains: quiet overall, quiet
            // in every channel, and not a field of dots pretending to be ground.
            val usable = luminance in 20.0..45.0 &&
                peak <= 90.0 &&
                contrast / maxOf(1.0, luminance) <= 0.60
            if (usable) nameAt(i) else null
        }.toSet()
        logger.lifecycle("reduceWad: ${floors.size} flats pass the backdrop filter")

        val kept = mutableListOf<Triple<String, Int, Int>>()      // name, offset, size
        for (i in 0 until count) {
            val e = dir + i * 16
            val name = nameAt(i)
            if (needed(name) || name in floors) kept += Triple(name, int(e), int(e + 4))
        }

        val out = ByteArrayOutputStream()
        fun writeInt(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }

        out.write("IWAD".toByteArray(Charsets.US_ASCII))
        writeInt(kept.size)
        writeInt(0)                                              // directory offset, patched below
        val offsets = kept.map { (_, off, size) ->
            val here = out.size()
            out.write(bytes, off, size)
            here
        }
        val dirOffset = out.size()
        kept.forEachIndexed { i, (name, _, size) ->
            writeInt(offsets[i])
            writeInt(size)
            out.write(name.padEnd(8, '\u0000').toByteArray(Charsets.US_ASCII), 0, 8)
        }
        val result = out.toByteArray()
        result[8] = dirOffset.toByte()
        result[9] = (dirOffset shr 8).toByte()
        result[10] = (dirOffset shr 16).toByte()
        result[11] = (dirOffset shr 24).toByte()
        target.writeBytes(result)

        val before = source.length() / 1024
        val after = target.length() / 1024
        logger.lifecycle("reduceWad: ${kept.size} of $count lumps, $before KB -> $after KB")
    }
}
