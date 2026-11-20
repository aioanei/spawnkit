package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.EditResult
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.ValueKind
import dev.spawnkit.runtime.addComponent
import dev.spawnkit.runtime.edit
import dev.spawnkit.runtime.inspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A separately compiled extension is discovered, inspected, added and edited through the generic model only. */
class ExtensionInspectorTest {
    @Test
    fun extensionComponentIsEditableWithoutCustomInspectorCode() {
        val builder = RuntimeBuilder()
        val feature = CounterFeature(builder)
        val runtime = builder.build()
        assertEquals(listOf("probe:counter"), runtime.catalog.map { it.component.id })
        val world = runtime.createWorld()
        val entity = world.spawn()
        assertEquals(EditResult.Applied(null), runtime.addComponent(world, entity, feature.counter))
        val inspected = runtime.inspect(world, entity).single()
        assertEquals("Counter", inspected.descriptor.component.authoring.name)
        val property = inspected.properties.single()
        assertIs<ValueKind.WholeNumber>(property.descriptor.kind)
        assertEquals(0L, property.value)
        assertEquals(EditResult.Applied(0L), runtime.edit(world, entity, "probe:counter", "value", 41L))
        assertIs<EditResult.Rejected>(runtime.edit(world, entity, "probe:counter", "value", -1L))
        world.step()
        // The edited value runs through the extension's own system (Counter(41) + 1 update).
        assertEquals(Counter(42), world.get(entity, feature.counter))
    }
}
