package com.example.rocketplan_android.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.databinding.FragmentAccountTypeBinding

class AccountTypeFragment : Fragment() {

    private var _binding: FragmentAccountTypeBinding? = null
    private val binding get() = _binding!!

    private val args: AccountTypeFragmentArgs by navArgs()
    private val viewModel: AccountTypeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountTypeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.createCompanyCard.setOnClickListener {
            val action = AccountTypeFragmentDirections
                .actionAccountTypeFragmentToFinalDetailsFragment(
                    userId = args.userId,
                    email = args.email,
                    isCreating = true,
                    phone = args.phone,
                    countryCode = args.countryCode
                )
            findNavController().navigate(action)
        }

        binding.joinCompanyCard.setOnClickListener {
            val action = AccountTypeFragmentDirections
                .actionAccountTypeFragmentToJoinCompanyFragment(
                    userId = args.userId,
                    email = args.email,
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
