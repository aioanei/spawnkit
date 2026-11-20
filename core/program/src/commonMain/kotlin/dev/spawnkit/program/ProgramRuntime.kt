package dev.spawnkit.program

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.CheckpointSystem
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.WorldCommands

/** Why a program instance stopped. Every kind is a deterministic outcome of the program and its inputs. */
public enum class FaultKind {
    /** The segment used more than [ProgramLimits.fuelPerSegment]. */
    FuelExhausted,

    /** Function calls nested deeper than [ProgramLimits.maxCallDepth]. */
    CallDepthExceeded,

    /** The segment created more list elements than allowed, or a list grew too large. */
    AllocationExceeded,

    /** Too many suspended waits, events or effect requests. */
    MessageLimitExceeded,

    /** Division by zero or an invalid wait duration. */
    InvalidArithmetic,

    /** A list position outside the list. */
    IndexOutOfRange,

    /** The object no longer exists or lacks the property's component. */
    MissingProperty,

    /** A host function failed or returned a value of the wrong type. */
    HostFailure,
}

/** A stopped instance's explanation, located at the program node that failed. */
public data class ProgramFault(
    /** Category. */
    public val kind: FaultKind,
    /** Failing node. */
    public val source: SourceRef,
    /** Plain-language explanation. */
    public val message: String,
    /** Tick on which the failing segment ran. */
    public val tick: Long,
)

/**
 * An external effect committed by a segment. [id] is derived from the entity slot/generation, the instance's
 * segment counter and the request's position, so replay from a checkpoint reproduces the same IDs and an external
 * sink can deduplicate deliveries.
 */
public data class EffectRequest(
    /** Deterministic request ID. */
    public val id: String,
    /** Registered effect name. */
    public val effect: String,
    /** Payload. */
    public val payload: ProgramValue,
)

/**
 * Per-entity execution state of a [PreparedProgram]: committed state fields, suspended threads, the event inbox
 * and committed effect requests. Attach through [ProgramFeature.behavior], one fresh instance per entity
 * (create each with [ProgramFeature.instantiate]); sharing an instance would share its mutable state. Fully
 * checkpointed by its codec.
 */
public class ProgramInstance internal constructor(
    /** Program being executed. */
    public val program: PreparedProgram,
) {
    internal var state: List<ProgramValue> = program.stateInitial
    internal val threads = mutableListOf<SuspendedThread>()
    internal var nextThreadId = 0L
    internal var segments = 0L
    internal val inbox = mutableListOf<Pair<String, ProgramValue>>()
    internal val outbox = mutableListOf<EffectRequest>()

    /** The fault that stopped this instance, or `null` while healthy. A stopped instance never runs again. */
    public var fault: ProgramFault? = null
        internal set

    /** Total deterministic fuel charged to this instance, including faulted segments. */
    public var fuelUsed: Long = 0L
        internal set

    /** Number of suspended waits. */
    public val suspendedCount: Int get() = threads.size

    /** Committed value of state field [name]. */
    public fun state(name: String): ProgramValue {
        val index = program.stateNames.indexOf(name)
        require(index >= 0) { "Unknown state field '$name'" }
        return state[index]
    }

    /** Committed effect requests not yet acknowledged, in commit order. */
    public val pendingEffects: List<EffectRequest> get() = outbox.toList()

    /** Removes delivered requests up to and including [id]; the host calls this after delivering them. */
    public fun acknowledgeEffects(id: String) {
        val index = outbox.indexOfFirst { it.id == id }
        require(index >= 0) { "Unknown pending effect '$id'" }
        repeat(index + 1) { outbox.removeAt(0) }
    }
}

internal class FrameState(
    val unit: Int,
    val pc: Int,
    val locals: List<ProgramValue>,
)

internal class SuspendedThread(
    val id: Long,
    val resumeTick: Long,
    val frames: List<FrameState>,
    val stack: List<ProgramValue>,
)

