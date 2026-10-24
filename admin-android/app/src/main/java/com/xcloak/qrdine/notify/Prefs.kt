package com.xcloak.qrdine.notify

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("qrdine_prefs", Context.MODE_PRIVATE)
    fun openForOrders(c: Context) = sp(c).getBoolean("open_for_orders", false)
    fun setOpenForOrders(c: Context, v: Boolean) = sp(c).edit().putBoolean("open_for_orders", v).apply()

    // this device
    fun kitchenMode(c: Context) = sp(c).getBoolean("kitchen_mode", false)
    fun setKitchenMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("kitchen_mode", v).apply()
    fun dismissedBroadcast(c: Context) = sp(c).getLong("dismissed_broadcast", 0L)
    fun setDismissedBroadcast(c: Context, id: Long) = sp(c).edit().putLong("dismissed_broadcast", id).apply()

    // Bluetooth thermal printer
    fun printerAddress(c: Context): String? = sp(c).getString("printer_address", null)
    fun printerName(c: Context): String? = sp(c).getString("printer_name", null)
    fun setPrinter(c: Context, address: String?, name: String?) = sp(c).edit().putString("printer_address", address).putString("printer_name", name).apply()
    fun paperChars(c: Context) = sp(c).getInt("paper_chars", 32)
    fun setPaperChars(c: Context, n: Int) = sp(c).edit().putInt("paper_chars", n).apply()
    fun autoKot(c: Context) = sp(c).getBoolean("auto_kot", false)
    fun setAutoKot(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_kot", v).apply()
}
