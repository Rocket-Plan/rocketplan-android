package com.example.rocketplan_android.ui.esignature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import java.util.Calendar
import com.example.rocketplan_android.data.model.PdfFormFieldDto
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class PdfFormSignFragment : Fragment() {

    private val args: PdfFormSignFragmentArgs by navArgs()

    private val viewModel: PdfFormSignViewModel by viewModels {
        PdfFormSignViewModel.provideFactory(requireActivity().application, args.submissionUuid, args.projectId)
    }

    private lateinit var scrollView: ScrollView
    private lateinit var pdfPagesContainer: LinearLayout
    private lateinit var pageIndicator: TextView
    private lateinit var submitButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    // Track field inputs (fieldId -> EditText), field types, required fields, and signature data
    private val fieldInputs = mutableMapOf<String, EditText>()
    private val fieldTypes = mutableMapOf<String, String>()
    private val requiredFieldIds = mutableSetOf<String>()
    private var signatureRequired = false
    private var signatureData: String? = null
    private var signatureContainer: FrameLayout? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var hasRendered = false
    private var isReadOnly = false
    private var totalPageCount = 0

    // Lazy rendering state
    private val renderedPages = mutableSetOf<Int>()
    private var pageLayoutHeights = intArrayOf()
    private var displayWidth = 0
    private var renderScale = 1
    private var bitmapConfig = Bitmap.Config.ARGB_8888
    // The scale at which currently rendered bitmaps were produced (1.0 = fit-to-width)
    private var renderedAtScale = 1.0f
    private var reRenderRunnable: Runnable? = null
    private var lazyFields: List<PdfFormFieldDto> = emptyList()
    private var lazyExistingValues: Map<String, Any> = emptyMap()
    private var lazyTokenDefaults: Map<String, String> = emptyMap()
    private var lazyTokenLabels: Map<String, String> = emptyMap()
    // Persists user-edited field values across page eviction/re-render
    private val userEditedValues = mutableMapOf<String, String>()
    // Tracks field types for all fields (persists across eviction for submitForm validation)
    private val allFieldTypes = mutableMapOf<String, String>()
    // Maps fieldId -> 0-based page index (persists across eviction for navigation)
    private val fieldPageMap = mutableMapOf<String, Int>()
    private companion object {
        private const val TAG = "PdfFormSign"
        private const val PAGE_BUFFER = 1 // render this many pages above/below visible
    }

    // Zoom + pan state
    private var currentScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pdf_form_sign, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        scrollView = root.findViewById(R.id.scrollView)
        pdfPagesContainer = root.findViewById(R.id.pdfPagesContainer)
        pageIndicator = root.findViewById(R.id.pageIndicator)
        submitButton = root.findViewById(R.id.submitButton)
        nextButton = root.findViewById(R.id.nextButton)
        loadingOverlay = root.findViewById(R.id.loadingOverlay)
        loadingText = root.findViewById(R.id.loadingText)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        submitButton.setOnClickListener { submitForm() }
        nextButton.setOnClickListener { scrollToNextEmptyRequiredField() }
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!isAdded) return@addOnScrollChangedListener
            updatePageIndicator()
            updateVisiblePages()
        }

        // Pinch-to-zoom
        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // When starting to zoom, absorb ScrollView's scroll into panY
                if (currentScale <= 1.01f && scrollView.scrollY != 0) {
                    panY = -scrollView.scrollY.toFloat()
                    scrollView.scrollTo(0, 0)
                    applyPan()
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = currentScale
                currentScale = (currentScale * detector.scaleFactor).coerceIn(1.0f, 3.0f)
                pdfPagesContainer.pivotX = 0f
                pdfPagesContainer.pivotY = 0f
                pdfPagesContainer.scaleX = currentScale
                pdfPagesContainer.scaleY = currentScale
                // Adjust pan so the focal point stays stable
                if (oldScale != currentScale) {
                    val focusX = detector.focusX
                    val focusY = detector.focusY
                    panX -= focusX * (currentScale / oldScale - 1f)
                    panY -= focusY * (currentScale / oldScale - 1f)
                    clampPan()
                    applyPan()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scheduleHiResReRender()
            }
        })

        // Double-tap to reset zoom
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                scheduleHiResReRender()
                return true
            }
        })

        scrollView.setOnTouchListener { view, event ->
            scaleGestureDetector?.onTouchEvent(event)
            gestureDetector?.onTouchEvent(event)

            // Handle panning when zoomed in
            if (currentScale > 1.01f) {
                // Ensure ScrollView scroll is absorbed
                if (scrollView.scrollY != 0) {
                    panY -= scrollView.scrollY.toFloat()
                    scrollView.scrollTo(0, 0)
                    clampPan()
                    applyPan()
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointerId = event.getPointerId(0)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isPanning = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scaleGestureDetector?.isInProgress == true) return@setOnTouchListener true
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex < 0) return@setOnTouchListener false
                        val dx = event.getX(pointerIndex) - lastTouchX
                        val dy = event.getY(pointerIndex) - lastTouchY
                        if (!isPanning && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isPanning = true
                        }
                        if (isPanning) {
                            panX += dx
                            panY += dy
                            clampPan()
                            applyPan()
                            lastTouchX = event.getX(pointerIndex)
                            lastTouchY = event.getY(pointerIndex)
                            // Suppress ScrollView's own scrolling
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val pointerIndex = event.actionIndex
                        if (event.getPointerId(pointerIndex) == activePointerId) {
                            val newIndex = if (pointerIndex == 0) 1 else 0
                            if (newIndex < event.pointerCount) {
                                activePointerId = event.getPointerId(newIndex)
                                lastTouchX = event.getX(newIndex)
                                lastTouchY = event.getY(newIndex)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        isPanning = false
                    }
                }
            }
            false
        }
    }

    private fun clampPan() {
        val containerW = pdfPagesContainer.width.toFloat()
        val containerH = pdfPagesContainer.height.toFloat()
        val viewW = scrollView.width.toFloat()
        val viewH = scrollView.height.toFloat()
        // With pivot at (0,0), scaled content spans [0, containerW*scale].
        // panX shifts it, so visible range is [-panX, -panX + viewW].
        // To see right edge: -panX + viewW >= containerW*scale → panX >= -(containerW*scale - viewW)
        // To see left edge: -panX <= 0 → panX >= 0... but we want panX <= 0 to go right
        val minPanX = -(containerW * currentScale - viewW).coerceAtLeast(0f)
        panX = panX.coerceIn(minPanX, 0f)
        val minPanY = -(containerH * currentScale - viewH).coerceAtLeast(0f)
        panY = panY.coerceIn(minPanY, 0f)
    }

    private fun applyPan() {
        pdfPagesContainer.translationX = panX
        pdfPagesContainer.translationY = panY
    }

    private fun resetZoom() {
        // Restore scroll position from panY before resetting
        val restoreScrollY = (-panY).toInt().coerceIn(0, (pdfPagesContainer.height - scrollView.height).coerceAtLeast(0))
        renderedAtScale = 1.0f
        currentScale = 1.0f
        panX = 0f
        panY = 0f
        pdfPagesContainer.scaleX = 1.0f
        pdfPagesContainer.scaleY = 1.0f
        pdfPagesContainer.pivotX = 0f
        pdfPagesContainer.pivotY = 0f
        pdfPagesContainer.translationX = 0f
        pdfPagesContainer.translationY = 0f
        scrollView.scrollTo(0, restoreScrollY)
    }

    private fun scheduleHiResReRender() {
        // Debounce: cancel any pending re-render and schedule a new one
        reRenderRunnable?.let { scrollView.removeCallbacks(it) }
        reRenderRunnable = Runnable { reRenderVisiblePagesAtCurrentScale() }
        scrollView.postDelayed(reRenderRunnable!!, 200)
    }

    private fun reRenderVisiblePagesAtCurrentScale() {
        val renderer = pdfRenderer ?: return
        if (totalPageCount == 0) return

        // If scale hasn't changed significantly from last render, skip
        val targetScale = currentScale
        if (Math.abs(targetScale - renderedAtScale) < 0.1f) return

        Log.d(TAG, "reRenderVisiblePages: re-rendering at scale=$targetScale (was $renderedAtScale)")
        renderedAtScale = targetScale

        // Re-render only currently rendered pages at the new scale
        for (pageIndex in renderedPages.toList()) {
            val pageFrame = pdfPagesContainer.getChildAt(pageIndex) as? FrameLayout ?: continue

            // Find and replace the ImageView (always child 0)
            val oldImageView = (0 until pageFrame.childCount)
                .map { pageFrame.getChildAt(it) }
                .filterIsInstance<ImageView>()
                .firstOrNull() ?: continue

            try {
                val page = renderer.openPage(pageIndex)
                val pageWidth = page.width
                val pageHeight = page.height
                val fitScale = displayWidth.toFloat() / pageWidth
                // Render at current zoom level, capped at 2x to avoid OOM
                val effectiveScale = targetScale.coerceAtMost(2.0f)
                val bitmapWidth = (displayWidth * effectiveScale).toInt()
                val bitmapHeight = (pageHeight * fitScale * effectiveScale).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, bitmapConfig)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Recycle old bitmap
                val oldDrawable = oldImageView.drawable
                oldImageView.setImageDrawable(null)
                if (oldDrawable is BitmapDrawable) {
                    oldDrawable.bitmap?.recycle()
                }

                oldImageView.setImageBitmap(bitmap)
                Log.d(TAG, "reRenderVisiblePages: page $pageIndex re-rendered at ${bitmapWidth}x${bitmapHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "reRenderVisiblePages: failed for page $pageIndex", e)
            }
        }
    }

    private fun allRequiredFieldsFilled(): Boolean {
        for (fieldId in requiredFieldIds) {
            // Check on-screen EditText first, fall back to persisted userEditedValues
            val value = fieldInputs[fieldId]?.text?.toString()
                ?: userEditedValues[fieldId]
            if (value.isNullOrBlank()) return false
        }
        if (signatureRequired && signatureData.isNullOrBlank()) return false
        return true
    }

    private fun updateButtonVisibility() {
        if (isReadOnly) {
            submitButton.isVisible = false
            nextButton.isVisible = false
            return
        }
        if (allRequiredFieldsFilled()) {
            submitButton.isVisible = true
            nextButton.isVisible = false
        } else {
            submitButton.isVisible = false
            nextButton.isVisible = true
            val remaining = requiredFieldIds.count { (fieldInputs[it]?.text?.toString() ?: userEditedValues[it]).isNullOrBlank() } +
                if (signatureRequired && signatureData.isNullOrBlank()) 1 else 0
            nextButton.text = getString(R.string.esignature_required_fields_remaining, remaining)
        }
    }

    private fun scrollToNextEmptyRequiredField() {
        // Find the first empty required text field (check both on-screen and off-screen)
        for (fieldId in requiredFieldIds) {
            val editText = fieldInputs[fieldId]
            val value = editText?.text?.toString() ?: userEditedValues[fieldId]
            if (!value.isNullOrBlank()) continue

            // Field is empty — if on-screen, scroll to it directly
            if (editText != null) {
                val location = IntArray(2)
                editText.getLocationInWindow(location)
                val scrollViewLocation = IntArray(2)
                scrollView.getLocationInWindow(scrollViewLocation)
                val relativeY = location[1] - scrollViewLocation[1] + scrollView.scrollY - scrollView.height / 3
                scrollView.smoothScrollTo(0, relativeY.coerceAtLeast(0))
                editText.requestFocus()
                return
            }

            // Field is on a non-rendered page — scroll to that page's position
            val pageIndex = fieldPageMap[fieldId] ?: continue
            if (pageIndex in 0 until pdfPagesContainer.childCount) {
                val pageView = pdfPagesContainer.getChildAt(pageIndex)
                scrollView.smoothScrollTo(0, pageView.top)
                // After scroll + lazy render, the field will appear and user can interact
            }
            return
        }
        // If all text fields are filled but signature is missing, prompt signature
        if (signatureRequired && signatureData.isNullOrBlank()) {
            showSignatureDialog()
        }
    }

    private fun updatePageIndicator() {
        if (totalPageCount <= 0 || pdfPagesContainer.childCount == 0) return
        val scrollY = scrollView.scrollY
        var currentPage = 1
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            if (child.top <= scrollY + scrollView.height / 2) {
                currentPage = i + 1
            }
        }
        pageIndicator.text = getString(R.string.esignature_page_indicator, currentPage, totalPageCount)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    Log.d(TAG, "uiState changed: ${state::class.simpleName}")
                    when (state) {
                        is PdfFormSignUiState.Loading -> {
                            loadingOverlay.isVisible = true
                            loadingText.text = getString(R.string.esignature_loading)
                        }
                        is PdfFormSignUiState.Ready -> {
                            Log.d(TAG, "Ready: pdfFile=${state.pdfFile.absolutePath} exists=${state.pdfFile.exists()} size=${state.pdfFile.length()} fields=${state.fields.size}")
                            loadingOverlay.isVisible = false
                            val isSigned = state.submission.status?.lowercase() == "signed"
                            isReadOnly = isSigned
                            if (!hasRendered) {
                                // Signed PDFs have all values baked in — skip field overlays
                                val fields = if (isSigned) emptyList() else state.fields
                                renderPdfWithFields(state.pdfFile, fields, state.submission, state.tokenDefaults, state.tokenLabels)
                                hasRendered = true
                            }
                        }
                        is PdfFormSignUiState.Error -> {
                            Log.e(TAG, "Error state: ${state.message}")
                            loadingOverlay.isVisible = false
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSubmitting.collect { isSubmitting ->
                    submitButton.isEnabled = !isSubmitting
                    if (isSubmitting) {
                        loadingOverlay.isVisible = true
                        loadingText.text = getString(R.string.esignature_signing)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is PdfFormSignEvent.FormSigned -> {
                            loadingOverlay.isVisible = false
                            Toast.makeText(requireContext(), R.string.esignature_signed_success, Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        }
                        is PdfFormSignEvent.SignFailed -> {
                            loadingOverlay.isVisible = false
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderPdfWithFields(
        pdfFile: File,
        fields: List<PdfFormFieldDto>,
        submission: PdfFormSubmissionDto,
        tokenDefaults: Map<String, String>,
        tokenLabels: Map<String, String>
    ) {
        Log.d(TAG, "renderPdfWithFields: file=${pdfFile.absolutePath} exists=${pdfFile.exists()} size=${pdfFile.length()} fields=${fields.size}")
        pdfPagesContainer.removeAllViews()
        fieldInputs.clear()
        fieldTypes.clear()
        requiredFieldIds.clear()
        renderedPages.clear()
        userEditedValues.clear()
        allFieldTypes.clear()
        fieldPageMap.clear()
        signatureRequired = false

        // Hydrate existing signature from submission
        if (signatureData == null && !submission.signatureData.isNullOrBlank()) {
            signatureData = submission.signatureData
            Log.d(TAG, "renderPdfWithFields: hydrated signature from submission")
        }

        // Store for lazy rendering
        lazyFields = fields
        lazyExistingValues = submission.fieldValuesById ?: emptyMap()
        lazyTokenDefaults = tokenDefaults
        lazyTokenLabels = tokenLabels

        try {
            // Close any previous renderer/fd to prevent leaks
            pdfRenderer?.close()
            pdfFileDescriptor?.close()

            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfFileDescriptor = fd
            pdfRenderer = PdfRenderer(fd)
            val renderer = pdfRenderer ?: return
            val pageCount = renderer.pageCount
            totalPageCount = pageCount
            Log.d(TAG, "renderPdfWithFields: pageCount=$pageCount")
            pageIndicator.text = getString(R.string.esignature_page_indicator, 1, pageCount)

            displayWidth = resources.displayMetrics.widthPixels - dpToPx(16)

            // Pre-measure all pages and compute layout heights
            pageLayoutHeights = IntArray(pageCount)
            var maxPagePixels1x = 0L
            for (i in 0 until pageCount) {
                val probe = renderer.openPage(i)
                val probeScale = displayWidth.toFloat() / probe.width
                pageLayoutHeights[i] = (probe.height * probeScale).toInt()
                val pixels = displayWidth.toLong() * pageLayoutHeights[i].toLong()
                probe.close()
                if (pixels > maxPagePixels1x) maxPagePixels1x = pixels
            }

            // Always render at 1x initially; re-render at zoom level after gesture ends
            renderScale = 1
            renderedAtScale = 1.0f
            bitmapConfig = Bitmap.Config.ARGB_8888
            Log.d(TAG, "renderPdfWithFields: pageCount=$pageCount renderScale=1 (re-renders on zoom)")

            // Create placeholder frames for all pages (correct height, no bitmap yet)
            for (pageIndex in 0 until pageCount) {
                val layoutHeight = pageLayoutHeights[pageIndex]
                val pageFrame = FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        layoutHeight
                    ).apply {
                        bottomMargin = dpToPx(8)
                    }
                    setBackgroundColor(Color.WHITE)
                    tag = pageIndex // store page index for identification
                }
                pdfPagesContainer.addView(pageFrame)
            }

            // Pre-scan ALL fields to register required fields, types, and resolve
            // initial values so that allRequiredFieldsFilled() and submitForm()
            // work correctly even for never-rendered pages.
            val existingValues = submission.fieldValuesById ?: emptyMap()
            for (field in fields) {
                Log.d(TAG, "Field: id=${field.id} name=${field.fieldName} type=${field.fieldType} required=${field.required} userFillable=${field.userFillable}")
                val fieldType = field.fieldType?.lowercase()
                val isSignature = fieldType == "signature"
                // Match iOS: signature fields always shown, others respect userFillable
                if (field.userFillable != true && !isSignature) continue
                if (isSignature) {
                    // Signature is always required — a form with a signature field
                    // should not be submittable without actually signing it.
                    signatureRequired = true
                    continue // signature is handled separately, not a text field
                }
                val idStr = field.id?.toString() ?: continue
                // Register type
                if (fieldType != null) {
                    allFieldTypes[idStr] = fieldType
                }
                // Register page
                fieldPageMap[idStr] = (field.page ?: 1) - 1
                // Register required
                if (field.required == true) {
                    requiredFieldIds.add(idStr)
                }
                // Resolve initial value (same logic as addFieldOverlay)
                val existingValue = existingValues[idStr]?.toString()?.takeIf { it.isNotBlank() }
                val defaultValue = tokenDefaults[field.fieldName ?: ""]
                val resolved = existingValue ?: defaultValue ?: ""
                if (resolved.isNotBlank()) {
                    userEditedValues[idStr] = resolved
                }
            }

            // Render initially visible pages after layout
            pdfPagesContainer.post { updateVisiblePages() }
        } catch (e: Exception) {
            Log.e(TAG, "renderPdfWithFields: EXCEPTION rendering PDF", e)
            Toast.makeText(requireContext(), R.string.esignature_load_failed, Toast.LENGTH_LONG).show()
        }
        Log.d(TAG, "renderPdfWithFields: done, container childCount=${pdfPagesContainer.childCount} signatureRequired=$signatureRequired")
        updateButtonVisibility()
    }

    private fun updateVisiblePages() {
        val renderer = pdfRenderer ?: return
        if (totalPageCount == 0) return

        val scrollY = scrollView.scrollY
        val viewHeight = scrollView.height
        if (viewHeight == 0) return

        // Determine which pages are visible
        var firstVisible = -1
        var lastVisible = -1
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val top = child.top
            val bottom = child.bottom
            if (bottom > scrollY && top < scrollY + viewHeight) {
                if (firstVisible == -1) firstVisible = i
                lastVisible = i
            }
        }
        if (firstVisible == -1) return

        val renderStart = (firstVisible - PAGE_BUFFER).coerceAtLeast(0)
        val renderEnd = (lastVisible + PAGE_BUFFER).coerceAtMost(totalPageCount - 1)

        // Render pages that should be visible
        for (i in renderStart..renderEnd) {
            if (i !in renderedPages) {
                renderPage(i, renderer)
            }
        }

        // Clear pages that are far from the viewport
        val clearThreshold = PAGE_BUFFER + 2
        val pagesToClear = renderedPages.filter { it < firstVisible - clearThreshold || it > lastVisible + clearThreshold }
        for (i in pagesToClear) {
            clearPage(i)
        }
    }

    private fun renderPage(pageIndex: Int, renderer: PdfRenderer) {
        if (pageIndex < 0 || pageIndex >= totalPageCount) return
        val pageFrame = pdfPagesContainer.getChildAt(pageIndex) as? FrameLayout ?: return

        try {
            val page = renderer.openPage(pageIndex)
            val pageWidth = page.width
            val pageHeight = page.height
            val scale = displayWidth.toFloat() / pageWidth
            val bitmapWidth = displayWidth * renderScale
            val bitmapHeight = (pageHeight * scale * renderScale).toInt()
            val layoutHeight = pageLayoutHeights[pageIndex]

            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, bitmapConfig)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Add image as first child
            val imageView = ImageView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            pageFrame.addView(imageView, 0)

            // Overlay fields for this page — merge server values with user edits (user edits win)
            val mergedValues: Map<String, Any> = lazyExistingValues + userEditedValues
            val pageFields = lazyFields.filter { (it.page ?: 1) - 1 == pageIndex && (it.userFillable == true || it.fieldType?.lowercase() == "signature") }
            for (field in pageFields) {
                addFieldOverlay(pageFrame, field, scale, displayWidth, layoutHeight, mergedValues, lazyTokenDefaults, lazyTokenLabels)
            }

            renderedPages.add(pageIndex)
            Log.d(TAG, "renderPage: rendered page $pageIndex (${renderedPages.size} total in memory)")
        } catch (e: Exception) {
            Log.e(TAG, "renderPage: failed to render page $pageIndex", e)
        }
        updateButtonVisibility()
    }

    private fun clearPage(pageIndex: Int) {
        val pageFrame = pdfPagesContainer.getChildAt(pageIndex) as? FrameLayout ?: return

        // Save field values into persistent userEditedValues before clearing
        val fieldsOnPage = mutableListOf<String>()
        for ((fieldId, editText) in fieldInputs) {
            if (editText.parent == pageFrame) {
                userEditedValues[fieldId] = editText.text?.toString() ?: ""
                fieldsOnPage.add(fieldId)
            }
        }
        // Remove from fieldInputs (but NOT from allFieldTypes or requiredFieldIds)
        for (fieldId in fieldsOnPage) {
            fieldInputs.remove(fieldId)
            fieldTypes.remove(fieldId)
        }

        // Remove views and recycle bitmap
        for (i in pageFrame.childCount - 1 downTo 0) {
            val child = pageFrame.getChildAt(i)
            if (child is ImageView) {
                val bmp = child.drawable
                child.setImageDrawable(null)
                if (bmp is BitmapDrawable) {
                    bmp.bitmap?.recycle()
                }
            }
            pageFrame.removeViewAt(i)
        }

        renderedPages.remove(pageIndex)
        Log.d(TAG, "clearPage: cleared page $pageIndex, saved ${fieldsOnPage.size} field values to userEditedValues (${renderedPages.size} pages in memory)")
    }

    private fun addFieldOverlay(
        pageFrame: FrameLayout,
        field: PdfFormFieldDto,
        scale: Float,
        pageWidth: Int,
        pageHeight: Int,
        existingValues: Map<String, Any>,
        tokenDefaults: Map<String, String>,
        tokenLabels: Map<String, String>
    ) {
        val x = ((field.x ?: 0f) * scale).toInt()
        val width = ((field.width ?: 100f) * scale).toInt()
        val height = ((field.height ?: 30f) * scale).toInt()
        // PDF coordinates have origin at bottom-left (y increases upward),
        // but Android layout has origin at top-left (y increases downward).
        // Flip the y-axis: topMargin = pageHeight - (scaledY + fieldHeight)
        val scaledY = ((field.y ?: 0f) * scale).toInt()
        val y = pageHeight - scaledY - height

        val fieldName = field.fieldName ?: ""
        val isRequired = field.required == true
        val baseName = tokenLabels[fieldName] ?: fieldName.removePrefix("{{").removeSuffix("}}")
            .replace("_", " ").replaceFirstChar { it.uppercase() }
        val friendlyName = if (isRequired) "$baseName *" else baseName
        val defaultValue = tokenDefaults[fieldName]

        when (field.fieldType?.lowercase()) {
            "signature" -> {
                signatureRequired = true
                val sigContainer = FrameLayout(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(width, height).apply {
                        leftMargin = x
                        topMargin = y
                    }
                    setOnClickListener { showSignatureDialog() }
                }
                signatureContainer = sigContainer
                updateSignatureView(sigContainer)
                pageFrame.addView(sigContainer)
            }
            "date" -> {
                val editText = createFieldEditText(x, y, width, height, field)
                editText.inputType = InputType.TYPE_NULL
                editText.isFocusable = false
                editText.hint = friendlyName
                val existingValue = field.id?.let { id ->
                    existingValues[id.toString()]?.toString()?.takeIf { it.isNotBlank() }
                }
                editText.setText(existingValue ?: defaultValue ?: "")
                editText.setOnClickListener { showDatePicker(editText) }
                pageFrame.addView(editText)
                field.id?.let { fieldInputs[it.toString()] = editText }
            }
            "phone" -> {
                val editText = createFieldEditText(x, y, width, height, field)
                editText.inputType = InputType.TYPE_CLASS_PHONE
                editText.hint = friendlyName
                val existingValue = field.id?.let { id ->
                    existingValues[id.toString()]?.toString()?.takeIf { it.isNotBlank() }
                }
                editText.setText(existingValue ?: defaultValue ?: "")
                pageFrame.addView(editText)
                field.id?.let { fieldInputs[it.toString()] = editText }
            }
            "email" -> {
                val editText = createFieldEditText(x, y, width, height, field)
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                editText.hint = friendlyName
                val existingValue = field.id?.let { id ->
                    existingValues[id.toString()]?.toString()?.takeIf { it.isNotBlank() }
                }
                editText.setText(existingValue ?: defaultValue ?: "")
                pageFrame.addView(editText)
                field.id?.let { fieldInputs[it.toString()] = editText }
            }
            else -> {
                // text field
                val editText = createFieldEditText(x, y, width, height, field)
                editText.inputType = InputType.TYPE_CLASS_TEXT
                editText.hint = friendlyName
                val existingValue = field.id?.let { id ->
                    existingValues[id.toString()]?.toString()?.takeIf { it.isNotBlank() }
                }
                editText.setText(existingValue ?: defaultValue ?: "")
                pageFrame.addView(editText)
                field.id?.let { fieldInputs[it.toString()] = editText }
            }
        }

        // Track field type for validation and required status
        field.id?.let { id ->
            val idStr = id.toString()
            field.fieldType?.lowercase()?.let { type ->
                fieldTypes[idStr] = type
                allFieldTypes[idStr] = type
            }
            if (isRequired) {
                requiredFieldIds.add(idStr)
                // Add text watcher to update button visibility when required fields change
                fieldInputs[idStr]?.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { updateButtonVisibility() }
                })
            }
        }
    }

    private fun showDatePicker(editText: EditText) {
        val cal = Calendar.getInstance()
        // Try to parse existing value to pre-select in picker
        val existing = editText.text?.toString()
        if (!existing.isNullOrBlank()) {
            try {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
                sdf.parse(existing)?.let { cal.time = it }
            } catch (_: Exception) { }
        }
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                cal.set(year, month, day)
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
                editText.setText(sdf.format(cal.time))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun createFieldEditText(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        field: PdfFormFieldDto
    ): EditText {
        val isRequired = field.required == true
        return EditText(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = x
                topMargin = y
            }
            background = GradientDrawable().apply {
                if (isRequired) {
                    setColor(Color.parseColor("#14FF0000")) // light red tint
                    setStroke(dpToPx(1), Color.parseColor("#CCFF0000")) // red border
                } else {
                    setColor(Color.parseColor("#10000000"))
                }
                cornerRadius = dpToPx(2).toFloat()
            }
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
            textSize = (field.fontSize ?: 10f) * 0.8f
            setTextColor(Color.BLACK)
            isSingleLine = true
        }
    }

    private fun showSignatureDialog() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setCancelable(false) // Must use Cancel or Done buttons
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        // Header row: Cancel | Title | Clear
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelButton = TextView(requireContext()).apply {
            text = getString(android.R.string.cancel)
            textSize = 16f
            setTextColor(resources.getColor(R.color.main_purple, null))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(cancelButton)

        val title = TextView(requireContext()).apply {
            text = getString(R.string.esignature_draw_signature)
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(title)

        val clearButton = TextView(requireContext()).apply {
            text = getString(R.string.esignature_clear_signature)
            textSize = 16f
            setTextColor(Color.RED)
        }
        headerRow.addView(clearButton)

        container.addView(headerRow)

        // Divider
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(16)
            }
            setBackgroundColor(Color.parseColor("#4D808080"))
        }
        container.addView(divider)

        // Canvas with rounded border
        val canvasWrapper = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(250)
            )
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#4D808080"))
            }
        }

        val canvas = SignatureCanvasView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        canvasWrapper.addView(canvas)
        container.addView(canvasWrapper)

        clearButton.setOnClickListener { canvas.clear() }

        // "Sign above the line" guidance
        val signLine = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(-24) // overlap into canvas area via negative margin
                leftMargin = dpToPx(16)
                rightMargin = dpToPx(16)
            }
            setBackgroundColor(Color.parseColor("#66808080"))
        }
        container.addView(signLine)

        val signCaption = TextView(requireContext()).apply {
            text = getString(R.string.esignature_sign_above_line)
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
        }
        container.addView(signCaption)

        // Full-width Done button
        val doneButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.esignature_done)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
            setBackgroundColor(resources.getColor(R.color.main_purple, null))
            setTextColor(Color.WHITE)
            cornerRadius = dpToPx(8)
            setOnClickListener {
                signatureData = canvas.toBase64Png()
                signatureContainer?.let { updateSignatureView(it) }
                updateButtonVisibility()
                dialog.dismiss()
            }
        }
        container.addView(doneButton)

        // Monitor canvas for strokes to enable/disable Done button
        canvas.setOnDrawListener { isEmpty ->
            doneButton.isEnabled = !isEmpty
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun updateSignatureView(container: FrameLayout) {
        container.removeAllViews()
        val data = signatureData
        if (data != null) {
            // Decode base64 and render the signature image
            val base64Str = data.removePrefix("data:image/png;base64,")
            val bytes = try {
                Base64.decode(base64Str, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "updateSignatureView: invalid base64 signature data", e)
                null
            }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                val imageView = ImageView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                container.addView(imageView)
            }
        } else {
            // Show "Tap to sign" label
            val label = TextView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                text = getString(R.string.esignature_tap_to_sign)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#20000000"))
                setTextColor(Color.parseColor("#9A00FF"))
                textSize = 12f
            }
            container.addView(label)
        }
    }

    private fun submitForm() {
        // Sync current on-screen field values into userEditedValues
        for ((fieldId, editText) in fieldInputs) {
            userEditedValues[fieldId] = editText.text?.toString() ?: ""
        }

        // Validate ALL fields (on-screen and off-screen) for email/phone format
        for ((fieldId, value) in userEditedValues) {
            if (value.isBlank()) continue

            if (allFieldTypes[fieldId] == "email" && !Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
                // If field is on-screen, show inline error; otherwise show toast
                val editText = fieldInputs[fieldId]
                if (editText != null) {
                    editText.error = "Invalid email address"
                    editText.requestFocus()
                } else {
                    Toast.makeText(requireContext(), "Invalid email address in a field on another page", Toast.LENGTH_LONG).show()
                }
                return
            }

            if (allFieldTypes[fieldId] == "phone" && !Patterns.PHONE.matcher(value).matches()) {
                val editText = fieldInputs[fieldId]
                if (editText != null) {
                    editText.error = "Invalid phone number"
                    editText.requestFocus()
                } else {
                    Toast.makeText(requireContext(), "Invalid phone number in a field on another page", Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        // Build complete payload — omit blank optional fields (server rejects empty strings)
        val fieldValues = mutableMapOf<String, Any>()
        for ((fieldId, value) in userEditedValues) {
            if (value.isNotBlank() || fieldId in requiredFieldIds) {
                fieldValues[fieldId] = value
            }
        }

        // Server expects raw base64 PNG, not a data URI
        val rawBase64 = signatureData?.removePrefix("data:image/png;base64,")
        viewModel.submitSignature(fieldValues, rawBase64)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        reRenderRunnable?.let { scrollView.removeCallbacks(it) }
        reRenderRunnable = null
        pdfRenderer?.close()
        pdfRenderer = null
        pdfFileDescriptor?.close()
        pdfFileDescriptor = null
        super.onDestroyView()
    }

}