/**
 * Installs portable program execution into a runtime: the `program:behavior` component and the `program:execute`
 * system. Each tick, for every program entity in slot order, it resumes due waits (oldest first), runs every-tick
 * rules, then rules for events delivered this tick. Events emitted during a tick are delivered on the next tick.
 *
 * Each run is an atomic segment: state, property writes, events and effect requests are buffered and applied only
 * when the segment returns or waits. A fault discards that segment's buffered work, records a [ProgramFault] and
 * stops the instance; segments committed earlier remain. Host functions are trusted Kotlin: a
 * [ProgramHostException] faults the segment, while any other exception propagates and faults the world as usual.
 */
public class ProgramFeature(
    builder: RuntimeBuilder,
    /** Capabilities available to programs. */
    public val host: ProgramHost,
) {
    private val library = mutableMapOf<String, PreparedProgram>()

    /** Component holding a [ProgramInstance]. */
    public val behavior: ComponentType<ProgramInstance> =
        builder.component(
            "program:behavior",
            AuthoringInfo("Behavior", "Runs rules and functions on this object", "Behaviors"),
            InstanceCodec(),
        )

    init {
        builder.system("program:execute") { ProgramSystem(this) }
    }

    /** Makes [prepared] restorable from checkpoints. Re-registering the same ID needs an identical fingerprint. */
    public fun register(prepared: PreparedProgram) {
        require(prepared.host === host) { "Program '${prepared.program.id}' was prepared for another host" }
        val existing = library[prepared.program.id]
        require(existing == null || existing.fingerprint == prepared.fingerprint) {
            "A different program is already registered as '${prepared.program.id}'"
        }
        library[prepared.program.id] = prepared
    }

    /** Creates a fresh instance (initial state, no threads) and registers its program. */
    public fun instantiate(prepared: PreparedProgram): ProgramInstance {
        register(prepared)
        return ProgramInstance(prepared)
    }

    internal fun lookup(
        id: String,
        fingerprint: Long,
    ): PreparedProgram {
        val prepared = requireNotNull(library[id]) { "Program '$id' is not registered" }
        require(prepared.fingerprint == fingerprint) { "Program '$id' changed since the checkpoint was taken" }
        return prepared
    }

    private inner class InstanceCodec : StateCodec<ProgramInstance> {
        override fun capture(
            value: ProgramInstance,
            context: StateCaptureContext,
        ): StateValue =
            StateValue.Record(
                mapOf(
                    "program" to StateValue.Text(value.program.program.id),
                    "fingerprint" to StateValue.Integer(value.program.fingerprint),
                    "state" to StateValue.Sequence(value.state.map { encode(it, context) }),
                    "threads" to StateValue.Sequence(value.threads.map { encodeThread(it, context) }),
                    "nextThread" to StateValue.Integer(value.nextThreadId),
                    "segments" to StateValue.Integer(value.segments),
                    "fuel" to StateValue.Integer(value.fuelUsed),
                    "inbox" to
                        StateValue.Sequence(
                            value.inbox.map { (name, payload) ->
                                StateValue.Sequence(listOf(StateValue.Text(name), encode(payload, context)))
                            },
                        ),
                    "outbox" to
                        StateValue.Sequence(
                            value.outbox.map {
                                StateValue.Sequence(listOf(StateValue.Text(it.id), StateValue.Text(it.effect), encode(it.payload, context)))
                            },
                        ),
                    "fault" to (value.fault?.let(::encodeFault) ?: StateValue.Null),
                ),
            )

        override fun restore(
            state: StateValue,
            context: StateRestoreContext,
        ): ProgramInstance {
            val fields = (state as StateValue.Record).fields
            val prepared = lookup(text(fields.getValue("program")), integer(fields.getValue("fingerprint")))
            return ProgramInstance(prepared).apply {
                this.state = sequence(fields.getValue("state")).map { decode(it, context) }
                sequence(fields.getValue("threads")).mapTo(threads) { decodeThread(it, context) }
                nextThreadId = integer(fields.getValue("nextThread"))
                segments = integer(fields.getValue("segments"))
                fuelUsed = integer(fields.getValue("fuel"))
                sequence(fields.getValue("inbox")).mapTo(inbox) {
                    val (name, payload) = sequence(it)
                    text(name) to decode(payload, context)
                }
                sequence(fields.getValue("outbox")).mapTo(outbox) {
                    val (id, effect, payload) = sequence(it)
                    EffectRequest(text(id), text(effect), decode(payload, context))
                }
                fault = fields.getValue("fault").takeIf { it != StateValue.Null }?.let(::decodeFault)
            }
        }
    }
}

