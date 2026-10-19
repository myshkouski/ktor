/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

description = "Ktor server engine that exposes an Application as a web-standard fetch handler."

plugins {
    id("ktorbuild.project.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ktorServerCore)
        }
        commonTest.dependencies {
            implementation(projects.ktorServerTestBase)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
