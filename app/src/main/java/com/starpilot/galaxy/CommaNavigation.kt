package com.starpilot.galaxy

import java.io.IOException
import kotlin.math.abs

/** Sets comma's destination and starts the route: read the status, turn navigation on if needed, then select the place. */
object CommaNavigation {
    const val STATUS = "/api/navigation/status"
    const val ACTION = "/api/navigation/action"

    data class State(val code: Int, val revision: String = "", val enabled: Boolean = false, val hasKey: Boolean = false,
                     val latitude: Double? = null, val longitude: Double? = null, val error: String? = null,
                     val sharedName: String? = null, val query: Boolean = false) {
        fun at(place: SharedDestination.Place) = latitude != null && longitude != null &&
            abs(latitude - place.latitude) < 1e-5 && abs(longitude - place.longitude) < 1e-5
    }
    interface Api {
        fun status(): State
        fun action(body: String): State
    }
    /** Galaxy answered and said no; another route to comma would get the same answer. */
    class Refused(message: String) : Exception(message)
    /** This route to comma needs a new sign-in. */
    class SignIn : IOException("Sign in to Galaxy again")
    sealed class Outcome {
        data class Started(val name: String) : Outcome()
        object NeedsSearch : Outcome()
        data class Refused(val message: String) : Outcome()
    }
    const val OLD_COMMA = "Invalid navigation action"

    /** comma finds the place in the shared text and starts the route; an older comma gets the place parsed here. */
    fun share(api: Api, text: String): Outcome {
        try {
            var state = checked(api.status())
            repeat(2) {
                val reply = api.action(shareRequest(state.revision, text))
                when {
                    reply.code == 409 -> state = checked(api.status())
                    reply.code == 400 && reply.query -> return Outcome.NeedsSearch
                    reply.code == 400 && reply.error == OLD_COMMA -> return when (val place = SharedDestination.parse(text)) {
                        is SharedDestination.Place -> { start(api, place); Outcome.Started(place.name) }
                        is SharedDestination.Search -> Outcome.NeedsSearch
                    }
                    else -> return Outcome.Started(checked(reply).sharedName?.trim()?.ifEmpty { null } ?: "Shared destination")
                }
            }
        } catch (error: Refused) { return Outcome.Refused(error.message ?: "Galaxy refused the destination") }
        throw IOException("Navigation kept changing on StarPilot")
    }

    fun start(api: Api, place: SharedDestination.Place) {
        var state = checked(api.status())
        if (!state.hasKey) throw Refused("Add a Mapbox token in Galaxy navigation first")
        if (state.enabled && state.at(place)) return
        if (!state.enabled) state = checked(api.action(configure(state.revision)))
        repeat(2) {
            val reply = api.action(select(state.revision, place))
            if (reply.code != 409) {
                if (!checked(reply).at(place)) throw IOException("StarPilot did not confirm the destination")
                return
            }
            // Another phone or Galaxy changed navigation; a select that already landed is not sent twice.
            state = checked(api.status())
            if (state.enabled && state.at(place)) return
        }
        throw IOException("Navigation kept changing on StarPilot")
    }

    private fun checked(state: State): State = when (state.code) {
        200 -> state
        401 -> throw SignIn()
        400 -> throw Refused(state.error ?: "Galaxy refused the destination")
        else -> throw IOException(state.error ?: "Galaxy returned HTTP ${state.code}")
    }

    fun shareRequest(revision: String, text: String) = """{"action":"share","revision":${quote(revision)},"text":${quote(text.take(SharedDestination.MAX_TEXT))}}"""
    fun configure(revision: String) = """{"action":"configure","revision":${quote(revision)},"patch":{"enabled":true}}"""
    fun select(revision: String, place: SharedDestination.Place) = """{"action":"select","revision":${quote(revision)},"destination":""" +
        """{"name":${quote(place.name.trim().take(256).ifEmpty { "Shared destination" })},"latitude":${place.latitude},"longitude":${place.longitude}}}"""

    fun quote(value: String) = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
