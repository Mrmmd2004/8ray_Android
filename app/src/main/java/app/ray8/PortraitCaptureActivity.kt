package app.ray8

import com.journeyapps.barcodescanner.CaptureActivity

/** ZXing's scanner is landscape by default; this subclass is declared portrait in the manifest. */
class PortraitCaptureActivity : CaptureActivity()
