/*
 * Copyright 2024 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import de.undercouch.gradle.tasks.download.Download
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.downloadTask)
    `maven-publish`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

operator fun DirectoryProperty.div(name: String): Path = get().asFile.toPath() / name

fun DirectoryProperty.ensureExists(): Path = get().asFile.toPath().apply {
    if(notExists()) createDirectories()
}

val downloadCgltfHeaders: Exec = tasks.create<Exec>("downloadCgltfHeaders") {
    group = "cgltfHeaders"
    workingDir = layout.buildDirectory.ensureExists().toFile()
    commandLine("git", "clone", "--branch", libs.versions.cgltf.get(), "--single-branch", "https://github.com/jkuhlmann/cgltf")
    onlyIf { (layout.buildDirectory / "cgltf").notExists() }
}

val updateCgltfHeaders: Exec = tasks.create<Exec>("updateCgltfHeaders") {
    group = "cgltfHeaders"
    dependsOn(downloadCgltfHeaders)
    workingDir = (layout.buildDirectory.ensureExists() / "cgltf").toFile()
    commandLine("git", "pull", "--force")
    onlyIf { (layout.buildDirectory / "cgltf").exists() }
}

kotlin {
    listOf(
        mingwX64(), linuxX64(), linuxArm64(), macosX64(), macosArm64()
    ).forEach {
        it.compilations.getByName("main") {
            cinterops {
                val cgltf by creating {
                    tasks.getByName(interopProcessingTaskName) {
                        dependsOn(updateCgltfHeaders)
                    }
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
}

val dokkaJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

tasks {
    dokkaHtml {
        dokkaSourceSets.create("main") {
            reportUndocumented = false
            jdkVersion = java.toolchain.languageVersion.get().asInt()
            noAndroidSdkLink = true
            externalDocumentationLink("https://docs.karmakrafts.dev/${rootProject.name}")
        }
    }
    System.getProperty("publishDocs.root")?.let { docsDir ->
        create<Copy>("publishDocs") {
            mustRunAfter(dokkaJar)
            from(zipTree(dokkaJar.get().outputs.files.first()))
            into(docsDir)
        }
    }
}

publishing {
    System.getenv("CI_API_V4_URL")?.let { apiUrl ->
        repositories {
            maven {
                url = uri("$apiUrl/projects/${System.getenv("CI_PROJECT_ID")}/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create("header", HttpHeaderAuthentication::class)
                }
            }
        }
    }
    publications.configureEach {
        if (this is MavenPublication) {
            artifact(dokkaJar)
            pom {
                name = project.name
                description = "Multiplatform bindings for cgltf on Linux, Windows and macOS."
                url = System.getenv("CI_PROJECT_URL")
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "kitsunealex"
                        name = "KitsuneAlex"
                        url = "https://git.karmakrafts.dev/KitsuneAlex"
                    }
                }
                scm {
                    url = this@pom.url
                }
            }
        }
    }
}