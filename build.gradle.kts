/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  android-tree-sitter library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.BaseExtension
import com.itsaky.androidide.treesitter.BuildTreeSitterTask
import com.itsaky.androidide.treesitter.CleanTreeSitterBuildTask
import com.itsaky.androidide.treesitter.projectVersionCode
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
  id("com.android.application") version "8.0.0" apply false
  id("com.android.library") version "8.0.2" apply false
  id("com.vanniktech.maven.publish.base") version "0.25.2" apply false
}

fun Project.configureBaseExtension() {
  extensions.findByType(BaseExtension::class)?.run {
    compileSdkVersion(33)

    defaultConfig {
      minSdk = 21
      targetSdk = 33
      versionCode = project.projectVersionCode
      versionName = rootProject.version.toString()
    }

    compileOptions {
      sourceCompatibility = JavaVersion.VERSION_11
      targetCompatibility = JavaVersion.VERSION_11
    }
  }
}

subprojects {
  plugins.withId("com.android.application") { configureBaseExtension() }
  plugins.withId("com.android.library") { configureBaseExtension() }

  plugins.withId("com.vanniktech.maven.publish.base") {
    configure<MavenPublishBaseExtension> {
      group = "com.itsaky.androidide"
      var versionName = rootProject.version.toString()
      if (System.getenv("PublishToMaven").isNullOrBlank()) {
        versionName = "$versionName-SNAPSHOT"
      }
      versionName = versionName.substring(1) // remove 'v' prefix

      pom {
        name.set(project.name)

        description.set(
            if (project.description.isNullOrBlank())
                "${project.name} grammar for android-tree-sitter."
            else project.description)

        inceptionYear.set("2022")
        url.set("https://github.com/itsaky/android-tree-sitter/")

        licenses {
          license {
            name.set("LGPL-v2.1")
            url.set("https://github.com/itsaky/android-tree-sitter/blob/main/LICENSE")
            distribution.set("repo")
          }
        }

        scm {
          url.set("https://github.com/itsaky/android-tree-sitter/")
          connection.set("scm:git:git://github.com/itsaky/android-tree-sitter.git")
          developerConnection.set("scm:git:ssh://git@github.com/itsaky/android-tree-sitter.git")
        }

        developers {
          developer {
            id.set("androidide")
            name.set("AndroidIDE")
            url.set("https://androidide.com")
          }
        }
      }

      coordinates(project.group.toString(), project.name, versionName)
      publishToMavenCentral(host = SonatypeHost.S01)
      signAllPublications()
      configure(AndroidSingleVariantLibrary(publishJavadocJar = false))
    }
  }
}

tasks.register<BuildTreeSitterTask>("buildTreeSitter")

tasks.register<CleanTreeSitterBuildTask>("cleanTreeSitterBuild")

tasks.register<Delete>("clean").configure {
  dependsOn("cleanTreeSitterBuild")
  delete(rootProject.buildDir)
  delete(rootProject.file("build/host"))
  delete(rootProject.file("tree-sitter-lib/cli/build"))
}
