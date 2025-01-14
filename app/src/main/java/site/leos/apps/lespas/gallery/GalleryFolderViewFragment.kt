/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.gallery

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.helper.LesPasFastScroller
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

class GalleryFolderViewFragment : Fragment(), ActionMode.Callback {
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var mediaList: RecyclerView
    private lateinit var subFolderChipGroup: ChipGroup
    private lateinit var chipForAll: Chip
    private lateinit var yearIndicator: TextView
    private var actionMode: ActionMode? = null
    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var selectionBackPressedCallback: OnBackPressedCallback
    private var spanCount = 0
    private lateinit var folderArgument: String

    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()

    private val currentMediaList = mutableListOf<GalleryFragment.LocalMedia>()

    private var stripExif = "2"
    private var currentCheckedTag = CHIP_FOR_ALL_TAG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        folderArgument = requireArguments().getString(ARGUMENT_FOLDER) ?: GalleryFragment.ALL_FOLDER

        mediaAdapter = MediaAdapter(
            { view, photoId, mimeType ->
                galleryModel.setCurrentPhotoId(photoId)

                if (mimeType.startsWith("video")) {
                    // Transition to surface view might crash some OEM phones, like Xiaomi
                    parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument, currentCheckedTag), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    reenterTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        excludeTarget(view, true)
                    }
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        excludeTarget(view, true)
                        excludeTarget(android.R.id.statusBarBackground, true)
                        excludeTarget(android.R.id.navigationBarBackground, true)
                    }

                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(view, view.transitionName)
                        .replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument, currentCheckedTag), GallerySlideFragment::class.java.canonicalName)
                        .addToBackStack(null)
                        .commit()
                }
            },
            { photo, imageView -> imageLoaderModel.setImagePhoto(photo, imageView, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            setMarks(galleryModel.getPlayMark(), galleryModel.getSelectedMark())
            setDateStrings(getString(R.string.today), getString(R.string.yesterday))
        }

        selectionBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, selectionBackPressedCallback)

        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaList.findViewHolderForAdapterPosition(mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()))?.let {
                    sharedElements?.put(names[0], it.itemView.findViewById(R.id.photo))
                }
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_gallery_folder, container, false)
    @SuppressLint("InflateParams")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        chipForAll = view.findViewById(R.id.chip_for_all)
        currentCheckedTag = galleryModel.getCurrentSubFolder()
        subFolderChipGroup = view.findViewById<ChipGroup?>(R.id.sub_chips).apply { if (folderArgument == GalleryFragment.TRASH_FOLDER) isVisible = false }

        yearIndicator = view.findViewById<TextView>(R.id.year_indicator).apply {
            doOnLayout {
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_error))
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setTopLeftCorner(CornerFamily.CUT, yearIndicator.height.toFloat()).build()
                }
            }
        }
        mediaList = view.findViewById<RecyclerView?>(R.id.gallery_list).apply {
            adapter = mediaAdapter

            spanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (mediaAdapter.getItemViewType(position) == MediaAdapter.TYPE_DATE) spanCount else 1
                }
            }

            selectionTracker = SelectionTracker.Builder(
                "galleryFolderFragmentSelection",
                this,
                MediaAdapter.PhotoKeyProvider(mediaAdapter),
                MediaAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object: SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean {
                    return when {
                        galleryModel.isPreparingShareOut() -> false                                     // Can't select when sharing out
                        key.isEmpty() -> false                                                          // Empty space in list
                        key.startsWith("content") -> true                                        // Normal media items
                        else -> {                                                                       // Date items
                            val startPos = mediaAdapter.getPhotoPosition(key)
                            var index = startPos
                            var selectWholeDate = false
                            val keys = arrayListOf<String>()
                            while(true) {
                                index++
                                if (index == mediaAdapter.currentList.size) break                       // End of list
                                if (mediaAdapter.currentList[index].photo.mimeType.isEmpty()) break     // Next date
                                mediaAdapter.getPhotoId(index).let { id ->
                                    keys.add(id)
                                    if (!selectionTracker.isSelected(id)) selectWholeDate = true
                                }
                            }

                            selectionTracker.setItemsSelected(keys, selectWholeDate)

                            false
                        }
                    }
                }
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()
                        updateUI()
                    }

                    override fun onSelectionRestored() {
                        super.onSelectionRestored()
                        updateUI()
                    }

                    private fun updateUI() {
                        val selectionSize = selectionTracker.selection.size()
                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(this@GalleryFolderViewFragment)
                            actionMode?.let { it.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize) }
                            selectionBackPressedCallback.isEnabled = true
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                            selectionBackPressedCallback.isEnabled = false
                        } else actionMode?.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)

                        // Disable sub folder chips when in selection mode
                        val enableChips = selectionSize <= 0
                        subFolderChipGroup.forEach { it.isEnabled = enableChips }
                    }
                })
            }
            mediaAdapter.setSelectionTracker(selectionTracker)
            savedInstanceState?.let { selectionTracker.onRestoreInstanceState(it) }

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = kotlinx.coroutines.Runnable {
                    TransitionManager.beginDelayedTransition(mediaList.parent as ViewGroup, Fade().apply { duration = 800 })
                    yearIndicator.visibility = View.GONE
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re-calculation, hide dataIndicator
                        yearIndicator.isVisible = false

                        // Convenient place to catch events triggered by scrollToPosition
                        if (flashDateId.isNotEmpty()) {
                            flashDate(mediaList.findViewWithTag(flashDateId))
                            flashDateId = ""
                        }
                    } else {
                        (recyclerView.layoutManager as GridLayoutManager).run {
                            if ((findLastCompletelyVisibleItemPosition() < mediaAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                yearIndicator.let {
                                    it.text = mediaAdapter.currentList[findLastVisibleItemPosition()].photo.dateTaken.format(DateTimeFormatter.ofPattern("MMM uuuu"))
                                    it.isVisible = true
                                }
                                hideHandler.postDelayed(hideDateIndicator, 1500)
                            }
                        }
                    }
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_phone_android_24)!!))

            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    mediaList.removeOnLayoutChangeListener(this)
                    val position = mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()).run { if (this < 0) 0 else this }
                    mediaList.layoutManager?.let { layoutManager ->
                        layoutManager.findViewByPosition(position).let { view ->
                            if (view == null || layoutManager.isViewPartiallyVisible(view, false, true)) mediaList.post { layoutManager.scrollToPosition(position) }
                        }
                    }
                }
            })
        }

        LesPasFastScroller(
            mediaList,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_width), 0, 0, resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_height)
        )

        parentFragmentManager.setFragmentResultListener(GALLERY_FOLDERVIEW_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) galleryModel.remove(getSelectedPhotos(), removeArchive = bundle.getBoolean(ConfirmDialogFragment.CHECKBOX_RESULT_KEY))
                STRIP_REQUEST_KEY -> galleryModel.shareOut(getSelectedPhotos(), bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false), false)
                EMPTY_TRASH_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) galleryModel.emptyTrash(arrayListOf<String>().apply { mediaAdapter.getAllItems().forEach { add(it.photo.id) }})
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (folderArgument) {
                    GalleryFragment.TRASH_FOLDER -> {
                        galleryModel.trash.collect {
                            var theDate: LocalDate
                            val listGroupedByDate = mutableListOf<NCShareViewModel.RemotePhoto>()
                            var currentDate = LocalDate.now().plusDays(1)
                            it?.forEach { media ->
                                theDate = media.media.photo.dateTaken.toLocalDate()
                                if (theDate != currentDate) {
                                    currentDate = theDate
                                    // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                                    listGroupedByDate.add(NCShareViewModel.RemotePhoto(Photo(id = currentDate.toString(), albumId = GalleryFragment.FROM_DEVICE_GALLERY, dateTaken = media.media.photo.dateTaken, lastModified = media.media.photo.dateTaken, mimeType = "")))
                                }
                                listGroupedByDate.add(media.media)
                            }

                            if (listGroupedByDate.isEmpty()) parentFragmentManager.popBackStack() else mediaAdapter.submitList(listGroupedByDate)
                        }
                    }
                    GalleryFragment.ALL_FOLDER -> galleryModel.medias.collect { prepareList(it) }
                    else -> galleryModel.mediasInFolder(folderArgument).collect { prepareList(it) }
                }
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.gallery_folder_menu, menu)
                if (folderArgument == GalleryFragment.TRASH_FOLDER) menu.findItem(R.id.empty_trash)?.isVisible = true
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when(menuItem.itemId) {
                R.id.option_menu_calendar_view -> {
                    mediaAdapter.dateRange()?.let { dateRange ->
                        MaterialDatePicker.Builder.datePicker()
                            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(object: CalendarConstraints.DateValidator {
                                override fun describeContents(): Int = 0
                                override fun writeToParcel(dest: Parcel, flags: Int) {}
                                override fun isValid(date: Long): Boolean = mediaAdapter.hasDate(date)
                            }).setStart(dateRange.first).setEnd(dateRange.second).setOpenAt(mediaAdapter.getDateByPosition((mediaList.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())).build())
                            .setTheme(R.style.ThemeOverlay_LesPas_DatePicker)
                            .build()
                            .apply {
                                addOnPositiveButtonClickListener { picked ->
                                    val currentBottom = (mediaList.layoutManager as GridLayoutManager).findLastCompletelyVisibleItemPosition()
                                    mediaAdapter.getPositionByDate(picked).let { newPosition ->
                                        mediaList.findViewHolderForAdapterPosition(newPosition)?.itemView?.findViewById<TextView>(R.id.date)?.let { view ->
                                            // new position is visible on screen now
                                            if (newPosition >= currentBottom) mediaList.scrollToPosition( min(mediaAdapter.currentList.size -1, newPosition + spanCount))
                                            flashDate(view)
                                        } ?: run {
                                            // flash the date after scroll finished
                                            flashDateId = mediaAdapter.getPhotoId(newPosition)
                                            mediaList.scrollToPosition(if (newPosition < currentBottom) newPosition else min(mediaAdapter.currentList.size - 1, newPosition + spanCount))
                                        }
                                    }
                                }
                            }.show(parentFragmentManager, null)
                    }
                    true
                }
                R.id.empty_trash -> {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_empty_trash), positiveButtonText = getString(R.string.yes_delete), individualKey = EMPTY_TRASH_REQUEST_KEY, requestKey = GALLERY_FOLDERVIEW_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    true
                }
                else -> false
            }

        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = requireArguments().getString(ARGUMENT_FOLDER)?.let {
                when(it) {
                    "DCIM" -> getString(R.string.camera_roll_name)
                    GalleryFragment.TRASH_FOLDER -> getString(R.string.trash_name)
                    GalleryFragment.ALL_FOLDER -> ""
                    else -> it
                }
            }
        }
    }

    override fun onPause() {
        galleryModel.saveCurrentSubFolder(currentCheckedTag)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        try { selectionTracker.onSaveInstanceState(outState) } catch (_: UninitializedPropertyAccessException) {}
/*
        // Because we might need scrolling to a new position when returning from GallerySliderFragment, we have to save current scroll state in this way, though it's not as perfect as layoutManager.onSavedInstanceState
        (mediaList.layoutManager as GridLayoutManager).findFirstVisibleItemPosition().let { position ->
            if (position != RecyclerView.NO_POSITION) galleryModel.setCurrentPhotoId(mediaAdapter.getPhotoId(position))
        }
*/
    }

    override fun onDestroyView() {
        mediaList.adapter = null

        super.onDestroyView()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.action_mode_gallery, menu)
        if (folderArgument == GalleryFragment.TRASH_FOLDER) menu?.findItem(R.id.remove)?.let {
            it.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_restore_from_trash_24)
            it.title = getString(R.string.action_undelete)
        }

        return true
    }
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.add -> {
                galleryModel.add(getSelectedPhotos())
                true
            }
            R.id.remove -> {
                val defaultSyncDeletionSetting = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.sync_deletion_perf_key), false)
                when {
                    folderArgument == GalleryFragment.TRASH_FOLDER -> galleryModel.restore(getSelectedPhotos())
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.R || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(requireContext())) -> galleryModel.remove(getSelectedPhotos(), removeArchive = defaultSyncDeletionSetting)
                    parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null -> ConfirmDialogFragment.newInstance(
                        getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), individualKey = DELETE_REQUEST_KEY, requestKey = GALLERY_FOLDERVIEW_REQUEST_KEY,
                        checkBoxText = getString(R.string.checkbox_text_remove_archive_copy), checkBoxChecked = defaultSyncDeletionSetting
                    ).show(parentFragmentManager, CONFIRM_DIALOG)
                }

                true
            }
            R.id.share -> {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), individualKey = STRIP_REQUEST_KEY, requestKey = GALLERY_FOLDERVIEW_REQUEST_KEY, positiveButtonText = getString(R.string.strip_exif_yes), negativeButtonText = getString(R.string.strip_exif_no), cancelable = true).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else galleryModel.shareOut(getSelectedPhotos(), false)
                } else galleryModel.shareOut(getSelectedPhotos(), stripExif == getString(R.string.strip_on_value))

                true
            }
            R.id.select_all -> {
                mediaAdapter.currentList.forEach { if (it.photo.mimeType.isNotEmpty()) selectionTracker.select(it.photo.id) }
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    @SuppressLint("InflateParams")
    private fun prepareList(localMedias: List<GalleryFragment.LocalMedia>?) {
        if (localMedias == null) return
        if (localMedias.isEmpty()) parentFragmentManager.popBackStack()

        // Disable list setting for now
        subFolderChipGroup.setOnCheckedStateChangeListener(null)

        // List facilitating sub folder view
        currentMediaList.clear()
        currentMediaList.addAll(localMedias)

        // Generate sub folder chips
        if (subFolderChipGroup.childCount > 1) subFolderChipGroup.removeViews(1, subFolderChipGroup.childCount - 1)
        if (folderArgument == GalleryFragment.ALL_FOLDER) currentMediaList.groupBy { item -> item.appName }.forEach { subFolder ->
            subFolderChipGroup.addView(
                (LayoutInflater.from(requireContext()).inflate(R.layout.chip_sub_folder, null) as Chip).apply {
                    text = subFolder.key
                    tag = subFolder.key
                    setOnCheckedChangeListener { buttonView, isChecked -> (buttonView as Chip).typeface = if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                }
            )
        }
        else currentMediaList.groupBy { item -> item.fullPath }.forEach { subFolder ->
            subFolderChipGroup.addView(
                (LayoutInflater.from(requireContext()).inflate(R.layout.chip_sub_folder, null) as Chip).apply {
                    text = subFolder.key.dropLast(1).substringAfterLast('/')
                    tag = subFolder.key
                    setOnCheckedChangeListener { buttonView, isChecked -> (buttonView as Chip).typeface = if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                }
            )
        }
        subFolderChipGroup.check(
            subFolderChipGroup.findViewWithTag<Chip>(currentCheckedTag)?.id
            ?: run {
                // If currentCheckedTag is not found in the new sub foler list, fall back to 'All'
                currentCheckedTag = CHIP_FOR_ALL_TAG
                chipForAll.id
            }
        )
        subFolderChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCheckedTag = subFolderChipGroup.findViewById<Chip>(checkedIds[0]).tag as String
            setList()
        }

        setList()
    }

    private fun setList() {
        val listGroupedByDate = mutableListOf<NCShareViewModel.RemotePhoto>()
        var theDate: LocalDate
        var currentDate = LocalDate.now().plusDays(1)

        when {
            currentCheckedTag == CHIP_FOR_ALL_TAG -> currentMediaList
            folderArgument == GalleryFragment.ALL_FOLDER -> currentMediaList.filter { it.appName == currentCheckedTag }
            else -> currentMediaList.filter { it.fullPath == currentCheckedTag }
        }.forEach { media ->
            theDate = media.media.photo.dateTaken.toLocalDate()
            if (theDate != currentDate) {
                currentDate = theDate
                // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                listGroupedByDate.add(NCShareViewModel.RemotePhoto(Photo(id = currentDate.toString(), albumId = GalleryFragment.FROM_DEVICE_GALLERY, dateTaken = media.media.photo.dateTaken, lastModified = media.media.photo.dateTaken, mimeType = "")))
            }
            listGroupedByDate.add(media.media)
        }

        //if (listGroupedByDate.isEmpty()) parentFragmentManager.popBackStack() else mediaAdapter.submitList(listGroupedByDate)
        if (listGroupedByDate.isNotEmpty()) mediaAdapter.submitList(listGroupedByDate)
    }

    private var flashDateId = ""
    private fun flashDate(view: View) {
        view.post {
            ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofFloat("translationX", 0f, 100f, 0f)).run {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                interpolator = BounceInterpolator()
                start()
            }
        }
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in selectionTracker.selection) {
            galleryModel.getPhotoById(photoId.toString())?.let { if (Tools.hasExif(it.mimeType)) return true }
        }

        return false
    }

    private fun getSelectedPhotos(): List<String> = mutableListOf<String>().apply {
        selectionTracker.selection.forEach { add(it) }
        selectionTracker.clearSelection()
    }

    class MediaAdapter(private val clickListener: (View, String, String) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, RecyclerView.ViewHolder>(MediaDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null
        private val defaultOffset = OffsetDateTime.now().offset
        private var sToday = ""
        private var sYesterday = ""

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { foregroundGravity = Gravity.CENTER }

            fun bind(item: NCShareViewModel.RemotePhoto) {
                val photo = item.photo
                itemView.let {
                    it.isSelected = selectionTracker.isSelected(photo.id)

                    with(ivPhoto) {
                        if (currentId != photo.id) {
                            imageLoader(item, this)
                            currentId = photo.id
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            else -> null
                        }

                        if (it.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(this, photo.id, photo.mimeType) }
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val tvDate = itemView.findViewById<TextView>(R.id.date)

            @SuppressLint("SetTextI18n")
            fun bind(item: NCShareViewModel.RemotePhoto) {
                with(item.photo.dateTaken) {
                    val now = LocalDate.now()
                    val date = this.toLocalDate()
                    tvDate.text = when {
                        date == now -> sToday
                        date == now.minusDays(1) -> sYesterday
                        date.year == now.year -> "${format(DateTimeFormatter.ofPattern("MMM d"))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                        else -> "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                    }
                    tvDate.tag = item.photo.id
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_MEDIA) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date_horizontal, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            else (holder as DateViewHolder).bind(currentList[position])
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> if (holder is MediaViewHolder) holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].photo.mimeType.isEmpty()) TYPE_DATE else TYPE_MEDIA

        internal fun setMarks(playMark: Drawable, selectedMark: Drawable) {
            this.playMark = playMark
            this.selectedMark = selectedMark
        }
        internal fun setDateStrings(today: String, yesterday: String) {
            sToday = today
            sYesterday = yesterday
        }
        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].photo.id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.photo.id == photoId }

        fun hasDate(date: Long): Boolean {
            val theDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate()
            return (currentList.indexOfFirst { it.photo.mimeType.isEmpty() && it.photo.dateTaken.toLocalDate().isEqual(theDate) }) != RecyclerView.NO_POSITION
        }
        fun dateRange(): Pair<Long, Long>? {
            return if (currentList.isNotEmpty()) Pair(currentList.last().photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli(), currentList.first().photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()) else null
        }
        fun getPositionByDate(date: Long): Int  = currentList.indexOfFirst { it.photo.mimeType.isEmpty() && it.photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli() - date < 86400000 }
        fun getDateByPosition(position: Int): Long = currentList[position].photo.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()
        fun getAllItems(): List<NCShareViewModel.RemotePhoto> = currentList.filter { it.photo.mimeType.isNotEmpty() }

        class PhotoKeyProvider(private val adapter: MediaAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String> {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    recyclerView.getChildViewHolder(it).let { holder ->
                        if (holder is MediaViewHolder) return holder.getItemDetails()
                        if (holder is DateViewHolder) return holder.getItemDetails()
                    }
                }
                return stubItemDetails()
            }

            // Default ItemDetailsLookup stub, to avoid clearing selection by clicking the empty area in the list
            private fun stubItemDetails() = object : ItemDetails<String>() {
                override fun getPosition(): Int = Int.MIN_VALUE
                override fun getSelectionKey(): String = ""
            }
        }

        companion object {
            private const val TYPE_MEDIA = 0
            const val TYPE_DATE = 1
        }
    }
    class MediaDiffCallback : DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = true
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val GALLERY_FOLDERVIEW_REQUEST_KEY = "GALLERY_FOLDERVIEW_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "GALLERY_STRIP_REQUEST_KEY"
        private const val DELETE_REQUEST_KEY = "GALLERY_DELETE_REQUEST_KEY"
        private const val EMPTY_TRASH_REQUEST_KEY = "EMPTY_TRASH_REQUEST_KEY"

        // Default to All, same tag set in R.layout.fragment_gallery_list for view R.id.chip_for_all
        const val CHIP_FOR_ALL_TAG = "...."

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"

        @JvmStatic
        fun newInstance(folder: String) = GalleryFolderViewFragment().apply { arguments = Bundle().apply { putString(ARGUMENT_FOLDER, folder) }}
    }
}