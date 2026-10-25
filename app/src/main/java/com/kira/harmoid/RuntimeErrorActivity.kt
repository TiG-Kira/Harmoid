package com.kira.harmoid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.kira.harmoid.logging.LogWriter
import kotlin.system.exitProcess

/** Error UI lives in the launcher process and never enters StageActivity/JNI. */
class RuntimeErrorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_init_failed_title)
            .setMessage(getString(R.string.dialog_init_failed_msg,
                intent.getStringExtra(EXTRA_ERROR) ?: "unknown"))
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_ERROR = "runtime_error"

        /** A failed Stage runtime cannot safely run any further Activity callbacks. */
        fun abortWorker(context: Context, message: String, cause: Throwable? = null): Nothing {
            try {
                LogWriter.e("Harmoid.Runtime", message, cause)
                context.startActivity(Intent(context, RuntimeErrorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_ERROR, message.take(2000))
                })
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }
}
