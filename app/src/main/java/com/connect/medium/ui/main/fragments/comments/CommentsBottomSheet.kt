package com.connect.medium.ui.main.fragments.comments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.connect.medium.databinding.FragmentCommentsBottomSheetBinding
import com.connect.medium.ui.main.adapters.CommentAdapter
import com.connect.medium.ui.main.fragments.home.HomeViewModel
import com.connect.medium.ui.main.fragments.home.HomeViewModelFactory
import com.connect.medium.utils.Resource
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CommentsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentCommentsBottomSheetBinding? = null
    private val binding get() = _binding!!

    // Shared with HomeFragment — same instance via activityViewModels
    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModelFactory(requireActivity().application)
    }

    private lateinit var commentAdapter: CommentAdapter
    private lateinit var postId: String
    private lateinit var postAuthorUid: String

    companion object {
        const val TAG = "CommentsBottomSheet"
        private const val ARG_POST_ID = "post_id"
        private const val ARG_POST_AUTHOR_UID = "post_author_uid"

        fun newInstance(postId: String, postAuthorUid: String): CommentsBottomSheet {
            return CommentsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_POST_ID, postId)
                    putString(ARG_POST_AUTHOR_UID, postAuthorUid)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommentsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postId = arguments?.getString(ARG_POST_ID) ?: return
        postAuthorUid = arguments?.getString(ARG_POST_AUTHOR_UID) ?: return

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        viewModel.loadComments(postId)
    }

    // Make bottom sheet taller — 90% of screen height
    override fun onStart() {
        super.onStart()
        dialog?.let {
            val bottomSheet = it.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                val screenHeight = resources.displayMetrics.heightPixels
                sheet.layoutParams.height = (screenHeight * 0.9).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onDestroyView() {
        viewModel.stopObservingComments()
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        commentAdapter = CommentAdapter()
        binding.rvComments.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = commentAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnSend.setOnClickListener {
            val text = binding.etComment.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.addComment(postId, postAuthorUid, text)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.commentsState.observe(viewLifecycleOwner) { resource ->
            if (resource is Resource.Success) {
                if (resource.data.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.rvComments.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.rvComments.visibility = View.VISIBLE
                    commentAdapter.submitList(resource.data)
                    binding.rvComments.scrollToPosition(resource.data.size - 1)
                }
            }
        }

        viewModel.addCommentState.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> binding.btnSend.isEnabled = false
                is Resource.Success -> {
                    binding.btnSend.isEnabled = true
                    binding.etComment.text?.clear()
                }
                is Resource.Error -> {
                    binding.btnSend.isEnabled = true
                    Toast.makeText(requireContext(), resource.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}