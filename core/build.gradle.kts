import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin
import java.io.FileOutputStream
import java.net.URL
import java.time.Duration

plugins {
    kotlin("android")
    id("com.android.library")
    kotlin("plugin.serialization") version "1.7.0"
    id("golang-android")
    id("maven-publish")
}

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")
    maven("https://maven.kr328.app/releases")
}

val geoipDatabaseUrl =
    "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/Country.mmdb"
val geoipInvalidate = Duration.ofDays(7)!!
val geoipOutput = buildDir.resolve("intermediates/golang_blob")
val golangSource = file("src/main/golang/native")

golang {
    sourceSets {
        create("meta-alpha") {
            tags.set(listOf("foss","with_gvisor"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    ndkVersion = "23.0.7599858"

    compileSdk = 31

    defaultConfig {
        minSdk = 21

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
            }
        }
    }

    packagingOptions {
        resources {
            excludes.add("DebugProbesKt.bin")
        }
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    productFlavors {
        flavorDimensions("feature")
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }

        create("meta") {

            dimension = flavorDimensionList[0]

            buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures.dataBinding = true
}

val coroutine = "1.6.3"
val coreKtx = "1.8.0"
val serialization = "1.3.3"

dependencies {
    implementation("com.github.codemenworld:react-native-clash-common:2c2b543ac5")

    implementation("androidx.core:core-ktx:$coreKtx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutine")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        it.inputs.dir(golangSource)
    }
}

task("downloadGeoipDatabase") {
    val databaseFile = geoipOutput.resolve("Country.mmdb")
    val moduleFile = geoipOutput.resolve("go.mod")
    val sourceFile = geoipOutput.resolve("blob.go")

    val moduleContent = """
        module "cfa/blob"
    """.trimIndent()

    val sourceContent = """
        package blob

        import _ "embed"

        //go:embed Country.mmdb
        var GeoipDatabase []byte
    """.trimIndent()

    outputs.dir(geoipOutput)

    onlyIf {
        System.currentTimeMillis() - databaseFile.lastModified() > geoipInvalidate.toMillis()
    }

    doLast {
        geoipOutput.mkdirs()

        moduleFile.writeText(moduleContent)
        sourceFile.writeText(sourceContent)

        URL(geoipDatabaseUrl).openConnection().getInputStream().use { input ->
            FileOutputStream(databaseFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}

afterEvaluate {
    val downloadTask = tasks["downloadGeoipDatabase"]

    tasks.forEach {
        if (it.name.startsWith("externalGolangBuild")) {
            it.dependsOn(downloadTask)
        }
    }
}

publishing {
  publications {
    register<MavenPublication>("metaRelease") {
      groupId = "com.github.codemenworld"
      artifactId = "react-native-clash-core"
      version = "1.0.0"

      afterEvaluate {
        from(components["metaRelease"])
      }
    }
  }
}

