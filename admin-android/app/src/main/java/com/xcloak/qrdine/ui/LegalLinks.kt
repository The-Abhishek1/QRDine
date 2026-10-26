package com.xcloak.qrdine.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

private const val LEGAL_BASE_URL = "https://qr-dine-admin.vercel.app"

fun openLegalPage(context: Context, page: String) {
    require(page == "privacy" || page == "terms")
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$LEGAL_BASE_URL/$page")))
}
