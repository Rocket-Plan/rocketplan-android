package com.example.rocketplan_android.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SupportChatFragment : Fragment() {

    private val args: SupportChatFragmentArgs by navArgs()

    private val viewModel: SupportChatViewModel by viewModels {
        SupportChatViewModel.provideFactory(
            requireActivity().application,
            args.conversationId
        )
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var inputContainer: LinearLayout
    private lateinit var closedBanner: LinearLayout

    private val adapter = SupportMessagesAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_support_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.messagesRecyclerView)
        messageInput = view.findViewById(R.id.messageInput)
        sendButton = view.findViewById(R.id.sendButton)
        inputContainer = view.findViewById(R.id.inputContainer)
        closedBanner = view.findViewById(R.id.closedBanner)

        val layoutManager = LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        sendButton.setOnClickListener {
            val message = messageInput.text?.toString() ?: return@setOnClickListener
            if (message.isNotBlank()) {
                viewModel.sendMessage(message)
                messageInput.text?.clear()
            }
        }

        setupMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    adapter.submitList(state.messages) {
                        // Scroll to bottom when new messages arrive
                        if (state.messages.isNotEmpty()) {
                            recyclerView.scrollToPosition(state.messages.size - 1)
                        }
                    }

                    // Show/hide input based on conversation status
                    if (state.isClosed) {
                        inputContainer.visibility = View.GONE
                        closedBanner.visibility = View.VISIBLE
                    } else {
                        inputContainer.visibility = View.VISIBLE
                        closedBanner.visibility = View.GONE
                    }

                    // Update toolbar title with subject
                    state.conversation?.let { conversation ->
                        activity?.title = conversation.subject
                    }
                }
            }
        }
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.support_chat_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_close_conversation -> {
                        showCloseConversationDialog()
                        true
                    }
                    else -> false
                }
            }

            override fun onPrepareMenu(menu: Menu) {
                // Hide close option if already closed
                menu.findItem(R.id.action_close_conversation)?.isVisible =
                    viewModel.uiState.value.isClosed == false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showCloseConversationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.support_close_conversation)
            .setMessage(R.string.support_close_conversation_confirm)
            .setPositiveButton(R.string.support_close) { dialog, _ ->
                viewModel.closeConversation()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
