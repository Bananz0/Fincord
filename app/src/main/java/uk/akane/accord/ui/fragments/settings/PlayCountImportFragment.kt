package uk.akane.accord.ui.fragments.settings

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.playcounts.ImportPlan
import org.akanework.gramophone.logic.data.playcounts.PlayCountImporter
import org.akanework.gramophone.logic.data.playcounts.PlayCountMatcher
import org.akanework.gramophone.logic.data.playcounts.PlayCountSource
import org.akanework.gramophone.logic.data.playcounts.ServerSideScrobbling
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.SettingsListBuilder
import java.text.NumberFormat

/**
 * Brings listening history from other services into Jellyfin's play counts.
 *
 * The counts go to the server, not into a local table, because that is where Accord already reads
 * them from and what the home feed is built on - so an import shows up in recommendations and on
 * every other client at once.
 *
 * Nothing is written without being shown first. The user is agreeing to a specific number of
 * changes to data they cannot inspect from here and cannot roll back, so the run is split in two:
 * read and plan, then - only on confirmation - write.
 */
class PlayCountImportFragment : Fragment() {

    private val mainActivity get() = requireActivity() as MainActivity
    private val importer by lazy { PlayCountImporter(requireContext().applicationContext) }
    private lateinit var builder: SettingsListBuilder
    private lateinit var statusRow: LinearLayout

    /** The run in flight, so leaving the screen does not leave a half-written import unattended. */
    private var running: Job? = null
    private var status: CharSequence? = null

    /**
     * The ledger, read once per visit rather than per row.
     *
     * Cached because building a row must not touch the database: this list is rebuilt on every
     * progress tick, and Room refuses main-thread reads anyway.
     */
    private var summaries: Map<PlayCountSource, PlayCountImporter.Summary> = emptyMap()

