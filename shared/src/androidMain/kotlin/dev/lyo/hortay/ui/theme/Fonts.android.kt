package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import dev.lyo.hortay.R

private val Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = GoogleFont("Inter")
private val PlusJakartaSans = GoogleFont("Plus Jakarta Sans")

actual val DisplayFontFamily: FontFamily = FontFamily(
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
)

actual val BodyFontFamily: FontFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.SemiBold),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Bold),
)
