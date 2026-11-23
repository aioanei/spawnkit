package dev.spawnkit.ai

/**
 * Makes characters from the photo the creator attached in the assistant chat (C16b, ADR-0013), for the
 * `make_character` tool. The builder implements it with the signed-in account's Character Forge, which strips the
 * photo's metadata before it leaves the device. The photo stays inside the implementation: models, tool inputs, tool
 * results and transcripts never contain it.
 */
public interface CharacterMaker {
    /** Whether the creator attached a photo, so the model can be told (it never sees the photo). */
    public val photoAttached: Boolean

    /** Why characters cannot be made now, in plain words (for example no photo, or not signed in), or `null`. */
    public fun unavailable(): String?

    /**
     * Sends the attached photo to the image model and makes characters in [styles]. Called only after the creator
     * confirmed in the chat; [consent] records that they confirmed the photo shows them or someone who agreed. Throws
     * with a plain message when the server refuses or cannot be reached.
     */
    public suspend fun make(
        styles: List<String>,
        consent: Boolean,
    ): CharacterResult
}

/** What `make_character` asked for, kept while the creator decides whether to send the photo. */
public data class CharacterRequest(
    /** Style IDs to make, in order. */
    public val styles: List<String>,
    /** Style ID of the image the Character shows. */
    public val use: String,
    /** Where the Character stands. */
    public val x: Double,
    /** Where the Character stands. */
    public val y: Double,
)
