package dev.spawnkit.gameplay

import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.OperationCatalog
import dev.spawnkit.editing.OperationSpec
import dev.spawnkit.editing.Parameter
import dev.spawnkit.editing.ParameterType
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneDoc
import dev.spawnkit.editing.objectParameter
import dev.spawnkit.editing.sceneParameter
import dev.spawnkit.runtime.AuthoringInfo

/**
 * Object operations of the standard engine that need its 2D placement, such as grouping. The builder's controls and
 * the agent tools both use these entries (ADR-0009).
 */
public object ObjectOperations {
    /** The entries. */
    public val catalog: OperationCatalog by lazy { OperationCatalog(listOf(moveIntoSpec)) }

    /**
     * Moves object [objectId] of scene [sceneId] into [parent] (`null` for the top level) as one undo step, keeping it
     * where it is on screen: its position and scale become relative to the new parent. Throws [IllegalArgumentException]
     * with a plain-language message when the move is not possible.
     */
    public fun moveInto(
        project: Project,
        sceneId: String,
        objectId: String,
        parent: String?,
    ): EditOperation {
        val scene = requireNotNull(project.scene(sceneId)) { "That scene no longer exists" }
        val obj = requireNotNull(scene.find(objectId)) { "That object no longer exists" }
        val target = parent?.let { requireNotNull(scene.find(it)) { "That object no longer exists" } }
        require(target == null || target.id !in scene.subtree(obj.id)) { "${obj.name} cannot go inside itself or something inside it" }
        require(obj.parent != parent) {
            if (target == null) "${obj.name} is already at the top level" else "${obj.name} is already inside ${target.name}"
        }
        val label = if (target == null) "Move ${obj.name} to the top level" else "Move ${obj.name} into ${target.name}"
        val operations = mutableListOf<EditOperation>(EditOperation.ReparentObject(sceneId, objectId, parent, label))
        if (obj.component(TRANSFORM) != null) {
            val own = place(scene, obj)
            val origin = target?.let { place(scene, it) } ?: Place(0.0, 0.0, 1.0, 1.0)
            if (origin.scaleX != 0.0 && origin.scaleY != 0.0) {
                val local =
                    mapOf(
                        "x" to (own.x - origin.x) / origin.scaleX,
                        "y" to (own.y - origin.y) / origin.scaleY,
                        "scaleX" to own.scaleX / origin.scaleX,
                        "scaleY" to own.scaleY / origin.scaleY,
                    )
                val current = transform(obj)
                for ((property, value) in local) {
                    if (current[property] != value) operations += EditOperation.SetProperty(sceneId, objectId, TRANSFORM, property, value, label)
                }
            }
        }
        return EditOperation.Batch(operations, label)
    }

    private val moveIntoSpec =
        OperationSpec(
            "move_into",
            AuthoringInfo(
                "Move into a group",
                "Puts an object inside another one, so they move, turn and resize together, such as a label inside its " +
                    "button. Leave the group out to move the object back to the top level. It stays where it is on screen.",
                "Objects",
            ),
            listOf(
                sceneParameter(),
                objectParameter(),
                Parameter(
                    "parent",
                    "Group",
                    "ID of the object to put it inside; leave it out for the top level.",
                    ParameterType.Object,
                    required = false,
                ),
            ),
            listOf("Put the Play label inside the Play button", "Take the label out of the button"),
        ) {
            try {
                moveInto(project, text("scene"), text("object"), textOrNull("parent"))
            } catch (problem: IllegalArgumentException) {
                reject(problem.message ?: "That move is not possible")
            }
        }

    private data class Place(
        val x: Double,
        val y: Double,
        val scaleX: Double,
        val scaleY: Double,
    )

    /** World position and scale of [obj]'s origin, from its authored transforms up the parent chain. */
    private fun place(
        scene: SceneDoc,
        obj: ObjectDoc,
    ): Place {
        var x = 0.0
        var y = 0.0
        var scaleX = 1.0
        var scaleY = 1.0
        var current: ObjectDoc? = obj
        while (current != null) {
            val t = transform(current)
            val sx = t.getValue("scaleX")
            val sy = t.getValue("scaleY")
            x = t.getValue("x") + sx * x
            y = t.getValue("y") + sy * y
            scaleX *= sx
            scaleY *= sy
            current = current.parent?.let(scene::find)
        }
        return Place(x, y, scaleX, scaleY)
    }

    private fun transform(obj: ObjectDoc): Map<String, Double> {
        val values = obj.component(TRANSFORM)?.values.orEmpty()
        fun read(
            key: String,
            default: Double,
        ) = (values[key] as? Number)?.toDouble() ?: default
        return mapOf("x" to read("x", 0.0), "y" to read("y", 0.0), "scaleX" to read("scaleX", 1.0), "scaleY" to read("scaleY", 1.0))
    }

    private const val TRANSFORM = "graphics:transform"
}
