package app.alextran.immich.homelink

import com.journeyapps.barcodescanner.CaptureActivity

/** zxing's CaptureActivity is landscape by default; the phone is held upright when scanning. */
class PortraitCaptureActivity : CaptureActivity()
