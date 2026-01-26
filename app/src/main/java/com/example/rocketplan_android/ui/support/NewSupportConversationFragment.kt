package com.example.rocketplan_android.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NewSupportConversationFragment : Fragment() {

    private val viewModel: NewSupportConversationViewModel by viewModels {
        NewSupportConversationViewModel.provideFactory(requireActivity().application)
    }

    private lateinit var subjectInput: TextInputEditText
    private lateinit var messageInput: TextInputEditText
    private lateinit var submitButton: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_new_support_conversation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subjectInput = view.findViewById(R.id.subjectInput)
        messageInput = view.findViewById(R.id.messageInput)
        submitButton = view.findViewById(R.id.submitButton)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        submitButton.setOnClickListener {
            submitConversation()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    // Handle loading state
                    loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    submitButton.isEnabled = !state.isLoading

                    // Handle error
                    state.error?.let { error ->
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                        viewModel.clearError()
                    }

                    // Navigate to chat on success
                    state.createdConversationId?.let { conversationId ->
                        findNavController().navigate(
                            NewSupportConversationFragmentDirections.actionToChat(conversationId)
                        )
                    }
                }
            }
        }
    }

    private fun submitConversation() {
        val subject = subjectInput.text?.toString() ?: ""
        val message = messageInput.text?.toString() ?: ""

        if (subject.isBlank()) {
            subjectInput.error = getString(R.string.support_subject_required)
            return
        }

        if (message.isBlank()) {
            messageInput.error = getString(R.string.support_message_required)
            return
        }

        viewModel.createConversation(subject, message)
    }
}
