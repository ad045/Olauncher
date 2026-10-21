package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import app.olauncher.R
import app.olauncher.data.BlockerConfig
import app.olauncher.data.Prefs
import app.olauncher.databinding.ActivityReflectBinding
import app.olauncher.helper.MyAccessibilityService
import app.olauncher.helper.blocker.ExcuseLog
import app.olauncher.helper.showToast

/**
 * The screen that stands between you and something you decided to stay out of.
 *
 * [MODE_ENTRY] - first contact tonight. Two gates, both required: the ten seconds have to
 * have passed AND a reason of at least [BlockerConfig.MIN_REASON_CHARS] characters has to be
 * typed. Buys one allowance.
 *
 * [MODE_ALLOWANCE_OVER] - the allowance ran out. Asks whether the time was well spent, then
 * offers three ways out. Teleporting and stopping are deliberately not gated behind writing
 * an answer; only asking for five more minutes is.
 *
 * Either way the answer is appended to the [ExcuseLog].
 */
class ReflectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReflectBinding
    private lateinit var prefs: Prefs
    private lateinit var excuseLog: ExcuseLog
    private lateinit var key: String
    private lateinit var targetPackage: String

    private var mode = MODE_ENTRY
    private var minutesUsed: Int? = null
    private var waited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReflectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        excuseLog = ExcuseLog(this)
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        mode = intent.getIntExtra(EXTRA_MODE, MODE_ENTRY)
        minutesUsed = intent.getIntExtra(EXTRA_MINUTES_USED, -1).takeIf { it >= 0 }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        binding.reason.doAfterTextChanged { refreshGates() }
        if (mode == MODE_ALLOWANCE_OVER) setupAllowanceOver() else setupEntry()
        refreshGates()
    }

    private fun setupEntry() {
        binding.title.setText(R.string.reflect_title)
        binding.body.setText(R.string.reflect_body)
        binding.question.setText(R.string.reflect_question)
        binding.open.text = getString(R.string.reflect_open, BlockerConfig.UNLOCK_MINUTES)
        binding.open.setOnClickListener { grantAllowance(ExcuseLog.KIND_OPEN, ExcuseLog.ACTION_OPEN) }
        tick(BlockerConfig.REFLECT_DELAY_MS)
    }

    private fun setupAllowanceOver() {
        binding.open.visibility = View.GONE
        binding.endActions.visibility = View.VISIBLE
        waited = true

        binding.title.text = getString(R.string.reflect_over_title, minutesUsed ?: BlockerConfig.UNLOCK_MINUTES)
        binding.body.text = getString(R.string.reflect_over_body, unlockOrdinal(), appLabel())
        binding.question.setText(R.string.reflect_over_question)
        binding.continueFive.text = getString(R.string.reflect_continue, BlockerConfig.UNLOCK_MINUTES)

        val teleportName = prefs.blockerTeleportName
        if (prefs.blockerTeleportPackage.isEmpty()) {
            binding.teleport.visibility = View.GONE
        } else {
            binding.teleport.text = getString(R.string.reflect_teleport, teleportName)
            binding.teleport.setOnClickListener { teleport() }
        }

        binding.stopPhone.setOnClickListener { stopAndLock() }
        binding.continueFive.setOnClickListener {
            grantAllowance(ExcuseLog.KIND_SESSION_END, ExcuseLog.ACTION_CONTINUE)
        }
    }

    /** Counts the entry delay down a second at a time so the screen does not look stuck. */
    private fun tick(remainingMs: Long) {
        if (remainingMs <= 0L) {
            waited = true
            refreshGates()
            return
        }
        binding.hint.text = getString(R.string.reflect_wait, (remainingMs / 1000L).toInt())
        binding.hint.postDelayed({ tick(remainingMs - 1000L) }, 1000L)
    }

    private fun refreshGates() {
        val answered = binding.reason.text.trim().length >= BlockerConfig.MIN_REASON_CHARS
        val through = waited && answered

        binding.open.isEnabled = through
        binding.continueFive.isEnabled = through

        if (waited) binding.hint.text = if (answered) "" else getString(R.string.reflect_write_more)
    }

    private fun log(kind: String, action: String, unlockNumber: Int) {
        excuseLog.append(
            app = appLabel(),
            kind = kind,
            unlockNumber = unlockNumber,
            minutesUsed = minutesUsed,
            action = action,
            answer = binding.reason.text.toString(),
        )
    }

    private fun grantAllowance(kind: String, action: String) {
        log(kind, action, prefs.blockerUnlockCount(key) + 1)
        val now = System.currentTimeMillis()
        prefs.blockerGrantUnlock(key, now, now + BlockerConfig.UNLOCK_MINUTES * 60_000L)
        finish()
    }

    private fun teleport() {
        log(ExcuseLog.KIND_SESSION_END, ExcuseLog.ACTION_TELEPORT, prefs.blockerUnlockCount(key))
        val launch = packageManager.getLaunchIntentForPackage(prefs.blockerTeleportPackage)
        if (launch == null) {
            showToast(getString(R.string.reflect_teleport_failed))
            goHome()
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /**
     * Leaves the app that was open and puts the phone to sleep. On Android 9 and up the
     * accessibility service does the locking, so no device admin is needed; below that it
     * falls back to device admin, which is the double-tap-to-lock setting.
     */
    /** Leaves the app that was open and puts the phone to sleep. */
    private fun stopAndLock() {
        log(ExcuseLog.KIND_SESSION_END, ExcuseLog.ACTION_STOP, prefs.blockerUnlockCount(key))

        if (MyAccessibilityService.lockScreen().not()) {
            // No accessibility service, or Android 8 and below: device admin is the other way in,
            // and it is the same permission behind the double-tap-to-lock setting.
            runCatching {
                val deviceManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                deviceManager.lockNow()
            }.onFailure { showToast(getString(R.string.please_turn_on_double_tap_to_unlock)) }
        }
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** "second", "third", … so the screen can say how often this has already happened tonight. */
    private fun unlockOrdinal(): String {
        val next = prefs.blockerUnlockCount(key) + 1
        val names = resources.getStringArray(R.array.reflect_ordinals)
        return names.getOrElse(next - 1) { getString(R.string.reflect_ordinal_nth, next) }
    }

    /** The app's own label, or the domain when a site was blocked rather than an app. */
    private fun appLabel(): String {
        if (key != targetPackage) return key
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        }.getOrDefault(targetPackage)
    }

    companion object {
        const val MODE_ENTRY = 0
        const val MODE_ALLOWANCE_OVER = 1

        private const val EXTRA_KEY = "blocker_key"
        private const val EXTRA_PACKAGE = "blocker_package"
        private const val EXTRA_MODE = "blocker_mode"
        private const val EXTRA_MINUTES_USED = "blocker_minutes_used"

        /**
         * @param key the unlock key - the package for an app, the domain for a site
         * @param targetPackage the package actually on screen, which is the browser for a site
         * @param minutesUsed minutes spent in the allowance that just ended, null in entry mode
         */
        fun intent(context: Context, key: String, targetPackage: String, mode: Int, minutesUsed: Int?): Intent =
            Intent(context, ReflectActivity::class.java)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_MINUTES_USED, minutesUsed ?: -1)
    }
}
