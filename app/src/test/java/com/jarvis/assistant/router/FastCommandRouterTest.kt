package com.jarvis.assistant.router

import com.jarvis.assistant.ai.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastCommandRouterTest {
    private fun p(s: String) = FastCommandRouter.parse(s)

    @Test fun opensAppsInEnglish() {
        assertEquals(FastCommand.OpenApp("youtube"), p("open YouTube"))
        assertEquals(FastCommand.OpenApp("whatsapp"), p("Hey Jarvis, open WhatsApp."))
        assertEquals(FastCommand.OpenApp("chrome"), p("please launch the chrome app"))
        assertEquals(FastCommand.OpenApp("settings"), p("open settings"))
        assertEquals(FastCommand.OpenApp("play store"), p("can you open play store please"))
    }

    @Test fun opensAppsInHinglishAndHindi() {
        assertEquals(FastCommand.OpenApp("youtube"), p("YouTube kholo"))
        assertEquals(FastCommand.OpenApp("whatsapp"), p("WhatsApp khol do"))
        assertEquals(FastCommand.OpenApp("youtube"), p("यूट्यूब खोलो"))
        assertEquals(FastCommand.OpenApp("whatsapp"), p("व्हाट्सएप खोलो"))
        assertEquals(FastCommand.OpenApp("instagram"), p("open karo instagram"))
    }

    @Test fun navigationKeys() {
        assertEquals(FastCommand.Press("back"), p("go back"))
        assertEquals(FastCommand.Press("back"), p("back"))
        assertEquals(FastCommand.Press("back"), p("peeche jao"))
        assertEquals(FastCommand.Press("back"), p("वापस जाओ"))
        assertEquals(FastCommand.Press("home"), p("go home"))
        assertEquals(FastCommand.Press("home"), p("home screen"))
        assertEquals(FastCommand.Press("home"), p("होम"))
        assertEquals(FastCommand.Press("recents"), p("show recent apps"))
        assertEquals(FastCommand.Press("notifications"), p("open notifications"))
        assertEquals(FastCommand.Press("quick_settings"), p("quick settings"))
    }

    @Test fun scrolling() {
        assertEquals(FastCommand.Scroll("down"), p("scroll down"))
        assertEquals(FastCommand.Scroll("up"), p("scroll up"))
        assertEquals(FastCommand.Scroll("left"), p("swipe left"))
        assertEquals(FastCommand.Scroll("down"), p("neeche scroll karo"))
        assertEquals(FastCommand.Scroll("up"), p("upar scroll karo"))
        assertNull(p("scroll through my instagram feed and like the first five posts"))
    }

    @Test fun volume() {
        assertEquals(FastCommand.Volume(VolumeChange.UP), p("volume up"))
        assertEquals(FastCommand.Volume(VolumeChange.UP), p("increase the volume"))
        assertEquals(FastCommand.Volume(VolumeChange.DOWN), p("turn the volume down"))
        assertEquals(FastCommand.Volume(VolumeChange.SET, 40), p("set volume to 40"))
        assertEquals(FastCommand.Volume(VolumeChange.SET, 100), p("volume 250"))
        assertEquals(FastCommand.Volume(VolumeChange.MUTE), p("mute"))
        assertEquals(FastCommand.Volume(VolumeChange.UP), p("volume badhao"))
        assertEquals(FastCommand.Volume(VolumeChange.DOWN), p("awaaz kam karo"))
        assertEquals(FastCommand.Volume(VolumeChange.UP), p("वॉल्यूम बढ़ाओ"))
    }

    @Test fun flashlight() {
        assertEquals(FastCommand.Flashlight(true), p("turn on the flashlight"))
        assertEquals(FastCommand.Flashlight(false), p("flashlight off"))
        assertEquals(FastCommand.Flashlight(true), p("torch jalao"))
        assertEquals(FastCommand.Flashlight(true), p("टॉर्च चालू करो"))
        assertEquals(FastCommand.Flashlight(false), p("torch band karo"))
        assertEquals(FastCommand.Flashlight(null), p("torch"))
    }

    @Test fun media() {
        assertEquals(FastCommand.Media(MediaAction.PLAY), p("play music"))
        assertEquals(FastCommand.Media(MediaAction.PAUSE), p("pause the music"))
        assertEquals(FastCommand.Media(MediaAction.PAUSE), p("stop music"))
        assertEquals(FastCommand.Media(MediaAction.NEXT), p("next song"))
        assertEquals(FastCommand.Media(MediaAction.PREVIOUS), p("previous song"))
        assertEquals(FastCommand.Media(MediaAction.PLAY), p("gaana bajao"))
        assertEquals(FastCommand.Media(MediaAction.NEXT), p("agla gaana"))
        assertNull(p("play despacito"))
    }

    @Test fun questionsAnsweredLocally() {
        assertEquals(FastCommand.Ask(QueryKind.TIME), p("what time is it"))
        assertEquals(FastCommand.Ask(QueryKind.TIME), p("what's the time"))
        assertEquals(FastCommand.Ask(QueryKind.TIME), p("kitne baje hain"))
        assertEquals(FastCommand.Ask(QueryKind.DATE), p("what's the date"))
        assertEquals(FastCommand.Ask(QueryKind.BATTERY), p("battery level"))
        assertEquals(FastCommand.Ask(QueryKind.BATTERY), p("how much battery is left"))
    }

    @Test fun timersAndAlarms() {
        assertEquals(FastCommand.SetTimer(300), p("set a timer for 5 minutes"))
        assertEquals(FastCommand.SetTimer(300), p("5 minute ka timer"))
        assertEquals(FastCommand.SetTimer(3600 + 1800), p("timer for 1 hour 30 minutes"))
        assertEquals(FastCommand.SetAlarm(7, 0), p("set an alarm for 7 am"))
        assertEquals(FastCommand.SetAlarm(18, 30), p("set alarm for 6:30 pm"))
        assertEquals(FastCommand.SetAlarm(6, 0), p("wake me up at 6"))
        assertEquals(FastCommand.SetAlarm(6, 0), p("subah 6 baje alarm laga do"))
        assertEquals(FastCommand.SetAlarm(17, 0), p("shaam 5 baje alarm lagao"))
        assertEquals(FastCommand.SetAlarm(0, 0), p("alarm for 12 am"))
        assertEquals(FastCommand.SetAlarm(12, 0), p("alarm for 12 pm"))
        assertEquals(FastCommand.SetTimer(600), p("set alarm in 10 minutes"))
        assertNull(p("set an alarm"))
    }

    @Test fun dialOnlyWithRealNumbers() {
        assertEquals(FastCommand.Dial("9876543210"), p("call 9876543210"))
        assertEquals(FastCommand.Dial("9876543210"), p("9876543210 ko call karo"))
        assertNull(p("call 98765")) // too short to be a number, and a name cannot contain digits
    }

    @Test fun settingsPages() {
        assertEquals(FastCommand.OpenSettings("wifi"), p("open wifi settings"))
        assertEquals(FastCommand.OpenSettings("bluetooth"), p("bluetooth settings kholo"))
        assertEquals(FastCommand.OpenSettings("wifi", true), p("turn on wifi"))
        assertEquals(FastCommand.OpenSettings("bluetooth", true), p("bluetooth chalu karo"))
        assertEquals(FastCommand.OpenSettings("airplane", true), p("turn off airplane mode"))
    }

    @Test fun cameraScreenshotLock() {
        assertEquals(FastCommand.OpenCamera, p("open camera"))
        assertEquals(FastCommand.OpenCamera, p("कैमरा खोलो"))
        assertEquals(FastCommand.Screenshot, p("take a screenshot"))
        assertEquals(FastCommand.LockScreen, p("lock the phone"))
    }

    @Test fun searches() {
        assertEquals(FastCommand.WebSearch("pizza near me", "google"), p("search for pizza near me"))
        assertEquals(FastCommand.WebSearch("weather in delhi", "google"), p("google weather in delhi"))
        assertEquals(FastCommand.WebSearch("cats", "youtube"), p("search cats on youtube"))
        assertEquals(FastCommand.WebSearch("lofi music", "youtube"), p("youtube par lofi music search karo"))
        assertNull(p("search for songs on spotify"))
    }

    @Test fun typingKeepsOriginalCase() {
        assertEquals(FastCommand.TypeText("Hello World"), p("Type Hello World."))
        assertEquals(FastCommand.TypeText("see you at 5"), p("hey jarvis type see you at 5"))
    }

    @Test fun tapByLabel() {
        assertEquals(FastCommand.TapText("send"), p("tap send"))
        assertEquals(FastCommand.TapText("login"), p("click on the login button"))
        assertNull(p("tap it"))
    }

    @Test fun modeAndLanguageSwitches() {
        assertEquals(FastCommand.SetMode(AiMode.OFFLINE), p("offline mode"))
        assertEquals(FastCommand.SetMode(AiMode.ONLINE), p("go online"))
        assertEquals(FastCommand.SetMode(AiMode.AUTO), p("automatic mode"))
        assertNull(p("online"))
        assertEquals(FastCommand.SetLanguage("hi"), p("hindi mode"))
        assertEquals(FastCommand.SetLanguage("hi"), p("listen in hindi"))
        assertEquals(FastCommand.SetLanguage("en"), p("english mode"))
        assertNull(p("hindi"))
    }

    @Test fun playingOnYouTube() {
        assertEquals(FastCommand.PlayYouTube("despacito"), p("open YouTube and play Despacito"))
        assertEquals(FastCommand.PlayYouTube("lofi music"), p("play lofi music on youtube"))
        assertEquals(FastCommand.PlayYouTube("arijit singh songs"), p("youtube par arijit singh songs chalao"))
        assertEquals(FastCommand.PlayYouTube("kesariya"), p("kesariya youtube par bajao"))
        assertEquals(FastCommand.WebSearch("cats", "youtube"), p("search cats on youtube"))
        assertEquals(FastCommand.OpenApp("youtube"), p("open youtube"))
        assertNull(p("open youtube and play the first video that you see in the list of recommended items"))
    }

    @Test fun callingByName() {
        assertEquals(FastCommand.CallContact("mom"), p("call mom"))
        assertEquals(FastCommand.CallContact("rahul sharma"), p("Hey Jarvis, call Rahul Sharma"))
        assertEquals(FastCommand.CallContact("rahul"), p("rahul ko call karo"))
        assertEquals(FastCommand.CallContact("mummy"), p("mummy ko phone lagao"))
        assertEquals(FastCommand.CallContact("प्रिया"), p("प्रिया को कॉल करो"))
        assertEquals(FastCommand.Dial("9876543210"), p("call 9876543210"))
        assertNull(p("call me a taxi"))
        assertNull(p("call back"))
        assertNull(p("call"))
    }

    @Test fun runtimeCommands() {
        assertEquals(FastCommand.RuntimeControl(RuntimeAction.DOWNLOAD), p("download the offline model"))
        assertEquals(FastCommand.RuntimeControl(RuntimeAction.DOWNLOAD), p("set up the on-device model"))
        assertEquals(FastCommand.RuntimeControl(RuntimeAction.STATUS), p("is the local model running"))
        assertEquals(FastCommand.RuntimeControl(RuntimeAction.STATUS), p("offline model status"))
        assertNull(p("download a movie"))
    }

    @Test fun conversationIsNotACommand() {
        assertNull(p("what is the capital of france"))
        assertNull(p("tell me a joke"))
        assertNull(p("write me a poem about the sea"))
        assertNull(p("who are you"))
        assertNull(p("open youtube and then search for lofi music and play the first video that you see"))
        assertNull(p("open youtube and search for cats"))
        assertNull(p(""))
    }

    @Test fun unknownOpenTargetsStillParseSoTheyCanFallBackToTheAgent() {
        assertEquals(FastCommand.OpenApp("door"), p("open the door"))
    }

    @Test fun earlyAcceptOnlyForClosedCommands() {
        assertEquals(800L, FastCommandRouter.earlyAcceptDelayMs("open youtube"))
        assertEquals(600L, FastCommandRouter.earlyAcceptDelayMs("scroll down"))
        assertEquals(600L, FastCommandRouter.earlyAcceptDelayMs("go back"))
        assertNull(FastCommandRouter.earlyAcceptDelayMs("search for pizza"))
        assertNull(FastCommandRouter.earlyAcceptDelayMs("set an alarm for 7"))
        assertNull(FastCommandRouter.earlyAcceptDelayMs("open"))
    }
}
