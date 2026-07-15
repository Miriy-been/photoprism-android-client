package ua.com.radiokot.photoprism.features.albums.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import ua.com.radiokot.photoprism.databinding.BottomSheetAlbumActionsBinding

class AlbumActionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAlbumActionsBinding? = null
    private val binding get() = _binding!!

    var onEditNameClicked: (() -> Unit)? = null
    var onDownloadZipClicked: (() -> Unit)? = null
    var onDeleteClicked: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetAlbumActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.editNameButton.setOnClickListener {
            onEditNameClicked?.invoke()
            dismiss()
        }

        binding.downloadZipButton.setOnClickListener {
            onDownloadZipClicked?.invoke()
            dismiss()
        }

        binding.deleteButton.setOnClickListener {
            onDeleteClicked?.invoke()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AlbumActionsBottomSheet"

        fun newInstance(): AlbumActionsBottomSheet =
            AlbumActionsBottomSheet()
    }
}