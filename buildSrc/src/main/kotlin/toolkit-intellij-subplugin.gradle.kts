// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import org.eclipse.jgit.api.Git
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension.Output
import org.gradle.util.internal.JarUtil
import org.jetbrains.intellij.tasks.DownloadRobotServerPluginTask
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import org.jetbrains.intellij.tasks.RunIdeForUiTestTask
import proguard.ClassPath
import proguard.Configuration
import proguard.ConfigurationParser
import proguard.ProGuard
import software.aws.toolkits.gradle.ciOnly
import software.aws.toolkits.gradle.findFolders
import software.aws.toolkits.gradle.intellij.IdeFlavor
import software.aws.toolkits.gradle.intellij.IdeVersions
import software.aws.toolkits.gradle.intellij.ToolkitIntelliJExtension
import software.aws.toolkits.gradle.isCi
import java.io.FileOutputStream
import java.io.IOException
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

val toolkitIntelliJ = project.extensions.create<ToolkitIntelliJExtension>("intellijToolkit")

val ideProfile = IdeVersions.ideProfile(project)
val toolkitVersion: String by project
val remoteRobotPort: String by project

// please check changelog generation logic if this format is changed
version = "$toolkitVersion-${ideProfile.shortName}"

plugins {
    id("toolkit-kotlin-conventions")
    id("toolkit-testing")
    id("org.jetbrains.intellij")
}

// Add our source sets per IDE profile version (i.e. src-211)
sourceSets {
    main {
        java.srcDirs(findFolders(project, "src", ideProfile))
        resources.srcDirs(findFolders(project, "resources", ideProfile))
    }
    test {
        java.srcDirs(findFolders(project, "tst", ideProfile))
        resources.srcDirs(findFolders(project, "tst-resources", ideProfile))
    }

    plugins.withType<ToolkitIntegrationTestingPlugin> {
        maybeCreate("integrationTest").apply {
            java.srcDirs(findFolders(project, "it", ideProfile))
            resources.srcDirs(findFolders(project, "it-resources", ideProfile))
        }
    }
}

configurations {
    runtimeClasspath {
        // Exclude dependencies that ship with iDE
        exclude(group = "org.slf4j")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")

        // Exclude dependencies we don't use to make plugin smaller
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    // TODO: https://github.com/gradle/gradle/issues/15383
    val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        testImplementation(platform(versionCatalog.findDependency("junit5-bom").get()))
        testImplementation(versionCatalog.findDependency("junit5-jupiterApi").get())

        testRuntimeOnly(versionCatalog.findDependency("junit5-jupiterEngine").get())
        testRuntimeOnly(versionCatalog.findDependency("junit5-jupiterVintage").get())
    }
}

