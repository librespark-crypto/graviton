#!/bin/bash
cat << 'INNER_EOF' > temp.diff
--- build.gradle.kts
+++ build.gradle.kts
@@ -10,6 +10,7 @@

 subprojects {
     apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)
+    apply(plugin = rootProject.libs.plugins.aboutLibraries.get().pluginId)

     configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
         android.set(true)
@@ -17,6 +18,13 @@
         ignoreFailures.set(false)
     }
+
+    configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
+        offlineMode.set(true)
+        export {
+            excludeFields.addAll("generated")
+        }
+    }
 }

 allprojects {
INNER_EOF
patch -p0 < temp.diff
