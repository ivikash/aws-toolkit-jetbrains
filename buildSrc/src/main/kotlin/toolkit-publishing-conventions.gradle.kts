// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import software.aws.toolkits.gradle.intellij.IdeVersions

plugins {
    id("org.jetbrains.intellij.platform")
}

intellijPlatform {
    publishing {
        val publishToken: String by project
        val publishChannel: String by project

        token.set(publishToken)
        channels.set(publishChannel.split(",").map { it.trim() })
    }
}

configurations {
    all {
        // IDE provides netty
        exclude("io.netty")
    }

    // Make sure we exclude stuff we either A) ships with IDE, B) we don't use to cut down on size
    runtimeClasspath {
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}

tasks.check {
    dependsOn(tasks.verifyPlugin)
}
