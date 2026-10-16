pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral()
    ivy("https://storage.googleapis.com/r8-releases") {
        patternLayout { artifact("/raw/[revision]/[module].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("r8") }
    }
} }
rootProject.name = "DocumentStudio"
include(":app", ":core", ":officeruntime")
