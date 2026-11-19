package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalConsumerTest {
    @Test
    fun externalComponentAndStatefulSystemRunWithoutADocumentOrEngineChanges() {
        val builder = RuntimeBuilder()
        val feature = CounterFeature(builder)
        val runtime = builder.build()
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, feature.counter, Counter(5))
        world.step()
        world.step()
        assertEquals(Counter(8), world.get(entity, feature.counter))
        assertEquals("Counter", feature.counter.authoring.name)
        val independent = runtime.createWorld()
        val other = independent.spawn()
        independent.set(other, feature.counter, Counter())
        independent.step()
        assertEquals(Counter(1), independent.get(other, feature.counter))
        assertEquals(Counter(8), world.get(entity, feature.counter))
    }
}
