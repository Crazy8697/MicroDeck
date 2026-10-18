package net.darkrelay.microdeck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapGateTest {

    @Test fun readyOnlyWithCameraSessionAndFrame() {
        assertTrue(SnapGate.ready(cameraOpen = true, sessionOpen = true, frameSeen = true, permissionDenied = false))
    }

    @Test fun noFrameYetIsNotReady() {
        // the black-snapshot case: camera and session up, nothing drawn yet
        assertFalse(SnapGate.ready(cameraOpen = true, sessionOpen = true, frameSeen = false, permissionDenied = false))
    }

    @Test fun noCameraOrSessionIsNotReady() {
        assertFalse(SnapGate.ready(cameraOpen = false, sessionOpen = true, frameSeen = true, permissionDenied = false))
        assertFalse(SnapGate.ready(cameraOpen = true, sessionOpen = false, frameSeen = true, permissionDenied = false))
        assertFalse(SnapGate.ready(cameraOpen = false, sessionOpen = false, frameSeen = false, permissionDenied = false))
    }

    @Test fun deniedPermissionIsNeverReady() {
        assertFalse(SnapGate.ready(cameraOpen = true, sessionOpen = true, frameSeen = true, permissionDenied = true))
        assertFalse(SnapGate.ready(cameraOpen = false, sessionOpen = false, frameSeen = false, permissionDenied = true))
    }

    @Test fun lockDisablesTheButtonEvenWhenReady() {
        assertTrue(SnapGate.buttonEnabled(ready = true, locked = false))
        assertFalse(SnapGate.buttonEnabled(ready = true, locked = true))
        assertFalse(SnapGate.buttonEnabled(ready = false, locked = false))
        assertFalse(SnapGate.buttonEnabled(ready = false, locked = true))
    }
}
