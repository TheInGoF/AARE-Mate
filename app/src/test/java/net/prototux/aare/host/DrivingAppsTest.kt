package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** System L-114 (Q-069 "A"): only the apps the driver allowed for the drive stay usable while the car moves. */
class DrivingAppsTest {
    @Test fun claudeOnly() {
        assertTrue(DrivingApps.allowed("com.anthropic.claude"))
        assertFalse(DrivingApps.allowed("com.google.android.youtube"))
        assertFalse(DrivingApps.allowed("org.schabi.newpipe"))
    }
}