/** Stateless between ticks: all program state lives in checkpointed [ProgramInstance] components. */
private class ProgramSystem(
    private val feature: ProgramFeature,
) : CheckpointSystem {
    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        val entities = world.entities(feature.behavior)
        val instances = entities.map { checkNotNull(world.get(it, feature.behavior)) }
        // Deliver all inboxes before running anything, so same-tick emissions always wait for the next tick.
        val deliveries =
            instances.map { instance -> instance.inbox.toList().also { instance.inbox.clear() } }
        for ((i, entity) in entities.withIndex()) {
            val instance = instances[i]
            if (instance.fault != null) continue
            val due = instance.threads.filter { it.resumeTick <= world.tick }.sortedBy { it.id }
            instance.threads.removeAll(due)
            for (thread in due) {
                if (instance.fault != null) break
                Segment(feature, world, entity, instance).resume(thread)
            }
            for ((index, handler) in instance.program.handlers.withIndex()) {
                if (instance.fault != null) break
                if (handler.trigger == Trigger.EveryTick) Segment(feature, world, entity, instance).start(index, null)
            }
            for ((event, payload) in deliveries[i]) {
                for ((index, handler) in instance.program.handlers.withIndex()) {
                    if (instance.fault != null) break
                    val trigger = handler.trigger
                    if (trigger is Trigger.OnEvent && trigger.event == event) {
                        Segment(feature, world, entity, instance).start(index, payload)
                    }
                }
            }
        }
    }

    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) {
        require(state == StateValue.Null) { "Program system has no private state" }
    }
}

private class FaultSignal(
    val kind: FaultKind,
    override val message: String,
) : RuntimeException(message)

private class Frame(
    val unit: CodeUnit,
    val unitIndex: Int,
    var pc: Int,
    val locals: Array<ProgramValue>,
)

