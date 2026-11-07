package app.djungle.backbeat.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PedalTest {
    @Test
    fun aPageTurnerStepsThroughTheTapes() {
        assertEquals(Command.NEXT, Pedal.command(KeyEvent.KEYCODE_PAGE_DOWN, typing = false))
        assertEquals(Command.PREVIOUS, Pedal.command(KeyEvent.KEYCODE_PAGE_UP, typing = false))
        assertEquals(Command.NEXT, Pedal.command(KeyEvent.KEYCODE_DPAD_RIGHT, typing = false))
    }

    @Test
    fun aHeadsetButtonStartsAndStops() {
        assertEquals(Command.START_STOP, Pedal.command(KeyEvent.KEYCODE_HEADSETHOOK, typing = false))
        assertEquals(Command.START_STOP, Pedal.command(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, typing = true))
    }

    @Test
    fun writingALabelKeepsItsKeys() {
        assertEquals(Command.START_STOP, Pedal.command(KeyEvent.KEYCODE_SPACE, typing = false))
        assertNull(Pedal.command(KeyEvent.KEYCODE_SPACE, typing = true))
        assertNull(Pedal.command(KeyEvent.KEYCODE_T, typing = true))
        assertNull(Pedal.command(KeyEvent.KEYCODE_DPAD_LEFT, typing = true))
        // A pedal's page keys type nothing, so they still work.
        assertEquals(Command.NEXT, Pedal.command(KeyEvent.KEYCODE_PAGE_DOWN, typing = true))
    }

    @Test
    fun otherKeysAreLeftAlone() {
        assertNull(Pedal.command(KeyEvent.KEYCODE_VOLUME_UP, typing = false))
        assertNull(Pedal.command(KeyEvent.KEYCODE_BACK, typing = false))
    }
}
