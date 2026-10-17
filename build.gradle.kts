plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.github.yufuchang"
version = "0.1.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("golandHome")
            .orElse("D:/SoftWareSpace/GoLand 2025.2.3")
        local(localIde.get())
        bundledPlugin("org.jetbrains.plugins.go")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }
}

