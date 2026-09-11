package com.graviton.feature.player

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlayerActivityTest {

    @Test
    fun finishBeforePlayerApiInitializedDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, PlayerActivity::class.java)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, intent)
        val activity = controller.get()

        // Explicitly calling finish() when playerApi is not initialized must not throw UninitializedPropertyAccessException.
        activity.finish()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun currentUriWithExplicitPlaylistStartsNewPlaybackQueue() {
        assertFalse(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = true,
            ),
        )
    }

    @Test
    fun currentUriWithoutExplicitPlaylistResumesExistingPlayback() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = false,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = false,
            ),
        )
    }

    @Test
    fun returningFromBackgroundAlwaysResumesExistingPlayback() {
        assertTrue(
            shouldResumeExistingPlayback(
                returningFromBackground = true,
                isRequestedUriCurrent = true,
                hasExplicitPlaylist = true,
            ),
        )
    }
}
