package com.ald.ra.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Site pack: everything site-specific (docs/kotlin-port/spec.md §1). Coordinates are pixels of each camera's stream. */
@Serializable
data class Site(
    val site: String,
    val cameras: List<Camera>,
    val zones: List<Zone>,
    /** Zone names in the order a unit passes through them; units only move forward and may skip zones. */
    val flow: List<String>,
    /** Optional travel-time windows between zones; missing links default to [0, Params.defaultTravelMaxS]. */
    val links: List<Link> = emptyList(),
    /** Model class names per object role. An empty list means the model doesn't detect that role. */
    val classes: Classes,
    val assembly: AssemblyCfg = AssemblyCfg(),
    val params: Params = Params(),
)

@Serializable
data class Camera(
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
    /** Detector frames per second for this camera (5 for assembly-only, 10 where containers get carried). */
    val fps: Double = 5.0,
    /** Polygons the detector should ignore (e.g. a menu board with a food photo). */
    val ignore: List<List<List<Double>>> = emptyList(),
    /** Shared map, counter surface: 4+ image points on the counter and the same points on the floor plan (metres). */
    val map: MapPoints? = null,
    /** Optional shared map for the floor (people's feet). Not used yet: people are matched in the image only (spec §4.3). */
    val floor: MapPoints? = null,
    /** Replays and demos only: this camera is the crop [x0, y0, x1, y1] of a wider source video (one video as several cameras). */
    val crop: List<Double>? = null,
)

@Serializable
data class MapPoints(val image: List<List<Double>>, val plan: List<List<Double>>)

@Serializable
enum class Role {
    @SerialName("assembly") ASSEMBLY,
    @SerialName("packing") PACKING,
    @SerialName("hold") HOLD,
    @SerialName("checkout") CHECKOUT,
    @SerialName("customer") CUSTOMER,
}

@Serializable
data class Zone(
    val name: String,
    val camera: String,
    val role: Role,
    /** Polygon in image pixels; null means the whole camera view. */
    val poly: List<List<Double>>? = null,
    /** For a customer zone: the checkout zone it belongs to. */
    @SerialName("for") val forZone: String? = null,
)

@Serializable
data class Link(val from: String, val to: String, @SerialName("travel_s") val travelS: List<Double>)

@Serializable
data class Classes(
    val base: List<String>,
    @SerialName("open_container") val openContainer: List<String> = emptyList(),
    @SerialName("closed_container") val closedContainer: List<String> = emptyList(),
    val person: List<String> = listOf("person"),
)

@Serializable
data class AssemblyCfg(
    /** `in_container`: the base is the container (bowl); `packed_after`: food goes into a container in a packing zone. */
    val mode: String = "packed_after",
    /** `leaves_zone` (default), `packed` or `tap`. */
    val done: String = "leaves_zone",
)

/** Thresholds, all in seconds or fractions so they carry across sites. Defaults from the pizza clip. */
@Serializable
data class Params(
    // tracker
    val trackHigh: Double = 0.5,
    val trackLow: Double = 0.2,
    val trackMatchIou: Double = 0.3,
    val trackMaxLostS: Double = 2.0,
    val trackMinHits: Int = 2,
    /** Fallback match for fast boxes: centre distance up to this many box sizes. */
    val trackMaxJump: Double = 1.0,
    // assembly: bases kept by position
    val baseIou: Double = 0.3,
    val baseMinS: Double = 2.0,
    val baseGoneS: Double = 10.0,
    /**
     * A base moved too fast to overlap its last box (IoU < baseIou) still continues if it was seen within this time
     * and its centre moved at most trackMaxJump box sizes (a dish slid along the line between two frames).
     */
    val baseJumpS: Double = 0.6,
    /** A base reappearing where a finished, not yet packed dish was, within this time, resumes that dish. */
    val baseResumeS: Double = 30.0,
    // ingredient checks
    val checkEveryS: Double = 3.0,
    /**
     * Check only when the detection is confident (hands not covering): conf >= min(checkMinConf, checkRelConf x the
     * base's best so far). Relative, because confidence depends on resolution and site. checkRelConf = 0: absolute only.
     */
    val checkMinConf: Double = 0.8,
    val checkRelConf: Double = 0.5,
    /** The base must not have moved for this long before a check (0 = off). */
    val checkStillS: Double = 1.0,
    val checkStillShift: Double = 0.05,
    val ingredientP: Double = 0.6,
    val ingredientVotes: Int = 3,
    val cropSide: Int = 384,
    val cropPad: Double = 0.08,
    // packing
    val packGapS: Double = 3.0,
    val packMinS: Double = 1.0,
    // checkout (occupancy fallback when closed containers aren't detected)
    val occWindowS: Double = 3.0,
    val occDrop: Double = 0.15,
    val customerWindowS: Double = 5.0,
    val customerMinS: Double = 2.0,
    val customerGoneS: Double = 6.0,
    // transit
    val defaultTravelMaxS: Double = 600.0,
    // custody (spec §4.2)
    /** A lost box that reappears here (IoU, or centre distance in box sizes) is the same box ("parked"). */
    val parkIou: Double = 0.3,
    val parkDist: Double = 0.5,
    /** A put-down box must be within the carrier's box grown by this fraction. */
    val carryGrow: Double = 0.25,
    /** A unit missing from the checkout zone this long, with a customer around, was handed over. */
    val handoverS: Double = 3.0,
    /** Shared map: boxes seen by two cameras within this distance (metres) and time (s) are the same box. */
    val mapTolM: Double = 0.5,
    val mapTolS: Double = 1.0,
    /** Track history kept for matching a box to where a unit was when it went missing. */
    val historyS: Double = 10.0,
    /** Track referee (spec §4.5): off until it beats "uncertain" on a staged swap clip. */
    val referee: Boolean = false,
    /** The referee's best assignment must beat the next best by this much (sum of probabilities). */
    val refereeMargin: Double = 0.3,
    /** Two bound boxes this close (centre distance, in box sizes) with one person touching both may have swapped tracks. */
    val refereeClose: Double = 0.8,
)

@Serializable
data class Menu(val ingredients: List<Ingredient>, val recipes: List<Recipe>)

@Serializable
data class Ingredient(
    val name: String,
    val look: String,
    @SerialName("confusable_with") val confusableWith: String? = null,
    @SerialName("ref_image") val refImage: String? = null,
)

@Serializable
data class Recipe(val name: String, val ingredients: List<String>)

@Serializable
data class Order(
    val id: String,
    val items: List<OrderItem>,
    /** When the order came in (stream seconds); orders are matched oldest first on ties. */
    val t: Double = 0.0,
    val channel: String? = null,
)

/** One dish of an order. Modifiers: "no X" removes X from the expected ingredients, "+X" adds it. */
@Serializable
data class OrderItem(val recipe: String, val modifiers: List<String> = emptyList())

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

fun loadSite(f: File): Site = json.decodeFromString(f.readText())
fun loadMenu(f: File): Menu = json.decodeFromString(f.readText())
fun loadOrders(f: File): List<Order> = json.decodeFromString(f.readText())