/** One atomic execution segment: runs until the handler ends, waits or faults, then commits or discards. */
private class Segment(
    private val feature: ProgramFeature,
    private val world: World,
    private val selfEntity: EntityHandle,
    private val instance: ProgramInstance,
) : HostCallContext {
    private val program = instance.program
    private val limits = feature.host.limits
    private val workingState = instance.state.toMutableList()
    private val writes = LinkedHashMap<Pair<EntityHandle, Int>, ProgramValue>()
    private val events = mutableListOf<Pair<String, ProgramValue>>()
    private val effects = mutableListOf<Pair<String, ProgramValue>>()
    private var fuel = 0L
    private var allocation = 0L
    private var hostArguments: List<ProgramValue> = emptyList()

    override val arguments: List<ProgramValue> get() = hostArguments
    override val self: EntityHandle get() = selfEntity
    override val tick: Long get() = world.tick

    override fun read(
        entity: EntityHandle,
        property: String,
    ): ProgramValue? {
        val index = feature.host.propertyIndex[property] ?: return null
        return readProperty(entity, index)
    }

    fun start(
        handler: Int,
        payload: ProgramValue?,
    ) {
        val unitIndex = program.handlerUnit(handler)
        val unit = program.units[unitIndex]
        val locals = Array<ProgramValue>(unit.localCount) { NothingValue }
        if (unit.parameterCount == 1) locals[0] = checkNotNull(payload)
        val id = instance.nextThreadId++
        execute(id, mutableListOf(Frame(unit, unitIndex, 0, locals)), mutableListOf())
    }

    fun resume(thread: SuspendedThread) {
        val frames =
            thread.frames.mapTo(mutableListOf()) {
                Frame(program.units[it.unit], it.unit, it.pc, it.locals.toTypedArray())
            }
        execute(thread.id, frames, thread.stack.toMutableList())
    }

    private fun execute(
        threadId: Long,
        frames: MutableList<Frame>,
        stack: MutableList<ProgramValue>,
    ) {
        try {
            val resumeTick = run(frames, stack)
            if (resumeTick != null) {
                if (instance.threads.size >= limits.maxSuspendedThreads) {
                    throw FaultSignal(FaultKind.MessageLimitExceeded, "Too many waits are pending at once")
                }
                instance.threads +=
                    SuspendedThread(
                        threadId,
                        resumeTick,
                        frames.map { FrameState(it.unitIndex, it.pc, it.locals.toList()) },
                        stack.toList(),
                    )
            }
            commit()
        } catch (signal: FaultSignal) {
            val frame = frames.lastOrNull()
            val source =
                frame?.let { it.unit.sources[(it.pc - 1).coerceIn(0, it.unit.sources.lastIndex)] }
                    ?: program.program.handlers
                        .firstOrNull()
                        ?.source
                    ?: SourceRef(program.program.id)
            instance.fault = ProgramFault(signal.kind, source, signal.message, world.tick)
            instance.threads.clear()
            instance.segments++
            instance.fuelUsed += fuel
        }
    }

    private fun commit() {
        instance.state = workingState.toList()
        for ((key, value) in writes) feature.host.properties[key.second].write(world, key.first, value)
        if (events.isNotEmpty()) {
            val targets = world.entities(feature.behavior).map { checkNotNull(world.get(it, feature.behavior)) }
            for ((event, payload) in events) {
                for (target in targets) {
                    if (target.fault == null && target.program.handlers.any { (it.trigger as? Trigger.OnEvent)?.event == event }) {
                        target.inbox += event to payload
                    }
                }
            }
        }
        effects.forEachIndexed { n, (effect, payload) ->
            instance.outbox += EffectRequest("${self.index}.${self.generation}:${instance.segments}:$n", effect, payload)
        }
        instance.segments++
        instance.fuelUsed += fuel
    }

    private fun readProperty(
        entity: EntityHandle,
        index: Int,
    ): ProgramValue? = writes[entity to index] ?: feature.host.properties[index].read(world, entity)

    /** Interprets until the outermost handler ends (returns `null`) or a wait suspends (returns its resume tick). */
    private fun run(
        frames: MutableList<Frame>,
        stack: MutableList<ProgramValue>,
    ): Long? {
        while (true) {
            val frame = frames.last()
            val op = frame.unit.code[frame.pc++]
            charge(1)
            when (op) {
                is Op.Push -> {
                    stack += op.value
                }

                is Op.Load -> {
                    stack += frame.locals[op.slot]
                }

                is Op.Store -> {
                    frame.locals[op.slot] = stack.pop()
                }

                is Op.LoadState -> {
                    stack += workingState[op.field]
                }

                is Op.StoreState -> {
                    workingState[op.field] = stack.pop()
                }

                is Op.Arithmetic -> {
                    val right = stack.pop()
                    stack += arithmetic(op.op, stack.pop(), right)
                }

                Op.Negate -> {
                    stack +=
                        when (val value = stack.pop()) {
                            is IntegerValue -> IntegerValue(0L - value.value)
                            is DecimalValue -> DecimalValue(-value.value)
                            else -> error("Unexpected operand")
                        }
                }

                Op.Not -> {
                    stack += BoolValue(!(stack.pop() as BoolValue).value)
                }

                is Op.Jump -> {
                    frame.pc = op.target
                }

                is Op.JumpIfFalse -> {
                    if (!(stack.pop() as BoolValue).value) frame.pc = op.target
                }

                is Op.Call -> {
                    if (frames.size >= limits.maxCallDepth) {
                        throw FaultSignal(
                            FaultKind.CallDepthExceeded,
                            "Functions called each other more than ${limits.maxCallDepth} levels deep",
                        )
                    }
                    val unit = program.units[op.unit]
                    val locals = Array<ProgramValue>(unit.localCount) { NothingValue }
                    for (i in op.argumentCount - 1 downTo 0) locals[i] = stack.pop()
                    frames += Frame(unit, op.unit, 0, locals)
                }

                is Op.CallHost -> {
                    callHost(op, stack)
                }

                Op.Return -> {
                    frames.removeAt(frames.lastIndex)
                }

                Op.End -> {
                    return null
                }

                Op.Pop -> {
                    stack.pop()
                }

                is Op.MakeList -> {
                    allocate(op.count.toLong(), op.count)
                    val items = List(op.count) { stack[stack.size - op.count + it] }
                    repeat(op.count) { stack.pop() }
                    stack += ListValue(op.element, items)
                }

                Op.Append -> {
                    val item = stack.pop()
                    val list = stack.pop() as ListValue
                    allocate(list.items.size + 1L, list.items.size + 1)
                    stack += ListValue(list.element, list.items + item)
                }

                Op.Size -> {
                    stack += IntegerValue((stack.pop() as ListValue).items.size.toLong())
                }

                Op.At -> {
                    val index = (stack.pop() as IntegerValue).value
                    val list = stack.pop() as ListValue
                    if (index < 0 || index >= list.items.size) {
                        throw FaultSignal(FaultKind.IndexOutOfRange, "Position $index is outside a list of ${list.items.size} items")
                    }
                    stack += list.items[index.toInt()]
                }

                is Op.Query -> {
                    val components = op.properties.map { feature.host.properties[it].component }
                    val found = world.entities(*components.toTypedArray())
                    allocate(found.size.toLong(), found.size)
                    stack += ListValue(EntityType, found.map(::EntityValue))
                }

                is Op.GetProperty -> {
                    val entity = (stack.pop() as EntityValue).entity
                    stack += readProperty(entity, op.property) ?: throw missing(entity, op.property)
                }

                is Op.SetProperty -> {
                    val value = stack.pop()
                    val entity = (stack.pop() as EntityValue).entity
                    if (readProperty(entity, op.property) == null) throw missing(entity, op.property)
                    writes[entity to op.property] = value
                }

                Op.Self -> {
                    stack += EntityValue(selfEntity)
                }

                is Op.Emit -> {
                    message()
                    events += op.event to (if (op.hasPayload) stack.pop() else NothingValue)
                }

                is Op.Effect -> {
                    message()
                    effects += op.effect to stack.pop()
                }

                Op.Wait -> {
                    val ticks = (stack.pop() as IntegerValue).value
                    if (ticks < 1) throw FaultSignal(FaultKind.InvalidArithmetic, "Wait for at least one tick, not $ticks")
                    if (ticks >
                        Long.MAX_VALUE - world.tick
                    ) {
                        throw FaultSignal(FaultKind.InvalidArithmetic, "Wait of $ticks ticks is too long")
                    }
                    return world.tick + ticks
                }
            }
        }
    }

    private fun callHost(
        op: Op.CallHost,
        stack: MutableList<ProgramValue>,
    ) {
        val function = feature.host.functions[op.function]
        charge(function.cost.toLong())
        val args = List(op.argumentCount) { stack[stack.size - op.argumentCount + it] }
        repeat(op.argumentCount) { stack.pop() }
        hostArguments = args
        val result =
            try {
                function.implementation(this)
            } catch (failure: ProgramHostException) {
                throw FaultSignal(FaultKind.HostFailure, "'${function.name}' failed: ${failure.message}")
            } finally {
                hostArguments = emptyList()
            }
        if (result.type != function.returns) {
            throw FaultSignal(
                FaultKind.HostFailure,
                "'${function.name}' returned ${describe(result.type)} instead of ${describe(function.returns)}",
            )
        }
        stack += result
    }

    private fun charge(amount: Long) {
        fuel += amount
        if (fuel > limits.fuelPerSegment) {
            throw FaultSignal(FaultKind.FuelExhausted, "This rule ran too long without waiting (more than ${limits.fuelPerSegment} steps)")
        }
    }

    private fun allocate(
        elements: Long,
        resultSize: Int,
    ) {
        allocation += elements
        if (resultSize > limits.maxListSize) {
            throw FaultSignal(FaultKind.AllocationExceeded, "A list would hold more than ${limits.maxListSize} items")
        }
        if (allocation > limits.allocationPerSegment) {
            throw FaultSignal(
                FaultKind.AllocationExceeded,
                "This rule built more than ${limits.allocationPerSegment} list items without waiting",
            )
        }
    }

    private fun message() {
        if (events.size + effects.size >= limits.maxMessagesPerSegment) {
            throw FaultSignal(
                FaultKind.MessageLimitExceeded,
                "This rule sent more than ${limits.maxMessagesPerSegment} messages without waiting",
            )
        }
    }

    private fun missing(
        entity: EntityHandle,
        property: Int,
    ): FaultSignal {
        val name =
            feature.host.properties[property]
                .authoring.name
        val reason = if (world.isAlive(entity)) "has no $name" else "no longer exists"
        return FaultSignal(FaultKind.MissingProperty, "The object $reason")
    }

    private fun arithmetic(
        op: BinaryOp,
        left: ProgramValue,
        right: ProgramValue,
    ): ProgramValue =
        when (op) {
            BinaryOp.Equal -> {
                BoolValue(left == right)
            }

            BinaryOp.NotEqual -> {
                BoolValue(left != right)
            }

            else -> {
                when (left) {
                    is IntegerValue -> integerOp(op, left.value, (right as IntegerValue).value)
                    is DecimalValue -> decimalOp(op, left.value, (right as DecimalValue).value)
                    is TextValue -> TextValue(left.value + (right as TextValue).value)
                    else -> error("Unexpected operands for $op")
                }
            }
        }

    private fun integerOp(
        op: BinaryOp,
        a: Long,
        b: Long,
    ): ProgramValue =
        when (op) {
            BinaryOp.Add -> {
                IntegerValue(a + b)
            }

            BinaryOp.Subtract -> {
                IntegerValue(a - b)
            }

            BinaryOp.Multiply -> {
                IntegerValue(a * b)
            }

            BinaryOp.Divide, BinaryOp.Remainder -> {
                if (b == 0L) throw FaultSignal(FaultKind.InvalidArithmetic, "Division by zero")
                // Define the one overflowing case identically on every target instead of relying on platform traps.
                val quotient = if (a == Long.MIN_VALUE && b == -1L) Long.MIN_VALUE else a / b
                IntegerValue(if (op == BinaryOp.Divide) quotient else a - quotient * b)
            }

            BinaryOp.Less -> {
                BoolValue(a < b)
            }

            BinaryOp.LessOrEqual -> {
                BoolValue(a <= b)
            }

            BinaryOp.Greater -> {
                BoolValue(a > b)
            }

            BinaryOp.GreaterOrEqual -> {
                BoolValue(a >= b)
            }

            else -> {
                error("Unexpected integer operator $op")
            }
        }

    private fun decimalOp(
        op: BinaryOp,
        a: Double,
        b: Double,
    ): ProgramValue =
        when (op) {
            BinaryOp.Add -> {
                DecimalValue(a + b)
            }

            BinaryOp.Subtract -> {
                DecimalValue(a - b)
            }

            BinaryOp.Multiply -> {
                DecimalValue(a * b)
            }

            BinaryOp.Divide -> {
                if (b == 0.0) throw FaultSignal(FaultKind.InvalidArithmetic, "Division by zero")
                DecimalValue(a / b)
            }

            BinaryOp.Less -> {
                BoolValue(a < b)
            }

            BinaryOp.LessOrEqual -> {
                BoolValue(a <= b)
            }

            BinaryOp.Greater -> {
                BoolValue(a > b)
            }

            BinaryOp.GreaterOrEqual -> {
                BoolValue(a >= b)
            }

            else -> {
                error("Unexpected decimal operator $op")
            }
        }
}

