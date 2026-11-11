package com.jarvis.assistant.router

import com.jarvis.assistant.ai.AiMode

enum class VolumeChange { UP, DOWN, MUTE, UNMUTE, SET }
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }
enum class QueryKind { TIME, DATE, BATTERY }
enum class RuntimeAction { DOWNLOAD, STATUS }

/** Things Jarvis can do straight away, without asking a language model. */
sealed interface FastCommand {
    data class OpenApp(val query: String) : FastCommand

    /** key is one of: back, home, recents, notifications, quick_settings. */
    data class Press(val key: String) : FastCommand

    /** direction is one of: up, down, left, right. */
    data class Scroll(val direction: String) : FastCommand
    data class Volume(val change: VolumeChange, val percent: Int? = null) : FastCommand

    /** on = null means toggle. */
    data class Flashlight(val on: Boolean?) : FastCommand
    data class Media(val action: MediaAction) : FastCommand
    data class WebSearch(val query: String, val site: String = "google") : FastCommand

    /** "open YouTube and play Despacito": search in the YouTube app, pick the first video, check that it plays. */
    data class PlayYouTube(val query: String) : FastCommand
    data class OpenSettings(val page: String, val toggleRequested: Boolean = false) : FastCommand
    data object OpenCamera : FastCommand
    data class SetAlarm(val hour: Int, val minute: Int) : FastCommand
    data class SetTimer(val seconds: Int) : FastCommand
    data class Dial(val number: String) : FastCommand

    /** "call Rahul": the name is looked up in the contacts, never guessed between two people. */
    data class CallContact(val name: String) : FastCommand
    data class TapText(val text: String) : FastCommand
    data class TypeText(val text: String) : FastCommand
    data object Screenshot : FastCommand
    data object LockScreen : FastCommand
    data class Ask(val kind: QueryKind) : FastCommand
    data class SetMode(val mode: AiMode) : FastCommand
    data class SetLanguage(val lang: String) : FastCommand

    /** "download the offline model" / "is the local model running". */
    data class RuntimeControl(val action: RuntimeAction) : FastCommand
}

/** Commands about Jarvis's own memory. Only ever created from what the user said, never from model or screen text. */
sealed interface MemoryIntent {
    /** "When I say [phrase], open [target]." */
    data class TeachApp(val phrase: String, val target: String) : MemoryIntent

    /** "When I say [phrase], I mean [meaning]": how this user sounds, applied to everything they say afterwards. */
    data class TeachPhrase(val phrase: String, val meaning: String) : MemoryIntent

    /** [style] is true for preferences about how to answer (short answers, language). */
    data class Remember(val text: String, val style: Boolean) : MemoryIntent
    data class Forget(val what: String) : MemoryIntent
    data object ListAll : MemoryIntent
}
