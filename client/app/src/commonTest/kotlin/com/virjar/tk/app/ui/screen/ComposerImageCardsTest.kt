package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerImageCardsTest {
    private val first = image("11111111-1111-4111-8111-111111111111", "first.png")
    private val second = image("22222222-2222-4222-8222-222222222222", "second.png")

    @Test
    fun `cards follow markdown placement order and disappear with references`() {
        val firstImage = "![first](${EmbeddedAsset.uri(first.assetId)})"
        val secondImage = "![second](${EmbeddedAsset.uri(second.assetId)})"
        val markdown = "$secondImage $firstImage `$firstImage` [image as file](${EmbeddedAsset.uri(first.assetId)})"

        assertEquals(
            listOf(ComposerImagePlacement(1, second), ComposerImagePlacement(2, first)),
            composerImagePlacements(markdown, listOf(first, second)),
        )
        assertEquals(
            listOf(ComposerImagePlacement(2, first)),
            composerImagePlacements(markdown, listOf(first)),
        )
        assertEquals(emptyList(), composerImagePlacements("body without images", listOf(first, second)))
    }

    private fun image(id: String, name: String) = EmbeddedAsset(
        assetId = id,
        attachment = Attachment("owner/$name", name, "image/png", 12),
        width = 320,
        height = 200,
    )
}