private fun encodeThread(
    thread: SuspendedThread,
    context: StateCaptureContext,
): StateValue =
    StateValue.Sequence(
        listOf(
            StateValue.Integer(thread.id),
            StateValue.Integer(thread.resumeTick),
            StateValue.Sequence(
                thread.frames.map { frame ->
                    StateValue.Sequence(
                        listOf(
                            StateValue.Integer(frame.unit.toLong()),
                            StateValue.Integer(frame.pc.toLong()),
                            StateValue.Sequence(frame.locals.map { encode(it, context) }),
                        ),
                    )
                },
            ),
            StateValue.Sequence(thread.stack.map { encode(it, context) }),
        ),
    )

private fun decodeThread(
    state: StateValue,
    context: StateRestoreContext,
): SuspendedThread {
    val (id, resume, frames, stack) = sequence(state)
    return SuspendedThread(
        integer(id),
        integer(resume),
        sequence(frames).map { frame ->
            val (unit, pc, locals) = sequence(frame)
            FrameState(integer(unit).toInt(), integer(pc).toInt(), sequence(locals).map { decode(it, context) })
        },
        sequence(stack).map { decode(it, context) },
    )
}

private fun encodeFault(fault: ProgramFault): StateValue =
    StateValue.Sequence(
        listOf(
            StateValue.Text(fault.kind.name),
            StateValue.Text(fault.source.nodeId),
            StateValue.Text(fault.source.label),
            StateValue.Text(fault.message),
            StateValue.Integer(fault.tick),
        ),
    )

