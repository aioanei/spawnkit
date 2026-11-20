package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptorTest {
    private data class Mover(
        val speed: Long = 5,
        val friction: Double = 0.5,
        val active: Boolean = true,
        val label: String = "Mover",
        val mode: String = "walk",
        val target: ObjectRef = ObjectRef(null),
    )

    private class Fixture {
        val builder = RuntimeBuilder()
        val mover = builder.component<Mover>("test:mover", AuthoringInfo("Mover", "Moves the object", "Motion"))
        val goal = builder.component<Int>("test:goal", AuthoringInfo("Goal"))
        val hidden = builder.component<Int>("test:hidden", AuthoringInfo("Hidden"))
        lateinit var speed: PropertyDescriptor<Mover, Long>
        lateinit var friction: PropertyDescriptor<Mover, Double>
        lateinit var target: PropertyDescriptor<Mover, ObjectRef>
        val descriptor =
            builder.describe(mover) {
                create { Mover() }
                speed =
                    property(
                        "speed",
                        ValueKind.WholeNumber(min = 0, max = 20, unit = "tiles/s"),
                        AuthoringInfo("Speed", "How fast it moves"),
                        default = 5L,
                        read = { it.speed },
                        write = { m, v -> m.copy(speed = v) },
                        examples = listOf("5 for walking", "12 for running"),
                    )
                friction =
                    property(
                        "friction",
                        ValueKind.Decimal(0.0, 1.0),
                        AuthoringInfo("Friction"),
                        0.5,
                        { it.friction },
                        { m, v -> m.copy(friction = v) },
                        visibility = Visibility.Advanced,
                    )
                property("active", ValueKind.YesNo, AuthoringInfo("Active"), true, { it.active }, { m, v -> m.copy(active = v) })
                property(
                    "label",
                    ValueKind.Text(maxLength = 10),
                    AuthoringInfo("Label"),
                    "Mover",
                    { it.label },
                    { m, v -> m.copy(label = v) },
                )
                property(
                    "mode",
                    ValueKind.Choice(listOf(ChoiceOption("walk", "Walk"), ChoiceOption("fly", "Fly"))),
                    AuthoringInfo("Mode"),
                    "walk",
                    { it.mode },
                    { m, v -> m.copy(mode = v) },
                )
                target =
                    property(
                        "target",
                        ValueKind.ObjectReference(listOf(goal)),
                        AuthoringInfo("Target", "Object to move towards"),
                        ObjectRef(null),
                        { it.target },
                        { m, v -> m.copy(target = v) },
                    )
            }
        val goalDescriptor =
            builder.describe(goal) {
                create { 0 }
                property("points", ValueKind.WholeNumber(0, 100), AuthoringInfo("Points"), 0L, { it.toLong() }, { _, v -> v.toInt() })
            }
        val hiddenDescriptor =
            builder.describe(hidden) {
                visibility = Visibility.Advanced
                create { 1 }
            }
        val runtime = builder.build()
    }

    @Test
    fun registrationIsValidatedAndCatalogedInIdOrder() {
        val fixture = Fixture()
        assertEquals(listOf("test:goal", "test:hidden", "test:mover"), fixture.runtime.catalog.map { it.component.id })
        assertEquals(fixture.descriptor, fixture.runtime.descriptor(fixture.mover))
        val builder = RuntimeBuilder()
        val score = builder.component<Long>("test:score", AuthoringInfo("Score"))
        assertFailsWith<IllegalArgumentException> {
            builder.describe(score) {
                create { 0L }
                property("points", ValueKind.WholeNumber(0, 10), AuthoringInfo("Points"), 50L, { it }, { _, v -> v })
            }
        }
        assertFailsWith<IllegalArgumentException> {
            builder.describe(score) {
                create { 0L }
                property("points", ValueKind.YesNo, AuthoringInfo("A"), true, { true }, { s, _ -> s })
                property("points", ValueKind.YesNo, AuthoringInfo("B"), true, { true }, { s, _ -> s })
            }
        }
        assertFailsWith<IllegalArgumentException> { builder.describe(score) { } }
        assertFailsWith<IllegalArgumentException> { builder.describe(Fixture().mover) { create { Mover() } } }
        assertFailsWith<IllegalArgumentException> {
            builder.describe(score) {
                create { 0L }
                property("other", ValueKind.ObjectReference(listOf(Fixture().goal)), AuthoringInfo("Other"), ObjectRef(null), {
                    ObjectRef(null)
                }, { s, _ -> s })
            }
        }
        builder.describe(score) { create { 0L } }
        assertFailsWith<IllegalArgumentException> { builder.describe(score) { create { 0L } } }
        builder.build()
        assertFailsWith<IllegalStateException> { builder.describe(score) { create { 0L } } }
    }

    @Test
    fun inspectorListsDescribedComponentsWithValuesAndRespectsVisibility() {
        val fixture = Fixture()
        val world = fixture.runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, fixture.mover, Mover(speed = 7))
        world.set(entity, fixture.hidden, 1)
        val advanced = fixture.runtime.inspect(world, entity)
        assertEquals(listOf("test:hidden", "test:mover"), advanced.map { it.descriptor.component.id })
        val mover = advanced.last()
        assertEquals(listOf("speed", "friction", "active", "label", "mode", "target"), mover.properties.map { it.descriptor.id })
        assertEquals(7L, mover.properties.first().value)
        val basic = fixture.runtime.inspect(world, entity, Visibility.Basic)
        assertEquals(listOf("test:mover"), basic.map { it.descriptor.component.id })
        assertEquals(listOf("speed", "active", "label", "mode", "target"), basic.single().properties.map { it.descriptor.id })
        world.destroy(entity)
        assertEquals(emptyList(), fixture.runtime.inspect(world, entity))
    }

    @Test
    fun editsValidateEveryValueKindAndReturnUndoValues() {
        val fixture = Fixture()
        val runtime = fixture.runtime
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, fixture.mover, Mover())
        assertEquals(EditResult.Applied(5L), runtime.edit(world, entity, fixture.speed, 9L))
        assertEquals(9L, world.get(entity, fixture.mover)?.speed)
        val rejections =
            listOf(
                runtime.edit(world, entity, fixture.speed, 21L),
                runtime.edit(world, entity, fixture.friction, 1.5),
                runtime.edit(world, entity, fixture.friction, Double.NaN),
                runtime.edit(world, entity, "test:mover", "label", "far too long text"),
                runtime.edit(world, entity, "test:mover", "label", "two\nlines"),
                runtime.edit(world, entity, "test:mover", "mode", "swim"),
                runtime.edit(world, entity, "test:mover", "speed", "fast"),
                runtime.edit(world, entity, "test:mover", "missing", 1L),
                runtime.edit(world, entity, "test:nothing", "speed", 1L),
            )
        rejections.forEach { assertIs<EditResult.Rejected>(it) }
        assertTrue((rejections[0] as EditResult.Rejected).message.startsWith("Speed:"), rejections[0].toString())
        assertEquals(Mover(speed = 9), world.get(entity, fixture.mover), "Rejected edits change nothing")
        assertEquals(EditResult.Applied(true), runtime.edit(world, entity, "test:mover", "active", false))
        assertEquals(EditResult.Applied("walk"), runtime.edit(world, entity, "test:mover", "mode", "fly"))
        // Undo by applying the previous value.
        assertEquals(EditResult.Applied(9L), runtime.edit(world, entity, fixture.speed, 5L))
        assertEquals(Mover(active = false, mode = "fly"), world.get(entity, fixture.mover))
    }

    @Test
    fun objectReferencesMustBeLiveSameWorldObjectsWithRequiredComponents() {
        val fixture = Fixture()
        val runtime = fixture.runtime
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, fixture.mover, Mover())
        val plain = world.spawn()
        val goal = world.spawn()
        world.set(goal, fixture.goal, 3)
        val otherWorld = runtime.createWorld()
        val foreign = otherWorld.spawn()
        otherWorld.set(foreign, fixture.goal, 1)
        assertEquals(listOf(goal), runtime.referenceCandidates(world, fixture.target.kind as ValueKind.ObjectReference))
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, fixture.target, ObjectRef(plain)))
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, fixture.target, ObjectRef(foreign)))
        assertEquals(EditResult.Applied(ObjectRef(null)), runtime.edit(world, entity, fixture.target, ObjectRef(goal)))
        world.destroy(goal)
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, fixture.target, ObjectRef(goal)))
        assertEquals(EditResult.Applied(ObjectRef(goal)), runtime.edit(world, entity, fixture.target, ObjectRef(null)))
    }

    @Test
    fun addComponentUsesTheFactoryAndRejectsDuplicatesOrStaleObjects() {
        val fixture = Fixture()
        val runtime = fixture.runtime
        val world = runtime.createWorld()
        val entity = world.spawn()
        assertEquals(EditResult.Applied(null), runtime.addComponent(world, entity, fixture.mover))
        assertEquals(Mover(), world.get(entity, fixture.mover))
        assertIs<EditResult.Rejected>(runtime.addComponent(world, entity, fixture.mover))
        world.destroy(entity)
        assertIs<EditResult.Rejected>(runtime.addComponent(world, entity, fixture.mover))
        val missing = world.spawn()
        assertIs<EditResult.Rejected>(runtime.edit(world, missing, fixture.speed, 1L))
        assertNull(world.get(missing, fixture.mover))
        val stranger = RuntimeBuilder().build()
        assertFailsWith<IllegalArgumentException> { stranger.inspect(world, missing) }
    }

    @Test
    fun decimalAndTextEditsAreConsistentAcrossTargets() {
        val fixture = Fixture()
        val runtime = fixture.runtime
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, fixture.mover, Mover())
        assertEquals(EditResult.Applied(0.5), runtime.edit(world, entity, fixture.friction, 0.25))
        // Untyped whole-number input converts to a decimal identically on every target.
        assertEquals(EditResult.Applied(0.25), runtime.edit(world, entity, "test:mover", "friction", 1))
        assertEquals(1.0, world.get(entity, fixture.mover)?.friction)
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, fixture.friction, Double.POSITIVE_INFINITY))
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, "test:mover", "friction", 5L))
        assertEquals(EditResult.Applied("Mover"), runtime.edit(world, entity, "test:mover", "label", "Hero"))
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, "test:mover", "label", "a\rb"))
        assertEquals("Hero", world.get(entity, fixture.mover)?.label)
    }

    private class Box(
        var size: Long,
    )

    @Test
    fun faultedWorldsRejectEditsBeforeTouchingMutableComponents() {
        val builder = RuntimeBuilder()
        val box = builder.component<Box>("test:box", AuthoringInfo("Box"))
        val extra = builder.component<Int>("test:extra", AuthoringInfo("Extra"))
        var descriptor: PropertyDescriptor<Box, Long>? = null
        builder.describe(box) {
            create { Box(1) }
            descriptor =
                property("size", ValueKind.WholeNumber(0, 10), AuthoringInfo("Size"), 1L, { it.size }, { b, v ->
                    b.apply {
                        this.size =
                            v
                    }
                })
        }
        builder.describe(extra) { create { 0 } }
        val size = checkNotNull(descriptor)
        builder.system("test:fail") { WorldSystem { _, _ -> error("boom") } }
        val runtime = builder.build()
        val world = runtime.createWorld()
        val entity = world.spawn()
        val value = Box(3)
        world.set(entity, box, value)
        assertFailsWith<IllegalStateException> { world.step() }
        assertTrue(world.isFaulted)
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, size, 7L))
        assertEquals(3L, value.size, "A rejected edit never runs the write lambda")
        assertIs<EditResult.Rejected>(runtime.addComponent(world, entity, extra))
    }

    @Test
    fun factoriesMustStartEveryPropertyAtItsDefault() {
        val builder = RuntimeBuilder()
        val score = builder.component<Long>("test:score", AuthoringInfo("Score"))
        assertFailsWith<IllegalArgumentException> {
            builder.describe(score) {
                create { 50L }
                property("points", ValueKind.WholeNumber(0, 100), AuthoringInfo("Points"), 0L, { it }, { _, v -> v })
            }
        }
    }
}
