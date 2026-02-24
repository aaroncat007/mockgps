package com.sofawander.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.sofawander.app.R
import com.sofawander.app.databinding.DialogFavoriteListBinding

class FavoriteListDialog(
    private val adapter: FavoriteAdapter,
    private val onItemClick: (FavoriteItem) -> Unit
) : DialogFragment() {

    private var _binding: DialogFavoriteListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFavoriteListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerFavoritesDialog.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFavoritesDialog.adapter = adapter
        
        binding.btnCloseFavorites.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