private fun decodeFault(state: StateValue): ProgramFault {
    val items = sequence(state)
    return ProgramFault(
        FaultKind.valueOf(text(items[0])),
        SourceRef(text(items[1]), text(items[2])),
        text(items[3]),
        integer(items[4]),
    )
}

private fun encode(
    value: ProgramValue,
    context: StateCaptureContext,
): StateValue =
    when (value) {
        is IntegerValue -> {
            StateValue.Integer(value.value)
        }

        is DecimalValue -> {
            StateValue.Decimal(value.value)
        }

        is BoolValue -> {
            StateValue.Flag(value.value)
        }

        is TextValue -> {
            StateValue.Text(value.value)
        }

        is EntityValue -> {
            context.entity(value.entity)
        }

        NothingValue -> {
            StateValue.Null
        }

        is ListValue -> {
            StateValue.Record(
                mapOf(
                    "element" to StateValue.Text(typeCode(value.element)),
                    "items" to StateValue.Sequence(value.items.map { encode(it, context) }),
                ),
            )
        }
    }

private fun decode(
    state: StateValue,
    context: StateRestoreContext,
): ProgramValue =
    when (state) {
        is StateValue.Integer -> {
            IntegerValue(state.value)
        }

        is StateValue.Decimal -> {
            DecimalValue(state.value)
        }

        is StateValue.Flag -> {
            BoolValue(state.value)
        }

        is StateValue.Text -> {
            TextValue(state.value)
        }

        is StateValue.EntityReference -> {
            EntityValue(context.entity(state))
        }

        StateValue.Null -> {
            NothingValue
        }

        is StateValue.Record -> {
            ListValue(
                parseType(text(state.fields.getValue("element"))),
                sequence(state.fields.getValue("items")).map { decode(it, context) },
            )
        }

        is StateValue.Sequence -> {
            error("Unexpected program value state")
        }
    }

private fun typeCode(type: ProgramType): String =
    when (type) {
        IntegerType -> "int"
        DecimalType -> "dec"
        BoolType -> "bool"
        TextType -> "text"
        EntityType -> "entity"
        NothingType -> "nothing"
        is ListType -> "list:" + typeCode(type.element)
    }

private fun parseType(code: String): ProgramType =
    when {
        code.startsWith("list:") -> ListType(parseType(code.removePrefix("list:")))
        code == "int" -> IntegerType
        code == "dec" -> DecimalType
        code == "bool" -> BoolType
        code == "text" -> TextType
        code == "entity" -> EntityType
        code == "nothing" -> NothingType
        else -> error("Unknown program type code '$code'")
    }

private fun sequence(state: StateValue): List<StateValue> = (state as StateValue.Sequence).values

private fun text(state: StateValue): String = (state as StateValue.Text).value

private fun integer(state: StateValue): Long = (state as StateValue.Integer).value

private fun MutableList<ProgramValue>.pop(): ProgramValue = removeAt(lastIndex)
