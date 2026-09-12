#!/bin/bash
cat << 'INNER_EOF' > temp.diff
--- feature/player/src/main/java/com/graviton/feature/player/PlayerActivity.kt
+++ feature/player/src/main/java/com/graviton/feature/player/PlayerActivity.kt
@@ -262,7 +262,7 @@

     private suspend fun playVideo(uri: Uri, requestGeneration: Long) = withContext(Dispatchers.Default) {
         val mediaContentUri = getMediaContentUri(uri)
-        val playlist = playerApi.getPlaylist().takeIf { it.isNotEmpty() }
+        val playlist = (if (::playerApi.isInitialized) playerApi.getPlaylist() else emptyList()).takeIf { it.isNotEmpty() }
             ?: mediaContentUri?.let { mediaUri ->
                 viewModel.getPlaylistFromUri(mediaUri)
                     .map { it.uriString }
@@ -290,15 +290,15 @@
                 }
                 setMediaMetadata(
                     MediaMetadata.Builder().apply {
-                        setTitle(extracted.title ?: playerApi.title)
+                        setTitle(extracted.title ?: if (::playerApi.isInitialized) playerApi.title else null)
                         extracted.uploader?.let(::setArtist)
                         extracted.thumbnailUrl?.let { setArtworkUri(it.toUri()) }
-                        if (index == mediaItemIndexToPlay) {
+                        if (index == mediaItemIndexToPlay && ::playerApi.isInitialized) {
                             setExtras(positionMs = playerApi.position?.toLong())
                         }
                     }.build(),
                 )
-                if (index == mediaItemIndexToPlay) {
+                if (index == mediaItemIndexToPlay && ::playerApi.isInitialized) {
                     val apiSubs = playerApi.getSubs().map { subtitle ->
                         uriToSubtitleConfiguration(
                             uri = subtitle.uri,
@@ -314,7 +314,8 @@
         withContext(Dispatchers.Main) {
             if (requestGeneration != playbackGeneration || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext
             mediaController?.run {
-                setMediaItems(mediaItems, mediaItemIndexToPlay, playerApi.position?.toLong() ?: C.TIME_UNSET)
+                val positionMs = if (::playerApi.isInitialized) playerApi.position?.toLong() else C.TIME_UNSET
+                setMediaItems(mediaItems, mediaItemIndexToPlay, positionMs ?: C.TIME_UNSET)
                 playWhenReady = viewModel.playWhenReady
                 prepare()
             }
@@ -397,8 +398,12 @@
     }

     private fun finishAndStopPlayerSession() {
-        finish()
+        if (::playerApi.isInitialized) {
+            finish()
+        } else {
+            super.finish()
+        }
         mediaController?.stopPlayerSession()
     }

INNER_EOF
patch -p0 < temp.diff
