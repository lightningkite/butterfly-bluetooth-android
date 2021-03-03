import java.util.Properties

buildscript {
    repositories {
        google()
        jcenter()
        mavenLocal()
    }
}

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-android-extensions")
    id("maven")
    id("signing")
    id("org.jetbrains.dokka") version "1.4.20"
    `maven-publish`
}

group = "com.lightningkite.butterfly"
version = "0.1.1"

repositories {
    jcenter()
    mavenCentral()
    maven("https://jitpack.io")
    google()
    mavenLocal()
    maven("https://maven.google.com")
}

android {
    compileSdkVersion(30)
    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(30)
        versionCode = 5
        versionName = "1.0.5"
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    api("com.lightningkite.butterfly:butterfly-android:0.1.1")
    testImplementation("junit:junit:4.12")
    androidTestImplementation("androidx.test:runner:1.3.0")
    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
    api("com.polidea.rxandroidble2:rxandroidble:1.11.0")
    api("androidx.appcompat:appcompat:1.2.0")
    api("io.reactivex.rxjava2:rxkotlin:2.4.0")
    api("io.reactivex.rxjava2:rxandroid:2.1.1")
}

tasks {
    val sourceJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(android.sourceSets["main"].java.srcDirs)
        from(project.projectDir.resolve("src/include"))
    }
    val javadocJar by creating(Jar::class) {
        dependsOn("dokkaJavadoc")
        archiveClassifier.set("javadoc")
        from(project.file("build/dokka/javadoc"))
    }
    artifacts {
        archives(sourceJar)
        archives(javadocJar)
    }
}

afterEvaluate {
    publishing {
        publications {
            val release by creating(MavenPublication::class) {
                from(components["release"])
                artifact(tasks.getByName("sourceJar"))
                artifact(tasks.getByName("javadocJar"))
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
            }
            val debug by creating(MavenPublication::class) {
                from(components["debug"])
                artifact(tasks.getByName("sourceJar"))
                artifact(tasks.getByName("javadocJar"))
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
            }
        }
    }
    signing {
        val props = project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
            Properties().apply { load(stream) }
        }
        val signingKey: String? = props?.getProperty("signingKey") ?: project.properties["signingKey"]?.toString()
        val signingPassword: String? =
            props?.getProperty("signingPassword") ?: project.properties["signingPassword"]?.toString()
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(configurations.archives.get())
    }
}



tasks.named<Upload>("uploadArchives") {
    repositories.withConvention(MavenRepositoryHandlerConvention::class) {
        mavenDeployer {
            beforeDeployment {
                signing.signPom(this)
            }
        }
    }

    val props = project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
        Properties().apply { load(stream) }
    }

    repositories.withGroovyBuilder {
        "mavenDeployer"{
            "repository"("url" to "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/") {
                "authentication"(
                    "userName" to (props?.getProperty("ossrhUsername")
                        ?: project.properties["ossrhUsername"]?.toString()),
                    "password" to (props?.getProperty("ossrhPassword")
                        ?: project.properties["ossrhPassword"]?.toString())
                )
            }
            "snapshotRepository"("url" to "https://s01.oss.sonatype.org/content/repositories/snapshots/") {
                "authentication"(
                    "userName" to (props?.getProperty("ossrhUsername")
                        ?: project.properties["ossrhUsername"]?.toString()),
                    "password" to (props?.getProperty("ossrhPassword")
                        ?: project.properties["ossrhPassword"]?.toString())
                )
            }
            "pom" {
                "project" {
                    setProperty("name", "Butterfly-Android")
                    setProperty("packaging", "aar")
                    setProperty(
                        "description",
                        "An Android framework for consistent and stable app development.  Built for easy use with Khrysalis, a code conversion tool."
                    )
                    setProperty("url", "https://github.com/lightningkite/butterfly-android")

                    "scm" {
                        setProperty("connection", "scm:git:https://github.com/lightningkite/butterfly-android.git")
                        setProperty(
                            "developerConnection",
                            "scm:git:https://github.com/lightningkite/butterfly-android.git"
                        )
                        setProperty("url", "https://github.com/lightningkite/butterfly-android")
                    }

                    "licenses" {
                        "license"{
                            setProperty("name", "The MIT License (MIT)")
                            setProperty("url", "https://www.mit.edu/~amini/LICENSE.md")
                            setProperty("distribution", "repo")
                        }

                    }
                    "developers"{
                        "developer"{
                            setProperty("id", "bjsvedin")
                            setProperty("name", "Brady Svedin")
                            setProperty("email", "brady@lightningkite.com")
                        }
                    }
                }
            }
        }
    }
}
