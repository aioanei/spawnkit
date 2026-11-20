package dev.spawnkit.editing

import dev.spawnkit.runtime.Runtime

/**
 * Installed-capability problems in loaded data. A project can still be opened and saved with these problems, but
 * execution is blocked. Storage never drops unavailable components or settings to make a game appear runnable.
 */
public fun Project.capabilityProblems(runtime: Runtime): List<String> {
    val applier = OperationApplier(runtime)
    return buildList {
        for (scene in scenes) {
            for (obj in scene.objects) {
                for (component in obj.components) {
                    val descriptor = runtime.catalog.firstOrNull { it.component.id == component.componentId }
                    if (descriptor == null) {
                        add(
                            "${obj.name} needs '${component.componentId}', which is not installed. " +
                                "Install its extension to play; its data is kept",
                        )
                    } else {
                        for ((property, value) in component.values) {
                            runCatching { applier.validateValue(component.componentId, property, value) }.exceptionOrNull()?.let {
                                add(
                                    "${obj.name}: ${it.message}. The stored value is kept; " +
                                        "correct it or install the matching extension version to play",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Preview-only projection: unavailable components are omitted and unsupported values use their defaults. */
internal fun Project.previewProjection(runtime: Runtime): Project {
    val applier = OperationApplier(runtime)
    return copy(
        scenes =
            scenes.map { scene ->
                scene.copy(
                    objects =
                        scene.objects.map { obj ->
                            obj.copy(
                                components =
                                    obj.components.mapNotNull { component ->
                                        if (runtime.catalog.none { it.component.id == component.componentId }) {
                                            null
                                        } else {
                                            component.copy(
                                                values =
                                                    component.values.filter { (property, value) ->
                                                        runCatching {
                                                            applier.validateValue(
                                                                component.componentId,
                                                                property,
                                                                value,
                                                            )
                                                        }.isSuccess
                                                    },
                                            )
                                        }
                                    },
                            )
                        },
                )
            },
    )
}
