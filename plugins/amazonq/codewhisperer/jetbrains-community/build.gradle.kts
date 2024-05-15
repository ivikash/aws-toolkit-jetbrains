// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import software.aws.toolkits.gradle.intellij.IdeFlavor

plugins {
    id("toolkit-intellij-subplugin")
}

intellijToolkit {
    ideFlavor.set(IdeFlavor.IC)
}

dependencies {
    compileOnly(project(":plugin-core:jetbrains-community"))

    implementation(project(":plugin-amazonq:shared:jetbrains-community"))
    // CodeWhispererTelemetryService uses a CircularFifoQueue, previously transitive from zjsonpatch
    implementation(libs.commons.collections)

    testFixturesApi(testFixtures(project(":plugin-core:jetbrains-community")))
    testFixturesApi(project(path = ":plugin-toolkit:jetbrains-core", configuration = "testArtifacts"))
}
