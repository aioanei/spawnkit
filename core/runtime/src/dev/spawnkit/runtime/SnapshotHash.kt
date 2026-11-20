package dev.spawnkit.runtime

internal fun hashCapturedWorld(state: CapturedWorld): Long {
    val hash = SnapshotHash()
    hash.text("spawnkit.runtime.checkpoint.v1")
    hash.number(state.tick)
    hash.number(state.slots.size.toLong())
    state.slots.forEach {
        hash.number(it.generation)
        hash.number(if (it.alive) 1L else 0L)
    }
    hash.number(state.freeSlots.size.toLong())
    state.freeSlots.forEach { hash.number(it.toLong()) }
    hash.number(state.registeredComponents.size.toLong())
    state.registeredComponents.forEach(hash::text)
    hash.number(state.components.size.toLong())
    state.components.forEach { store ->
        hash.text(store.component.id)
        hash.number(store.entries.size.toLong())
        store.entries.forEach { (index, value) ->
            hash.number(index.toLong())
            hash.state(value)
        }
    }
    hash.number(state.systems.size.toLong())
    state.systems.forEach { (id, value) ->
        hash.text(id)
        hash.state(value)
    }
    // Sections added by R2b are hashed only when present, so existing checkpoints keep their fingerprints.
    if (state.hierarchy.isNotEmpty()) {
        hash.text("hierarchy")
        hash.number(state.hierarchy.size.toLong())
        state.hierarchy.forEach { (parent, children) ->
            hash.number(parent.toLong())
            hash.number(children.size.toLong())
            children.forEach { hash.number(it.toLong()) }
        }
    }
    if (state.resources.isNotEmpty()) {
        hash.text("resources")
        hash.number(state.resources.size.toLong())
        state.resources.forEach { (id, value) ->
            hash.text(id)
            hash.state(value)
        }
    }
    state.scenes?.let { scenes ->
        hash.text("scenes")
        hash.number(scenes.counter)
        hash.number(scenes.identities.size.toLong())
        scenes.identities.forEach { (index, identity) ->
            hash.number(index.toLong())
            hash.text(identity.templateId)
            hash.number(identity.instance)
            hash.text(identity.objectId)
        }
    }
    state.input?.let { input ->
        hash.text("input")
        hashFrame(hash, input.current)
        hashFrame(hash, input.previous)
        hash.number(if (input.pending == null) 0L else 1L)
        input.pending?.let { hashFrame(hash, it) }
    }
    return hash.result
}

private fun hashFrame(
    hash: SnapshotHash,
    frame: CapturedFrame,
) {
    hash.number(frame.down.size.toLong())
    frame.down.forEach(hash::text)
    hash.number(frame.axes.size.toLong())
    frame.axes.forEach { (id, value) ->
        hash.text(id)
        hash.number(value.toRawBits())
    }
    hash.number(frame.pointers.size.toLong())
    frame.pointers.forEach {
        hash.number(it.id.toLong())
        hash.number(it.x.toRawBits())
        hash.number(it.y.toRawBits())
        hash.number(if (it.pressed) 1L else 0L)
    }
}

private class SnapshotHash {
    var result: Long = -3750763034362895579L
        private set

    fun number(value: Long) {
        repeat(8) { byte -> result = (result xor ((value ushr (byte * 8)) and 0xFF)) * 0x100000001b3L }
    }

    fun text(value: String) {
        number(value.length.toLong())
        value.forEach { number(it.code.toLong()) }
    }

    fun state(value: StateValue) {
        when (value) {
            StateValue.Null -> {
                number(0)
            }

            is StateValue.Integer -> {
                number(1)
                number(value.value)
            }

            is StateValue.Decimal -> {
                number(2)
                number(value.value.toRawBits())
            }

            is StateValue.Flag -> {
                number(3)
                number(if (value.value) 1L else 0L)
            }

            is StateValue.Text -> {
                number(4)
                text(value.value)
            }

            is StateValue.Sequence -> {
                number(5)
                number(value.values.size.toLong())
                value.values.forEach(::state)
            }

            is StateValue.Record -> {
                number(6)
                number(value.fields.size.toLong())
                value.fields.toSortedEntries().forEach {
                    text(it.key)
                    state(it.value)
                }
            }

            is StateValue.EntityReference -> {
                number(7)
                number(value.index.toLong())
                number(value.generation)
            }
        }
    }
}
