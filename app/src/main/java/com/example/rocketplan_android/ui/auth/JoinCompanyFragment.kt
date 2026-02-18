package com.example.rocketplan_android.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.databinding.FragmentJoinCompanyBinding

class JoinCompanyFragment : Fragment() {

    private var _binding: FragmentJoinCompanyBinding? = null
    private val binding get() = _binding!!

    private val args: JoinCompanyFragmentArgs by navArgs()
    private val viewModel: JoinCompanyViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJoinCompanyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.createInsteadButton.setOnClickListener {
            val action = JoinCompanyFragmentDirections
                .actionJoinCompanyFragmentToFinalDetailsFragment(
                    userId = args.userId,
                    email = args.email,
                    isCreating = true,
                    phone = args.phone,
                    countryCode = args.countryCode
                )
            findNavController().navigate(action)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
