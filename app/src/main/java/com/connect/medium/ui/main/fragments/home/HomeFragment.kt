package com.connect.medium.ui.main.fragments.home

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.connect.medium.R
import com.connect.medium.databinding.FragmentHomeBinding
import com.connect.medium.ui.main.adapters.PostAdapter
import com.connect.medium.ui.main.fragments.comments.CommentsBottomSheet
import com.connect.medium.utils.Resource

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [HomeFragment.newInstance] factory method to
 * create an instance of this fragment.
 */

@UnstableApi
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModelFactory(requireActivity().application)
    }
    private lateinit var postAdapter: PostAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadFeed()
        }
        binding.btnCreatePost.setOnClickListener {
            findNavController().navigate(R.id.createPostFragment)
        }
    }

    private fun showShimmer(show: Boolean) {
        if (show) {
            binding.shimmerLayout.visibility = View.VISIBLE
            binding.shimmerLayout.startShimmer()
            binding.rvFeed.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        } else {
            binding.shimmerLayout.stopShimmer()
            binding.shimmerLayout.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        postAdapter = PostAdapter(
            onLikeClick = { post ->
                viewModel.toggleLike(post)
            },
            onCommentClick = { post ->
                CommentsBottomSheet.newInstance(post.postId, post.authorUid)
                    .show(parentFragmentManager, CommentsBottomSheet.TAG)
            },
            onProfileClick = { uid ->
                val action = HomeFragmentDirections.actionHomeToProfile(uid)
                findNavController().navigate(action)
            }
        )
        binding.rvFeed.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = postAdapter
        }
    }

    private fun observeViewModel(){
        viewModel.postsState.observe(viewLifecycleOwner){ resource ->
            when(resource){
                is Resource.Loading -> {
                    showShimmer(true)
                    binding.swipeRefresh.isRefreshing = false
                }
                is Resource.Success -> {
                    binding.swipeRefresh.isRefreshing = false
                    if (resource.data.isEmpty()) {
                        showShimmer(false)
                        binding.emptyState.visibility = View.VISIBLE
                        binding.rvFeed.visibility = View.GONE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        postAdapter.submitList(resource.data)
                        viewModel.checkLikedPosts(resource.data.map { it.postId })

                        // Wait for the RecyclerView to actually lay out before hiding shimmer
                        binding.rvFeed.post {
                            showShimmer(false)
                            binding.rvFeed.visibility = View.VISIBLE
                        }
                    }
                }
                is Resource.Error -> {
                    binding.swipeRefresh.isRefreshing = false

                    showShimmer(false)
                    binding.rvFeed.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE

                    context?.let {
                        Toast.makeText(it, resource.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        viewModel.likedPostIds.observe(viewLifecycleOwner) { likedIds ->
            postAdapter.setLikedPosts(likedIds)
        }
        viewModel.commentCountDeltas.observe(viewLifecycleOwner) { deltas ->
            postAdapter.setCommentCountDeltas(deltas)
        }
        viewModel.likeState.observe(viewLifecycleOwner){ resource ->
            if (resource is Resource.Error) {
                Toast.makeText(requireContext(), resource.message, Toast.LENGTH_SHORT).show()
            }
        }
    }



    override fun onDestroyView() {
        binding.rvFeed.adapter = null
        super.onDestroyView()
        _binding = null
    }
}