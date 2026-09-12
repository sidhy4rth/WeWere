package com.rollapp.shared.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat

/** The Android share sheet, in the two shapes this app needs. */
object Sharing {

    fun shareInvite(context: Context, groupName: String, code: String, link: String) {
        val text = buildString {
            append("Join our \"")
            append(groupName)
            append("\" roll on WeWere 📸\n\n")
            append(link)
            append("\n\nOr enter the code in WeWere: ")
            append(code)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Join $groupName on WeWere")
        }
        context.startActivity(Intent.createChooser(intent, "Invite friends"))
    }

    fun sharePhoto(context: Context, uri: Uri, caption: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!caption.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            // The receiving app gets read access to this one uri and nothing else.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share photo"))
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
}
