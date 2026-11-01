package com.kitefoldlabs.bridle.core.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val tagsSerializer = ListSerializer(String.serializer())
private val envSerializer = MapSerializer(String.serializer(), String.serializer())

/** Stores tag sets and environment maps as JSON text. Output is sorted, so equal values store equally. */
internal class Converters {
    @TypeConverter
    fun tagsToJson(tags: Set<String>): String = Json.encodeToString(tagsSerializer, tags.sorted())

    @TypeConverter
    fun tagsFromJson(json: String): Set<String> = Json.decodeFromString(tagsSerializer, json).toSet()

    @TypeConverter
    fun envToJson(env: Map<String, String>): String = Json.encodeToString(envSerializer, env.toSortedMap())

    @TypeConverter
    fun envFromJson(json: String): Map<String, String> = Json.decodeFromString(envSerializer, json)
}
