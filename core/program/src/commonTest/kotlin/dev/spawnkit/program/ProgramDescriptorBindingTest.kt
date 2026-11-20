package dev.spawnkit.program

import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.get
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.setProp
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.text
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ChoiceOption
import dev.spawnkit.runtime.ObjectRef
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProgramDescriptorBindingTest {
    private data class Hero(
        val health: Long = 3,
        val speed: Double = 1.5,
        val shielded: Boolean = false,
        val name: String = "Hero",
        val mood: String = "calm",
        val target: ObjectRef = ObjectRef(null),
    )

    private class Fixture {
        val builder = RuntimeBuilder()
        val hero = builder.component<Hero>("test:hero", AuthoringInfo("Hero"))
        val descriptor =
            builder.describe(hero) {
                create { Hero() }
                property(
                    "health",
                    ValueKind.WholeNumber(0, 10),
                    AuthoringInfo("Health", "Hit points"),
                    3L,
                    { it.health },
                    { h, v -> h.copy(health = v) },
                )
                property("speed", ValueKind.Decimal(0.0, 5.0), AuthoringInfo("Speed"), 1.5, { it.speed }, { h, v -> h.copy(speed = v) })
                property("shielded", ValueKind.YesNo, AuthoringInfo("Shielded"), false, { it.shielded }, { h, v -> h.copy(shielded = v) })
                property("name", ValueKind.Text(maxLength = 8), AuthoringInfo("Name"), "Hero", { it.name }, { h, v -> h.copy(name = v) })
                property(
                    "mood",
                    ValueKind.Choice(listOf(ChoiceOption("calm", "Calm"), ChoiceOption("angry", "Angry"))),
                    AuthoringInfo("Mood"),
                    "calm",
                    { it.mood },
                    { h, v -> h.copy(mood = v) },
                )
                property(
                    "target",
                    ValueKind.ObjectReference(),
                    AuthoringInfo("Target"),
                    ObjectRef(null),
                    { it.target },
                    { h, v -> h.copy(target = v) },
                )
            }
        val host = ProgramHostBuilder().properties(descriptor, prefix = "hero_").build()
        val feature = ProgramFeature(builder, host)
        val runtime = builder.build()

        fun run(program: Program): Pair<Hero, ProgramInstance> {
            val prepared = assertIs<PrepareResult.Ready>(prepareProgram(program, host)).program
            val world = runtime.createWorld()
            val entity = world.spawn()
            world.set(entity, hero, Hero())
            world.set(entity, feature.behavior, feature.instantiate(prepared))
            world.step()
            return checkNotNull(world.get(entity, hero)) to checkNotNull(world.get(entity, feature.behavior))
        }
    }

    @Test
    fun descriptorPropertiesBecomeTypedProgramPropertiesWithFriendlyMetadata() {
        val fixture = Fixture()
        val catalog = fixture.host.propertyCatalog
        assertEquals(listOf("hero_health", "hero_speed", "hero_shielded", "hero_name", "hero_mood"), catalog.map { it.name })
        assertEquals(listOf(IntegerType, DecimalType, BoolType, TextType, TextType), catalog.map { it.type })
        assertEquals("Hit points", catalog.first().authoring.description)
    }

    @Test
    fun programsReadAndWriteEveryMappedKind() {
        val fixture = Fixture()
        val (hero, instance) =
            fixture.run(
                Program(
                    "edit",
                    state = listOf(field("seen", IntegerValue(0))),
                    handlers =
                        listOf(
                            everyTick(
                                setState("seen", get(self(), "hero_health")),
                                setProp(self(), "hero_health", op(BinaryOp.Add, get(self(), "hero_health"), int(4))),
                                setProp(self(), "hero_speed", Expr.Literal(DecimalValue(2.5), P.s("lit"))),
                                setProp(self(), "hero_shielded", P.bool(true)),
                                setProp(self(), "hero_name", text("Ada")),
                                setProp(self(), "hero_mood", text("angry")),
                            ),
                        ),
                ),
            )
        assertNull(instance.fault)
        assertEquals(IntegerValue(3), instance.state("seen"))
        assertEquals(Hero(health = 7, speed = 2.5, shielded = true, name = "Ada", mood = "angry"), hero)
    }

    @Test
    fun constraintViolationsFaultAtTheWriteAndRollBackTheSegment() {
        val cases =
            listOf(
                "hero_health" to int(11),
                "hero_speed" to Expr.Literal(DecimalValue(9.0), P.s("lit")),
                "hero_name" to text("Much too long"),
                "hero_mood" to text("sleepy"),
            )
        for ((property, value) in cases) {
            val fixture = Fixture()
            val (hero, instance) =
                fixture.run(
                    Program(
                        "bad-$property",
                        handlers =
                            listOf(
                                everyTick(
                                    setProp(self(), "hero_shielded", P.bool(true)),
                                    setProp(self(), property, value, id = "block:bad"),
                                ),
                            ),
                    ),
                )
            val fault = instance.fault
            assertEquals(FaultKind.InvalidValue, fault?.kind, property)
            assertEquals("block:bad", fault?.source?.nodeId)
            assertEquals(Hero(), hero, "Earlier writes in the faulted segment are rolled back ($property)")
        }
    }
}
