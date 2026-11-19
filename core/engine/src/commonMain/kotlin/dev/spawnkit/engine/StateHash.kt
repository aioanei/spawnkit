package dev.spawnkit.engine

private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME: Long = 0x100000001b3L
private const val BYTE_MASK: Long = 0xFF
private const val BITS_PER_BYTE = 8
private const val BYTES_PER_LONG = 8

/**
 * FNV-1a 64-bit hash of all dynamic simulation state: tick, entity positions, velocities and flags, variables,
 * completion and the player controller state. Doubles are hashed by their raw IEEE-754 bits, so equal hashes on
 * different targets prove bit-identical simulation (ADR-0004).
 */
public fun RuntimeWorld.stateHash(): Long {
    var hash = FNV_OFFSET_BASIS

    fun mix(value: Long) {
        for (byte in 0 until BYTES_PER_LONG) {
            hash = (hash xor ((value ushr (byte * BITS_PER_BYTE)) and BYTE_MASK)) * FNV_PRIME
        }
    }

    fun mix(value: Double) = mix(value.toRawBits())

    fun mix(value: Boolean) = mix(if (value) 1L else 0L)

    mix(tick)
    mix(levelCompleted)
    val s = entities
    for (i in 0 until s.count) {
        mix(s.alive[i])
        mix(s.x[i])
        mix(s.y[i])
        mix(s.vx[i])
        mix(s.vy[i])
        mix(s.grounded[i])
        mix(s.patrolDirection[i])
        mix(s.checkpointActivated[i])
    }
    variables.forEach { mix(it) }
    val p = player
    mix(p.respawnX)
    mix(p.respawnY)
    mix(p.ticksSinceGrounded.toLong())
    mix(p.jumpBuffer.toLong())
    mix(p.jumpWasDown)
    mix(p.jumpedSinceGround)
    mix(p.jumpCut)
    mix(p.previousBottom)
    mix(p.graceTicks.toLong())
    return hash
}
