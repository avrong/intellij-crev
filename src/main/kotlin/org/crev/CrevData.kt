/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Like [CrevPackage], but for creating reviews */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CrevPackagePrototype(
    val review: Review,
    val alternatives: List<String>,
    val comment: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CrevPackage(
    val date: String,
    val `package`: PackageInfo,
    val review: Review,
    val comment: String?,
)

data class Review(
    val thoroughness: Level = Level.Low,
    val understanding: Level = Level.Medium,
    val rating: Rating = Rating.Positive,
)

enum class Level {
    @JsonProperty("high")
    High,
    @JsonProperty("medium")
    Medium,
    @JsonProperty("low")
    Low,
    @JsonProperty("none")
    None;

    companion object {
        fun fromString(name: String): Level = values().find { it.name == name }!!
    }
}

enum class Rating {
    @JsonProperty("strong")
    Strong,
    @JsonProperty("positive")
    Positive,
    @JsonProperty("neutral")
    Neutral,
    @JsonProperty("negative")
    Negative;

    companion object {
        fun fromString(name: String): Rating = values().find { it.name == name }!!
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PublicId(
    val id: String,
    val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageInfo(
    val source: String,
    val name: String,
    val version: String,
    val revision: String?,
    val digest: String,
)
