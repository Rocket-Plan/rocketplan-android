package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.ui.projects.ScopeCatalogItem
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.rocketplan_android.ui.projects.PhotosAddedResult
import java.util.Locale

class RoomDetailFragment : Fragment() {

    private val args: RoomDetailFragmentArgs by navArgs()

    private val viewModel: RoomDetailViewModel by viewModels {
        RoomDetailViewModel.provideFactory(requireActivity().application, args.projectId, args.roomId)
    }

    private val syncQueueManager: SyncQueueManager by lazy {
        (requireActivity().application as RocketPlanApplication).syncQueueManager
    }

    private lateinit var menuButton: ImageButton
    private lateinit var roomIcon: ImageView
    private lateinit var roomTitle: TextView
    private lateinit var noteSummary: TextView
    private lateinit var tabToggleGroup: MaterialButtonToggleGroup
    private lateinit var photosTabButton: MaterialButton
    private lateinit var damagesTabButton: MaterialButton
    private lateinit var scopeTabButton: MaterialButton
    private lateinit var noteCard: View
    private lateinit var addPhotoCard: View
    private lateinit var noteCardLabel: TextView
    private lateinit var noteCardSummary: TextView
    private lateinit var gridSectionTitle: TextView
    private lateinit var albumsHeader: TextView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var addPhotoChip: Chip
    private lateinit var damageAssessmentChip: Chip
    private lateinit var damageCategoryGroup: MaterialButtonToggleGroup
    private lateinit var totalEquipmentButton: MaterialButton
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var damagesRecyclerView: RecyclerView
    private lateinit var scopeRecyclerView: RecyclerView
    private lateinit var placeholderContainer: View
    private lateinit var placeholderText: TextView
    private lateinit var placeholderImage: ImageView
    private lateinit var photosLoadingSpinner: View
    private lateinit var loadingOverlay: View
    private lateinit var addScopeCard: View
    private lateinit var scopePickerContainer: View
    private lateinit var scopePickerSearchInput: TextInputEditText
    private lateinit var scopePickerRecyclerView: RecyclerView
    private lateinit var scopePickerLoading: ProgressBar
    private lateinit var scopePickerEmpty: TextView
    private lateinit var scopePickerAddButton: MaterialButton
    private lateinit var scopePickerCustomButton: MaterialButton
    private lateinit var scopePickerCancelButton: MaterialButton
    private lateinit var photoConcatAdapter: ConcatAdapter
    private var latestPhotoCount: Int = 0
    private var latestDamages: List<RoomDamageItem> = emptyList()
    private var latestScopeGroups: List<RoomScopeGroup> = emptyList()
    private var latestDamageSections: List<RoomDamageSection> = emptyList()
    private var photoLoadStartTime: Long = 0L
    private var latestLoadState: LoadState? = null
    private var snapshotRefreshInProgress: Boolean = false
    private var awaitingRealtimePhotos: Boolean = false

    private val initialTab: RoomDetailTab by lazy {
        when (args.startTab.lowercase(Locale.US)) {
            "damages" -> RoomDetailTab.DAMAGES
            "scope", "scope_sheet", "sketch" -> RoomDetailTab.DAMAGES
            else -> RoomDetailTab.PHOTOS
        }
    }