    /**
     * Sources the server already imports for itself, and must not be imported twice.
     *
     * Null for a source whose answer could not be established - listing plugins needs an
     * administrator - which is warned about rather than treated as safe.
     */
    private var serverHandled: Map<PlayCountSource, Boolean?> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_settings_child_list, container, false)

        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        navigationBar.setTitle(getString(R.string.settings_import_play_counts))
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                v.paddingLeft,
                maxOf(systemBars.top, cutout.top),
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        navigationBar.setOnReturnClickListener {
            mainActivity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        navigationBar.attach(
            rootView.findViewById<NestedScrollView>(R.id.scrollContainer),
            applyTopPadding = false,
        )
        navigationBar.post { navigationBar.resetToExpandedState() }

        statusRow = rootView.findViewById(R.id.settings_rows)
        builder = SettingsListBuilder(statusRow)
        return rootView
    }

    override fun onResume() {
        super.onResume()
        rebuild()
        lifecycleScope.launch {
            summaries = PlayCountSource.entries.associateWith { importer.summaryOf(it) }
            serverHandled = PlayCountSource.entries
                .associateWith { ServerSideScrobbling.handledByServer(it) }
            if (isAdded) rebuild()
        }
    }

    private fun rebuild() {
        builder.build(
            listOf(
                SettingsListBuilder.Section(
                    title = getString(R.string.import_section_sources),
                    rows = PlayCountSource.entries.map(::rowFor),
                    footer = status ?: getString(R.string.import_header),
                )
            )
        )
    }

    private fun rowFor(source: PlayCountSource) = SettingsListBuilder.Row.Navigation(
        title = getString(source.labelRes),
        summary = summaryFor(source),
    ) { onSourceTapped(source) }

    /**
     * What a source has already contributed, so a repeat import is an informed decision.
     *
     * Without this the screen would be four identical buttons with no way to tell which had been
     * run, and re-running is exactly the operation a user would be nervous about.
     */
    private fun summaryFor(source: PlayCountSource): CharSequence {
        if (source.kind == PlayCountSource.Kind.ARCHIVE) {
            return getString(source.descriptionRes)
        }
        if (serverHandled[source] == true) return getString(R.string.import_server_handles_it)
        val summary = summaries[source]
        if (summary == null || !summary.hasRun) return getString(source.descriptionRes)
        val relative = DateUtils.getRelativeTimeSpanString(
            summary.lastImportedAt!!,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        return getString(
            R.string.import_summary_done,
            NUMBERS.format(summary.plays),
            NUMBERS.format(summary.tracks),
            relative,
        )
    }

    private fun onSourceTapped(source: PlayCountSource) {
        if (running?.isActive == true) return
        if (source.kind == PlayCountSource.Kind.ARCHIVE) {
            showArchiveInstructions(source)
            return
        }
        when (serverHandled[source]) {
            true -> showServerConflict(source)
            null -> showServerUnknown(source)
            else -> if (summaries[source]?.hasRun == true) showRepeatOptions(source)
                    else start(source)
        }
    }

    /**
     * Explains what file to hand over before opening the picker.
     *
     * The archive has to be requested from the service and arrives days later, so a user tapping
     * this row for the first time has nothing to give and no way to know that from a file picker.
     * The instructions are the useful part of this screen for three of the four sources.
     */
    private fun showArchiveInstructions(source: PlayCountSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(source.labelRes))
            .setMessage(getString(archiveHelpFor(source)))
            .setPositiveButton(R.string.import_choose_file) { _, _ ->
                pendingArchiveSource = source
                // Deliberately wide. The picker filters by MIME type and these providers report
                // zips and JSON under half a dozen of them, several of them wrong; a narrow filter
                // greys out the very file the user was told to choose.
                archivePicker.launch(arrayOf("*/*"))
            }
            .setNegativeButton(R.string.import_preview_cancel, null)
            .show()
    }

    private fun archiveHelpFor(source: PlayCountSource): Int = when (source) {
        PlayCountSource.SPOTIFY -> R.string.import_help_spotify
        PlayCountSource.APPLE_MUSIC -> R.string.import_help_apple
        PlayCountSource.YOUTUBE_MUSIC -> R.string.import_help_ytmusic
        PlayCountSource.LAST_FM -> R.string.import_source_lastfm_summary
    }

    /**
     * Refuses an import the server is already doing.
     *
     * No override offered. This is not a preference: the two mechanisms write the same field from
     * the same history, and the ledger cannot tell the plugin's increases from real listening, so
     * running both produces counts that drift upward and cannot be repaired from here.
     */
    private fun showServerConflict(source: PlayCountSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(source.labelRes))
            .setMessage(getString(R.string.import_server_conflict, getString(source.labelRes)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Cannot see the server's plugins - usually not an administrator - so it says so and asks. */
    private fun showServerUnknown(source: PlayCountSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(source.labelRes))
            .setMessage(getString(R.string.import_server_unknown, getString(source.labelRes)))
            .setPositiveButton(R.string.import_server_unknown_continue) { _, _ ->
                if (summaries[source]?.hasRun == true) showRepeatOptions(source) else start(source)
            }
            .setNegativeButton(R.string.import_preview_cancel, null)
            .show()
    }

    private fun showRepeatOptions(source: PlayCountSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(source.labelRes))
            .setMessage(summaryFor(source))
            .setPositiveButton(R.string.settings_import_play_counts) { _, _ -> start(source) }
            .setNeutralButton(R.string.import_run_full) { _, _ -> start(source, full = true) }
            .setNegativeButton(R.string.import_forget) { _, _ -> confirmForget(source) }
            .show()
    }

    private fun confirmForget(source: PlayCountSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_forget)
            .setMessage(getString(R.string.import_forget_explain, getString(source.labelRes)))
            .setPositiveButton(R.string.import_forget_confirm) { _, _ ->
                lifecycleScope.launch {
                    importer.forget(source)
                    summaries = PlayCountSource.entries.associateWith { importer.summaryOf(it) }
                    rebuild()
                }
            }
            .setNegativeButton(R.string.import_preview_cancel, null)
            .show()
    }

    /**
     * The archive picker.
     *
     * A field because a launcher has to be registered before the fragment reaches RESUMED, which
     * rules out creating one when the row is tapped.
     */
    private val archivePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val source = pendingArchiveSource
        pendingArchiveSource = null
        if (uri != null && source != null) startArchive(source, uri)
    }

    private var pendingArchiveSource: PlayCountSource? = null

    private fun startArchive(source: PlayCountSource, uri: android.net.Uri) {
        running = lifecycleScope.launch {
            when (val result = importer.planArchive(source, uri) { showProgress(it) }) {
                is PlayCountImporter.PlanResult.Failed -> setStatus(describe(result.reason))
                is PlayCountImporter.PlanResult.Ready -> {
                    val plan = result.plan
                    if (plan.tracksChanged == 0) {
                        setStatus(getString(R.string.import_nothing_new))
                    } else {
                        setStatus(null)
                        showPreview(plan)
                    }
                }
            }
        }
    }

    private fun start(source: PlayCountSource, full: Boolean = false) {
        running = lifecycleScope.launch {
            val result = importer.plan(source, full) { progress -> showProgress(progress) }
            when (result) {
                is PlayCountImporter.PlanResult.Failed -> {
                    setStatus(describe(result.reason))
                }
                is PlayCountImporter.PlanResult.Ready -> {
                    val plan = result.plan
                    if (plan.tracksChanged == 0) {
                        setStatus(getString(R.string.import_nothing_new))
                    } else {
                        setStatus(null)
                        showPreview(plan)
                    }
                }
            }
        }
    }

    /**
     * The plan, before anything is written.
     *
     * Deliberately leads with what will change and what will not be touched. The unmatched count is
     * the number worth reacting to: a poor match rate usually means the library is tagged
     * differently from the service, which is fixable, and finding that out after the write would be
     * finding it out too late.
     */
    private fun showPreview(plan: ImportPlan) {
        val lines = buildList {
            add(getString(R.string.import_preview_matched, NUMBERS.format(plan.matched)))
            add(getString(R.string.import_preview_plays, NUMBERS.format(plan.playsAdded)))
            if (plan.deduped > 0) {
                add(getString(R.string.import_preview_deduped, NUMBERS.format(plan.deduped)))
            }
            if (plan.unmatchedPlays > 0) {
                add(
                    getString(
                        R.string.import_preview_unmatched,
                        NUMBERS.format(plan.unmatched.size),
                    )
                )
            }
            val loose = plan.changes.count {
                it.confidence == PlayCountMatcher.Confidence.TITLE_ONLY
            }
            if (loose > 0) {
                add(getString(R.string.import_preview_low_confidence, NUMBERS.format(loose)))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_preview_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.import_preview_apply) { _, _ -> apply(plan) }
            .setNegativeButton(R.string.import_preview_cancel, null)
            .show()
    }

    private fun apply(plan: ImportPlan) {
        running = lifecycleScope.launch {
            val result = importer.apply(plan) { progress -> showProgress(progress) }
            setStatus(
                when {
                    result.cancelled -> getString(R.string.import_cancelled, result.written)
                    result.failed > 0 ->
                        getString(R.string.import_done_partial, result.written, result.failed)
                    else -> getString(R.string.import_done, result.written)
                }
            )
            summaries = PlayCountSource.entries.associateWith { importer.summaryOf(it) }
            if (isAdded) rebuild()
            // The counts just changed on the server, and the cached library still holds the old
            // ones - so the home feed would keep ranking by yesterday's numbers until something
            // else forced a sync.
            mainActivity.updateLibrary()
        }
    }

    /**
     * Progress arrives on whichever thread is doing the work, which is never this one.
     *
     * The importer reads and writes on the IO dispatcher and reports from there, so touching views
     * directly here throws - and it throws inside a coroutine, which surfaces as a crash rather
     * than as a failed import. Hopped once, at the boundary, rather than asking every caller in the
     * importer to remember.
     */
    private fun showProgress(progress: PlayCountImporter.Progress) {
        lifecycleScope.launch(Dispatchers.Main.immediate) { applyProgress(progress) }
    }

    private fun applyProgress(progress: PlayCountImporter.Progress) {
        setStatus(
            when (progress) {
                is PlayCountImporter.Progress.Fetching ->
                    if (progress.total > 0) {
                        getString(
                            R.string.import_fetching,
                            NUMBERS.format(progress.fetched),
                            NUMBERS.format(progress.total),
                        )
                    } else {
                        getString(
                            R.string.import_fetching_unknown,
                            NUMBERS.format(progress.fetched),
                        )
                    }
                PlayCountImporter.Progress.Reading -> getString(R.string.import_reading)
                PlayCountImporter.Progress.Matching -> getString(R.string.import_matching)
                is PlayCountImporter.Progress.Writing ->
                    getString(R.string.import_writing, progress.written, progress.total)
            }
        )
    }

    private fun describe(reason: PlayCountImporter.Failure): CharSequence = when (reason) {
        PlayCountImporter.Failure.NotLinked -> getString(R.string.import_not_linked)
        PlayCountImporter.Failure.EmptyLibrary -> getString(R.string.import_library_empty)
        PlayCountImporter.Failure.UnrecognisedArchive ->
            getString(R.string.import_archive_unrecognised)
        is PlayCountImporter.Failure.UnreadableArchive ->
            getString(R.string.import_failed, reason.message)
        is PlayCountImporter.Failure.Network -> getString(R.string.import_failed, reason.message)
    }

    private fun setStatus(text: CharSequence?) {
        status = text
        if (isAdded) rebuild()
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private val NUMBERS: NumberFormat = NumberFormat.getIntegerInstance()
    }
}
