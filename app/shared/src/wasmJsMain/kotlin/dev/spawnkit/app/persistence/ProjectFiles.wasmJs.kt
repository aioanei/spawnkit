@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.spawnkit.app.persistence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.spawnkit.editing.DraftStore

@Composable
actual fun rememberProjectFiles(): ProjectFiles =
    remember {
        object : ProjectFiles {
            override val picker: ProjectFilePicker? = null
            override val drafts: DraftStore =
                object : DraftStore {
                    override fun read(key: String): String? = localRead(key)

                    override fun write(
                        key: String,
                        text: String,
                    ) {
                        localWrite(key, text)
                    }
                }
        }
    }

private fun localRead(key: String): String? = js("globalThis.localStorage.getItem('spawnkit.recovery.' + key)")

private fun localWrite(
    key: String,
    text: String,
): Unit = js("{ globalThis.localStorage.setItem('spawnkit.recovery.' + key, text); }")
