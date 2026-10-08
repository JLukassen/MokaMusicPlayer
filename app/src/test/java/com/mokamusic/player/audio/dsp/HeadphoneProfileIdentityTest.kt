package com.mokamusic.player.audio.dsp

import org.junit.Assert.*
import org.junit.Test

class HeadphoneProfileIdentityTest {
    @Test fun uniqueBluetoothDeviceProducesKey() {
        val key = HeadphoneProfileStore.identity("Bluetooth headphones", "Sony WH-1000XM5")
        assertEquals("bluetooth headphones|sony wh-1000xm5", key)
    }
    @Test fun genericOutputsAreNotAutomaticallyAssigned() {
        assertNull(HeadphoneProfileStore.identity("Bluetooth headphones", "Bluetooth headphones"))
        assertNull(HeadphoneProfileStore.identity("Phone speaker", "Phone speaker"))
        assertNull(HeadphoneProfileStore.identity("USB DAC", "Android audio"))
    }
    @Test fun namingIsCaseInsensitive() {
        assertEquals(
            HeadphoneProfileStore.identity("USB DAC", "DAC TYPE A"),
            HeadphoneProfileStore.identity("usb dac", "dac type a")
        )
    }
}
