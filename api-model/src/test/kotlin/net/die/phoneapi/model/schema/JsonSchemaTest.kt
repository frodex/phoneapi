package net.die.phoneapi.model.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import net.die.phoneapi.model.LogLevel
import net.die.phoneapi.model.NodeState
import net.die.phoneapi.model.ScreenCleanupOnRelease
import net.die.phoneapi.model.SwipeDirection
import net.die.phoneapi.model.wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonSchemaTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun `cleanup policy wire values`() {
        val schema = jsonSchema(serializer<ScreenCleanupOnRelease>().descriptor)
        assertEquals(
            listOf("home-lock", "home", "none"),
            schema["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        for (mode in ScreenCleanupOnRelease.entries) {
            assertEquals(
                "\"${mode.wire}\"",
                json.encodeToString(ScreenCleanupOnRelease.serializer(), mode),
            )
            assertEquals(
                mode,
                json.decodeFromString<ScreenCleanupOnRelease>("\" ${mode.wire.uppercase()} \""),
            )
        }
        assertThrows<SerializationException> {
            json.decodeFromString<ScreenCleanupOnRelease>("\"invalid\"")
        }
    }

    @Test
    fun `enum lists serial names`() {
        val schema = jsonSchema(serializer<Color>().descriptor)
        val values = schema.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("red", "blue"), values)
        assertEquals("string", schema["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nullable and default fields`() {
        val schema = jsonSchema(serializer<Box>().descriptor)
        val required = schema.required()
        assertEquals(listOf("color"), required)
        assertFalse("label" in required)
        assertFalse("count" in required)
        val label = schema.properties()["label"]!!.jsonObject
        assertEquals("string", label["type"]!!.jsonPrimitive.content)
        val pick = jsonSchema(serializer<Pick>().descriptor).properties()["pick"]!!.jsonObject
        assertEquals(
            "null",
            pick["anyOf"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `nullable ref shares def`() {
        val schema = jsonSchema(serializer<MaybeNest>().descriptor)
        assertEquals(setOf("Box"), schema.defs().keys)
        val box = schema.properties()["box"]!!.jsonObject
        assertEquals("#/\$defs/Box", box["\$ref"]!!.jsonPrimitive.content)
        val also = schema.properties()["also"]!!.jsonObject
        assertEquals("#/\$defs/Box", also["\$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested type is a ref`() {
        val schema = jsonSchema(serializer<Nest>().descriptor)
        val box = schema.properties()["box"]!!.jsonObject
        assertEquals("#/\$defs/Box", box["\$ref"]!!.jsonPrimitive.content)
        assertTrue("Box" in schema.defs())
    }

    @Test
    fun `recursive type refs itself`() {
        val schema = jsonSchema(serializer<Tree>().descriptor)
        val items = schema.properties()["children"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("#/\$defs/Tree", items["\$ref"]!!.jsonPrimitive.content)
        assertTrue(
            schema.defs()["Tree"]!!.jsonObject["properties"]!!.jsonObject.containsKey("children")
        )
    }

    @Test
    fun `sealed type is a oneOf`() {
        val schema = jsonSchema(serializer<Shape>().descriptor)
        assertEquals(
            "type",
            schema["discriminator"]!!.jsonObject["propertyName"]!!.jsonPrimitive.content,
        )
        val names =
            schema["oneOf"]!!.jsonArray.map { ref ->
                ref.jsonObject["\$ref"]!!.jsonPrimitive.content.substringAfterLast('/')
            }
        assertEquals(listOf("circle", "rect"), names)
        val circle = schema.defs()["circle"]!!.jsonObject
        assertEquals(
            "circle",
            circle.properties()["type"]!!.jsonObject["const"]!!.jsonPrimitive.content,
        )
        val encoded =
            json.parseToJsonElement(json.encodeToString(serializer<Shape>(), Shape.Circle(r = 2)))
        assertEquals("circle", encoded.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `doc text reaches the schema`() {
        val schema = jsonSchema(serializer<Named>().descriptor)
        assertEquals("A named thing.", schema["description"]!!.jsonPrimitive.content)
        assertEquals(
            "What it is called.",
            schema.properties()["name"]!!.jsonObject["description"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `lenient enums fold case`() {
        assertEquals(
            NodeState.PRESENT,
            json.decodeFromString(serializer<NodeState>(), "\" Present \""),
        )
        assertEquals(
            SwipeDirection.UP,
            json.decodeFromString(serializer<SwipeDirection>(), "\"UP\""),
        )
        assertEquals(LogLevel.WARNING, json.decodeFromString(serializer<LogLevel>(), "\"Warning\""))
        val failure =
            assertThrows<SerializationException> {
                json.decodeFromString(serializer<NodeState>(), "\"bogus\"")
            }
        assertTrue(failure.message.orEmpty().contains("present"))
    }

    private fun JsonObject.properties(): JsonObject = getValue("properties").jsonObject

    private fun JsonObject.required(): List<String> =
        this["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.defs(): JsonObject = getValue("\$defs").jsonObject
}

@Serializable
private enum class Color {
    @SerialName("red") RED,
    @SerialName("blue") BLUE,
}

@Serializable
private data class Box(val color: Color, val label: String? = null, val count: Int = 1)

@Serializable private data class Nest(val box: Box)

@Serializable
private data class MaybeNest(val box: Box? = null, val also: Box = Box(Color.entries[0]))

@Serializable private data class Pick(val pick: Color?)

@Serializable private data class Tree(val name: String, val children: List<Tree> = emptyList())

@Serializable
private sealed interface Shape {
    @Serializable @SerialName("circle") data class Circle(val r: Int) : Shape

    @Serializable @SerialName("rect") data class Rect(val w: Int, val h: Int) : Shape
}

@Doc("A named thing.")
@Serializable
private data class Named(@Doc("What it is called.") val name: String)

/**
 * Builds a JSON Schema document for [descriptor]. Classes, sealed types, and objects are named in
 * `$defs` (or [refPrefix]) and referenced from there, so nested and recursive types stay finite.
 * The returned object is the root schema with a `$defs` member when any named type was used.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun jsonSchema(
    descriptor: SerialDescriptor,
    config: SchemaConfig = SchemaConfig(),
    refPrefix: String = "#/\$defs/",
): JsonObject {
    val schemas = JsonSchemas(config, refPrefix)
    val root = schemas.inline(descriptor)
    if (schemas.definitions.isEmpty()) return root
    return buildJsonObject {
        root.forEach { key, value -> put(key, value) }
        put("\$defs", JsonObject(schemas.definitions))
    }
}
