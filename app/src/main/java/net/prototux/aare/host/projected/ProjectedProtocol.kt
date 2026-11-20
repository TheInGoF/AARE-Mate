package net.prototux.aare.host.projected

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Parcel
import android.view.Surface

/**
 * Constants and parcel builders for the private `com.google.android.gms.car.ICarProjection`
 * protocol that Google Maps' car activity speaks. The host ([ProjectedHost]) binds the app's
 * exported projection service, then drives it with these Binder transactions.
 *
 * The protocol is undocumented; the transaction numbers, interface descriptors and the
 * SafeParcelable field layouts below are the observable wire format of the service.
 */
internal object ProjectedProtocol {

    const val PROJECTION = "com.google.android.gms.car.ICarProjection"
    const val CAR = "com.google.android.gms.car.ICar"
    const val CALLBACK = "com.google.android.gms.car.ICarProjectionCallback"

    // ICarProjection transactions the host sends.
    const val TX_ON_SETUP = 1
    const val TX_ON_NEW_INTENT = 3
    const val TX_ON_PROJECTION_RESUME = 4
    const val TX_ON_CONFIGURATION_CHANGED = 6
    const val TX_ON_PROJECTION_PAUSE = 5
    const val TX_ON_MOTION_EVENT = 7
    const val TX_ON_KEY_EVENT = 8
    const val TX_ON_PROJECTION_STOP = 9
    const val TX_ON_INPUT_FOCUS_CHANGE = 18
    const val TX_ON_PROJECTION_START = 26

    // ICar transactions the app asks of the host.
    const val TX_CAR_GET_CAR_INFO = 1
    const val TX_CAR_GET_CAR_UI_INFO = 2
    const val TX_CAR_GET_DISPLAY_MANAGER = 77

    // ICarDisplayManager / ICarDisplay.
    const val TX_DISPLAY_MANAGER_GET_DISPLAY = 3
    const val TX_DISPLAY_GET_CAR_UI_INFO = 3

    /** The main (and only) projected display and region the host offers. */
    const val DISPLAY_ID = 0
    const val REGION_ID = 0

    /**
     * Builds the `onProjectionStart` (tx 26) parcel: display id, region id, then a present-flagged
     * [DrawingSpec] carrying the render [surface], and the trailing present flags the modern
     * signature expects: an empty intent, the [config] the app lays itself out with (day or night is its
     * `uiMode`), extras (empty).
     */
    fun writeProjectionStart(data: Parcel, surface: Surface, width: Int, height: Int, densityDpi: Int, config: Configuration, insetBottom: Int = 0) {
        data.writeInterfaceToken(PROJECTION)
        data.writeInt(DISPLAY_ID)
        data.writeInt(REGION_ID)
        data.writeInt(1) // DrawingSpec present
        writeDrawingSpec(data, surface, width, height, densityDpi, insetBottom)
        // each written in full: a "present" flag with nothing behind it makes the app read the next item as this one
        data.writeInt(1); android.content.Intent().writeToParcel(data, 0)
        data.writeInt(1); config.writeToParcel(data, 0)
        data.writeInt(1); android.os.Bundle().writeToParcel(data, 0)
    }

    /**
     * `onConfigurationChanged` (tx 6): display id, what changed (`ActivityInfo.CONFIG_*` bits, like an
     * activity's `configChanges`), the drawing spec (unchanged) and the new [config]. Maps puts the new
     * `uiMode` into its resources and switches between its day and night map.
     */
    fun writeConfigurationChanged(data: Parcel, surface: Surface, width: Int, height: Int, densityDpi: Int, config: Configuration, insetBottom: Int = 0) {
        data.writeInterfaceToken(PROJECTION)
        data.writeInt(DISPLAY_ID)
        data.writeInt(android.content.pm.ActivityInfo.CONFIG_UI_MODE)
        data.writeInt(1); writeDrawingSpec(data, surface, width, height, densityDpi, insetBottom)
        data.writeInt(1); config.writeToParcel(data, 0)
    }

    /** The phone's configuration as a car head unit shows it: car mode, and day or night. */
    fun carConfiguration(base: Configuration, night: Boolean): Configuration = Configuration(base).apply {
        uiMode = Configuration.UI_MODE_TYPE_CAR or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

    /**
     * `com.google.android.gms.car.DrawingSpec`: the surface and geometry the app renders into.
     * Fields: 1 width, 2 height, 3 density dpi, 4 Surface, 5 inset Rect (left, top, right, bottom), 6 flags,
     * 7 display id, 8 extras Bundle (omitted = null).
     */
    fun writeDrawingSpec(p: Parcel, surface: Surface, width: Int, height: Int, densityDpi: Int, insetBottom: Int = 0) {
        val start = SafeParcel.begin(p)
        SafeParcel.intField(p, 1, width)
        SafeParcel.intField(p, 2, height)
        SafeParcel.intField(p, 3, densityDpi)
        SafeParcel.parcelableField(p, 4, surface)
        // insets, not bounds: how much of each edge the app must keep clear. With the full size here Maps
        // laid its whole content out at 0 x 0 and showed an empty background.
        // O101: the map reaches the bottom edge, the app keeps its buttons above the shell's controls there
        SafeParcel.parcelableField(p, 5, Rect(0, 0, 0, insetBottom))
        SafeParcel.intField(p, 6, 0)
        SafeParcel.intField(p, 7, DISPLAY_ID)
        SafeParcel.end(p, start)
    }

    /**
     * `com.google.android.gms.car.CarInfo`: the vehicle description the app reads before drawing.
     * No real car is attached, so this is a consistent placeholder identifying AARE. The app mostly
     * uses the make/model strings and the year/type ints; the rest are left empty or zero.
     */
    fun writeCarInfo(p: Parcel) {
        val start = SafeParcel.begin(p)
        SafeParcel.stringField(p, 1, "AARE")       // make
        SafeParcel.stringField(p, 2, "Projected")  // model
        SafeParcel.stringField(p, 3, "AARE")       // model year label
        SafeParcel.stringField(p, 4, "AARE")       // head unit make
        SafeParcel.intField(p, 5, 2026)            // model year
        SafeParcel.intField(p, 6, 0)
        SafeParcel.intField(p, 7, 0)
        SafeParcel.intField(p, 8, 0)
        for (id in 9..12) SafeParcel.stringField(p, id, "")
        for (id in 13..15) SafeParcel.intField(p, id, 0)
        SafeParcel.stringField(p, 16, "")
        SafeParcel.stringField(p, 17, "")
        SafeParcel.intField(p, 18, 1)              // head unit / projection capable
        SafeParcel.end(p, start)
    }

    /**
     * `com.google.android.gms.car.CarUiInfo`: input capabilities of the car UI. A minimal,
     * internally consistent instance — no rotary controller, no touchpad, no special insets.
     * Fields 1-10 are booleans/ints left at 0; field 5 (an int[]) is omitted, i.e. null, which is
     * safe because the app checks the touchscreen flag before reading the array.
     */
    /** CarUiInfo fields reported as "yes" (debug builds can change it with debug_ui_info for finding the layout). */
    @Volatile var UI_INFO_ON: Set<Int> = setOf(2)

    fun writeCarUiInfo(p: Parcel) {
        val start = SafeParcel.begin(p)
        for (id in 1..10) {
            if (id == 5) continue
            // 2 = has a touchscreen (O97: with all zeros Maps hid its search and hung when going back)
            SafeParcel.intField(p, id, if (id in UI_INFO_ON) 1 else 0)
        }
        SafeParcel.end(p, start)
    }
}
