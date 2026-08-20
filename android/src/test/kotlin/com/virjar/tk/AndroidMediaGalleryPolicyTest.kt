package com.virjar.tk

import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.GalleryMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMediaGalleryPolicyTest {
    @Test
    fun `gallery hides ime before it becomes visible and clamps its page`() {
        val events = mutableListOf<String>()
        val items = listOf(
            GalleryItem("one.jpg", GalleryMediaType.IMAGE),
            GalleryItem("two.mp4", GalleryMediaType.VIDEO),
        )

        openAndroidMediaGallery(
            items = items,
            requestedIndex = 99,
            hideIme = { events += "hide-ime" },
            present = { presented, index ->
                events += "present:${presented.size}:$index"
            },
        )

        assertEquals(listOf("hide-ime", "present:2:1"), events)
    }

    @Test
    fun `empty gallery does not disturb focus or publish overlay state`() {
        var hideImeCalls = 0
        var presentationCalls = 0

        openAndroidMediaGallery(
            items = emptyList(),
            requestedIndex = 0,
            hideIme = { hideImeCalls++ },
            present = { _, _ -> presentationCalls++ },
        )

        assertEquals(0, hideImeCalls)
        assertEquals(0, presentationCalls)
    }

    @Test
    fun `android gallery owns a fullscreen window and uses a compose layout compatible video surface`() {
        val policy = androidMediaGalleryPolicy

        assertTrue(policy.dismissOnBackPress)
        assertFalse(policy.dismissOnClickOutside)
        assertFalse(policy.usePlatformDefaultWidth)
        assertFalse(policy.decorFitsSystemWindows)
        assertFalse(policy.animateEnterExit)
        assertEquals(AndroidGalleryVideoSurfaceType.TEXTURE_VIEW, policy.videoSurfaceType)
    }

    @Test
    fun `only the current foreground video receives initial autoplay ownership`() {
        val currentPageGate = GalleryVideoPlaybackGate()
        val adjacentPageGate = GalleryVideoPlaybackGate()

        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            currentPageGate.update(allowedNow = true, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            adjacentPageGate.update(allowedNow = false, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.PAUSE,
            adjacentPageGate.update(allowedNow = false, playerWantsPlayback = true),
        )
    }

    @Test
    fun `playing video pauses off page and resumes when it owns the page again`() {
        val gate = GalleryVideoPlaybackGate()

        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = true, playerWantsPlayback = true),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.PAUSE,
            gate.update(allowedNow = false, playerWantsPlayback = true),
        )
        // Player callback after pause must not erase the remembered resume intent.
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = false, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
    }

    @Test
    fun `manual pause is preserved across page and lifecycle ownership changes`() {
        val gate = GalleryVideoPlaybackGate()

        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = true, playerWantsPlayback = true),
        )
        // The user pauses while this is still the current foreground page.
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = false, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
    }

    @Test
    fun `foreground loss uses the same pause and conditional resume policy`() {
        val gate = GalleryVideoPlaybackGate()

        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.NONE,
            gate.update(allowedNow = true, playerWantsPlayback = true),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.PAUSE,
            gate.update(allowedNow = false, playerWantsPlayback = true),
        )
        assertEquals(
            GalleryVideoPlaybackCommand.PLAY,
            gate.update(allowedNow = true, playerWantsPlayback = false),
        )
    }

    @Test
    fun `video download states map to visible loading player and retry presentations`() {
        assertEquals(
            GalleryVideoLoadPresentation.LOADING,
            galleryVideoLoadPresentation(GalleryVideoLoadState.Loading),
        )
        assertEquals(
            GalleryVideoLoadPresentation.PLAYER,
            galleryVideoLoadPresentation(GalleryVideoLoadState.Ready("/cache/video.mp4")),
        )
        assertEquals(
            GalleryVideoLoadPresentation.RETRY,
            galleryVideoLoadPresentation(GalleryVideoLoadState.Failed),
        )
    }
}