tasks.processResources {
    // needed because both rider and ultimate include plugin-datagrip.xml which we are fine with
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Run after the project has been evaluated so that the extension (intellijToolkit) has been configured
intellij {
    pluginName.set("aws-toolkit-jetbrains")

    localPath.set(toolkitIntelliJ.localPath())
    version.set(toolkitIntelliJ.version())

    plugins.set(toolkitIntelliJ.productProfile().map { it.plugins.toMutableList() })

    downloadSources.set(toolkitIntelliJ.ideFlavor.map { it == IdeFlavor.IC && !project.isCi() })
    instrumentCode.set(toolkitIntelliJ.ideFlavor.map { it != IdeFlavor.RD })
}

tasks.jar {
    archiveBaseName.set(toolkitIntelliJ.ideFlavor.map { "aws-toolkit-jetbrains-$it" })
}

tasks.patchPluginXml {
    sinceBuild.set(toolkitIntelliJ.ideProfile().map { it.sinceVersion })
    untilBuild.set(toolkitIntelliJ.ideProfile().map { it.untilVersion })
}

// attach the current commit hash on local builds
if (!project.isCi()){
    val buildMetadata = try {
        val git = Git.open(project.rootDir)
        val currentShortHash = git.repository.findRef("HEAD").objectId.abbreviate(7).name()
        val isDirty = git.status().call().hasUncommittedChanges()

        buildString {
            append(currentShortHash)

            if (isDirty) {
                append(".modified")
            }
        }
    } catch(e: IOException) {
        logger.warn("Could not determine current commit", e)

        "unknownCommit"
    }

    tasks.patchPluginXml {
        version.set("${version.get()}+$buildMetadata")
    }

    tasks.buildPlugin {
        archiveClassifier.set(buildMetadata)
    }
}

// Disable building the settings search cache since it 1. fails the build, 2. gets run on the final packaged plugin
tasks.buildSearchableOptions {
    enabled = false
}

tasks.withType<Test>().all {
    systemProperty("log.dir", intellij.sandboxDir.map { "$it-test/logs" }.get())
    systemProperty("testDataPath", project.rootDir.resolve("testdata").absolutePath)
    val jetbrainsCoreTestResources = project(":jetbrains-core").projectDir.resolve("tst-resources")
    // FIX_WHEN_MIN_IS_221: log4j 1.2 removed in 221
    systemProperty("log4j.configuration", jetbrainsCoreTestResources.resolve("log4j.xml"))
    systemProperty("idea.log.config.properties.file", jetbrainsCoreTestResources.resolve("toolkit-test-log.properties"))

    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    systemProperty("aws.toolkits.enableTelemetry", false)
}

tasks.runIde {
    systemProperty("aws.toolkit.developerMode", true)
    systemProperty("ide.plugins.snapshot.on.unload.fail", true)
    systemProperty("memory.snapshots.path", project.rootDir)

    val alternativeIde = providers.environmentVariable("ALTERNATIVE_IDE").forUseAtConfigurationTime()
    if (alternativeIde.isPresent) {
        // remove the trailing slash if there is one or else it will not work
        val value = alternativeIde.get()
        val path = File(value.trimEnd('/'))
        if (path.exists()) {
            ideDir.set(path)
        } else {
            throw GradleException("ALTERNATIVE_IDE path not found $value")
        }
    }
}

// TODO: https://github.com/gradle/gradle/issues/15383
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
tasks.withType<DownloadRobotServerPluginTask> {
    version.set(versionCatalog.findVersion("intellijRemoteRobot").get().requiredVersion)
}

// Enable coverage for the UI test target IDE
ciOnly {
    extensions.getByType<JacocoPluginExtension>().applyTo(tasks.withType<RunIdeForUiTestTask>())
}
tasks.withType<RunIdeForUiTestTask>().all {
    systemProperty("robot-server.port", remoteRobotPort)
    systemProperty("ide.mac.file.chooser.native", "false")
    systemProperty("jb.consents.confirmation.enabled", "false")
    // This does some magic in EndUserAgreement.java to make it not show the privacy policy
    systemProperty("jb.privacy.policy.text", "<!--999.999-->")
    systemProperty("ide.show.tips.on.startup.default.value", false)

    systemProperty("aws.telemetry.skip_prompt", "true")
    systemProperty("aws.suppress_deprecation_prompt", true)
    systemProperty("idea.trust.all.projects", "true")

    // These are experiments to enable for UI tests
    systemProperty("aws.experiment.connectedLocalTerminal", true)
    systemProperty("aws.experiment.dynamoDb", true)

    debugOptions {
        enabled.set(true)
        suspend.set(false)
    }

    ciOnly {
        configure<JacocoTaskExtension> {
            includes = listOf("software.aws.toolkits.*")
            output = Output.TCP_CLIENT // Dump to our jacoco server instead of to a file
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)
val minified = Attribute.of("minified", Boolean::class.javaObjectType)

dependencies {
    attributesSchema {
        attribute(minified)
    }
    artifactTypes.getByName("jar") {
        attributes.attribute(minified, false)
    }

    registerTransform(Minify::class) {
        from.attribute(minified, false).attribute(artifactType, "jar")
        to.attribute(minified, true).attribute(artifactType, "jar")
    }
}

configurations.compileClasspath {
    afterEvaluate {
        if (isCanBeResolved) {
            attributes.attribute(minified, true)
        }
    }
}

configurations.runtimeClasspath {
    afterEvaluate {
        if (isCanBeResolved) {
            attributes.attribute(minified, true)
        }
    }
}

@CacheableTransform
abstract class Minify @Inject constructor(): TransformAction<TransformParameters.None> {
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    @get:Classpath
    @get:InputArtifactDependencies
    abstract val runtimeClasspath: FileCollection

    override
    fun transform(outputs: TransformOutputs) {
        val file = inputArtifact.get().asFile
        val fileName = file.name
        val nameWithoutExtension = fileName.substring(0, fileName.length - 4)
        if (file.absolutePath.contains("software.amazon.awssdk") && fileName !in listOf("annotations-2.17.138.jar", "third-party-jackson-core-2.17.138.jar", "aws-query-protocol-2.17.138.jar")) {
            minify(file, outputs.file("${nameWithoutExtension}-min.jar"))
            return
        }
        println("Nothing to minify - using ${fileName} unchanged")
        outputs.file(inputArtifact)
    }

    private fun minify(artifact: File, jarFile: File) {
        println("Minifying ${artifact.name}")
        println(jarFile)
        val pgc = Configuration()

        val args = arrayOf(
            "-injars ${artifact}",
            "-outjars ${jarFile}",
            "-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)",
            "-libraryjars <java.home>/jmods/java.desktop.jmod(!**.jar;!module-info.class)",
            "-libraryjars ${runtimeClasspath.asFileTree.joinToString(separator = File.pathSeparator)}",
            "-dontobfuscate",
            "-dontwarn org.slf4j.**",
            "-keepattributes *",
            "-keep class !**.paginators.**,!**.*Async*,!software.amazon.awssdk.services.ec2.** { *; }",
            "-keep interface !**.*Async*,!software.amazon.awssdk.services.ec2.** { *; }",
            "-keep class software.amazon.awssdk.services.ec2.model.DescribeInstances*",
            "-keep class software.amazon.awssdk.services.ec2.*Ec2BaseClientBuilder { *; }",
            """
                -keep interface software.amazon.awssdk.services.ec2.Ec2Client* {
                    <init>(...);
                    <fields>;
                    *** describeInstances*(...);
                }
            """.trimIndent(),
            """
                -keep class * implements software.amazon.awssdk.services.ec2.Ec2Client* {
                    <init>(...);
                    <fields>;
                    *** describeInstances*(...);
                }
            """.trimIndent()
        )
        println(args)
        ConfigurationParser(args, System.getProperties()).parse(pgc)
        val pg = ProGuard(pgc)
        pg.execute()
    }
}
