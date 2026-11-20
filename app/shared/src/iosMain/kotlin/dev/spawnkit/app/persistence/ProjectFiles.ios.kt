package dev.spawnkit.app.persistence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.spawnkit.editing.DraftStore
import platform.Foundation.NSUserDefaults

@Composable
actual fun rememberProjectFiles(): ProjectFiles =
    remember {
        object : ProjectFiles {
            override val picker: ProjectFilePicker? = null
            override val drafts: DraftStore =
                object : DraftStore {
                    private val defaults = NSUserDefaults.standardUserDefaults

                    override fun read(key: String): String? = defaults.stringForKey("spawnkit.recovery.$key")

                    override fun write(
                        key: String,
                        text: String,
                    ) {
                        defaults.setObject(text, forKey = "spawnkit.recovery.$key")
                    }
                }
        }
    }