    private val albumsAdapter by lazy {
        AlbumsAdapter(
            onAlbumClick = { album ->
                Toast.makeText(requireContext(), "Album: ${album.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val addPhotoAdapter by lazy {
        RoomPhotoAddAdapter { navigateToBatchCapture() }
    }

    private val photoAdapter by lazy {
        RoomPhotoPagingAdapter(
            onPhotoSelected = { openPhotoViewer(it) }
        )
    }

    private val damagesAdapter by lazy { RoomDamageAdapter() }

    private val scopeAdapter by lazy {
        RoomScopeAdapter { item -> openScopeEditor(item) }
    }

    private val photoLoadStateAdapter by lazy {
        RoomPhotoLoadStateAdapter { photoAdapter.retry() }
    }

    private val roomDamageSectionAdapter by lazy {
        RoomDamageSectionAdapter(
            onAddNote = { section -> openNotesForRoom(section.roomId, section.serverRoomId) },
            onAddScope = { section ->
                Log.d(TAG, "➕ Add scope tapped for section='${section.title}' (roomId=${section.roomId})")
                viewModel.refreshWorkScopesIfStale()
                showScopePickerBottomSheet()
            },
            onScopeLineItemClick = { item -> openScopeEditor(item) }
        )
    }

    private var scopePickerAdapter: ScopePickerAdapter? = null
    private var roomTitleBase: String = ""


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_room_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "🧭 onViewCreated(projectId=${args.projectId}, roomId=${args.roomId})")
        bindViews(view)
        configureRecycler()
        configureToggleGroup(initialTab)
        bindListeners()
        observeNavigationResults()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        syncQueueManager.pauseProjectPhotoSync(args.projectId)
    }

    override fun onPause() {
        super.onPause()
        syncQueueManager.resumeProjectPhotoSync(args.projectId)
    }


    private fun bindViews(root: View) {
        menuButton = root.findViewById(R.id.menuButton)
        roomIcon = root.findViewById(R.id.roomIcon)
        roomTitle = root.findViewById(R.id.roomTitle)
        noteSummary = root.findViewById(R.id.noteSummary)
        tabToggleGroup = root.findViewById(R.id.roomTabGroup)
        photosTabButton = root.findViewById(R.id.roomPhotosTabButton)
        damagesTabButton = root.findViewById(R.id.roomDamagesTabButton)
        scopeTabButton = root.findViewById(R.id.roomScopeTabButton)
        noteCard = root.findViewById(R.id.roomNoteCard)
        noteCardLabel = root.findViewById(R.id.roomNoteLabel)
        noteCardSummary = root.findViewById(R.id.roomNoteSummary)
        addPhotoCard = root.findViewById(R.id.addPhotoQuickCard)
        albumsHeader = root.findViewById(R.id.roomAlbumsHeader)
        albumsRecyclerView = root.findViewById(R.id.roomAlbumsRecyclerView)
        gridSectionTitle = root.findViewById(R.id.gridSectionTitle)
        totalEquipmentButton = root.findViewById(R.id.totalEquipmentButton)
        filterChipGroup = root.findViewById(R.id.photoFilterChips)
        addPhotoChip = root.findViewById(R.id.addPhotoChip)
        damageAssessmentChip = root.findViewById(R.id.damageAssessmentChip)
        damageCategoryGroup = root.findViewById(R.id.damageCategoryGroup)
        photosRecyclerView = root.findViewById(R.id.roomPhotosRecyclerView)
        damagesRecyclerView = root.findViewById(R.id.roomDamagesRecyclerView)
        scopeRecyclerView = root.findViewById(R.id.roomScopeRecyclerView)
        placeholderContainer = root.findViewById(R.id.photosPlaceholder)
        placeholderText = root.findViewById(R.id.photosPlaceholderText)
        placeholderImage = root.findViewById(R.id.photosPlaceholderImage)
        photosLoadingSpinner = root.findViewById(R.id.photosLoadingSpinner)
        loadingOverlay = root.findViewById(R.id.loadingOverlay)
        addScopeCard = root.findViewById(R.id.addScopeCard)
        scopePickerContainer = root.findViewById(R.id.scopePickerContainer)
        scopePickerSearchInput = root.findViewById(R.id.scopePickerSearchInput)
        scopePickerRecyclerView = root.findViewById(R.id.scopePickerRecycler)
        scopePickerLoading = root.findViewById(R.id.scopePickerLoading)
        scopePickerEmpty = root.findViewById(R.id.scopePickerEmpty)
        scopePickerAddButton = root.findViewById(R.id.scopePickerAddButton)
        scopePickerCustomButton = root.findViewById(R.id.scopePickerCustomButton)
        scopePickerCancelButton = root.findViewById(R.id.scopePickerCancelButton)

        scopeTabButton.isVisible = false
        addPhotoChip.isChecked = initialTab == RoomDetailTab.PHOTOS
        addPhotoCard.isVisible = initialTab == RoomDetailTab.PHOTOS
        addScopeCard.isVisible = false
        damageCategoryGroup.check(R.id.damageFilterAll)
        damageCategoryGroup.isVisible = false
        filterChipGroup.isVisible = initialTab == RoomDetailTab.PHOTOS
        gridSectionTitle.text = when (initialTab) {
            RoomDetailTab.PHOTOS -> getString(R.string.damage_assessment)
            RoomDetailTab.DAMAGES -> getString(R.string.damages)
            RoomDetailTab.SCOPE -> getString(R.string.damages)
        }
        gridSectionTitle.isVisible = initialTab == RoomDetailTab.PHOTOS
        noteCardLabel.text = getString(R.string.add_note_with_plus)
    }

    private fun configureRecycler() {
        albumsRecyclerView.configureForAlbums(albumsAdapter)

        photoConcatAdapter = ConcatAdapter(addPhotoAdapter, photoAdapter, photoLoadStateAdapter)

        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val isHeader = position == 0
                    val loadStateCount = photoLoadStateAdapter.itemCount
                    val isFooter = loadStateCount > 0 &&
                        position >= photoConcatAdapter.itemCount - loadStateCount
                    return if (isHeader || isFooter) spanCount else 1
                }
            }
        }

        photosRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = photoConcatAdapter
            // Allow height to grow after data loads; otherwise the view may stay collapsed (e.g. 22px).
            setHasFixedSize(false)
            (itemAnimator as? SimpleItemAnimator)?.apply {
                supportsChangeAnimations = false
                changeDuration = 0
            }
        }
        photosRecyclerView.addItemDecoration(
            SimpleGridSpacingDecoration(
                spanCount = 3,
                spacing = resources.getDimensionPixelSize(R.dimen.room_grid_spacing)
            )
        )

        damagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = roomDamageSectionAdapter
            isVisible = false
        }

        scopeRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = scopeAdapter
            isVisible = false
        }

        scopePickerRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
        }
    }

    private fun configureToggleGroup(initial: RoomDetailTab) {
        viewModel.selectTab(initial)
        val initialButtonId = when (initial) {
            RoomDetailTab.PHOTOS -> R.id.roomPhotosTabButton
            RoomDetailTab.DAMAGES, RoomDetailTab.SCOPE -> R.id.roomDamagesTabButton
        }
        tabToggleGroup.check(initialButtonId)
        updateToggleStyles(initial)
        tabToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tab = when (checkedId) {
                R.id.roomPhotosTabButton -> RoomDetailTab.PHOTOS
                R.id.roomDamagesTabButton, R.id.roomScopeTabButton -> RoomDetailTab.DAMAGES
                else -> RoomDetailTab.PHOTOS
            }
            viewModel.selectTab(tab)
            if (tab == RoomDetailTab.DAMAGES) viewModel.refreshDamagesIfStale()
            if (tab == RoomDetailTab.DAMAGES) viewModel.refreshWorkScopesIfStale()
            updateToggleStyles(tab)
        }
    }

    private fun updateToggleStyles(active: RoomDetailTab) {
        val selectedBackground =
            ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
        val unselectedBackground =
            ContextCompat.getColorStateList(requireContext(), android.R.color.white)
        val selectedText = ContextCompat.getColor(requireContext(), android.R.color.white)
        val unselectedText = ContextCompat.getColor(requireContext(), R.color.main_purple)

        listOf(
            photosTabButton to RoomDetailTab.PHOTOS,
            damagesTabButton to RoomDetailTab.DAMAGES
        ).forEach { (button, tab) ->
            val isSelected = tab == active
            button.backgroundTintList = if (isSelected) selectedBackground else unselectedBackground
            button.strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
            button.setTextColor(if (isSelected) selectedText else unselectedText)
        }
    }

    private fun updateDamageVisibility() {
        updateDamageAndScopeVisibility()
    }

    private fun updateScopeVisibility() {
        updateDamageAndScopeVisibility()
    }

    private fun updateDamageAndScopeVisibility() {
        val tab = viewModel.selectedTab.value
        if (tab == RoomDetailTab.PHOTOS) {
            damagesRecyclerView.isVisible = false
            scopeRecyclerView.isVisible = false
            placeholderContainer.isVisible = false
            placeholderImage.isVisible = false
            addScopeCard.isVisible = false
            return
        }

        val hasSections = latestDamageSections.isNotEmpty()

        scopeRecyclerView.isVisible = false
        damagesRecyclerView.isVisible = hasSections

        val showPlaceholder = !hasSections
        placeholderContainer.isVisible = showPlaceholder
        placeholderImage.isVisible = showPlaceholder
        placeholderText.text = if (showPlaceholder) {
            getString(R.string.room_damages_empty_state)
        } else ""

        // Match iOS: show the room-level add-scope card when in Damages/Scope tabs
        addScopeCard.isVisible = tab == RoomDetailTab.DAMAGES || tab == RoomDetailTab.SCOPE
    }

    private fun bindListeners() {
        menuButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.edit_room), Toast.LENGTH_SHORT).show()
        }
        addPhotoCard.setOnClickListener { showAddPhotoOptions(it) }
        addPhotoChip.setOnClickListener { showAddPhotoOptions(it) }
        damageAssessmentChip.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.damage_assessment), Toast.LENGTH_SHORT).show()
        }
        totalEquipmentButton.setOnClickListener { openEquipmentTotals() }
        addScopeCard.setOnClickListener {
            viewModel.refreshWorkScopesIfStale()
            showScopePickerBottomSheet()
        }
        scopePickerAddButton.setOnClickListener { submitInlineScopeSelection() }
        scopePickerCustomButton.setOnClickListener {
            hideScopePickerInline()
            showAddScopeDialog()
        }
        scopePickerCancelButton.setOnClickListener { hideScopePickerInline() }
        scopePickerSearchInput.addTextChangedListener { text ->
            scopePickerAdapter?.filter(text?.toString().orEmpty())
        }
        noteCard.setOnClickListener {
            val categoryId = resolveNoteCategoryId()
            val action = RoomDetailFragmentDirections
                .actionRoomDetailFragmentToProjectNotesFragment(
                    projectId = args.projectId,
                    roomId = args.roomId,
                    categoryId = categoryId
                )
            findNavController().navigate(action)
        }
    }

    private fun resolveNoteCategoryId(): Long {
        return when (viewModel.selectedTab.value) {
            RoomDetailTab.DAMAGES -> 1L // Matches iOS Category.roomDamage raw value
            RoomDetailTab.SCOPE -> 1L // Scope notes share the roomDamage category for now
            RoomDetailTab.PHOTOS -> 2L // Matches iOS Category.roomPhoto raw value
        }
    }

    private fun navigateToBatchCapture() {
        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToBatchCaptureFragment(
                projectId = args.projectId,
                roomId = args.roomId
            )
        findNavController().navigate(action)
    }

    private fun navigateToFlirCapture() {
        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToBatchCaptureFragment(
                projectId = args.projectId,
                roomId = args.roomId,
                captureMode = "ir"
            )
        findNavController().navigate(action)
    }

    private fun openScopeEditor(item: RoomScopeItem) {
        val actions = arrayOf(
            getString(R.string.scope_line_item_edit),
            getString(R.string.scope_line_item_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.title.ifBlank { getString(R.string.scope_sheet) })
            .setItems(actions) { dialog, which ->
                when (which) {
                    0 -> {
                        dialog.dismiss()
                        showScopeEditDialog(item)
                    }

                    1 -> {
                        dialog.dismiss()
                        confirmScopeDeletion(item)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showScopeEditDialog(item: RoomScopeItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_scope_item, null)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.scopeEditTitleLayout)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.scopeEditTitleInput)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.scopeEditDescriptionInput)
        val quantityLayout = dialogView.findViewById<TextInputLayout>(R.id.scopeEditQuantityLayout)
        val quantityInput = dialogView.findViewById<TextInputEditText>(R.id.scopeEditQuantityInput)
        val unitInput = dialogView.findViewById<TextInputEditText>(R.id.scopeEditUnitInput)
        val rateLayout = dialogView.findViewById<TextInputLayout>(R.id.scopeEditRateLayout)
        val rateInput = dialogView.findViewById<TextInputEditText>(R.id.scopeEditRateInput)

        titleInput.setText(item.title)
        descriptionInput.setText(item.description.orEmpty())
        quantityInput.setText(formatQuantityInput(item.quantity))
        unitInput.setText(item.unit.orEmpty())
        rateInput.setText(formatDecimalInput(item.rate))

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.scope_line_item_edit))
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    titleLayout.error = getString(R.string.scope_edit_title_required)
                    return@setOnClickListener
                }
                titleLayout.error = null

                val description = descriptionInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                val quantityText = quantityInput.text?.toString()?.trim()
                val quantity = quantityText.takeUnless { it.isNullOrEmpty() }?.toDoubleOrNull()
                if (quantityText?.isNotEmpty() == true && (quantity == null || quantity <= 0.0)) {
                    quantityLayout.error = getString(R.string.scope_edit_invalid_quantity)
                    return@setOnClickListener
                }
                quantityLayout.error = null

                val unit = unitInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                val rateText = rateInput.text?.toString()?.trim()
                val rate = rateText.takeUnless { it.isNullOrEmpty() }?.toDoubleOrNull()
                if (rateText?.isNotEmpty() == true && rate == null) {
                    rateLayout.error = getString(R.string.scope_edit_invalid_rate)
                    return@setOnClickListener
                }
                rateLayout.error = null

                viewModel.updateScopeItem(
                    itemId = item.id,
                    title = title,
                    description = description,
                    quantity = quantity,
                    unit = unit,
                    rate = rate
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.scope_edit_saved),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun confirmScopeDeletion(item: RoomScopeItem) {
        val title = item.title.ifBlank { getString(R.string.scope_sheet) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.scope_line_item_delete))
            .setMessage(getString(R.string.scope_delete_confirmation, title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteScopeItem(item.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.scope_delete_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun formatQuantityInput(value: Double?): String {
        value ?: return ""
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun formatDecimalInput(value: Double?): String {
        value ?: return ""
        return String.format(Locale.US, "%.2f", value)
    }

    private fun openNotesForRoom(roomId: Long, serverRoomId: Long?) {
        val categoryId = 1L // Damage notes
        val targetRoomId = serverRoomId ?: roomId
        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToProjectNotesFragment(
                projectId = args.projectId,
                roomId = targetRoomId,
                categoryId = categoryId
            )
        findNavController().navigate(action)
    }

    private fun openEquipmentTotals() {
        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToRocketDryFragment(
                projectId = args.projectId,
                startTab = "equipment"
            )
        findNavController().navigate(action)
    }

    private fun showAddScopeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_scope_sheet, null)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.scopeTitleLayout)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.scopeTitleInput)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.scopeDescriptionInput)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_scope_sheet))
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString().orEmpty().trim()
                if (title.isBlank()) {
                    titleLayout.error = getString(R.string.add_scope_sheet_title_required)
                    return@setOnClickListener
                }
                titleLayout.error = null
                val description = descriptionInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
                viewModel.addScopeItem(title, description)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showScopePickerInline() {
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(TAG, "📋 showScopePickerInline() loading catalog")
            addScopeCard.isVisible = false
            scopePickerLoading.isVisible = true
            scopePickerEmpty.isVisible = false
            scopePickerRecyclerView.isVisible = false
            scopePickerAddButton.isEnabled = false
            scopePickerContainer.isVisible = true
            val catalog = viewModel.loadScopeCatalog()
            if (catalog.isEmpty()) {
                Log.w(TAG, "⚠️ Scope catalog empty, showing custom scope dialog")
                scopePickerLoading.isVisible = false
                scopePickerEmpty.isVisible = true
                scopePickerAddButton.isEnabled = false
                return@launch
            }
            Log.d(TAG, "📋 Scope catalog loaded: ${catalog.size} items")
            scopePickerAdapter = ScopePickerAdapter(catalog)
            scopePickerRecyclerView.adapter = scopePickerAdapter
            scopePickerSearchInput.setText("")
            scopePickerLoading.isVisible = false
            scopePickerEmpty.isVisible = false
            scopePickerRecyclerView.isVisible = true
            scopePickerAddButton.isEnabled = true
        }
    }

    private fun showScopePickerBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.bottom_sheet_scope_picker, null)
        dialog.setContentView(content)

        val searchInput = content.findViewById<TextInputEditText>(R.id.fullScopeSearchInput)
        val chipGroup = content.findViewById<com.google.android.material.chip.ChipGroup>(R.id.fullScopeChipGroup)
        val recycler = content.findViewById<RecyclerView>(R.id.fullScopeRecycler)
        val loading = content.findViewById<ProgressBar>(R.id.fullScopeLoading)
        val empty = content.findViewById<TextView>(R.id.fullScopeEmpty)
        val cancel = content.findViewById<MaterialButton>(R.id.fullScopeCancel)
        val custom = content.findViewById<MaterialButton>(R.id.fullScopeCustom)
        val add = content.findViewById<MaterialButton>(R.id.fullScopeAdd)

        recycler.layoutManager = LinearLayoutManager(requireContext())

        val selectedQuantities = mutableMapOf<Long, Double>()
        var allItems: List<ScopeCatalogItem> = emptyList()
        var displayedCategory = "All"
        val expandedCategories = mutableSetOf<String>()
        var rebuildListFn: ((String, String) -> Unit)? = null

        data class Row(val header: String? = null, val item: ScopeCatalogItem? = null)

        fun computeMeta(item: ScopeCatalogItem): String {
            val parts = mutableListOf<String>()
            item.unit.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            item.category.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            item.rate?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            return parts.joinToString(" • ")
        }

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val rows = mutableListOf<Row>()
            private val TYPE_HEADER = 0
            private val TYPE_ITEM = 1

            fun submitRows(newRows: List<Row>) {
                rows.clear()
                rows.addAll(newRows)
                notifyDataSetChanged()
            }

            override fun getItemViewType(position: Int): Int =
                if (rows[position].header != null) TYPE_HEADER else TYPE_ITEM

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                return if (viewType == TYPE_HEADER) {
                    val view = inflater.inflate(R.layout.item_scope_picker_header, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                } else {
                    val view = inflater.inflate(R.layout.item_scope_picker_full_row, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                if (row.header != null) {
                    val title = holder.itemView.findViewById<TextView>(R.id.scopeHeaderTitle)
                    val chevron = holder.itemView.findViewById<ImageView>(R.id.scopeHeaderChevron)
                    val expanded = expandedCategories.contains(row.header)
                    title.text = row.header
                    chevron.rotation = if (expanded) 90f else 0f
                    holder.itemView.setOnClickListener {
                        if (expanded) expandedCategories.remove(row.header) else expandedCategories.add(row.header)
                        rebuildListFn?.invoke(displayedCategory, searchInput.text?.toString().orEmpty())
                    }
                } else {
                    val item = row.item ?: return
                    val checkBox = holder.itemView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.fullScopeCheckBox)
                    val title = holder.itemView.findViewById<TextView>(R.id.fullScopeTitle)
                    val meta = holder.itemView.findViewById<TextView>(R.id.fullScopeMeta)
                    val minus = holder.itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.fullScopeMinus)
                    val plus = holder.itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.fullScopePlus)
                    val qtyView = holder.itemView.findViewById<TextView>(R.id.fullScopeQuantity)

                    title.text = item.description
                    meta.text = computeMeta(item)
                    val qty = selectedQuantities[item.id] ?: 0.0
                    checkBox.isChecked = qty > 0
                    qtyView.text = if (qty > 0 && qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()

                    fun setQty(newQty: Double) {
                        val clamped = if (newQty < 0.0) 0.0 else newQty
                        if (clamped == 0.0) {
                            selectedQuantities.remove(item.id)
                        } else {
                            selectedQuantities[item.id] = clamped
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    holder.itemView.setOnClickListener {
                        if (selectedQuantities.containsKey(item.id)) {
                            selectedQuantities.remove(item.id)
                        } else {
                            selectedQuantities[item.id] = if (qty > 0) qty else 1.0
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    checkBox.setOnClickListener {
                        if (checkBox.isChecked) {
                            selectedQuantities[item.id] = if (qty > 0) qty else 1.0
                        } else {
                            selectedQuantities.remove(item.id)
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    minus.setOnClickListener { setQty((selectedQuantities[item.id] ?: 1.0) - 1.0) }
                    plus.setOnClickListener { setQty((selectedQuantities[item.id] ?: 0.0) + 1.0) }
                }
            }

            override fun getItemCount(): Int = rows.size
        }

        recycler.adapter = adapter

        fun buildCategories(items: List<ScopeCatalogItem>): List<String> {
            val cats = items.map { item ->
                when {
                    item.tabName.isNotBlank() -> item.tabName
                    item.category.isNotBlank() -> item.category
                    else -> "Other"
                }
            }.distinct().sorted()
            return listOf("All") + cats
        }

        fun rebuildList(category: String, query: String) {
            displayedCategory = category
            val q = query.trim().lowercase(Locale.US)
            val filtered = allItems.filter { item ->
                val matchesCategory = category == "All" || (when {
                    item.tabName.isNotBlank() -> item.tabName
                    item.category.isNotBlank() -> item.category
                    else -> "Other"
                } == category)
                if (!matchesCategory) return@filter false
                if (q.isBlank()) return@filter true
                val haystack = listOf(
                    item.description,
                    item.codePart1,
                    item.codePart2,
                    item.category,
                    item.tabName
                ).joinToString(" ").lowercase(Locale.US)
                haystack.contains(q)
            }

            val rows = mutableListOf<Row>()
            val grouped = filtered.groupBy {
                when {
                    it.tabName.isNotBlank() -> it.tabName
                    it.category.isNotBlank() -> it.category
                    else -> "Other"
                }
            }.toSortedMap()

            grouped.forEach { (cat, items) ->
                rows.add(Row(header = cat))
                val expanded = expandedCategories.contains(cat)
                if (expanded) {
                    items.forEach { item ->
                        rows.add(Row(item = item))
                    }
                }
            }

            loading.isVisible = false
            empty.isVisible = rows.isEmpty()
            recycler.isVisible = rows.isNotEmpty()
            adapter.submitRows(rows)
        }
        rebuildListFn = ::rebuildList

        fun refreshChips(categories: List<String>) {
            chipGroup.removeAllViews()
            categories.forEachIndexed { index, cat ->
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = cat
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener {
                        chipGroup.check(id)
                        rebuildList(cat, searchInput.text?.toString().orEmpty())
                    }
                }
                chipGroup.addView(chip)
                if (index == 0) chipGroup.check(chip.id)
            }
        }

        searchInput.addTextChangedListener { text ->
            rebuildList(displayedCategory, text?.toString().orEmpty())
        }

        cancel.setOnClickListener { dialog.dismiss() }
        custom.setOnClickListener {
            dialog.dismiss()
            showAddScopeDialog()
        }
        add.setOnClickListener {
            val selections = selectedQuantities.mapNotNull { (id, qty) ->
                val item = allItems.firstOrNull { it.id == id } ?: return@mapNotNull null
                ScopeCatalogSelection(item, qty)
            }
            if (selections.isEmpty()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            add.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val success = runCatching { viewModel.addCatalogItems(selections) }
                    .onFailure { Log.e(TAG, "❌ Failed to add catalog scope items", it) }
                    .getOrDefault(false)
                add.isEnabled = true
                if (success) {
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.scope_picker_add_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        loading.isVisible = true
        recycler.isVisible = false
        empty.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val catalog = viewModel.loadScopeCatalog()
            allItems = catalog
            if (catalog.isEmpty()) {
                loading.isVisible = false
                empty.isVisible = true
                recycler.isVisible = false
                add.isEnabled = false
            } else {
                loading.isVisible = false
                add.isEnabled = true
                val cats = buildCategories(catalog)
                expandedCategories.clear()
                expandedCategories.addAll(cats.filter { it != "All" })
                refreshChips(cats)
                rebuildList("All", searchInput.text?.toString().orEmpty())
            }
        }

        dialog.show()
    }


    private fun hideScopePickerInline() {
        scopePickerContainer.isVisible = false
        scopePickerRecyclerView.adapter = null
        scopePickerAdapter = null
        scopePickerSearchInput.setText("")
        scopePickerLoading.isVisible = false
        scopePickerEmpty.isVisible = false
        scopePickerAddButton.isEnabled = true
        updateDamageAndScopeVisibility()
    }

    private fun submitInlineScopeSelection() {
        val selected = scopePickerAdapter?.selectedItems().orEmpty()
        if (selected.isEmpty()) {
            hideScopePickerInline()
            return
        }
        scopePickerAddButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val success = runCatching { viewModel.addCatalogItems(selected) }
                .onFailure { Log.e(TAG, "❌ Failed to add catalog scope items", it) }
                .getOrDefault(false)
            scopePickerAddButton.isEnabled = true
            if (!success) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.scope_picker_add_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            hideScopePickerInline()
        }
    }

    private inner class ScopePickerAdapter(
        private val allItems: List<ScopeCatalogItem>
    ) : RecyclerView.Adapter<ScopePickerAdapter.ScopePickerViewHolder>() {
        private val selectedIds = mutableSetOf<Long>()
        private val selectedQuantities = mutableMapOf<Long, Double>()
        private var filtered: List<ScopeCatalogItem> = allItems
        private val itemLookup = allItems.associateBy { it.id }

        fun filter(query: String) {
            val q = query.trim().lowercase(Locale.US)
            filtered = if (q.isBlank()) {
                allItems
            } else {
                allItems.filter { item ->
                    val haystack = listOf(
                        item.description,
                        item.codePart1,
                        item.codePart2,
                        item.unit,
                        item.category
                    ).joinToString(" ") { it?.lowercase(Locale.US).orEmpty() }
                    haystack.contains(q)
                }
            }
            notifyDataSetChanged()
        }

        fun selectedItems(): List<ScopeCatalogSelection> {
            return selectedIds.mapNotNull { id ->
                val item = itemLookup[id] ?: return@mapNotNull null
                val qty = selectedQuantities[id]?.coerceAtLeast(1.0) ?: 1.0
                ScopeCatalogSelection(item, qty)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScopePickerViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scope_picker, parent, false)
            return ScopePickerViewHolder(view)
        }

        override fun onBindViewHolder(holder: ScopePickerViewHolder, position: Int) {
            holder.bind(filtered[position])
        }

        override fun getItemCount(): Int = filtered.size

        private fun formatQuantity(value: Double): String {
            val intValue = value.toInt()
            return if (value == intValue.toDouble()) intValue.toString() else String.format(Locale.US, "%.2f", value)
        }

        inner class ScopePickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val checkBox = itemView.findViewById<MaterialCheckBox>(R.id.scopeCheckBox)
            private val title = itemView.findViewById<TextView>(R.id.scopeTitle)
            private val meta = itemView.findViewById<TextView>(R.id.scopeMeta)
            private val minus = itemView.findViewById<MaterialButton>(R.id.scopeQuantityMinus)
            private val plus = itemView.findViewById<MaterialButton>(R.id.scopeQuantityPlus)
            private val quantityValue = itemView.findViewById<TextView>(R.id.scopeQuantityValue)

            fun bind(item: ScopeCatalogItem) {
                val isSelected = selectedIds.contains(item.id)
                val quantity = (selectedQuantities[item.id] ?: 1.0).coerceAtLeast(1.0)
                selectedQuantities[item.id] = quantity

                val code = listOfNotNull(item.codePart1.takeIf { it.isNotBlank() }, item.codePart2.takeIf { it.isNotBlank() })
                    .joinToString("")
                val titleText = if (code.isNotBlank()) "$code — ${item.description}" else item.description
                title.text = titleText

                val metaParts = mutableListOf<String>()
                if (item.unit.isNotBlank()) metaParts.add(item.unit)
                if (item.category.isNotBlank()) metaParts.add(item.category)
                item.rate?.takeIf { it.isNotBlank() }?.let { metaParts.add("$${it}") }
                meta.text = metaParts.joinToString(" • ")
                meta.isVisible = meta.text.isNotBlank()

                checkBox.isChecked = isSelected
                quantityValue.text = formatQuantity(quantity)

                itemView.setOnClickListener { toggleSelection(item, checkBox) }
                checkBox.setOnClickListener { toggleSelection(item, checkBox) }
                minus.setOnClickListener { adjustQuantity(item, -1.0, quantityValue, checkBox) }
                plus.setOnClickListener { adjustQuantity(item, 1.0, quantityValue, checkBox) }
            }

            private fun toggleSelection(item: ScopeCatalogItem, box: MaterialCheckBox) {
                if (selectedIds.contains(item.id)) {
                    selectedIds.remove(item.id)
                    box.isChecked = false
                } else {
                    selectedIds.add(item.id)
                    selectedQuantities.putIfAbsent(item.id, 1.0)
                    box.isChecked = true
                }
            }

            private fun adjustQuantity(
                item: ScopeCatalogItem,
                delta: Double,
                quantityView: TextView,
                box: MaterialCheckBox
            ) {
                val current = selectedQuantities[item.id] ?: 1.0
                val newValue = (current + delta).coerceAtLeast(1.0)
                selectedQuantities[item.id] = newValue
                quantityView.text = formatQuantity(newValue)
                if (!selectedIds.contains(item.id)) {
                    selectedIds.add(item.id)
                    box.isChecked = true
                }
            }
        }
    }

    private fun showAddPhotoOptions(anchor: View) {
        if (viewModel.selectedTab.value != RoomDetailTab.PHOTOS) {
            Log.d(TAG, "Ignoring add photo action; Photos tab not active")
            return
        }
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_add_photo_options, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_add_photo_standard -> {
                    navigateToBatchCapture()
                    true
                }

                R.id.menu_add_photo_flir -> {
                    navigateToFlirCapture()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun observeNavigationResults() {
        val handle = findNavController().currentBackStackEntry?.savedStateHandle ?: return
        handle.getLiveData<PhotosAddedResult>(PHOTOS_ADDED_RESULT_KEY).observe(viewLifecycleOwner) { result ->
            handle.remove<PhotosAddedResult>(PHOTOS_ADDED_RESULT_KEY)
            Log.d(TAG, "📥 Photos added result received: $result")
            viewModel.onPhotosAdded(result)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        Log.d(TAG, "🎨 State received: $state")
                        when (state) {
                            RoomDetailUiState.Loading -> showLoading()
                            is RoomDetailUiState.Ready -> {
                                Log.d(TAG, "📸 Ready state: ${state.photoCount} photos, ${state.albums.size} albums")
                                renderState(state)
                            }
                        }
                    }
                }
                launch {
                    viewModel.photoPagingData.collectLatest { pagingData ->
                        Log.d(TAG, "📥 Received new PagingData, submitting to adapter")
                        photoAdapter.submitData(viewLifecycleOwner.lifecycle, pagingData)
                    }
                }
                launch {
                    photoAdapter.loadStateFlow.collectLatest { loadStates ->
                        val refresh = loadStates.refresh
                        latestLoadState = refresh
                        val itemCount = photoAdapter.itemCount

                        // Track load timing
                        when (refresh) {
                            is LoadState.Loading -> {
                                if (itemCount == 0) {
                                    photoLoadStartTime = System.currentTimeMillis()
                                    Log.d(TAG, "📊 LoadState: refresh=Loading (start), itemCount=0")
                                }
                            }
                            is LoadState.NotLoading -> {
                                if (photoLoadStartTime > 0L) {
                                    val loadTime = System.currentTimeMillis() - photoLoadStartTime
                                    Log.d(TAG, "⏱️ Photo load completed in ${loadTime}ms, itemCount=$itemCount")
                                    photoLoadStartTime = 0L
                                } else {
                                    Log.d(TAG, "📊 LoadState: refresh=NotLoading, itemCount=$itemCount")
                                }
                            }
                            is LoadState.Error -> {
                                val loadTime = if (photoLoadStartTime > 0L) {
                                    System.currentTimeMillis() - photoLoadStartTime
                                } else 0L
                                Log.e(TAG, "❌ Photo load failed after ${loadTime}ms: ${refresh.error.message}", refresh.error)
                                photoLoadStartTime = 0L
                            }
                        }

                        Log.d(TAG, "📊 LoadState: refresh=$refresh, append=${loadStates.append}, itemCount=$itemCount")
                        photoLoadStateAdapter.loadState = loadStates.append
                        updatePhotoVisibility(refresh)
                    }
                }
                launch {
                    viewModel.selectedTab.collect { tab ->
                        applyTabState(tab)
                        updateToggleStyles(tab)
                    }
                }
                launch {
                    viewModel.roomDamages.collect { damages ->
                        latestDamages = damages
                        damagesAdapter.submitList(damages)
                        updateDamageVisibility()
                    }
                }
                launch {
                    viewModel.roomScopeGroups.collect { scopes ->
                        latestScopeGroups = scopes
                        scopeAdapter.submitList(scopes)
                        updateScopeVisibility()
                    }
                }
                launch {
                    viewModel.roomDamageSections.collect { sections ->
                        latestDamageSections = sections
                        roomDamageSectionAdapter.submitList(sections)
                        updateDamageAndScopeVisibility()
                    }
                }
                launch {
                    viewModel.isSnapshotRefreshing.collect { refreshing ->
                        snapshotRefreshInProgress = refreshing
                        Log.d(TAG, if (refreshing) "🌀 Snapshot refresh started" else "✅ Snapshot refresh finished")
                        updatePhotoVisibility()
                    }
                }
                launch {
                    viewModel.isAwaitingRealtimePhotos.collect { awaiting ->
                        awaitingRealtimePhotos = awaiting
                        Log.d(TAG, if (awaiting) "⏳ Awaiting realtime photo completion" else "✅ Realtime photo processing done")
                        updatePhotoVisibility()
                    }
                }
            }
        }
    }

    private fun showLoading() {
        latestPhotoCount = 0
        loadingOverlay.isVisible = true
        roomTitleBase = ""
        roomTitle.text = ""
        noteSummary.text = ""
        photosRecyclerView.isVisible = false
        damagesRecyclerView.isVisible = false
        scopeRecyclerView.isVisible = false
        placeholderContainer.isVisible = false
        placeholderImage.isVisible = false
        photosLoadingSpinner.isVisible = false
        addPhotoCard.isVisible = false
        addScopeCard.isVisible = false
        hideScopePickerInline()
        filterChipGroup.isVisible = false
        damageCategoryGroup.isVisible = false
        gridSectionTitle.isVisible = false
    }

    private fun renderState(state: RoomDetailUiState.Ready) {
        Log.d(TAG, "🎛 renderState: photoCount=${state.photoCount}, albums=${state.albums.size}")
        loadingOverlay.isVisible = false
        roomTitleBase = state.header.title
        roomTitle.text = state.header.title
        roomIcon.setImageResource(state.header.iconRes)
        updateRoomTitleForTab(viewModel.selectedTab.value)
        noteSummary.text = state.header.noteSummary
        noteCardSummary.text = state.header.noteSummary
        latestPhotoCount = state.photoCount

        // Albums - convert RoomAlbumItem to AlbumSection
        val albumSections = state.albums.map { albumItem ->
            AlbumSection(
                albumId = albumItem.id,
                name = albumItem.name,
                photoCount = albumItem.photoCount,
                thumbnailUrl = albumItem.thumbnailUrl
            )
        }
        Log.d(TAG, "📚 submitting albums: ${albumSections.size}")
        albumsAdapter.submitList(albumSections)
        albumsHeader.isVisible = albumSections.isNotEmpty() && viewModel.selectedTab.value == RoomDetailTab.PHOTOS
        albumsRecyclerView.isVisible = albumSections.isNotEmpty() && viewModel.selectedTab.value == RoomDetailTab.PHOTOS

        // Photos (visibility handled via load state listener)
        updatePhotoVisibility()
    }

    private fun applyTabState(tab: RoomDetailTab) {
        Log.d(TAG, "🧩 applyTabState: $tab, albums=${albumsAdapter.currentList.size}, photoCount=$latestPhotoCount")
        when (tab) {
            RoomDetailTab.PHOTOS -> {
                gridSectionTitle.text = getString(R.string.damage_assessment)
                gridSectionTitle.isVisible = true
                noteSummary.isVisible = true
                noteCard.isVisible = true
                damagesRecyclerView.isVisible = false
                scopeRecyclerView.isVisible = false
                hideScopePickerInline()
                albumsHeader.isVisible = albumsAdapter.currentList.isNotEmpty()
                albumsRecyclerView.isVisible = albumsAdapter.currentList.isNotEmpty()
                updatePhotoVisibility()
                filterChipGroup.isVisible = true
                totalEquipmentButton.isVisible = false
                addPhotoCard.isVisible = true
                addScopeCard.isVisible = false
                damageCategoryGroup.isVisible = false
            }
            RoomDetailTab.DAMAGES, RoomDetailTab.SCOPE -> {
                gridSectionTitle.text = getString(R.string.damages)
                gridSectionTitle.isVisible = false
                noteSummary.isVisible = false
                noteCard.isVisible = false
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                filterChipGroup.isVisible = false
                totalEquipmentButton.isVisible = false
                photosRecyclerView.isVisible = false
                hideScopePickerInline()
                photosLoadingSpinner.isVisible = false
                loadingOverlay.isVisible = false
                updateDamageAndScopeVisibility()
                photosLoadingSpinner.isVisible = false
                addPhotoCard.isVisible = false
                addScopeCard.isVisible = false
                damageCategoryGroup.isVisible = false
            }
        }
        updateRoomTitleForTab(tab)
    }

    private fun updateRoomTitleForTab(tab: RoomDetailTab) {
        val baseTitle = roomTitleBase.ifBlank { roomTitle.text.toString() }.trim()
        if (baseTitle.isBlank()) return
        val suffix = when (tab) {
            RoomDetailTab.PHOTOS -> getString(R.string.photos)
            RoomDetailTab.DAMAGES -> getString(R.string.damages)
            RoomDetailTab.SCOPE -> getString(R.string.scope_sheet)
        }
        roomTitle.text = "$baseTitle $suffix"
    }

    private fun openPhotoViewer(photo: RoomPhotoItem) {
        // Get all photo IDs from the adapter
        val photoIds = mutableListOf<Long>()
        for (i in 0 until photoAdapter.itemCount) {
            photoAdapter.peek(i)?.let { photoIds.add(it.id) }
        }

        // Find the index of the clicked photo
        val startIndex = photoIds.indexOf(photo.id).coerceAtLeast(0)

        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToPhotoViewerFragment(
                photoIds = photoIds.toLongArray(),
                startIndex = startIndex
            )
        findNavController().navigate(action)
    }

    private fun updatePhotoVisibility(loadState: LoadState? = null) {
        loadState?.let { latestLoadState = it }
        val activeTab = viewModel.selectedTab.value
        if (activeTab != RoomDetailTab.PHOTOS) {
            return
        }

        val waitingForRealtime = awaitingRealtimePhotos

        if (snapshotRefreshInProgress || waitingForRealtime) {
            loadingOverlay.isVisible = true
            photosRecyclerView.isVisible = false
            placeholderContainer.isVisible = false
            placeholderImage.isVisible = false
            photosLoadingSpinner.isVisible = false
            return
        }

        val adapterItemCount = photoAdapter.itemCount
        val hasPhotos = adapterItemCount > 0
        val effectiveLoadState = latestLoadState
        val isLoading = effectiveLoadState is LoadState.Loading && adapterItemCount == 0
        // Hide full-screen overlay, use inline spinner instead
        loadingOverlay.isVisible = false

        val showPlaceholder = adapterItemCount == 0 && latestPhotoCount == 0 && effectiveLoadState !is LoadState.Loading
        val showSpinner = isLoading || (adapterItemCount == 0 && latestPhotoCount > 0 && effectiveLoadState !is LoadState.Error)

        Log.d(
            TAG,
            "👁 updatePhotoVisibility: latestPhotoCount=$latestPhotoCount, adapterItemCount=$adapterItemCount, loadState=$effectiveLoadState, hasPhotos=$hasPhotos, showPlaceholder=$showPlaceholder, showSpinner=$showSpinner, snapshotRefreshing=$snapshotRefreshInProgress"
        )

        photosRecyclerView.isVisible = hasPhotos
        placeholderContainer.isVisible = showPlaceholder
        placeholderImage.isVisible = false
        placeholderText.text = if (showPlaceholder) getString(R.string.rocket_scan_empty_state) else ""
        photosLoadingSpinner.isVisible = showSpinner

        // Log RecyclerView dimensions to debug layout issues
        photosRecyclerView.post {
            Log.d(TAG, "📐 RecyclerView dimensions: width=${photosRecyclerView.width}, height=${photosRecyclerView.height}, measuredHeight=${photosRecyclerView.measuredHeight}, childCount=${photosRecyclerView.childCount}")
        }
    }

    companion object {
        private const val TAG = "RoomDetailFrag"
        const val PHOTOS_ADDED_RESULT_KEY = "room_detail_photos_added"
    }
}
